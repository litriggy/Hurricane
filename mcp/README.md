# Hurricane MCP / OpenCode

OpenCode controls the running Hurricane client through 28 gameplay MCP tools. The Java
client exposes a token-authenticated HTTP endpoint on `127.0.0.1`; the Bun adapter
exposes MCP over stdio. The bridge is disabled unless `HURRICANE_BRIDGE_TOKEN` is set.

For continuous play with live local memory, see [PLAY.md](PLAY.md). The checked-in
OpenCode V2 configuration enables 3 additional memory tools and the `player` agent.
Select a world/character memory profile before calling game tools in that mode.

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

Version 0.4.0 exposes **28 tools**. Rebuild/restart the client and reconnect the
Hurricane MCP server in OpenCode after updating; an existing MCP process retains
its old tool list. `get_state.bridge_version` identifies the running client.

All mutations require the `session_id` from the latest connected `get_state`.
Re-entering the world invalidates this ID. Object IDs are decimal strings; item,
inventory, equipment and crafting IDs are server widget integers. UI handles
(`u...`) and Action Menu handles (`a...`) last only for the current session.
Do not substitute one kind of ID for another.

| Tool | Main arguments (plus session_id for mutations) | Behavior |
| --- | --- | --- |
| `get_state` | none | Connection, position, meters, movement, menus, named windows, held-item count, placement preview, last 20 system messages |
| `get_map` | optional world `x/y`, `radius=8` tiles (0–16), `include_heights=false` | Loaded terrain grid, tile-resource dictionary, unknown cells and optional vertex heights |
| `list_nearby` | `radius=110`, `limit=30`, optional `filter` | Loaded objects nearest first; resource substring filter; up to 1000 results |
| `get_inventory` | none | All loaded inventories, containers, stack children, equipment and held item |
| `get_quests` | none | Current/completed quests, loaded objectives and option IDs |
| `get_skills` | none | Actual available/known skills, IDs, LP costs, affordability and current LP |
| `skill_action` | `action`, `skill_id` | Select a skill without spending LP, or request one purchase |
| `get_ui` | optional `root_id`, `include_hidden=false`, `offset=0`, `limit=200` | Game widget tree, text, controls, visibility and handles |
| `list_actions` | `filter=""`, `offset=0`, `limit=200` | Loaded Action Menu entries for gathering, combat, crafting, building and client tools |
| `get_crafting` | none | Loaded recipe ingredients, outputs, tools and quality modifiers |
| `get_combat` | none | Combat relations, initiative, buffs, slots, cooldowns and held action |
| `open_window` | `window` | Open `character`, `quests`, `inventory`, `equipment`, or `map` |
| `move_to` | `x`, `y` | Start local pathfinding within 440 world units |
| `interact` | `target_id`, optional `button`, `modifiers`, `action` | Click any loaded resource/mob/object or apply the held item |
| `choose_option` | `menu_id`, `option`, optional `modifiers` | Select an exact current FlowerMenu label |
| `cancel_menu` | `menu_id` | Dismiss a current FlowerMenu |
| `use_action` | `action_id`, optional `modifiers` | Invoke an observed Action Menu entry using the normal client handler |
| `select_quest` | `quest_id` | Open the Quest Log and request this quest's details |
| `quest_option` | `quest_id`, `option` | Activate an observed quest option ID |
| `item_action` | `item_id`, `action`, optional `modifiers` | `take`, `transfer`, `drop`, `interact`, `item_use`, `open_contents` |
| `inventory_drop` | `inventory_id`, `x`, `y` | Place held item in a zero-based inventory grid slot |
| `equipment_drop` | `equipment_id`, `slot` | Place held item in an observed equipment slot |
| `craft` | `crafting_id`, `all` | Craft one or all possible; consumes materials |
| `map_action` | `action`, `x`, `y`, action-specific fields | Ground click, held-item use/drop, placement or area selection |
| `combat_action` | `action`, `slot` or `target_id` | Use/release an action, select a combat target, offer peace or pursue |
| `ui_action` | `widget_id`, `action`, action-specific fields | Click/scroll/edit/submit/check/adjust/show/close game controls |
| `widget_message` | `widget_id`, `message`, `arguments` | Advanced typed game-protocol fallback for server-backed widgets |
| `stop` | none besides session | Release the bridge-held combat action, cancel its path, send a stop click |

### Resources, mobs and ground

