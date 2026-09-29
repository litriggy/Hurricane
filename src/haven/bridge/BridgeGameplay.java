package haven.bridge;

import haven.*;
import java.lang.ref.WeakReference;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

final class BridgeGameplay {
    private final GameUI gui;
    private final BridgeWidgets widgets;
    private final Map<MenuGrid.Pagina, String> actionIds = new WeakHashMap<>();
    private final Map<String, WeakReference<MenuGrid.Pagina>> actions = new HashMap<>();
    private long nextAction;
    private Fightsess heldCombat;
    private int heldSlot = -1;

    BridgeGameplay(GameUI gui, BridgeWidgets widgets) { this.gui = gui; this.widgets = widgets; }

    private Set<MenuGrid.Pagina> pages() {
        Set<MenuGrid.Pagina> pages = new HashSet<>();
        if (gui.menu == null) return pages;
        for (MenuGrid.Pagina page : gui.menu.paginae) {
            for (MenuGrid.Pagina p = page; p != null && pages.add(p);) {
                try { p = p.parent(); } catch (Loading e) { break; }
            }
        }
        return pages;
    }

    JSONObject listActions(JSONObject a) {
        if (gui.menu == null) return new JSONObject().put("available", false);
        Set<MenuGrid.Pagina> pages = pages();
        actions.entrySet().removeIf(e -> e.getValue().get() == null || !pages.contains(e.getValue().get()));
        List<JSONObject> rows = new ArrayList<>();
        String filter = a.optString("filter", "").toLowerCase(Locale.ROOT);
        for (MenuGrid.Pagina page : pages) {
            String id = actionIds.computeIfAbsent(page, p -> "a" + (++nextAction));
            actions.put(id, new WeakReference<>(page));
            JSONObject row = new JSONObject().put("action_id", id);
            try {
                MenuGrid.PagButton button = page.button();
                String[] ad = button.act().ad;
                row.put("name", button.name()).put("resource", page.res().name).put("loading", false)
                    .put("local", ad.length > 0 && ad[0].equals("@"));
                MenuGrid.Pagina parent = page.parent();
                row.put("parent_resource", parent == null ? JSONObject.NULL : parent.res().name);
                JSONArray command = new JSONArray();
                for (String value : ad) command.put(value);
                row.put("command", command);
                if (!filter.isEmpty() && !(button.name() + " " + page.res().name).toLowerCase(Locale.ROOT).contains(filter)) continue;
            } catch (Loading e) {
                row.put("loading", true);
                if (!filter.isEmpty()) continue;
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing((JSONObject r) -> r.optString("resource", "")).thenComparing(r -> r.getString("action_id")));
        int offset = a.optInt("offset", 0), limit = a.optInt("limit", 200);
        JSONArray list = new JSONArray();
        for (int i = offset; i < Math.min(rows.size(), offset + limit); i++) list.put(rows.get(i));
        return new JSONObject().put("available", true).put("actions", list).put("total", rows.size())
            .put("next_offset", offset + limit < rows.size() ? offset + limit : JSONObject.NULL).put("scope", "loaded_action_menu");
    }

    JSONObject useAction(JSONObject a) {
        WeakReference<MenuGrid.Pagina> ref = actions.get(a.getString("action_id"));
        MenuGrid.Pagina page = ref == null ? null : ref.get();
        if (gui.menu == null || page == null || !pages().contains(page))
            return error("action_missing", "Read list_actions again and use an observed action_id");
        int mods = a.optInt("modifiers", 0);
        return BridgeWidgets.modifiers(gui.ui, mods, () -> {
            gui.menu.use(page.button(), new MenuGrid.Interaction(1, mods), false);
            return new JSONObject().put("status", "action_invoked");
        });
    }

    JSONObject crafting() {
        JSONArray recipes = new JSONArray();
        for (Widget w : widgets.all()) {
            if (!(w instanceof Makewindow make)) continue;
            JSONArray inputs = new JSONArray(), outputs = new JSONArray(), tools = new JSONArray(), quality = new JSONArray();
            for (Makewindow.Input in : make.inputs) inputs.put(spec(in.spec).put("index", in.idx).put("using", in.using));
            for (Makewindow.SpecWidget out : make.outputs) outputs.put(spec(out.spec));
            for (Indir<Resource> res : make.tools) tools.put(resource(res));
            for (Indir<Resource> res : make.qmod) quality.put(resource(res));
            recipes.put(new JSONObject().put("crafting_id", gui.ui.widgetid(make)).put("widget_id", widgets.id(make))
                .put("name", make.rcpnm).put("inputs", inputs).put("outputs", outputs).put("tools", tools)
                .put("quality_modifiers", quality).put("visible", BridgeWidgets.visible(make)));
        }
        return new JSONObject().put("available", recipes.length() > 0).put("recipes", recipes);
    }

    private JSONObject spec(Makewindow.Spec spec) {
        JSONObject data = new JSONObject().put("quantity", spec.num);
        try {
            data.put("resource", spec.resource().name).put("optional", spec.opt());
            ItemInfo.Name name = ItemInfo.find(ItemInfo.Name.class, spec.info());
            BridgeWidgets.putText(data, "name", name == null ? null : name.str.text);
            data.put("loading", false);
        } catch (Loading e) { data.put("loading", true); }
        return data;
    }

    JSONObject craft(JSONObject a) {
        Widget w = widgets.server(a.getInt("crafting_id"));
        if (!(w instanceof Makewindow make)) return error("crafting_missing", "Open a recipe with use_action, then read get_crafting");
        make.wdgmsg("make", a.getBoolean("all") ? 1 : 0);
        return new JSONObject().put("status", "craft_sent");
    }

    JSONObject combat() {
        Fightview fv = gui.fv;
        JSONArray relations = new JSONArray(), slots = new JSONArray();
        if (fv != null) for (Fightview.Relation rel : fv.lsrel) {
            JSONObject data = new JSONObject().put("target_id", Long.toString(rel.gobid)).put("give_state", rel.gst)
                .put("player_initiative", rel.ip).put("opponent_initiative", rel.oip)
                .put("buffs", buffs(rel.buffs)).put("relation_buffs", buffs(rel.relbuffs))
                .put("last_action", resource(rel.lastact));
            Gob gob = gui.map.glob.oc.getgob(rel.gobid);
            if (gob != null) data.put("object", BridgeSnapshot.gob(gob)).put("distance", gob.rc.dist(gui.map.player().rc));
            relations.put(data);
        }
        double now = Utils.rtime();
        if (gui.fs != null) for (int i = 0; i < gui.fs.actions.length; i++) {
            Fightsess.Action action = gui.fs.actions[i];
            if (action != null) slots.put(new JSONObject().put("slot", i).put("action", resource(action.res))
                .put("cooldown_seconds", Math.max(0, action.ct - now)));
        }
        return new JSONObject().put("active", fv != null && !fv.lsrel.isEmpty())
            .put("current_target_id", fv == null || fv.current == null ? JSONObject.NULL : Long.toString(fv.current.gobid))
            .put("relations", relations).put("actions", slots).put("buffs", fv == null ? new JSONArray() : buffs(fv.buffs))
            .put("cooldown_seconds", fv == null ? 0 : Math.max(0, fv.atkct - now))
            .put("selected_action", gui.fs == null ? -1 : gui.fs.use)
            .put("held_action", heldCombat == gui.fs ? heldSlot : -1);
    }

    private JSONArray buffs(Widget parent) {
        JSONArray list = new JSONArray();
        for (Widget w : parent.children()) if (w instanceof Buff buff) {
            JSONObject data = resource(buff.res).put("widget_id", widgets.id(buff));
            try { data.put("meter", BridgeWidgets.nullable(buff.ameteri.get())); }
            catch (Loading e) { data.put("loading", true); }
            list.put(data);
        }
        return list;
    }

    private static JSONObject resource(Indir<Resource> ref) {
        JSONObject data = new JSONObject().put("resource", JSONObject.NULL);
        if (ref == null) return data.put("loading", false);
        try {
            Resource res = ref.get();
            data.put("resource", res.name).put("loading", false);
            Resource.Tooltip tip = res.layer(Resource.tooltip);
            if (tip != null) BridgeWidgets.putText(data, "name", tip.t);
        } catch (Loading e) { data.put("loading", true); }
        return data;
    }

    JSONObject combatAction(JSONObject a) {
        String action = a.getString("action");
        if (action.equals("use") || action.equals("release")) {
            Fightsess fs = gui.fs;
            int slot = a.getInt("slot");
            if (fs == null || slot >= fs.actions.length || fs.actions[slot] == null)
                return error("combat_action_missing", "Read get_combat for current action slots");
            if (action.equals("release")) {
                fs.wdgmsg("rel", slot);
                if (heldCombat == fs && heldSlot == slot) { heldCombat = null; heldSlot = -1; }
            } else {
                releaseCombat();
                if (a.has("x")) fs.wdgmsg("use", slot, 1, a.optInt("modifiers", 0),
                    new Coord2d(a.getDouble("x"), a.getDouble("y")).floor(OCache.posres));
                else fs.wdgmsg("use", slot, 1, a.optInt("modifiers", 0));
                heldCombat = fs; heldSlot = slot;
            }
        } else {
            long target = Long.parseLong(a.getString("target_id"));
            Fightview fv = gui.fv;
            Fightview.Relation rel = fv == null ? null : fv.lsrel.stream().filter(r -> r.gobid == target).findFirst().orElse(null);
            if (rel == null) return error("combat_target_missing", "Target must be a current combat relation from get_combat");
            switch (action) {
                case "target" -> fv.wdgmsg("bump", (int)target);
                case "peace" -> {
                    if (rel.gst == 1) return new JSONObject().put("status", "already_offering_peace");
                    fv.wdgmsg("give", (int)target, 1);
                }
                case "pursue" -> fv.wdgmsg("prs", (int)target);
                default -> throw new IllegalArgumentException("Unsupported combat action");
            }
        }
        return new JSONObject().put("status", "combat_action_sent");
    }

    void releaseCombat() {
        if (heldCombat != null && heldCombat == gui.fs && heldSlot >= 0) heldCombat.wdgmsg("rel", heldSlot);
        heldCombat = null; heldSlot = -1;
    }

    JSONObject selectQuest(JSONObject a) {
        QuestWnd wnd = gui.chrwdg == null ? null : gui.chrwdg.quest;
        int id = a.getInt("quest_id");
        if (wnd == null || (wnd.cqst.quests.stream().noneMatch(q -> q.id == id) && wnd.dqst.quests.stream().noneMatch(q -> q.id == id)))
            return error("quest_missing", "Read get_quests for loaded quest IDs");
        BridgeWindows.open(gui, "quests");
        wnd.wdgmsg("qsel", id);
        return new JSONObject().put("status", "selection_sent").put("quest_id", id);
    }

    JSONObject questOption(JSONObject a) {
        QuestWnd wnd = gui.chrwdg == null ? null : gui.chrwdg.quest;
        if (wnd != null) for (Widget child : wnd.questbox.children()) {
            if (child instanceof QuestWnd.Quest.DefaultBox box && box.id == a.getInt("quest_id")) {
                for (Pair<String, String> option : box.options) if (option.a.equals(a.getString("option"))) {
                    box.wdgmsg("opt", option.a);
                    return new JSONObject().put("status", "selection_sent");
                }
            }
        }
        return error("quest_option_missing", "Select the quest and read its currently loaded options first");
    }

    private static JSONObject error(String code, String message) { return BridgeServer.error(code, message); }
}
