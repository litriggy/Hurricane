package haven.bridge;

import haven.*;
import haven.iosys.audio.DummyAudio;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Real placement objects and protocol dispatch; no renderer, account or world mutations. */
public final class BridgePlacementTest {
    private static int checks;
    private static final class Sent {
        final String message;
        final Object[] values;
        Sent(String message, Object[] values) { this.message = message; this.values = values; }
    }

    private static class MapFixture extends MapView {
        Plob preview;
        MapFixture() { super(new Coord(800, 600), new Glob(null), Coord2d.z, -1); }
        public Coord3f getcc() { return Coord3f.o; }
        public Plob placementPreview() { return preview; }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("haven.uiscale", "1");
        System.setProperty("java.awt.headless", "true");
        OptWnd.groundRenderDistanceSlider = new HSlider(10, 1, 3, 1);
        OptWnd.toggleGobHidingCheckBox = new CheckBox("");
        OptWnd.showObjectCollisionBoxesCheckBox = new CheckBox("");
        OptWnd.showContainerFullnessCheckBox = new CheckBox("");
        OptWnd.showWorkstationProgressCheckBox = new CheckBox("");
        OptWnd.flatCupboardsCheckBox = new CheckBox("");
        for (String option : List.of("dontHideObjectsThatHaveTheirMapIconEnabledCheckBox", "hideTreesCheckbox", "hideBushesCheckbox",
            "hideBouldersCheckbox", "hideTreeLogsCheckbox", "hideWallsCheckbox", "hideHousesCheckbox", "hideStockpilesCheckbox",
            "hideCropsCheckbox", "hideTrellisCheckbox")) OptWnd.class.getField(option).set(null, new CheckBox(""));
        UI ui = new UI(null, new Audio.Root(DummyAudio.DummySink.instance), new Coord(800, 600), null);
        MapFixture map = new MapFixture();
        try {
            List<Sent> sent = new ArrayList<>();
            ui.setreceiver((id, message, values) -> sent.add(new Sent(message, values)));
            map.ui = ui;
            map.currentCursorLocation = new Coord(500, 400);
            ui.bind(map, 100);
            var constructor = MapView.Plob.class.getDeclaredConstructor(MapView.class, Indir.class, Message.class);
            constructor.setAccessible(true);
            map.preview = constructor.newInstance(map, WItem.missing.indir(), Message.nil);
            map.preview.adjust = (plob, pc, mc, mods) -> plob.move(mc, 0);
            Object lateMouse = adjust(map.preview);
            BridgeProtocol.ground(map, placement("place", -11.5, 22.5, 90));
            check(map.preview.rc.equals(new Coord2d(-11.5, 22.5)) && Math.abs(map.preview.a - Math.PI / 2) < 1e-9,
                "placing must synchronize the preview with the requested world point and angle");
            check(sent.size() == 1 && sent.get(0).message.equals("place") && sent.get(0).values[0].equals(new Coord(-1071, 2094))
                && sent.get(0).values[1].equals(16384) && sent.get(0).values[2].equals(1) && sent.get(0).values[3].equals(0),
                "placement sends exactly one world-coordinate command, independent of the mouse");
            var hit = lateMouse.getClass().getDeclaredMethod("hit", Coord.class, Coord2d.class);
            hit.setAccessible(true);
            hit.invoke(lateMouse, new Coord(500, 400), new Coord2d(9000, 9000));
            check(map.preview.rc.equals(new Coord2d(-11.5, 22.5)), "a delayed mouse hit must not overwrite bridge placement");
            BridgeProtocol.ground(map, placement("preview", 33, -44, -90));
            check(sent.size() == 1 && map.preview.rc.equals(new Coord2d(33, -44)) && map.preview.a == -Math.PI / 2,
                "preview moves locally without placing or consuming materials");
            System.out.println("BridgePlacementTest: " + checks + " checks passed");
        } finally { map.dispose(); ui.destroy(); }
    }

    private static Object adjust(MapView.Plob plob) throws Exception {
        Class<?> type = Class.forName("haven.MapView$Plob$Adjust");
        var constructor = type.getDeclaredConstructor(MapView.Plob.class, Coord.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(plob, new Coord(500, 400), 0);
    }

    private static JSONObject placement(String action, double x, double y, double angle) {
        return new JSONObject().put("action", action).put("x", x).put("y", y).put("angle", angle);
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
}
