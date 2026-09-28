import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createServer } from "./server";

if (process.argv.includes("--help")) {
  console.info(
    "Hurricane MCP bridge\nUsage: bun mcp/src/index.ts\nRequired: HURRICANE_BRIDGE_TOKEN (same as client, 32+ URL-safe characters)\nOptional: HURRICANE_BRIDGE_PORT (default 18711)\nTransport: MCP over stdio; client HTTP endpoint is loopback only.",
  );
} else if (process.argv.length > 2) {
  console.error("Unknown argument. Use --help.");
  process.exitCode = 2;
} else {
  try {
    const server = createServer(process.env);
    await server.connect(new StdioServerTransport());
  } catch (error) {
    if (!(error instanceof Error)) throw error;
    console.error(
      "Hurricane MCP startup failed. Check the token/port configuration and client connection.",
    );
    process.exitCode = 1;
  }
}