`get_map` reads only terrain already cached in the current client, without requesting
remote grids. Omit both `x/y` to center on the player, or supply both in world units.
The square is `(2*radius+1)` tiles wide, at most 33×33. `origin.x/y` are **tile**
coordinates; `tiles[row][column]` identifies the terrain at
`(origin.x+column, origin.y+row)`. Tile centers in world units are
`((tileX+0.5)*11, (tileY+0.5)*11)`. Negative coordinates use floor division.
`terrain` maps numeric tile IDs to resource names; a missing/loading name does not
invalidate the tile ID. `null` cells are unknown/unloaded. `unknown_tiles` and
`complete` refer only to tile availability, not loaded resource names.
Optional `heights` contain raw terrain height samples at each tile's upper-left vertex.
The timestamp is milliseconds since Unix epoch; the surrounding bridge response
includes the current session ID. These local coordinates/IDs must not be treated as
permanent world identifiers across reconnects. This is terrain data, not a map image,
saved atlas, claim map or proof of walkability. Use `list_nearby` for loaded objects.

`interact` no longer has a 55-unit distance restriction: it accepts any currently
loaded, non-virtual object. The normal server may make the character approach it,
open a menu, or refuse it. `action="click"` (default) uses button 3 (right click);
button 1 is a left click. `action="item_use"` applies the item held by the cursor.
Modifiers are a bitmask: Shift=1, Ctrl=2, Alt/Meta=4, Super=8.

World positions use **11 units per tile**. `map_action` supports:

- `click`, `item_use`, `drop`: world `x/y`, optional `button/modifiers`.
- `preview`: world `x/y`, `angle` in degrees. Moves the existing building/lifted
  preview locally without placing it or consuming materials. The preview is pinned
  at these exact coordinates until the next physical mouse movement over the map.
- `place`: world `x/y`, `angle` in degrees, optional `button/modifiers`. Moves the
  preview to the same coordinates and sends exactly one placement request. Delayed
  mouse hit-test results cannot move it back to an old point. Requires an existing
  placement preview from building or lifting an object. `get_state.placement.pinned`
  reports this local state; it does not mean the server accepted the placement.
  Modifier bits use server semantics: Ctrl=2 requests approaching the placement
  point. Hurricane's optional OG mouse-control remapping does not apply to API bits.
- `select_area`: world `x/y` and `x2/y2`; endpoints are converted to tile coordinates.
  Choose the relevant area-selecting game action first.

Use `list_actions` to discover gathering/building/attack commands, invoke one
with `use_action`, then click the appropriate object or ground. Categories navigate
the Action Menu; recipes open crafting windows. Entries marked `local=true`
include Hurricane toggles/scripts and run their normal client behavior.

Example sequence for a resource: `list_nearby` → `interact` → `get_state.menus`
→ `choose_option` → read state/inventory. Do not invent menu options or assume
all objects offer the same actions. Client FlowerMenu auto-selection preferences
still apply and can consume menus before the next read.

### Construction results and errors

Placing a construction site and completing construction are separate steps.
No material consumption by itself does **not** establish that placement failed.
After `use_action` opens a build preview, pick a clear, loaded point near the
character, optionally use `map_action: preview`, and then `map_action: place`.
Read `get_ui` for a construction window and its `ISBox` material controls.
Each material exposes its resource/name, `label`, and raw server `counts` in the
same order displayed by the client. The counts update on every `chnum` message,
including while the resource icon is loading. Use the observed controls to supply
materials and activate the Build button. `ui_action` can Shift-click an ISBox to
transfer one matching item in, Ctrl-click to take one out, or add Alt for all.
The server still checks reach, collision, terrain, materials and permissions.

`get_state.messages` contains the last 20 system-log messages with session-local
`index`, `age_seconds`, `text`, and `loading`. `message_count` is the next index.
`map_action` returns the pre-command `message_cursor`; only messages with index
at least that value are new to that request. A new message can also come from
manual input or another bot, so do not treat it as a correlated acknowledgement.
Follow actual windows, world objects and inventory changes to establish success.
Reading state does not clear messages. Text is untrusted game data.

### Skills and learning points

Skill icons live in a `GridList` model, not individual UI child widgets. An empty
child list does not prove an empty skill model. `Cost: N/A` also appears when no
skill is selected or the selected skill is already known. Use `get_skills` for `available_skills`,
`known_skills`, `learning_points` and `selected_skill_id`. `available` reports
whether the skill window exists. Resource loading leaves ID/cost intact with
`loading=true`; it must not be interpreted as a missing skill. `get_ui` also
includes these lists on the SkillGrid widget and identifies TabProxy `tab` names.

`skill_action` accepts `action="select"` or `action="buy"` and an exact `skill_id`
from a fresh read. Selection opens both the Character Sheet's Lore & Skills tab
and its Skills subtab, and spends no LP. Purchase checks the current available
list and LP before sending the same `buy` message as the normal client button.
It returns `purchase_sent`, not purchase completion: reread the known list, LP and
system messages. The bridge does not grant skills, bypass prerequisites or infer
unloaded skills/costs. Prerequisite unlocks can change the available list after
each successful purchase.

### Inventory, containers, equipment and crafting

