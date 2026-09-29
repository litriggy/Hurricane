import { afterEach, expect, test } from "bun:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { z } from "zod";
import { createServer } from "../src/server";

test("all gameplay mutations require a current session before HTTP dispatch", async () => {
  let calls = 0;
  const client = await connect(() => {
    calls++;
    return Response.json({ ok: true, result: {} });
  });
  const { tools } = await client.listTools();
  const mutations = tools.filter((tool) => !tool.annotations?.readOnlyHint);
  expect(mutations.length).toBe(18);
  for (const tool of mutations) {
    expect(tool.inputSchema.required).toContain("session_id");
    const result = await client.callTool({ name: tool.name, arguments: {} });
    expect(result.isError).toBe(true);
  }
  expect(calls).toBe(0);
});

test("forwards typed gameplay arguments once including booleans and nested protocol values", async () => {
  const calls: z.infer<typeof requestSchema>[] = [];
  const client = await connect(async (request) => {
    calls.push(requestSchema.parse(await request.json()));
    return Response.json({ ok: true, result: { status: "sent" } });
  });
  const cases = [
    {
      name: "interact",
      arguments: { session_id: session, target_id: "4000000001", action: "item_use", modifiers: 2 },
    },
    { name: "inventory_drop", arguments: { session_id: session, inventory_id: 12, x: 3, y: 2 } },
    { name: "craft", arguments: { session_id: session, crafting_id: 13, all: false } },
    {
      name: "skill_action",
      arguments: { session_id: session, action: "select", skill_id: "farming" },
    },
    {
      name: "skill_action",
      arguments: { session_id: session, action: "buy", skill_id: "farming" },
    },
    {
      name: "map_action",
      arguments: { session_id: session, action: "preview", x: -11.5, y: 22, angle: 90 },
    },
    {
      name: "combat_action",
      arguments: { session_id: session, action: "use", slot: 0, x: -11.5, y: 0 },
    },
    {
      name: "ui_action",
      arguments: { session_id: session, widget_id: "u12", action: "set_text", text: "" },
    },
    {
      name: "map_action",
      arguments: { session_id: session, action: "place", x: 1, y: 2, angle: 90 },
    },
    {
      name: "widget_message",
      arguments: {
        session_id: session,
        widget_id: "u12",
        message: "custom",
        arguments: [
          null,
          1,
          { type: "long", value: "9007199254740993" },
          [{ type: "coord", x: -3, y: 4 }],
          { type: "bytes", value: [0, 255] },
        ],
      },
    },
  ];
  for (const call of cases) expect((await client.callTool(call)).isError).not.toBe(true);
  expect(calls).toEqual(cases.map((c) => ({ method: c.name, arguments: c.arguments })));
});

test("rejects invalid action variants and typed messages before reaching the game", async () => {
  let calls = 0;
  const client = await connect(() => {
    calls++;
    return Response.json({ ok: true, result: {} });
  });
  const cases = [
    { name: "map_action", arguments: { action: "place", x: 0, y: 0 } },
    { name: "map_action", arguments: { action: "preview", x: 0, y: 0 } },
    { name: "map_action", arguments: { action: "preview", x: 0, y: 0, angle: 90, modifiers: 2 } },
    { name: "skill_action", arguments: { action: "buy", skill_id: "" } },
    { name: "map_action", arguments: { action: "click", x: 0, y: 0, angle: 90 } },
    { name: "combat_action", arguments: { action: "use", slot: 0, x: 10 } },
    { name: "combat_action", arguments: { action: "peace", slot: 0 } },
    { name: "ui_action", arguments: { widget_id: "u1", action: "set_checked", checked: 1 } },
    { name: "ui_action", arguments: { widget_id: "u1", action: "click", text: "ignored" } },
    { name: "widget_message", arguments: { widget_id: "u1", message: "act", arguments: [true] } },
    {
      name: "widget_message",
      arguments: {
        widget_id: "u1",
        message: "act",
        arguments: [{ type: "long", value: "9223372036854775808" }],
      },
    },
    {
      name: "widget_message",
      arguments: { widget_id: "u1", message: "act", arguments: [{ type: "coord", x: 1.5, y: 0 }] },
    },
    {
      name: "widget_message",
      arguments: { widget_id: "u1", message: "act", arguments: [{ type: "bytes", value: [256] }] },
    },
    {
      name: "widget_message",
      arguments: { widget_id: "u1", message: "act", arguments: [[[[[[0]]]]]] },
    },
  ];
  for (const c of cases) {
    const result = await client.callTool({
      ...c,
      arguments: { session_id: session, ...c.arguments },
    });
    expect(result.isError).toBe(true);
  }
  expect(calls).toBe(0);
});

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

test("discovers the complete gameplay toolset through an MCP handshake", async () => {
  const client = await connect(() => Response.json({ ok: true, result: {} }));
  const result = await client.listTools();
  expect(result.tools.map((tool) => tool.name).sort()).toEqual([
    "cancel_menu",
    "choose_option",
    "combat_action",
    "craft",
    "equipment_drop",
    "get_combat",
    "get_crafting",
    "get_inventory",
    "get_map",
    "get_quests",
    "get_skills",
    "get_state",
    "get_ui",
    "interact",
    "inventory_drop",
    "item_action",
    "list_actions",
    "list_nearby",
    "map_action",
    "move_to",
    "open_window",
    "quest_option",
    "select_quest",
    "skill_action",
    "stop",
    "ui_action",
    "use_action",
    "widget_message",
  ]);
});

