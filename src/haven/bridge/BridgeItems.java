package haven.bridge;

import haven.*;
import haven.res.ui.tt.q.qbuff.QBuff;
import haven.res.ui.tt.wear.Wear;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

final class BridgeItems {
    private final GameUI gui;
    private final BridgeWidgets widgets;
    BridgeItems(GameUI gui, BridgeWidgets widgets) { this.gui = gui; this.widgets = widgets; }

    JSONObject snapshot() {
        JSONArray inventories = new JSONArray(), equipment = new JSONArray(), allItems = new JSONArray();
        JSONArray main = new JSONArray();
        for (Widget w : widgets.all()) {
            if (w instanceof Inventory inv) {
                JSONArray items = new JSONArray();
                List<WItem> sorted = new ArrayList<>(inv.wmap.values());
                sorted.sort(Comparator.comparingInt((WItem i) -> i.c.y).thenComparingInt(i -> i.c.x));
                for (WItem i : sorted) items.put(item(i.item).put("slot", BridgeWidgets.coord(i.c.sub(1, 1).div(Inventory.sqsz)))
                    .put("size", BridgeWidgets.coord(i.sz.div(Inventory.sqsz))));
                JSONObject data = location(inv).put("inventory_id", gui.ui.widgetid(inv))
                    .put("size", BridgeWidgets.coord(inv.isz)).put("items", items).put("main", inv == gui.maininv);
                JSONArray blocked = new JSONArray();
                if (inv.sqmask != null) for (int i = 0; i < inv.sqmask.length; i++)
                    if (inv.sqmask[i]) blocked.put(BridgeWidgets.coord(new Coord(i % inv.isz.x, i / inv.isz.x)));
                data.put("blocked_slots", blocked);
                inventories.put(data);
                if (inv == gui.maininv) main = items;
            } else if (w instanceof Equipory eq) {
                JSONArray slots = new JSONArray();
                for (int i = 0; i < eq.slots.length; i++) {
                    JSONObject slot = new JSONObject().put("slot", i)
                        .put("name", Equipory.etts[i] == null ? JSONObject.NULL : Equipory.etts[i].text);
                    slot.put("item", eq.slots[i] == null ? JSONObject.NULL : item(eq.slots[i].item));
                    slots.put(slot);
                }
                equipment.put(location(eq).put("equipment_id", gui.ui.widgetid(eq)).put("slots", slots)
                    .put("player", eq == gui.getequipory()));
            }
            if (w instanceof GItem item) allItems.put(item(item).put("parent_id", BridgeWidgets.nullable(widgets.id(item.parent))));
        }
        return new JSONObject().put("available", gui.maininv != null).put("items", main).put("inventories", inventories)
            .put("equipment", equipment).put("all_items", allItems).put("hand_count", gui.hand.size())
            .put("held_item", gui.vhand == null ? JSONObject.NULL : item(gui.vhand.item)).put("scope", "loaded_containers_and_equipment");
    }

    JSONObject item(GItem item) {
        JSONObject data = new JSONObject().put("item_id", gui.ui.widgetid(item)).put("widget_id", widgets.id(item))
            .put("quantity", item.num < 0 ? JSONObject.NULL : item.num).put("meter", item.meter)
            .put("contents_loaded", item.contents != null).put("contents_id", BridgeWidgets.nullable(widgets.id(item.contents)));
        try {
            data.put("resource", item.getres().name);
            List<ItemInfo> info = item.info();
            ItemInfo.Name name = ItemInfo.find(ItemInfo.Name.class, info);
            BridgeWidgets.putText(data, "name", name == null ? null : name.str.text);
            GItem.NumberInfo count = ItemInfo.find(GItem.NumberInfo.class, info);
            if (count != null) data.put("quantity", count.itemnum());
            QBuff q = item.getQBuff();
            if (q != null) data.put("quality", q.q);
            Wear wear = ItemInfo.find(Wear.class, info);
            if (wear != null) data.put("wear", new JSONObject().put("damage", wear.d).put("maximum", wear.m));
            data.put("loading", false);
        } catch (Loading e) { data.put("loading", true); }
        return data;
    }

    private JSONObject location(Widget w) {
        Window win = w.getparent(Window.class);
        return new JSONObject().put("widget_id", widgets.id(w)).put("window_id", BridgeWidgets.nullable(widgets.id(win)))
            .put("window", win == null ? JSONObject.NULL : win.cap).put("visible", BridgeWidgets.visible(w));
    }

    JSONObject action(JSONObject a) {
        Widget w = widgets.server(a.getInt("item_id"));
        if (!(w instanceof GItem item)) return error("item_missing", "Read get_inventory again");
        int mods = a.optInt("modifiers", 0);
        switch (a.getString("action")) {
            case "take" -> {
                if (!gui.hand.isEmpty()) return error("hand_not_empty", "Place the held item before taking another");
                item.wdgmsg("take", Coord.z);
            }
            case "transfer" -> item.wdgmsg("transfer", Coord.z, 1);
            case "drop" -> item.wdgmsg("drop", Coord.z, 1);
            case "interact" -> item.wdgmsg("iact", Coord.z, mods);
            case "item_use" -> {
                if (gui.hand.isEmpty()) return emptyHand();
                item.wdgmsg("itemact", mods);
            }
            case "open_contents" -> {
                if (item.contentswnd == null || !widgets.contains(item.contentswnd))
                    return error("contents_unavailable", "This item has no loaded contents window; interact and inspect the resulting menu");
                item.contentswnd.wndshow(true);
                return new JSONObject().put("status", "opened").put("window_id", widgets.id(item.contentswnd));
            }
            default -> throw new IllegalArgumentException("Unknown item action");
        }
        return new JSONObject().put("status", "item_action_sent");
    }

    JSONObject inventoryDrop(JSONObject a) {
        Widget w = widgets.server(a.getInt("inventory_id"));
        if (!(w instanceof Inventory inv)) return error("inventory_missing", "Read get_inventory again");
        if (gui.hand.isEmpty()) return emptyHand();
        Coord slot = new Coord(a.getInt("x"), a.getInt("y"));
        if (!slot.isect(Coord.z, inv.isz)) return error("out_of_range", "Slot is outside the inventory");
        int index = slot.y * inv.isz.x + slot.x;
        if (inv.sqmask != null && index < inv.sqmask.length && inv.sqmask[index])
            return error("blocked_slot", "This inventory slot is disabled");
        inv.wdgmsg("drop", slot);
        return new JSONObject().put("status", "drop_sent");
    }

    JSONObject equipmentDrop(JSONObject a) {
        Widget w = widgets.server(a.getInt("equipment_id"));
        if (!(w instanceof Equipory eq)) return error("equipment_missing", "Read get_inventory again");
        if (gui.hand.isEmpty()) return emptyHand();
        int slot = a.getInt("slot");
        if (slot >= eq.slots.length) return error("out_of_range", "Equipment slot does not exist");
        eq.wdgmsg("drop", slot);
        return new JSONObject().put("status", "drop_sent");
    }

    private static JSONObject emptyHand() { return error("hand_empty", "Take an item first and confirm held_item in get_inventory"); }
    private static JSONObject error(String code, String message) { return BridgeServer.error(code, message); }
}
