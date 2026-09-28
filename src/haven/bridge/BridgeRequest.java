package haven.bridge;

import java.util.Set;
import org.json.JSONObject;

public final class BridgeRequest {
    private final String method;
    private final JSONObject arguments;

    private BridgeRequest(String method, JSONObject arguments) {
        this.method = method;
        this.arguments = arguments;
    }

    public String method() { return method; }
    public JSONObject arguments() { return arguments; }

    public static BridgeRequest parse(JSONObject body) {
        if (!body.keySet().equals(Set.of("method", "arguments")))
            throw new IllegalArgumentException("Expected method and arguments");
        String method = string(body, "method");
        JSONObject args = body.getJSONObject("arguments");
        Set<String> fields = switch (method) {
            case "get_state", "get_inventory" -> Set.of();
            case "list_nearby" -> Set.of("radius", "limit");
            case "move_to" -> Set.of("session_id", "x", "y");
            case "interact" -> Set.of("session_id", "target_id");
            case "choose_option" -> Set.of("session_id", "menu_id", "option");
            case "stop" -> Set.of("session_id");
            default -> throw new IllegalArgumentException("Unknown method");
        };
        if (!args.keySet().equals(fields))
            throw new IllegalArgumentException("Unexpected or missing arguments");
        if (fields.contains("session_id")) string(args, "session_id");
        switch (method) {
            case "list_nearby" -> {
                number(args, "radius", 1, 440);
                integer(args, "limit", 1, 100);
            }
            case "move_to" -> {
                number(args, "x", -100_000_000, 100_000_000);
                number(args, "y", -100_000_000, 100_000_000);
            }
            case "interact" -> {
                String id = string(args, "target_id");
                if (!id.matches("[0-9]{1,19}"))
                    throw new IllegalArgumentException("target_id must be a decimal string");
                Long.parseLong(id);
            }
            case "choose_option" -> {
                integer(args, "menu_id", 0, Integer.MAX_VALUE);
                string(args, "option");
            }
            default -> { }
        }
        return new BridgeRequest(method, args);
    }

    private static String string(JSONObject obj, String key) {
        if (!(obj.get(key) instanceof String value) || value.isBlank() || value.length() > 128)
            throw new IllegalArgumentException(key + " must be a nonempty string (max 128 characters)");
        return value;
    }

    private static double number(JSONObject obj, String key, double min, double max) {
        if (!(obj.get(key) instanceof Number value))
            throw new IllegalArgumentException(key + " must be a number");
        double n = value.doubleValue();
        if (!Double.isFinite(n) || n < min || n > max)
            throw new IllegalArgumentException(key + " is out of range");
        return n;
    }

    private static void integer(JSONObject obj, String key, int min, int max) {
        double n = number(obj, key, min, max);
        if (n != Math.rint(n)) throw new IllegalArgumentException(key + " must be an integer");
    }
}
