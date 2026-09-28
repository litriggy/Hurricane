package haven.bridge;

import haven.*;
import haven.automated.pathfinder.Pathfinder;
import java.util.UUID;
import org.json.JSONObject;
import static haven.OCache.posres;

final class BridgeActions {
    private final GameUI gui;
    private Pathfinder path;
    private Thread thread;
    private Coord2d destination;
    private String actionId;
    private String status = "idle";
    private long started;

    BridgeActions(GameUI gui) { this.gui = gui; }

    JSONObject move(JSONObject args) {
        if (gui.map.pfthread != null && gui.map.pfthread.isAlive())
            return BridgeServer.error("busy", "A pathfinder is already running; stop it first");
        Coord2d target = new Coord2d(args.getDouble("x"), args.getDouble("y"));
        if (target.dist(gui.map.player().rc) > 440)
            return BridgeServer.error("out_of_range", "Move at most 440 world units per command");
        if (target.dist(gui.map.player().rc) <= 3)
            return new JSONObject().put("status", "already_at_destination");
        gui.map.pfLeftClick(target.floor(), null);
        if (gui.map.pfthread == thread || gui.map.pfthread == null)
            return BridgeServer.error("path_not_started", "Pathfinder did not start");
        path = gui.map.pf;
        thread = gui.map.pfthread;
        destination = target;
        actionId = UUID.randomUUID().toString();
        status = "running";
        started = System.nanoTime();
        return current();
    }

    void tick() {
        if (!status.equals("running")) return;
        Gob player = gui.map == null ? null : gui.map.player();
        if (player == null || gui.map.pf != path) {
            cancel();
        } else if (!thread.isAlive()) {
            status = player.rc.dist(destination) <= 3 ? "arrived" : "failed";
        } else if (System.nanoTime() - started > 60_000_000_000L) {
            cancel();
            halt();
            status = "timed_out";
        }
    }

    JSONObject current() {
        JSONObject result = new JSONObject().put("status", status);
        if (actionId != null) result.put("action_id", actionId)
            .put("destination", new JSONObject().put("x", destination.x).put("y", destination.y));
        return result;
    }

    JSONObject interact(JSONObject args) {
        if (gui.map.pfthread != null && gui.map.pfthread.isAlive())
            return BridgeServer.error("busy", "Wait for movement to finish first");
        Gob gob = gui.map.glob.oc.getgob(Long.parseLong(args.getString("target_id")));
        if (gob == null || gob.removed || gob.virtual)
            return BridgeServer.error("target_missing", "Target is no longer loaded");
        if (gob.rc.dist(gui.map.player().rc) > 55)
            return BridgeServer.error("out_of_range", "Move within 55 world units before interacting");
        gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), 3, 0, 0,
            (int) gob.id, gob.rc.floor(posres), 0, -1);
        return new JSONObject().put("status", "click_sent")
            .put("next", "Read get_state for the actual menu or changed world state");
    }

    JSONObject choose(JSONObject args) {
        Widget widget = gui.ui.getwidget(args.getInt("menu_id"));
        if (!(widget instanceof FlowerMenu menu))
            return BridgeServer.error("menu_missing", "Read the current menu from get_state");
        for (FlowerMenu.Petal option : menu.opts) {
            if (option.name.equals(args.getString("option"))) {
                menu.choose(option);
                return new JSONObject().put("status", "selection_sent");
            }
        }
        return BridgeServer.error("option_missing", "The current menu does not contain that option");
    }

    JSONObject stop() {
        cancel();
        halt();
        return new JSONObject().put("status", "stop_sent")
            .put("scope", "bridge_path_and_current_character_action");
    }

    void cancel() {
        if (path != null && status.equals("running")) {
            path.terminate = true;
            thread.interrupt();
            status = "cancelled";
        }
    }

    private void halt() {
        Gob player = gui.map == null ? null : gui.map.player();
        if (player != null) gui.map.wdgmsg("click", Coord.z, player.rc.floor(posres), 1, 0);
    }
}
