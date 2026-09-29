package haven.bridge;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

public final class BridgeRequest {
    private final String method;
    private final JSONObject arguments;
    private BridgeRequest(String method, JSONObject arguments) { this.method = method; this.arguments = arguments; }
    public String method() { return method; }
    public JSONObject arguments() { return arguments; }

    public static BridgeRequest parse(JSONObject body) {
        fields(body, "method arguments", "");
        String method = string(body, "method");
        JSONObject a = body.getJSONObject("arguments");
        switch (method) {
            case "get_state", "get_inventory", "get_quests", "get_crafting", "get_combat", "get_skills" -> fields(a, "", "");
            case "get_map" -> {
                fields(a, "", "x y radius include_heights");
                if (a.has("x") || a.has("y")) position(a);
                optionalInteger(a, "radius", 0, 16);
                if (a.has("include_heights")) bool(a, "include_heights");
            }
            case "skill_action" -> {
                fields(a, "session_id action skill_id", "");
                choice(a, "action", "select buy"); string(a, "skill_id");
            }
            case "get_ui" -> {
                fields(a, "", "root_id include_hidden offset limit");
                if (a.has("root_id")) string(a, "root_id");
                if (a.has("include_hidden")) bool(a, "include_hidden");
                optionalInteger(a, "offset", 0, 100000); optionalInteger(a, "limit", 1, 1000);
            }
            case "list_actions" -> {
                fields(a, "", "filter offset limit");
                if (a.has("filter")) string(a, "filter", 128, true);
                optionalInteger(a, "offset", 0, 100000); optionalInteger(a, "limit", 1, 1000);
            }
            case "list_nearby" -> {
                fields(a, "radius limit", "filter");
                number(a, "radius", 1, 100000); integer(a, "limit", 1, 1000);
                if (a.has("filter")) string(a, "filter", 128, true);
            }
            case "open_window" -> {
                fields(a, "session_id window", "");
                if (!BridgeWindows.NAMES.contains(string(a, "window"))) throw new IllegalArgumentException("Unsupported window name");
            }
            case "move_to" -> { fields(a, "session_id x y", ""); position(a); }
            case "interact" -> {
                fields(a, "session_id target_id", "button modifiers action");
                objectId(a, "target_id"); optionalInteger(a, "button", 1, 3); optionalInteger(a, "modifiers", 0, 15);
                if (a.has("action")) choice(a, "action", "click item_use");
            }
            case "choose_option" -> {
                fields(a, "session_id menu_id option", "modifiers");
                integer(a, "menu_id", 0, Integer.MAX_VALUE); string(a, "option"); optionalInteger(a, "modifiers", 0, 15);
            }
            case "cancel_menu" -> { fields(a, "session_id menu_id", ""); integer(a, "menu_id", 0, Integer.MAX_VALUE); }
            case "select_quest" -> { fields(a, "session_id quest_id", ""); integer(a, "quest_id", 0, Integer.MAX_VALUE); }
            case "quest_option" -> {
                fields(a, "session_id quest_id option", "");
                integer(a, "quest_id", 0, Integer.MAX_VALUE); string(a, "option");
            }
            case "use_action" -> {
                fields(a, "session_id action_id", "modifiers");
                string(a, "action_id"); optionalInteger(a, "modifiers", 0, 15);
            }
            case "item_action" -> {
                fields(a, "session_id item_id action", "modifiers");
                integer(a, "item_id", 0, Integer.MAX_VALUE);
                choice(a, "action", "take transfer drop interact item_use open_contents");
                optionalInteger(a, "modifiers", 0, 15);
            }
            case "inventory_drop" -> {
                fields(a, "session_id inventory_id x y", "");
                integer(a, "inventory_id", 0, Integer.MAX_VALUE); integer(a, "x", 0, 1000); integer(a, "y", 0, 1000);
            }
            case "equipment_drop" -> {
                fields(a, "session_id equipment_id slot", "");
                integer(a, "equipment_id", 0, Integer.MAX_VALUE); integer(a, "slot", 0, 255);
            }
            case "craft" -> {
                fields(a, "session_id crafting_id all", "");
                integer(a, "crafting_id", 0, Integer.MAX_VALUE); bool(a, "all");
            }
            case "map_action" -> {
                String act = choice(a, "action", "click item_use drop place preview select_area");
                fields(a, "session_id action x y" + (act.equals("place") || act.equals("preview") ? " angle" : act.equals("select_area") ? " x2 y2" : ""), act.equals("preview") ? "" : "button modifiers");
                position(a); optionalInteger(a, "button", 1, 3); optionalInteger(a, "modifiers", 0, 15);
                if (a.has("angle")) number(a, "angle", -360, 360);
                if (a.has("x2")) { number(a, "x2", -100_000_000, 100_000_000); number(a, "y2", -100_000_000, 100_000_000); }
            }
            case "combat_action" -> {
                String act = choice(a, "action", "use release target peace pursue");
                fields(a, "session_id action " + (act.equals("use") || act.equals("release") ? "slot" : "target_id"), act.equals("use") ? "x y modifiers" : "");
                if (a.has("slot")) integer(a, "slot", 0, 255);
                if (a.has("target_id")) objectId(a, "target_id");
                pair(a, "x", "y"); if (a.has("x")) position(a);
                optionalInteger(a, "modifiers", 0, 15);
            }
            case "ui_action" -> {
                String act = choice(a, "action", "click scroll set_text activate set_checked set_value show close");
                String extra = switch (act) {
                    case "scroll" -> " amount";
                    case "set_text" -> " text";
                    case "set_checked" -> " checked";
                    case "set_value" -> " value";
                    default -> "";
                };
                fields(a, "session_id widget_id action" + extra, act.equals("click") || act.equals("scroll") ? "x y button modifiers" : "");
                string(a, "widget_id"); pair(a, "x", "y");
                optionalInteger(a, "x", 0, 100000); optionalInteger(a, "y", 0, 100000);
                optionalInteger(a, "button", 1, 3); optionalInteger(a, "modifiers", 0, 15);
                if (a.has("amount")) integer(a, "amount", -100, 100);
                if (a.has("text")) string(a, "text", 4096, true);
                if (a.has("checked")) bool(a, "checked");
                if (a.has("value")) integer(a, "value", Integer.MIN_VALUE, Integer.MAX_VALUE);
            }
            case "widget_message" -> {
                fields(a, "session_id widget_id message arguments", ""); string(a, "widget_id");
                if (!string(a, "message", 64, false).matches("[A-Za-z][A-Za-z0-9_-]*")) throw new IllegalArgumentException("Invalid message name");
                BridgeProtocol.values(a.getJSONArray("arguments"));
            }
            case "stop" -> fields(a, "session_id", "");
            default -> throw new IllegalArgumentException("Unknown method");
        }
        if (a.has("session_id")) string(a, "session_id");
        return new BridgeRequest(method, a);
    }

