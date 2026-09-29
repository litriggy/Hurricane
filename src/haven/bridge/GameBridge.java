package haven.bridge;

import haven.*;
import java.io.IOException;
import java.net.BindException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.json.JSONObject;

/** One bridge per UI lifetime, started on the first UI tick after the old UI is destroyed. */
public final class GameBridge implements AutoCloseable {
    private final UI ui;
    private final ArrayBlockingQueue<Runnable> pending = new ArrayBlockingQueue<>(32);
    private BridgeServer server;
    private boolean attempted;
    private volatile boolean started;
    private boolean closed;
    private final Preferences preferences;
    private final Map<String, String> environment;
    private final String token;
    private BridgeSettings settings;
    private String lastError;
    private GameUI gui;
    private BridgeActions actions;
    private BridgeWidgets widgets;
    private BridgeItems items;
    private BridgeGameplay gameplay;
    private String sessionId;

    public GameBridge(UI ui) { this(ui, Utils.prefs(), System.getenv()); }

    GameBridge(UI ui, Preferences preferences, Map<String, String> environment) {
        this.ui = ui;
        this.preferences = preferences;
        this.environment = Map.copyOf(environment);
        this.token = environment.get("HURRICANE_BRIDGE_TOKEN");
        try { settings = BridgeSettings.load(preferences, environment); }
        catch (RuntimeException e) {
            settings = BridgeSettings.validated(false, "18711");
            lastError = "Saved bridge settings could not be read. Apply new settings to recover.";
        }
    }

    public void start() { if (!closed) started = true; }

    public BridgeSettings settings() { return settings; }
    public BridgeSettings launcherDefaults() { return BridgeSettings.defaults(environment); }
    public String lastError() { return lastError; }
    public boolean tokenConfigured() { return token != null && token.matches("[A-Za-z0-9_-]{32,}"); }
    public String statusText() {
        if (closed) return "Stopped (client UI closed)";
        if (server != null) return "Listening on 127.0.0.1:" + server.port();
        if (!settings.enabled) return "Disabled";
        return attempted ? "Not running" : "Waiting for client UI";
    }
    public String activityText() {
        if (server == null || server.secondsSinceRequest() < 0) return "No authenticated requests yet";
        return server.requests() + " authenticated requests; last " + server.secondsSinceRequest() + "s ago";
    }
    public boolean inGame() { return ui.gui != null && ui.gui.map != null && ui.gui.map.player() != null; }

    /** UI thread only. Bind/save first so a bad port cannot tear down a working connection. */
    public String applySettings(boolean enabled, String port) {
        if (closed || !started) return lastError = "The client UI is not ready. Open this panel again.";
        BridgeServer replacement = null;
        try {
            BridgeSettings next = BridgeSettings.validated(enabled, port);
            boolean replace = enabled && (server == null || server.port() != Integer.parseInt(next.port));
            if (replace) replacement = listen(next);
            next.save(preferences);
            if (!enabled || replace) {
                if (actions != null) actions.cancel();
                if (gameplay != null) gameplay.releaseCombat();
                BridgeServer previous = server;
                server = replacement;
                replacement = null;
                if (previous != null) previous.close();
                if (server == null) cancelPending();
                resetAccess(ui.gui);
            }
            settings = next;
            attempted = true;
            lastError = null;
            if (replace) System.err.printf("Hurricane bridge listening on 127.0.0.1:%d%n", server.port());
            return null;
        } catch (IOException | IllegalArgumentException | BackingStoreException | SecurityException e) {
            if (replacement != null) replacement.close();
            return lastError = failure(e);
        }
    }

    private BridgeServer listen(BridgeSettings settings) throws IOException {
        if (!tokenConfigured()) throw new IllegalArgumentException("A valid HURRICANE_BRIDGE_TOKEN is required at launch.");
        return new BridgeServer(new BridgeServer.Config(Integer.parseInt(settings.port), token), this::execute, task -> {
            if (!pending.offer(task)) throw new RejectedExecutionException();
        });
    }

