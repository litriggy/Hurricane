import ky from "ky";
import { z } from "zod";
import type { PlayMemory } from "./memory";

const configuration = z.object({
  HURRICANE_BRIDGE_TOKEN: z
    .string()
    .min(32)
    .regex(/^[A-Za-z0-9_-]+$/),
  HURRICANE_BRIDGE_PORT: z.coerce.number().int().min(1024).max(65535).default(18711),
});

const response = z.discriminatedUnion("ok", [
  z.object({ ok: z.literal(true), result: z.record(z.string(), z.json()) }),
  z.object({
    ok: z.literal(false),
    error: z.object({ code: z.string(), message: z.string() }),
  }),
]);

export function createBridge(environment: NodeJS.ProcessEnv, memory?: PlayMemory) {
  const config = configuration.parse(environment);
  const http = ky.create({
    baseUrl: `http://127.0.0.1:${config.HURRICANE_BRIDGE_PORT}/`,
    headers: { Authorization: `Bearer ${config.HURRICANE_BRIDGE_TOKEN}` },
    timeout: 5000,
    retry: 0,
    redirect: "error",
    throwHttpErrors: false,
  });
  return async (method: string, args: Readonly<Record<string, unknown>>) => {
    const event = memory?.begin(method, args);
    try {
      const res = await http.post("api", { json: { method, arguments: args } });
      const data = response.parse(await res.json());
      if (!res.ok && data.ok) throw new BridgeProtocolError();
      if (event !== undefined)
        memory?.finish(
          event,
          data.ok ? "response" : data.error.code === "ui_timeout" ? "unknown" : "rejected",
          data,
        );
      switch (data.ok) {
        case true:
          return { content: [{ type: "text" as const, text: JSON.stringify(data.result) }] };
        case false:
          return {
            isError: true,
            content: [{ type: "text" as const, text: JSON.stringify(data.error) }],
          };
      }
    } catch (error) {
      if (event !== undefined)
        memory?.finish(event, "unknown", {
          reason: "Request or recording failed; reobserve before retrying.",
        });
      throw error;
    }
  };
}

class BridgeProtocolError extends Error {
  constructor() {
    super("Bridge returned an inconsistent HTTP response");
    this.name = "BridgeProtocolError";
  }
}
