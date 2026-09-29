package haven.bridge;

import haven.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class BridgeSnapshot {
    static JSONObject state(GameUI gui) {
        Gob player = gui.map.player();
        JSONObject meters = new JSONObject();
        for (String name : List.of("hp", "stam", "nrj")) {
            JSONArray values = new JSONArray();
            List<IMeter.Meter> entries = gui.getmeters(name);
            if (entries != null) for (IMeter.Meter meter : entries) values.put(meter.a);
            meters.put(name, values);
        }
        JSONObject result = new JSONObject().put("connected", true)
            .put("player", gob(player)).put("moving", player.isMoving()).put("meters", meters)
            .put("working", gui.prog != null && gui.prog.prog >= 0);
        JSONArray menus = new JSONArray();
        for (FlowerMenu menu : gui.ui.root.children(FlowerMenu.class)) {
            JSONArray options = new JSONArray();
            for (FlowerMenu.Petal option : menu.opts) options.put(option.name);
            menus.put(new JSONObject().put("menu_id", gui.ui.widgetid(menu)).put("options", options));
        }
        return result.put("menus", menus).put("windows", BridgeWindows.snapshot(gui)).put("bridge_version", "0.4.0")
            .put("hand_count", gui.hand.size()).put("placement", placement(gui.map))
            .put("messages", messages(gui.syslog)).put("message_count", messageCount(gui.syslog));
    }

    static Object placement(MapView map) {
        MapView.Plob placing = map.placementPreview();
        return placing == null ? JSONObject.NULL : gob(placing).put("angle", Math.toDegrees(placing.a))
            .put("pinned", map.placementPinned());
    }

    static int messageCount(ChatUI.Channel log) {
        if (log == null) return 0;
        synchronized (log.rmsgs) { return log.rmsgs.size(); }
    }

    static JSONArray messages(ChatUI.Channel log) {
        JSONArray result = new JSONArray();
        if (log == null) return result;
        synchronized (log.rmsgs) {
            for (int i = Math.max(0, log.rmsgs.size() - 20); i < log.rmsgs.size(); i++) {
                ChatUI.Channel.RenderedMessage entry = log.rmsgs.get(i);
                JSONObject row = new JSONObject().put("index", i).put("age_seconds", Math.max(0, Utils.ntime() - entry.msg.time));
                try {
                    String text = entry.msg instanceof ChatUI.Channel.SimpleMessage simple ? simple.text : entry.text().text;
                    BridgeWidgets.putText(row, "text", text);
                    row.put("loading", false);
                } catch (Loading e) { row.put("text", JSONObject.NULL).put("loading", true); }
                result.put(row);
            }
        }
        return result;
    }

    static JSONObject nearby(GameUI gui, JSONObject args) {
        Gob player = gui.map.player();
        double radius = args.getDouble("radius");
        int limit = args.getInt("limit");
        List<JSONObject> objects = new ArrayList<>();
        synchronized (gui.map.glob.oc) {
            for (Gob gob : gui.map.glob.oc) {
                double distance = gob.rc.dist(player.rc);
                if (gob.id != player.id && !gob.virtual && !gob.removed && distance <= radius) {
                    JSONObject object = gob(gob).put("distance", distance);
                    String filter = args.optString("filter", "");
                    if (filter.isEmpty() || object.optString("resource", "").contains(filter)) objects.add(object);
                }
            }
        }
        objects.sort(Comparator.comparingDouble(obj -> obj.getDouble("distance")));
        JSONArray nearby = new JSONArray();
        for (JSONObject object : objects.subList(0, Math.min(limit, objects.size()))) nearby.put(object);
        return new JSONObject().put("objects", nearby)
            .put("truncated", objects.size() > limit).put("scope", "loaded_objects");
    }

    static JSONObject inventory(GameUI gui) {
        if (gui.maininv == null) return new JSONObject().put("available", false);
        JSONArray items = new JSONArray();
        for (WItem item : gui.maininv.getAllItems()) {
            JSONObject data = new JSONObject().put("item_id", gui.ui.widgetid(item.item));
            try {
                data.put("resource", item.item.getres().name);
                ItemInfo.Name name = ItemInfo.find(ItemInfo.Name.class, item.item.info());
                data.put("name", name == null ? JSONObject.NULL : name.str.text);
                data.put("loading", false);
            } catch (Loading e) {
                data.put("loading", true);
            }
            items.put(data);
        }
        return new JSONObject().put("available", true).put("items", items);
    }

    static JSONObject quests(GameUI gui) {
        return quests(gui.chrwdg == null ? null : gui.chrwdg.quest);
    }

    static JSONObject quests(QuestWnd window) {
        if (window == null) return new JSONObject().put("available", false);
        JSONArray current = new JSONArray();
        for (QuestWnd.Quest quest : window.cqst.quests) current.put(quest(window, quest));
        JSONArray completed = new JSONArray();
        for (QuestWnd.Quest quest : window.dqst.quests) completed.put(quest(window, quest));
        return new JSONObject().put("available", true).put("quests", current)
            .put("completed_quests", completed).put("scope", "loaded_quests")
            .put("selected_quest_id", window.quest == null ? JSONObject.NULL : window.quest.questid());
    }

    private static JSONObject quest(QuestWnd window, QuestWnd.Quest quest) {
        JSONObject data = new JSONObject().put("id", quest.id).put("done", quest.done)
            .put("ncond", quest.ncond).put("ndcond", quest.ndcond);
        try {
            data.put("title", quest.title()).put("title_loading", false);
        } catch (Loading e) {
            data.put("title", JSONObject.NULL).put("title_loading", true);
        }
        data.put("objectives_loaded", false).put("objectives", JSONObject.NULL);
        for (Widget child : window.questbox.children()) {
            if (!(child instanceof QuestWnd.Quest.Box box) || box.id != quest.id || box.cond == null)
                continue;
            JSONArray objectives = new JSONArray();
            for (QuestWnd.Quest.Condition condition : box.cond) {
                objectives.put(new JSONObject().put("desc", condition.desc).put("done", condition.done)
                    .put("status", condition.status == null ? JSONObject.NULL : condition.status));
            }
            data.put("objectives", objectives).put("objectives_loaded", box.cond.length == quest.ncond);
            JSONArray options = new JSONArray();
            if (box instanceof QuestWnd.Quest.DefaultBox defaults)
                for (Pair<String, String> option : defaults.options)
                    options.put(new JSONObject().put("id", option.a).put("label", option.b));
            data.put("options", options);
            break;
        }
        return data;
    }

    static JSONObject gob(Gob gob) {
        JSONObject data = new JSONObject().put("id", Long.toString(gob.id))
            .put("x", gob.rc.x).put("y", gob.rc.y);
        try {
            Resource resource = gob.getres();
            data.put("resource", resource == null ? JSONObject.NULL : resource.name)
                .put("loading", resource == null);
        } catch (Loading e) {
            data.put("loading", true);
        }
        return data;
    }
}
