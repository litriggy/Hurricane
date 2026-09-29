package haven.bridge;

import haven.*;
import java.util.Arrays;
import java.util.Map;
import org.json.JSONObject;

public final class BridgeMapTest {
    private static int checks;
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        MCache map = new MCache(null);
        JSONObject empty = BridgeMap.snapshot(map, Coord2d.z, new JSONObject().put("radius", 0));
        check(empty.getInt("unknown_tiles") == 1 && empty.getJSONArray("tiles").getJSONArray(0).isNull(0), "unloaded is null");
        var requests = MCache.class.getDeclaredField("req");
        requests.setAccessible(true);
        check(((Map<?, ?>) requests.get(map)).isEmpty(), "reading map must not request remote grids");
        MCache.Grid grid = map.new Grid(new Coord(-1, -1));
        Arrays.fill(grid.tiles, 7);
        Arrays.fill(grid.z, 12.5f);
        var grids = MCache.class.getDeclaredField("grids");
        grids.setAccessible(true);
        ((Map<Coord, MCache.Grid>) grids.get(map)).put(grid.gc, grid);
        JSONObject data = BridgeMap.snapshot(map, new Coord2d(-0.5, -0.5), new JSONObject().put("radius", 1).put("include_heights", true));
        check(data.getJSONObject("origin").getInt("x") == -2 && data.getJSONObject("origin").getInt("y") == -2, "negative world coordinates use floor");
        check(data.getInt("width") == 3 && data.getInt("unknown_tiles") == 5, "grid boundary has four loaded and five unknown tiles");
        check(data.getJSONArray("tiles").getJSONArray(1).getInt(1) == 7 && data.getJSONArray("tiles").getJSONArray(2).isNull(1), "row-major placement");
        check(data.getJSONArray("heights").getJSONArray(0).getDouble(0) == 12.5, "height samples retained");
        check(data.getJSONObject("terrain").getJSONObject("7").isNull("resource"), "missing resource remains unknown without losing tile ID");
        JSONObject centered = BridgeMap.snapshot(map, new Coord2d(100, 100), new JSONObject().put("x", -11).put("y", -11).put("radius", 0));
        check(centered.getInt("unknown_tiles") == 0 && !centered.has("heights"), "explicit world center and omitted heights");
        for (String invalid : new String[]{"{\"radius\":17}", "{\"x\":1}", "{\"radius\":1.5}", "{\"include_heights\":1}"}) {
            try {
                BridgeRequest.parse(new JSONObject().put("method", "get_map").put("arguments", new JSONObject(invalid)));
                throw new AssertionError("invalid map arguments accepted");
            } catch (IllegalArgumentException | org.json.JSONException expected) { checks++; }
        }
        BridgeRequest.parse(new JSONObject("{\"method\":\"get_map\",\"arguments\":{}}"));
        System.out.println("BridgeMapTest: " + checks + " checks passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