    static void fields(JSONObject a, String required, String optional) {
        Set<String> must = words(required), allowed = new HashSet<>(must);
        allowed.addAll(words(optional));
        if (!a.keySet().containsAll(must) || !allowed.containsAll(a.keySet())) throw new IllegalArgumentException("Unexpected or missing arguments");
    }
    private static Set<String> words(String s) { return s.isBlank() ? Set.of() : Set.of(s.trim().split(" +")); }
    private static String choice(JSONObject a, String key, String values) {
        String value = string(a, key);
        if (!words(values).contains(value)) throw new IllegalArgumentException("Unsupported " + key);
        return value;
    }
    private static String string(JSONObject a, String key) { return string(a, key, 128, false); }
    static String string(JSONObject a, String key, int max, boolean empty) {
        if (!(a.get(key) instanceof String v) || (!empty && v.isBlank()) || v.length() > max)
            throw new IllegalArgumentException(key + " must be a string of at most " + max + " characters");
        return v;
    }
    static double number(JSONObject a, String key, double min, double max) {
        if (!(a.get(key) instanceof Number value)) throw new IllegalArgumentException(key + " must be a number");
        double n = value.doubleValue();
        if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException(key + " is out of range");
        return n;
    }
    private static void integer(JSONObject a, String key, int min, int max) {
        double n = number(a, key, min, max);
        if (n != Math.rint(n)) throw new IllegalArgumentException(key + " must be an integer");
    }
    private static void optionalInteger(JSONObject a, String key, int min, int max) { if (a.has(key)) integer(a, key, min, max); }
    private static void bool(JSONObject a, String key) { if (!(a.get(key) instanceof Boolean)) throw new IllegalArgumentException(key + " must be boolean"); }
    private static void pair(JSONObject a, String x, String y) { if (a.has(x) != a.has(y)) throw new IllegalArgumentException("Provide both " + x + " and " + y); }
    private static void position(JSONObject a) { number(a, "x", -100_000_000, 100_000_000); number(a, "y", -100_000_000, 100_000_000); }
    private static void objectId(JSONObject a, String key) {
        String id = string(a, key);
        if (!id.matches("[0-9]{1,19}")) throw new IllegalArgumentException(key + " must be a decimal string");
        Long.parseLong(id);
    }
}
