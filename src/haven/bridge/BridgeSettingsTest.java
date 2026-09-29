package haven.bridge;

import haven.*;
import haven.iosys.audio.DummyAudio;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Isolated UI and loopback listener checks; never connects to a game account. */
public final class BridgeSettingsTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("haven.uiscale", "1");
        System.setProperty("java.awt.headless", "true");
        int port = Integer.parseInt(System.getenv("HURRICANE_BRIDGE_PORT"));
        System.setProperty("haven.prefs.mcpBridgeSettings", "{\"enabled\":false,\"port\":\"" + port + "\"}");
        UI ui = new UI(null, new Audio.Root(DummyAudio.DummySink.instance), new Coord(1000, 800), null);
        try {
            ui.startBridge();
            ui.tick();
            if (listening(port)) throw new AssertionError("Saved disabled setting must override a configured launch token");
            System.out.println("BridgeSettingsTest: saved disable honored");
        } finally { ui.destroy(); }
    }

    private static boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (java.io.IOException expected) { return false; }
    }
}
