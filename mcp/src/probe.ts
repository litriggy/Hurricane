import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const client = new Client({ name: "hurricane-probe", version: "0.1.0" });
const environment: Record<string, string> = {};
for (const [key, value] of Object.entries(process.env)) {
  if (value !== undefined) environment[key] = value;
}
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [fileURLToPath(new URL("./index.ts", import.meta.url))],
  env: environment,
  stderr: "inherit",
});
try {
  await client.connect(transport);
  const tools = await client.listTools();
  console.info(JSON.stringify({ tools: tools.tools.map((tool) => tool.name) }));
  const state = await client.callTool({ name: "get_state", arguments: {} });
  console.info(JSON.stringify(state));
  if (state.isError) process.exitCode = 1;
} finally {
  await client.close();
}