test("reads skill models and LP without a session or a selection request", async () => {
  const calls: z.infer<typeof requestSchema>[] = [];
  const skills = {
    available: true,
    learning_points: 2075,
    selected_skill_id: null,
    available_skills: [
      {
        id: "farming",
        cost: 400,
        known: false,
        affordable: true,
        loading: true,
        name: null,
        resource: null,
      },
    ],
    known_skills: [],
    scope: "loaded_skills",
    widget_id: "u42",
  };
  const client = await connect(async (request) => {
    calls.push(requestSchema.parse(await request.json()));
    return Response.json({ ok: true, result: { ...skills, session_id: session } });
  });
  const result = await client.callTool({ name: "get_skills", arguments: {} });
  expect(result.isError).not.toBe(true);
  expect(calls).toEqual([{ method: "get_skills", arguments: {} }]);
  expect(result.content).toEqual([
    { type: "text", text: JSON.stringify({ ...skills, session_id: session }) },
  ]);
});

test("reads quest objectives without a session or extra requests", async () => {
  const calls: z.infer<typeof requestSchema>[] = [];
  const quests = {
    available: true,
    selected_quest_id: 12,
    quests: [
      {
        id: 12,
        title: "A new beginning",
        done: 0,
        ncond: 2,
        ndcond: 1,
        title_loading: false,
        objectives_loaded: true,
        objectives: [
          { desc: "Meet the quest giver", done: 1, status: null },
          { desc: "Collect branches", done: 0, status: "1/3" },
        ],
      },
    ],
    completed_quests: [],
    scope: "loaded_quests",
    session_id: session,
  };
  const client = await connect(async (request) => {
    expect(request.headers.get("Authorization")).toBe(`Bearer ${token}`);
    calls.push(requestSchema.parse(await request.json()));
    return Response.json({ ok: true, result: quests });
  });
  const tools = await client.listTools();
  expect(tools.tools.find((tool) => tool.name === "get_quests")?.annotations?.readOnlyHint).toBe(
    true,
  );
  const result = await client.callTool({ name: "get_quests", arguments: {} });
  expect(calls).toEqual([{ method: "get_quests", arguments: {} }]);
  expect(result.isError).not.toBe(true);
  expect(result.content).toEqual([{ type: "text", text: JSON.stringify(quests) }]);
});

test("rejects quest selection arguments without contacting the client", async () => {
  let called = false;
  const client = await connect(() => {
    called = true;
    return Response.json({ ok: true, result: {} });
  });
  const result = await client.callTool({ name: "get_quests", arguments: { quest_id: 12 } });
  expect(result.isError).toBe(true);
  expect(called).toBe(false);
});

test("opens a named window once with a session and truthful annotations", async () => {
  const calls: z.infer<typeof requestSchema>[] = [];
  const client = await connect(async (request) => {
    expect(request.headers.get("Authorization")).toBe(`Bearer ${token}`);
    calls.push(requestSchema.parse(await request.json()));
    return Response.json({
      ok: true,
      result: { status: "opened", window: "quests", available: true, visible: true },
    });
  });
  const tools = await client.listTools();
  expect(tools.tools.find((tool) => tool.name === "open_window")?.annotations).toMatchObject({
    readOnlyHint: false,
    destructiveHint: false,
    idempotentHint: true,
  });
  const args = { session_id: session, window: "quests" };
  const result = await client.callTool({ name: "open_window", arguments: args });
  expect(result.isError).not.toBe(true);
  expect(calls).toEqual([{ method: "open_window", arguments: args }]);
});

test("rejects unsupported windows and missing sessions before reaching the client", async () => {
  let called = false;
  const client = await connect(() => {
    called = true;
    return Response.json({ ok: true, result: {} });
  });
  for (const args of [
    { window: "quests" },
    { session_id: session, window: "arbitrary-widget" },
    { session_id: session, window: "quests", widget_id: 12 },
  ]) {
    const result = await client.callTool({ name: "open_window", arguments: args });
    expect(result.isError).toBe(true);
  }
  expect(called).toBe(false);
});

test("preserves a stale-session rejection when opening a window", async () => {
  const client = await connect(() =>
    Response.json({ ok: false, error: { code: "stale_session", message: "Read state" } }),
  );
  const result = await client.callTool({
    name: "open_window",
    arguments: { session_id: session, window: "quests" },
  });
  expect(result.isError).toBe(true);
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

test("map reads supply bounded defaults and reject partial coordinates before HTTP", async () => {
  const calls: unknown[] = [];
  const client = await connect(async (request) => {
    calls.push(await request.json());
    return Response.json({ ok: true, result: { scope: "loaded_terrain" } });
  });
  await client.callTool({ name: "get_map", arguments: {} });
  expect(calls).toEqual([{ method: "get_map", arguments: { radius: 8, include_heights: false } }]);
  for (const args of [{ x: 1 }, { radius: 17 }, { radius: 1.5 }]) {
    expect((await client.callTool({ name: "get_map", arguments: args })).isError).toBe(true);
  }
  expect(calls).toHaveLength(1);
});
