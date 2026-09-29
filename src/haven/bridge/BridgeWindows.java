package haven.bridge;

import haven.*;
import java.util.List;
import org.json.JSONObject;

/** Named, already-created client windows. Called only from the UI dispatcher. */
final class BridgeWindows {
    static final List<String> NAMES = List.of("character", "quests", "inventory", "equipment", "map");

    private static Window target(GameUI gui, String name) {
        return switch (name) {
            case "character" -> gui.chrwdg;
            case "quests" -> gui.chrwdg == null || gui.chrwdg.quest == null ? null : gui.chrwdg;
            case "inventory" -> gui.maininv == null ? null : gui.maininv.getparent(Window.class);
            case "equipment" -> {
                Equipory equipment = gui.getequipory();
                yield equipment == null ? null : equipment.getparent(Window.class);
            }
            case "map" -> gui.mapfile;
            default -> throw new IllegalArgumentException("Unsupported window");
        };
    }

    private static Tabs.Tab tab(GameUI gui, String name) {
        return name.equals("quests") && gui.chrwdg != null ? gui.chrwdg.questtab : null;
    }

    static JSONObject snapshot(GameUI gui) {
        JSONObject windows = new JSONObject();
        for (String name : NAMES) windows.put(name, state(target(gui, name), tab(gui, name)));
        return windows;
    }

    static JSONObject open(GameUI gui, String name) {
        Window window = target(gui, name);
        Tabs.Tab tab = tab(gui, name);
        if (!show(window, tab))
            return BridgeServer.error("window_unavailable", "This window has not been created by the client yet");
        return state(window, tab).put("window", name).put("status", "opened");
    }

    static JSONObject state(Window window, Tabs.Tab tab) {
        boolean available = window != null && window.parent != null;
        boolean visible = available && window.visible() && window.tvisible()
            && (tab == null || (tab.visible() && tab.tvisible()));
        return new JSONObject().put("available", available).put("visible", visible);
    }

    static boolean show(Window window, Tabs.Tab tab) {
        if (window == null || window.parent == null) return false;
        if (!window.visible()) window.show();
        if (tab != null && !tab.visible()) tab.showtab();
        window.raise();
        window.parent.setfocus(window);
        return true;
    }
}
