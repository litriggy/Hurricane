package haven.bridge;

import haven.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Session-local handles include client-only buttons, not just server-registered widgets. */
final class BridgeWidgets {
    private final Widget root;
    private final Map<Widget, String> ids = new WeakHashMap<>();
    private final Map<String, WeakReference<Widget>> widgets = new HashMap<>();
    private long nextId;

    BridgeWidgets(Widget root) { this.root = root; }

    boolean contains(Widget w) {
        for (Widget p = w; p != null; p = p.parent) if (p == root) return true;
        return false;
    }

    static boolean visible(Widget w) {
        // Window.visible() reports a closing animation as hidden before its raw flag changes.
        for (Widget p = w; p != null; p = p.parent) if (!p.visible()) return false;
        return true;
    }

    String id(Widget w) {
        if (w == null || !contains(w)) return null;
        String id = ids.get(w);
        if (id == null) {
            id = "u" + (++nextId);
            ids.put(w, id);
            widgets.put(id, new WeakReference<>(w));
        }
        return id;
    }

    Widget find(String id) {
        WeakReference<Widget> ref = widgets.get(id);
        Widget w = ref == null ? null : ref.get();
        return w != null && contains(w) ? w : null;
    }

    Widget server(int id) {
        Widget w = root.ui.getwidget(id);
        return contains(w) ? w : null;
    }

    List<Widget> all() { return descendants(root); }

