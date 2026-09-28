package haven.bridge;

import haven.*;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import org.json.JSONObject;

/** One bridge per UI lifetime, started on the first UI tick after the old UI is destroyed. */
public final class GameBridge implements AutoCloseable {
    private final UI ui;
    private final ArrayBlockingQueue<Runnable> pending = new ArrayBlockingQueue<>(32);
    private BridgeServer server;
    private boolean attempted;
    private volatile boolean enabled;
    private GameUI gui;
    private BridgeActions actions;
    private String sessionId;

    public GameBridge(UI ui) { this.ui = ui; }

    public void start() { enabled = true; }

    public void tick() {
        if (!enabled) return;
        if (!attempted) {
            attempted = true;
            String token = System.getenv("HURRICANE_BRIDGE_TOKEN");
            if (token != null) {
                try {
                    String value = System.getenv("HURRICANE_BRIDGE_PORT");
                    int port = value == null ? 18711 : Integer.parseInt(value);
                    if (port < 1024 || port > 65535)
                        throw new IllegalArgumentException("Bridge port must be between 1024 and 65535");
                    server = new BridgeServer(new BridgeServer.Config(port, token), this::execute, task -> {
                        if (!pending.offer(task)) throw new RejectedExecutionException();
                    });
                    System.err.printf("Hurricane bridge listening on 127.0.0.1:%d%n", server.port());
                } catch (IOException | IllegalArgumentException e) {
                    new Warning(e, "Hurricane bridge could not start").issue();
                }
            }
        }
        if (server == null) return;
        if (gui != ui.gui) {
            if (actions != null) actions.cancel();
            gui = ui.gui;
            sessionId = UUID.randomUUID().toString();
            actions = gui == null ? null : new BridgeActions(gui);
        }
        if (actions != null) actions.tick();
        for (int i = 0; i < 8; i++) {
            Runnable task = pending.poll();
            if (task == null) break;
            task.run();
        }
    }

    private JSONObject execute(BridgeRequest request) {
        boolean ready = gui != null && gui.map != null && gui.map.player() != null;
        if (!ready) {
            if (request.method().equals("get_state"))
                return success(new JSONObject().put("connected", false));
            return BridgeServer.error("not_in_game", "Log in and enter the world first");
        }
        JSONObject args = request.arguments();
        if (args.has("session_id") && !sessionId.equals(args.getString("session_id")))
            return BridgeServer.error("stale_session", "Read get_state again after reconnecting");
        try {
            JSONObject result = switch (request.method()) {
                case "get_state" -> BridgeSnapshot.state(gui).put("action", actions.current());
                case "get_inventory" -> BridgeSnapshot.inventory(gui);
                case "list_nearby" -> BridgeSnapshot.nearby(gui, args);
                case "move_to" -> actions.move(args);
                case "interact" -> actions.interact(args);
                case "choose_option" -> actions.choose(args);
                case "stop" -> actions.stop();
                default -> throw new IllegalArgumentException("Unknown bridge method");
            };
            if (result.has("ok")) return result;
            return success(result.put("session_id", sessionId));
        } catch (Loading e) {
            return BridgeServer.error("loading", "Game resources are still loading; read state again");
        }
    }

    private static JSONObject success(JSONObject result) {
        return new JSONObject().put("ok", true).put("result", result);
    }

    public void close() {
        if (actions != null) actions.cancel();
        if (server != null) server.close();
        pending.clear();
    }
}
