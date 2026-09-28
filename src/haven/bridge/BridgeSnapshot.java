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
        return result.put("menus", menus);
    }

    static JSONObject nearby(GameUI gui, JSONObject args) {
        Gob player = gui.map.player();
        double radius = args.getDouble("radius");
        int limit = args.getInt("limit");
        List<JSONObject> objects = new ArrayList<>();
        synchronized (gui.map.glob.oc) {
            for (Gob gob : gui.map.glob.oc) {
                double distance = gob.rc.dist(player.rc);
                if (gob.id != player.id && !gob.virtual && !gob.removed && distance <= radius)
                    objects.add(gob(gob).put("distance", distance));
            }
        }
        objects.sort(Comparator.comparingDouble(obj -> obj.getDouble("distance")));
        return new JSONObject().put("objects", new JSONArray(objects.subList(0, Math.min(limit, objects.size()))))
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
