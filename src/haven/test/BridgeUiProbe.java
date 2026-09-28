package haven.test;

import haven.Audio;
import haven.Coord;
import haven.UI;
import haven.iosys.audio.DummyAudio;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class BridgeUiProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("haven.uiscale", "1");
        System.setProperty("java.awt.headless", "true");
        UI ui = new UI(null, new Audio.Root(DummyAudio.DummySink.instance), new Coord(800, 600), null);
        var loop = Executors.newSingleThreadScheduledExecutor();
        try {
            ui.startBridge();
            loop.scheduleAtFixedRate(() -> {
                synchronized (ui) {
                    ui.tick();
                }
            }, 0, 16, TimeUnit.MILLISECONDS);
            System.out.println("Headless client UI probe. No game session. Press Enter to exit.");
            System.in.read();
        } finally {
            loop.shutdownNow();
            loop.awaitTermination(5, TimeUnit.SECONDS);
            ui.destroy();
        }
    }
}
