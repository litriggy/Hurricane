import { afterEach, expect, test } from "bun:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { z } from "zod";
import { createServer } from "../src/server";

const token = "bridge-test-token-00000000000000000000";
const session = "8a5b96a9-59c8-4a91-9c12-1349d3b23e18";
const requestSchema = z.object({ method: z.string(), arguments: z.record(z.string(), z.json()) });
const cleanup: (() => Promise<void> | void)[] = [];
afterEach(async () => {
  for (const close of cleanup.splice(0).reverse()) await close();
});

async function connect(handler: (request: Request) => Response | Promise<Response>) {
  const http = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: handler });
  cleanup.push(() => http.stop(true));
  const server = createServer({
    HURRICANE_BRIDGE_TOKEN: token,
    HURRICANE_BRIDGE_PORT: String(http.port),
  });
  cleanup.push(() => server.close());
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);
  const client = new Client({ name: "bridge-test", version: "1.0.0" });
  cleanup.push(() => client.close());
  await client.connect(clientTransport);
  return client;
}

test("discovers seven tools through an MCP handshake", async () => {
  const client = await connect(() => Response.json({ ok: true, result: {} }));
  const result = await client.listTools();
  expect(result.tools.map((tool) => tool.name).sort()).toEqual([
    "choose_option",
    "get_inventory",
    "get_state",
    "interact",
    "list_nearby",
    "move_to",
    "stop",
  ]);
});

test("forwards session-bound movement once with authentication", async () => {
  const calls: z.infer<typeof requestSchema>[] = [];
  const client = await connect(async (request) => {
    expect(request.headers.get("Authorization")).toBe(`Bearer ${token}`);
    calls.push(requestSchema.parse(await request.json()));
    return Response.json({ ok: true, result: { status: "running", action_id: "a1" } });
  });
  const args = { session_id: session, x: 12, y: 24 };
  const result = await client.callTool({ name: "move_to", arguments: args });
  expect(calls).toEqual([{ method: "move_to", arguments: args }]);
  expect(result.isError).not.toBe(true);
  expect(result.content).toEqual([{ type: "text", text: '{"status":"running","action_id":"a1"}' }]);
});

test("rejects malformed movement before calling the bridge", async () => {
  let called = false;
  const client = await connect(() => {
    called = true;
    return Response.json({ ok: true, result: {} });
  });
  const result = await client.callTool({
    name: "move_to",
    arguments: { session_id: session, x: "12", y: 0 },
  });
  expect(result.isError).toBe(true);
  expect(called).toBe(false);
});

test("preserves a stale-session rejection as an MCP tool error", async () => {
  const client = await connect(() =>
    Response.json({ ok: false, error: { code: "stale_session", message: "Read state" } }),
  );
  const result = await client.callTool({ name: "stop", arguments: { session_id: session } });
  expect(result.isError).toBe(true);
});

test("does not retry a failed mutation", async () => {
  let calls = 0;
  const client = await connect(() => {
    calls++;
    return Response.json(
      { ok: false, error: { code: "ui_timeout", message: "Read state" } },
      { status: 504 },
    );
  });
  const result = await client.callTool({
    name: "interact",
    arguments: { session_id: session, target_id: "9007199254740993" },
  });
  expect(result.isError).toBe(true);
  expect(calls).toBe(1);
});

test("supplies nearby defaults at the tool boundary", async () => {
  let received: unknown;
  const client = await connect(async (request) => {
    received = await request.json();
    return Response.json({ ok: true, result: { objects: [] } });
  });
  await client.callTool({ name: "list_nearby", arguments: {} });
  expect(received).toEqual({ method: "list_nearby", arguments: { radius: 110, limit: 30 } });
});
