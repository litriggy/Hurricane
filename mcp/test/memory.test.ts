import { expect, test } from "bun:test";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { PlayMemory } from "../src/memory";
import { createServer } from "../src/server";

test("checkpoint revision remains readable despite large notes and event history", () => {
  const memory = new PlayMemory(mkdtempSync(join(tmpdir(), "hurricane-memory-")));
  memory.select("world/alice");
  memory.save("current", "Checkpoint", 0);
  for (let i = 0; i < 30; i++) memory.save(`a${i}`, "x".repeat(8000), 0);
  const event = memory.begin("get_ui", {});
  memory.finish(event, "response", { tree: "y".repeat(100000) });
  const exact = memory.read("", undefined, { key: "current", include_events: false });
  expect(exact.notes).toHaveLength(1);
  expect(exact.notes[0]?.revision).toBe(1);
  expect(exact.events).toEqual([]);
  memory.save("current", "Next step", exact.notes[0]?.revision ?? -1);
  expect(JSON.stringify(memory.read()).length).toBeLessThan(20000);
  memory.close();
});

test("MCP records real HTTP evidence, blocks unscoped calls, and preserves unknown outcomes", async () => {
  const dir = mkdtempSync(join(tmpdir(), "hurricane-memory-"));
  let calls = 0;
  const http = Bun.serve({
    hostname: "127.0.0.1",
    port: 0,
    fetch: () => {
      calls++;
      return calls === 1
        ? Response.json({ ok: true, result: { connected: false } })
        : new Response("invalid response", { status: 500 });
    },
  });
  const server = createServer({
    HURRICANE_MEMORY_DIR: dir,
    HURRICANE_BRIDGE_PORT: String(http.port),
    HURRICANE_BRIDGE_TOKEN: "test-token-000000000000000000000000000",
  });
  const client = new Client({ name: "memory-test", version: "1" });
  const [a, b] = InMemoryTransport.createLinkedPair();
  try {
    await server.connect(b);
    await client.connect(a);
    expect((await client.callTool({ name: "get_state", arguments: {} })).isError).toBe(true);
    expect(calls).toBe(0);
    await client.callTool({ name: "memory_select", arguments: { profile: "world/alice" } });
    expect((await client.callTool({ name: "get_state", arguments: {} })).isError).not.toBe(true);
    expect((await client.callTool({ name: "get_inventory", arguments: {} })).isError).toBe(true);
    expect(calls).toBe(2);
    const result = await client.callTool({ name: "memory_read", arguments: {} });
    const serialized = JSON.stringify(result);
    expect(serialized).toContain("connected");
    expect(serialized).toContain("unknown");
    expect(serialized).not.toContain("test-token-");
    const saved = await client.callTool({
      name: "memory_save",
      arguments: { key: "current", body: "Need login", revision: 0 },
    });
    expect(saved.isError).not.toBe(true);
  } finally {
    await client.close();
    await server.close();
    http.stop(true);
  }
  const reopened = new PlayMemory(dir);
  reopened.select("world/alice");
  expect(reopened.read().notes[0]?.body).toBe("Need login");
  expect(reopened.read().events).toHaveLength(2);
  reopened.close();
});

test("memory survives reconnect and isolates character profiles", () => {
  const dir = mkdtempSync(join(tmpdir(), "hurricane-memory-"));
  const first = new PlayMemory(dir);
  first.select("world-a/alice");
  first.save("current", "Prepare a farm", 0);
  expect(() => first.save("current", "stale overwrite", 0)).toThrow();
  first.close();
  const next = new PlayMemory(dir);
  expect(() => next.read()).toThrow();
  next.select("world-a/bob");
  expect(next.read().notes).toEqual([]);
  next.select("world-a/alice");
  expect(next.read().notes[0]?.body).toBe("Prepare a farm");
  next.close();
});

test("interrupted requests remain uncertain and cannot be marked as game success", () => {
  const memory = new PlayMemory(mkdtempSync(join(tmpdir(), "hurricane-memory-")));
  memory.select("world/alice");
  const id = memory.begin("craft", { session_id: "old-session" });
  expect(() => memory.select("world/bob")).toThrow();
  expect(memory.read().events[0]?.status).toBe("pending");
  memory.finish(id, "response", { status: "craft_sent" });
  expect(memory.read().events[0]?.status).toBe("response");
  expect(memory.read().events[0]?.result).toContain("craft_sent");
  memory.select("world/bob");
  expect(memory.read().events).toEqual([]);
  memory.close();
});
