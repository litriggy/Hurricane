# Hurricane MCP / OpenCode

OpenCode controls the running Hurricane client through seven MCP tools. The Java
client exposes a token-authenticated HTTP endpoint on `127.0.0.1`; the Bun adapter
exposes MCP over stdio. The bridge is disabled unless `HURRICANE_BRIDGE_TOKEN` is set.

## Build

Use **JDK 21**, Apache Ant, Bun and pnpm. Although upstream's build.xml targets
Java 17 bytecode, current upstream source uses Java 21 collection methods. Run
this build with JDK 21 and run the resulting client with JDK 21 as well.

```sh
ant -Dext-lib-base=https://www.havenandhearth.com/java bin
pnpm --dir mcp install --frozen-lockfile
```

The modified client is `bin/hafen.jar`. `Release/hafen.jar` is the original
upstream binary and does not contain this bridge. Do not use the upstream updater
to replace the modified binary.

## Start on macOS / Linux

From the repository root, in one terminal with Java 21 on PATH:

```sh
export HURRICANE_BRIDGE_TOKEN="$(openssl rand -hex 32)"
export HURRICANE_BRIDGE_PORT=18711
(cd bin && bash Play_Linux.sh) &
opencode
```

The checked-in `opencode.json` starts `bun run mcp/src/index.ts`. OpenCode and the
client must inherit the **same** token and port. For separate terminals, set those
same environment variables in each terminal. Never put the token in a committed
configuration file. Tokens must contain at least 32 letters, digits, `_` or `-`.

On Windows, set these two environment variables in PowerShell, start
`bin/Play.bat`, then run OpenCode at the repository root with the same environment.

When the client reports `Hurricane bridge listening on 127.0.0.1:18711`, check:

```sh
opencode mcp list
bun mcp/src/probe.ts
```

The probe uses a real MCP stdio client and calls only `get_state`. At the login
screen, the expected result is `{"connected":false}`. Log in manually and enter
the world before requesting character actions. Account credentials are not
exposed through this bridge.

### Apple Silicon launch limitation observed during validation

On the validation machine, the default LWJGL renderer exited with macOS's
`NSOpenGLContext setView: must be called from the main thread` assertion. Explicit
`-Dhaven.toolkit=jogl` also failed because the upstream packaged JOGL native library
was x86_64-only. Neither attempt reached bridge startup. A graphical launch on
this Apple Silicon environment is therefore **not verified**; the renderer/native
library compatibility issue remains. The bridge was verified with the real client
UI running without a graphics window, using the probe below.

Example prompts:

> hurricane 도구로 현재 위치와 스태미나, 주변 물체를 보여줘.

> 주변 나무 하나를 골라 근처까지 이동해. 도착했는지 확인한 다음 멈춰.

> 방금 선택한 나무를 우클릭하고 실제로 나타난 메뉴를 보여줘.

## Tools

| Tool | Arguments | Behavior |
| --- | --- | --- |
| `get_state` | none | Connection, session ID, player, meters, movement result, open menus |
| `list_nearby` | `radius=110`, `limit=30` | Loaded objects, nearest first; maximum 440 units / 100 results |
| `get_inventory` | none | Main inventory's top-level items, including resource loading state |
| `move_to` | `session_id`, `x`, `y` | Start a path within 440 world units |
| `interact` | `session_id`, `target_id` | Right-click a loaded object within 55 units |
| `choose_option` | `session_id`, `menu_id`, `option` | Select an exact option from a current menu |
| `stop` | `session_id` | Cancel the bridge's path and send a stop click |

Coordinates are **world units**, not pixels or tiles; one tile is 11 units. Object
IDs are decimal strings to avoid JavaScript integer precision loss. Mutations
require the session ID from a fresh connected `get_state` response. Re-entering
the world changes this ID, rejecting stale commands.

`move_to` returning `running` only acknowledges that pathfinding started. Read
`get_state.action` for `arrived`, `failed`, `cancelled` or `timed_out`. Arrival
requires the pathfinder to finish and the player to be within 3 world units of
the destination. The bridge cancels movement after 60 seconds. Other commands
return `click_sent`, `selection_sent` or `stop_sent`; inspect subsequent game
state to establish what actually happened.

Disable other bots while using MCP. `stop` does not disable independent scripts,
and existing FlowerMenu auto-selection preferences still apply. Menus may therefore
be consumed by the client before MCP reads them. Inventory stacks and nested
containers are not expanded. Nearby objects are only those currently loaded by
the client. Combat, crafting, container transfers and long-range navigation are
outside this initial implementation.

## Execution and failures

Requests are validated at both the MCP and HTTP boundaries. HTTP workers enqueue
commands into a bounded queue; the game UI tick drains that queue under its normal
UI lock. No HTTP thread directly mutates widgets. The old UI closes its server
before the replacement UI starts a new one during login or reconnection.

HTTP requests expire after 3 seconds; commands cancelled while still queued will
not execute later. A request that already started may have sent an action before
the response was lost. The MCP adapter never retries a mutation automatically.
Read state after a timeout instead of blindly repeating the action.

The endpoint accepts authenticated JSON POST requests to `/api`, rejects browser
Origin headers and oversized bodies, and never listens on an external interface.

## Validation

```sh
pnpm --dir mcp check
pnpm --dir mcp test
java -cp 'build/classes:lib/jglob.jar' haven.test.BridgeServerTest
```

Use `;` instead of `:` in the Java classpath on Windows. The Java checks exercise
real HTTP authentication, parsing, thread dispatch and cancellation. MCP tests use
the SDK protocol with an HTTP fixture and do not prove live gameplay.

For an in-game smoke test: read the session, move a short unobstructed distance,
observe `arrived`, right-click a nearby object, select a returned menu option,
then stop a longer movement. Reconnect and verify that the old session is rejected.
These gameplay checks require a logged-in character.

To test the real UI/HTTP/MCP connection without a graphics window or game account,
start this process from `bin/` with the bridge token exported, then run
`bun mcp/src/probe.ts` and `opencode mcp list` from the repository root in another
terminal with the same token:

```sh
java -cp hafen.jar haven.test.BridgeUiProbe
```

Press Enter in the Java probe to close it. This uses the actual `UI`, `GameBridge`
and HTTP server with a disconnected game state. It does not simulate movement or
prove in-game behavior.
