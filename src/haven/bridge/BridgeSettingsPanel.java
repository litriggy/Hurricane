package haven.bridge;

import haven.*;
import java.awt.Color;
import java.util.function.Supplier;

/** Small Options page; drafts are applied together instead of restarting on every keystroke. */
public final class BridgeSettingsPanel extends Widget {
    private final Supplier<GameBridge> bridge;
    private final CheckBox enabled;
    private final TextEntry port;
    private final Label status, authentication, activity, game, feedback;
    private double refresh;

    public BridgeSettingsPanel(Supplier<GameBridge> bridge, Runnable back) {
        this.bridge = bridge;
        add(new Label("Connect OpenCode to this Hurricane client."), UI.scale(0, 0));
        enabled = add(new CheckBox("Enable MCP bridge"), UI.scale(0, 32));
        add(new Label("Port:"), UI.scale(0, 67));
        port = add(new TextEntry(UI.scale(100), "18711"), UI.scale(80, 64));
        add(new Label("127.0.0.1 (this computer only)"), UI.scale(200, 67));
        authentication = add(new Label(""), UI.scale(0, 101));
        status = add(new Label(""), UI.scale(0, 135));
        game = add(new Label(""), UI.scale(0, 159));
        activity = add(new Label(""), UI.scale(0, 183));
        add(new Label("Use the same port in your OpenCode connection settings."), UI.scale(0, 220));
        add(new Label("Apply saves changes without restarting the game."), UI.scale(0, 244));
        feedback = add(new Label(""), UI.scale(0, 274));
        add(new Button(UI.scale(150), "Apply", false).action(this::apply), UI.scale(0, 310));
        add(new Button(UI.scale(150), "Launcher defaults", false).action(() -> {
            load(bridge.get().launcherDefaults());
            notice("Launcher defaults loaded. Click Apply to save.", false);
        }), UI.scale(165, 310));
        add(new Button(UI.scale(150), "Back", false).action(back), UI.scale(330, 310));
        load(bridge.get().settings());
        updateStatus();
        if (bridge.get().lastError() != null) notice(bridge.get().lastError(), true);
        resize(UI.scale(500, 344));
    }

    private void load(BridgeSettings settings) {
        enabled.set(settings.enabled);
        port.settext(settings.port);
    }

    private void apply() {
        String error = bridge.get().applySettings(enabled.state(), port.text());
        if (error == null) {
            load(bridge.get().settings());
            notice("Settings saved.", false);
        } else {
            notice(error, true);
        }
        updateStatus();
    }

    private void notice(String text, boolean error) {
        feedback.settext(text);
        feedback.setcolor(error ? new Color(255, 128, 128) : new Color(128, 220, 128));
    }

    private void updateStatus() {
        GameBridge current = bridge.get();
        text(status, "Status: " + current.statusText());
        text(authentication, current.tokenConfigured() ? "Authentication: launch token configured" : "Authentication: launch token missing or invalid");
        text(game, current.inGame() ? "Game: character connected" : "Game: waiting for character login");
        text(activity, current.activityText());
    }

    private static void text(Label label, String text) {
        if (!text.equals(label.texts)) label.settext(text);
    }

    public void tick(double dt) {
        super.tick(dt);
        refresh -= dt;
        if (refresh <= 0) { refresh = 0.5; updateStatus(); }
    }
}