    private static String failure(Exception error) {
        if (error instanceof BindException) return "Port is already in use. Choose another port and Apply.";
        if (error instanceof IllegalArgumentException) return error.getMessage();
        if (error instanceof BackingStoreException || error instanceof SecurityException)
            return "Could not save bridge settings. Existing settings were kept.";
        return "Could not open the local bridge. Check the port and Apply again.";
    }

    private void resetAccess(GameUI next) {
        gui = next;
        sessionId = UUID.randomUUID().toString();
        actions = gui == null ? null : new BridgeActions(gui);
        widgets = gui == null ? null : new BridgeWidgets(gui);
        items = gui == null ? null : new BridgeItems(gui, widgets);
        gameplay = gui == null ? null : new BridgeGameplay(gui, widgets);
    }

    public void tick() {
        if (!started || closed) return;
        if (!attempted) {
            attempted = true;
            if (settings.enabled) {
                try {
                    settings = BridgeSettings.validated(true, settings.port);
                    server = listen(settings);
                    System.err.printf("Hurricane bridge listening on 127.0.0.1:%d%n", server.port());
                } catch (IOException | IllegalArgumentException e) {
                    lastError = failure(e);
                    System.err.println("Hurricane bridge: " + lastError);
                }
            }
        }
        if (server == null) return;
        if (gui != ui.gui) {
            if (actions != null) actions.cancel();
            resetAccess(ui.gui);
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
                return success(new JSONObject().put("connected", false).put("bridge_version", "0.4.0"));
            return BridgeServer.error("not_in_game", "Log in and enter the world first");
        }
        JSONObject args = request.arguments();
        if (args.has("session_id") && !sessionId.equals(args.getString("session_id")))
            return BridgeServer.error("stale_session", "Read get_state again after reconnecting");
        try {
            JSONObject result = switch (request.method()) {
                case "get_state" -> BridgeSnapshot.state(gui).put("action", actions.current());
                case "get_inventory" -> items.snapshot();
                case "get_map" -> BridgeMap.snapshot(gui.map.glob.map, gui.map.player().rc, args);
                case "get_quests" -> BridgeSnapshot.quests(gui);
                case "get_skills" -> BridgeSkills.snapshot(gui.chrwdg, widgets);
                case "skill_action" -> BridgeSkills.action(gui.chrwdg, widgets, args);
                case "get_ui" -> widgets.snapshot(args);
                case "ui_action" -> widgets.action(args);
                case "widget_message" -> widgets.message(args);
                case "list_actions" -> gameplay.listActions(args);
                case "use_action" -> gameplay.useAction(args);
                case "get_crafting" -> gameplay.crafting();
                case "craft" -> gameplay.craft(args);
                case "get_combat" -> gameplay.combat();
                case "combat_action" -> gameplay.combatAction(args);
                case "select_quest" -> gameplay.selectQuest(args);
                case "quest_option" -> gameplay.questOption(args);
                case "item_action" -> items.action(args);
                case "inventory_drop" -> items.inventoryDrop(args);
                case "equipment_drop" -> items.equipmentDrop(args);
                case "open_window" -> BridgeWindows.open(gui, args.getString("window"));
                case "list_nearby" -> BridgeSnapshot.nearby(gui, args);
                case "move_to" -> actions.move(args);
                case "interact" -> actions.interact(args);
                case "map_action" -> actions.mapAction(args);
                case "choose_option" -> actions.choose(args);
                case "cancel_menu" -> actions.cancelMenu(args);
                case "stop" -> { gameplay.releaseCombat(); yield actions.stop(); }
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
        closed = true;
        started = false;
        if (actions != null) actions.cancel();
        if (gameplay != null) gameplay.releaseCombat();
        if (server != null) server.close();
        server = null;
        cancelPending();
    }

    private void cancelPending() {
        Runnable task;
        while ((task = pending.poll()) != null) if (task instanceof Future<?> future) future.cancel(false);
    }
}
