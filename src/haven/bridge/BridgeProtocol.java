package haven.bridge;

import haven.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Typed values for the existing widget-message protocol. Never evaluates code. */
final class BridgeProtocol {
    static Object[] values(JSONArray array) { return values(array, 0); }

    private static Object[] values(JSONArray array, int depth) {
        if (depth > 4 || array.length() > 32)
            throw new IllegalArgumentException("Message arrays allow at most 32 entries and 4 nesting levels");
        Object[] result = new Object[array.length()];
        for (int i = 0; i < result.length; i++) result[i] = value(array.get(i), depth);
        return result;
    }

    private static Object value(Object raw, int depth) {
        if (raw == JSONObject.NULL) return null;
        if (raw instanceof String text) {
            if (text.length() > 4096) throw new IllegalArgumentException("Message string is too long");
            return text;
        }
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d) || Math.abs(d) > 9_007_199_254_740_991d)
                throw new IllegalArgumentException("Use a typed long for large integers");
            if (d == Math.rint(d)) {
                if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) return (int)d;
                return (long)d;
            }
            return d;
        }
        if (raw instanceof JSONArray a) return values(a, depth + 1);
        if (!(raw instanceof JSONObject o))
            throw new IllegalArgumentException("Use protocol numbers (0/1), not booleans");
        String type = o.getString("type");
        switch (type) {
            case "coord", "coord2d" -> {
                BridgeRequest.fields(o, "type x y", "");
                double x = BridgeRequest.number(o, "x", -100_000_000, 100_000_000);
                double y = BridgeRequest.number(o, "y", -100_000_000, 100_000_000);
                if (type.equals("coord2d")) return new Coord2d(x, y);
                if (x != Math.rint(x) || y != Math.rint(y))
                    throw new IllegalArgumentException("coord requires integer x/y");
                return new Coord((int)x, (int)y);
            }
            case "long" -> {
                BridgeRequest.fields(o, "type value", "");
                String n = BridgeRequest.string(o, "value", 20, false);
                if (!n.matches("-?[0-9]+")) throw new IllegalArgumentException("Invalid long");
                return Long.parseLong(n);
            }
            case "float" -> {
                BridgeRequest.fields(o, "type value", "");
                return (float)BridgeRequest.number(o, "value", -Float.MAX_VALUE, Float.MAX_VALUE);
            }
            case "bytes" -> {
                BridgeRequest.fields(o, "type value", "");
                JSONArray a = o.getJSONArray("value");
                if (a.length() > 1024) throw new IllegalArgumentException("Byte array is too long");
                byte[] bytes = new byte[a.length()];
                for (int i = 0; i < bytes.length; i++) {
                    Object v = a.get(i);
                    if (!(v instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 0 || n.intValue() > 255)
                        throw new IllegalArgumentException("Byte must be an integer from 0 to 255");
                    bytes[i] = (byte)((Number)v).intValue();
                }
                return bytes;
            }
            default -> throw new IllegalArgumentException("Unsupported protocol type");
        }
    }

    static void objectClick(Widget map, long id, Coord2d position, int button, int modifiers, boolean heldItem) {
        Coord mc = position.floor(OCache.posres);
        if (heldItem) map.wdgmsg("itemact", Coord.z, mc, modifiers, 0, (int)id, mc, 0, -1);
        else map.wdgmsg("click", Coord.z, mc, button, modifiers, 0, (int)id, mc, 0, -1);
    }

    static void ground(Widget map, JSONObject args) {
        Coord2d world = new Coord2d(args.getDouble("x"), args.getDouble("y"));
        Coord mc = world.floor(OCache.posres);
        int mods = args.optInt("modifiers", 0), button = args.optInt("button", 1);
        String action = args.getString("action");
        if ((action.equals("place") || action.equals("preview")) && map instanceof MapView view) {
            if (!view.positionPlacement(world, Math.toRadians(args.getDouble("angle"))))
                throw new IllegalArgumentException("No loaded placement preview");
        }
        switch (action) {
            case "click" -> map.wdgmsg("click", Coord.z, mc, button, mods);
            case "item_use" -> map.wdgmsg("itemact", Coord.z, mc, mods);
            case "drop" -> map.wdgmsg("drop", Coord.z, mc, mods);
            case "place" -> map.wdgmsg("place", mc, (int)Math.round(args.getDouble("angle") * 32768 / 180), button, mods);
            case "preview" -> { /* Client preview only; no server request. */ }
            case "select_area" -> map.wdgmsg("sel", world.floor(MCache.tilesz),
                new Coord2d(args.getDouble("x2"), args.getDouble("y2")).floor(MCache.tilesz), mods);
            default -> throw new IllegalArgumentException("Unknown map action");
        }
    }
}