    static List<Widget> descendants(Widget root) {
        List<Widget> result = new ArrayList<>();
        Deque<Widget> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty() && result.size() < 20000) {
            Widget w = stack.pop();
            result.add(w);
            for (Widget c = w.lchild; c != null; c = c.prev) stack.push(c);
        }
        return result;
    }

    JSONObject snapshot(JSONObject args) {
        widgets.entrySet().removeIf(e -> e.getValue().get() == null || !contains(e.getValue().get()));
        Widget start = args.has("root_id") ? find(args.getString("root_id")) : root;
        if (start == null) return error("widget_missing", "Read get_ui again; this handle is no longer attached");
        List<Widget> all = descendants(start);
        boolean hidden = args.optBoolean("include_hidden", false);
        List<Widget> selected = all.stream().filter(w -> hidden || visible(w)).toList();
        int offset = args.optInt("offset", 0), limit = args.optInt("limit", 200);
        JSONArray list = new JSONArray();
        for (int i = offset; i < Math.min(selected.size(), offset + limit); i++) list.put(describe(selected.get(i)));
        return new JSONObject().put("root_id", id(start)).put("widgets", list).put("total", selected.size())
            .put("next_offset", offset + limit < selected.size() ? offset + limit : JSONObject.NULL)
            .put("tree_truncated", all.size() == 20000).put("scope", "game_ui");
    }

    JSONObject describe(Widget w) {
        int serverId = w.ui == null ? -1 : w.ui.widgetid(w);
        JSONObject data = new JSONObject().put("id", id(w)).put("server_id", serverId >= 0 ? serverId : JSONObject.NULL)
            .put("parent_id", nullable(id(w.parent))).put("type", w.getClass().getName())
            .put("visible", visible(w)).put("position", coord(w.c)).put("size", coord(w.sz));
        JSONArray actions = new JSONArray();
        actions.put("click").put("scroll");
        if (serverId >= 0) actions.put("widget_message");
        if (w instanceof Window window) {
            putText(data, "title", window.cap);
            actions.put("show").put("close");
        }
        if (w instanceof Tabs.Tab) actions.put("show");
        if (w instanceof Button button) {
            putText(data, "text", button.text == null ? null : button.text.text);
            data.put("disabled", button.disabled());
        }
        if (w instanceof Label label) putText(data, "text", label.texts);
        if (w instanceof SkillWnd.SkillGrid grid) {
            JSONObject skills = BridgeSkills.grid(grid);
            for (String key : skills.keySet()) data.put(key, skills.get(key));
        }
        if (w instanceof CharWnd.TabProxy proxy) data.put("tab", proxy.id);
        if (w instanceof ISBox box) {
            JSONObject material = new JSONObject().put("counts", new JSONArray(box.counts())).put("label", box.label())
                .put("resource", JSONObject.NULL).put("name", JSONObject.NULL);
            try {
                Resource resource = box.resource().get();
                material.put("resource", resource.name).put("loading", false);
                Resource.Tooltip tip = resource.layer(Resource.tooltip);
                if (tip != null) putText(material, "name", tip.t);
            } catch (Loading e) { material.put("loading", true); }
            data.put("material", material);
        }
        if (w instanceof TextEntry entry) {
            data.put("password", entry.pw);
            if (!entry.pw) {
                putText(data, "text", entry.text());
                actions.put("set_text").put("activate");
            }
        }
        if (w instanceof ACheckBox box) {
            data.put("checked", box.state()); actions.put("set_checked");
            if (w instanceof CheckBox cb) putText(data, "text", cb.label());
        }
        if (w instanceof HSlider slider) {
            data.put("value", slider.val).put("min", slider.min).put("max", slider.max);
            actions.put("set_value");
        }
        if (w instanceof CharWnd.AttrWdg attr) {
            data.put("attribute", attr.nm).put("base", attr.attr.base).put("effective", attr.attr.comp);
            if (attr instanceof SAttrWnd.SAttr skill)
                data.put("planned_base", skill.tbv).put("cost", skill.cost);
        }
        if (w instanceof GItem item) data.put("item_id", serverId);
        if (w instanceof WItem item) data.put("item_id", w.ui.widgetid(item.item));
        if (w.tooltip instanceof String text) putText(data, "tooltip", text);
        else if (w.tooltip instanceof Text text) putText(data, "tooltip", text.text);
        return data.put("actions", actions);
    }

    JSONObject action(JSONObject args) {
        Widget w = find(args.getString("widget_id"));
        if (w == null) return error("widget_missing", "Read get_ui again; this handle is no longer attached");
        String action = args.getString("action");
        if (!action.equals("show") && !action.equals("close") && !visible(w))
            return error("widget_hidden", "Show the window or tab first");
        if (w instanceof TextEntry entry && entry.pw) return error("protected_widget", "Password fields are not exposed");
        switch (action) {
            case "show" -> {
                if (!(w instanceof Window) && !(w instanceof Tabs.Tab)) return wrongType();
                List<Widget> ancestors = new ArrayList<>();
                for (Widget p = w; p != root && p != null; p = p.parent) ancestors.add(p);
                Collections.reverse(ancestors);
                for (Widget p : ancestors) {
                    if (p instanceof Tabs.Tab tab) tab.showtab();
                    else if (p instanceof GItem.ContentsWindow win) win.wndshow(true);
                    else if (p instanceof Window win) BridgeWindows.show(win, null);
                }
            }
            case "close" -> {
                if (!(w instanceof Window window)) return wrongType();
                window.reqclose();
            }
            case "set_text" -> {
                if (!(w instanceof TextEntry entry)) return wrongType();
                entry.settext(args.getString("text"));
            }
            case "activate" -> {
                if (!(w instanceof TextEntry entry)) return wrongType();
                entry.activate(entry.text());
            }
            case "set_checked" -> {
                if (!(w instanceof ACheckBox box)) return wrongType();
                box.set(args.getBoolean("checked"));
            }
            case "set_value" -> {
                if (!(w instanceof HSlider slider)) return wrongType();
                int value = args.getInt("value");
                if (value < slider.min || value > slider.max) return error("out_of_range", "Value is outside the slider range");
                if (slider.val != value) { slider.val = value; slider.changed(); slider.fchanged(); }
            }
            case "click", "scroll" -> {
                if (w instanceof MapView) return error("use_map_action", "Use map_action with world coordinates");
                if (w instanceof Button b && b.disabled()) return error("widget_disabled", "The button is disabled");
                Coord c = args.has("x") ? new Coord(args.getInt("x"), args.getInt("y")) : w.sz.div(2);
                if (!c.isect(Coord.z, w.sz)) return error("out_of_range", "Coordinates must be inside the widget");
                return modifiers(w.ui, args.optInt("modifiers", 0), () -> {
                    boolean handled;
                    if (action.equals("scroll")) {
                        int n = args.getInt("amount");
                        handled = w.ui.dispatch(w, new Widget.MouseWheelEvent(c, n, n));
                    } else {
                        int button = args.optInt("button", 1);
                        handled = w.ui.dispatch(w, new Widget.MouseDownEvent(c, button));
                        handled = w.ui.dispatch(w, new Widget.MouseUpEvent(c, button)) || handled;
                    }
                    return new JSONObject().put("status", "input_dispatched").put("handled", handled);
                });
            }
            default -> throw new IllegalArgumentException("Unsupported UI action");
        }
        return new JSONObject().put("status", "input_dispatched").put("visible", visible(w));
    }

    JSONObject message(JSONObject args) {
        Widget w = find(args.getString("widget_id"));
        if (w == null) return error("widget_missing", "Read get_ui again");
        if (w.ui.widgetid(w) < 0) return error("client_only_widget", "Use ui_action on a client-only control");
        if (w instanceof TextEntry entry && entry.pw) return error("protected_widget", "Password fields are not exposed");
        w.wdgmsg(args.getString("message").intern(), BridgeProtocol.values(args.getJSONArray("arguments")));
        return new JSONObject().put("status", "message_sent");
    }

    static <T> T modifiers(UI ui, int flags, Supplier<T> command) {
        boolean shift = ui.modshift, ctrl = ui.modctrl, meta = ui.modmeta, sup = ui.modsuper;
        try {
            ui.modshift = (flags & UI.MOD_SHIFT) != 0; ui.modctrl = (flags & UI.MOD_CTRL) != 0;
            ui.modmeta = (flags & UI.MOD_META) != 0; ui.modsuper = (flags & UI.MOD_SUPER) != 0;
            return command.get();
        } finally {
            ui.modshift = shift; ui.modctrl = ctrl; ui.modmeta = meta; ui.modsuper = sup;
        }
    }

    static JSONObject coord(Coord c) { return new JSONObject().put("x", c.x).put("y", c.y); }
    static Object nullable(Object v) { return v == null ? JSONObject.NULL : v; }
    static void putText(JSONObject data, String key, String text) {
        data.put(key, text == null ? JSONObject.NULL : text.substring(0, Math.min(4096, text.length())));
        if (text != null && text.length() > 4096) data.put(key + "_truncated", true);
    }
    private static JSONObject wrongType() { return error("wrong_widget_type", "This control does not support that action"); }
    private static JSONObject error(String code, String message) { return BridgeServer.error(code, message); }
}