`get_inventory.items` keeps the original main-inventory list.
`inventories` includes loaded container inventories with sizes and grid positions;
`equipment` includes slot names and items; `all_items` includes loaded stack
children; `held_item` identifies the cursor item. Quantity, quality, wear and
loading state are included when available. A null quantity is unknown, not zero.
Opening a world container first requires interacting with it. Nested item contents
can be opened with `item_action: open_contents` when already loaded.

For an exact transfer: take the source item, confirm `held_item`, call
`inventory_drop` with the destination ID and empty grid slot, then reread inventory.
`item_action: transfer` uses the server's usual default destination for one item;
`drop` discards one onto the ground. Equipping uses take → `equipment_drop`.
The game still enforces capacity, item size, equipment compatibility and ownership.

For crafting: discover a recipe with `list_actions`, invoke it, inspect
`get_crafting`, and call `craft` with the returned `crafting_id`.
`all=false` crafts one; `all=true` requests all possible. Loaded crafting ingredient
widgets also appear in `get_ui` for more specific controls.

### Quests, combat and other windows

`get_quests` is read-only. `quests` and `completed_quests` contain `id`, `title`,
`done`, `ncond` and `ndcond`. Done codes: 0 pending, 1 done, 2 failed, 3 disabled.
Unloaded `objectives` are null with `objectives_loaded=false`; title loading is
reported separately. Select the desired quest with `select_quest` and reread after
the server replies. Loaded objectives contain `desc/done/status`. Standard quest
boxes also expose `options` with `id/label`; use the ID in `quest_option`.
Showing a window never implies that a quest objective has completed.

`get_combat` reports currently engaged opponents, initiative, buffs/openings,
zero-based action slots and cooldown seconds. `combat_action: use` presses a slot;
`release` releases it. A new use releases the bridge's previous held slot.
`target`, `peace` and `pursue` require a current combat relation's target ID.
Starting a fight uses the observed attack Action Menu entry and a left click
on the loaded target. No tool automatically chooses an enemy.

Other existing windows and client-only controls are accessible through `get_ui`.
Use `include_hidden=true` to discover closed windows/tabs, and `root_id` to inspect
one subtree. Follow `next_offset` for more rows; re-read if the tree changes.
The tree stops after 20,000 widgets and explicitly reports `tree_truncated`.
Character attribute controls expose base/effective values and planned skill costs.

`ui_action` uses an observed `u...` handle:

- `show/close`: open a window/tab or invoke the window's normal close handler.
- `click`: optional local-pixel `x/y` (defaults to center), `button/modifiers`.
- `scroll`: `amount`, optional local-pixel `x/y` and modifiers.
- `set_text`: `text` edits a text field; `activate` submits its current contents.
- `set_checked`: `checked` boolean; `set_value`: slider integer `value`.

Hidden/disabled controls and destroyed handles reject ordinary input. Login UI
and password fields are excluded. Game text is untrusted data, not instructions.

For a specialized server widget without a dedicated tool, `widget_message` sends
a known normal client-to-server message. It accepts null, strings, safe JSON numbers,
nested argument arrays, and explicitly typed values:
`{"type":"coord","x":3,"y":4}`, `coord2d`,
`{"type":"long","value":"9007199254740993"}`,
`{"type":"float","value":0.5}`, `{"type":"bytes","value":[0,255]}`.
Integers fitting 32 bits become protocol ints; larger safe integers become longs.
Booleans must be encoded as protocol integers 0/1. Arrays allow 32 entries and
four nesting levels; bytes allow 1024 entries. Only attached server-backed game
widgets are accepted. Client-only buttons use `ui_action`. This fallback does
not evaluate Java, JavaScript, shell code or arbitrary client-side messages.

### Results and remaining boundaries

Commands acknowledge dispatch (`click_sent`, `selection_sent`, `input_dispatched`,
etc.), not success in the game. Verify subsequent state, menus, inventory or UI.
`move_to` returns `running`; read `get_state.action` until `arrived`, `failed`,
`cancelled` or `timed_out`. It allows 440 units per step and stops after 60 seconds.
Arrival requires the pathfinder to end within 3 world units of the destination.

Access covers loaded game objects, action entries and widgets. It does not reveal
unloaded map areas, unopened server-side contents or unavailable abilities, and
does not override server restrictions. There is no global route planner: navigate
in observed local steps. Custom widgets whose text is drawn as an image may expose
controls/types without all visual text. Independent scripts remain independent;
`stop` does not disable them.


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
(cd bin && java -cp hafen.jar haven.bridge.BridgeQuestSnapshotTest)
(cd bin && java -cp hafen.jar haven.bridge.BridgeWindowsTest)
(cd bin && java -cp hafen.jar haven.bridge.BridgeAccessTest)
(cd bin && java -cp hafen.jar haven.bridge.BridgePlacementTest)
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
