package haven.bridge;

import haven.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** A bounded snapshot of cached terrain, never a global map or collision map. */
final class BridgeMap {
    static JSONObject snapshot(MCache map, Coord2d player, JSONObject args) {
        int radius = args.optInt("radius", 8);
        Coord2d center = args.has("x") ? new Coord2d(args.getDouble("x"), args.getDouble("y")) : player;
        Coord tile = center.floor(MCache.tilesz);
        Coord origin = tile.sub(radius, radius);
        int width = radius * 2 + 1, unknown = 0;
        boolean heights = args.optBoolean("include_heights", false);
        JSONArray rows = new JSONArray(), elevation = new JSONArray();
        JSONObject terrain = new JSONObject();
        for (int y = 0; y < width; y++) {
            JSONArray row = new JSONArray(), zrow = new JSONArray();
            for (int x = 0; x < width; x++) {
                Coord tc = origin.add(x, y);
                MCache.Grid grid = map.loadedgrid(tc.div(MCache.cmaps));
                if (grid == null) {
                    row.put(JSONObject.NULL);
                    zrow.put(JSONObject.NULL);
                    unknown++;
                    continue;
                }
                Coord local = tc.sub(grid.ul);
                int id = grid.gettile(local);
                row.put(id);
                if (heights) {
                    double z = grid.getz(local);
                    zrow.put(Double.isFinite(z) ? z : JSONObject.NULL);
                }
                String key = Integer.toString(id);
                if (!terrain.has(key)) {
                    JSONObject entry = new JSONObject().put("resource", JSONObject.NULL).put("loading", false);
                    try {
                        Resource resource = map.tilesetr(id);
                        if (resource != null) entry.put("resource", resource.name);
                    } catch (Loading e) { entry.put("loading", true); }
                    terrain.put(key, entry);
                }
            }
            rows.put(row);
            if (heights) elevation.put(zrow);
        }
        JSONObject result = new JSONObject().put("scope", "loaded_terrain")
            .put("observed_at", System.currentTimeMillis()).put("tile_size", 11)
            .put("origin", new JSONObject().put("x", origin.x).put("y", origin.y))
            .put("width", width).put("height", width).put("tiles", rows).put("terrain", terrain)
            .put("unknown_tiles", unknown).put("complete", unknown == 0)
            .put("coordinate_system", "session_local_tile_coordinates")
            .put("traversability", "unknown; terrain alone does not include collision, claims or movement abilities");
        if (heights) result.put("heights", elevation);
        return result;
    }
}
