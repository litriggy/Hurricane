package haven.bridge;

import java.util.Map;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.json.JSONObject;

/** Only non-secret client preferences; authentication remains in the launch environment. */
public final class BridgeSettings {
    static final String KEY = "mcpBridgeSettings";
    public final boolean enabled;
    public final String port;

    private BridgeSettings(boolean enabled, String port) { this.enabled = enabled; this.port = port; }

    static BridgeSettings defaults(Map<String, String> environment) {
        String token = environment.get("HURRICANE_BRIDGE_TOKEN");
        return new BridgeSettings(token != null && !token.isBlank(), environment.getOrDefault("HURRICANE_BRIDGE_PORT", "18711"));
    }

    static BridgeSettings load(Preferences preferences, Map<String, String> environment) {
        String saved = preferences.get(KEY, null);
        if (saved == null) return defaults(environment);
        JSONObject data = new JSONObject(saved);
        if (!(data.get("enabled") instanceof Boolean) || !(data.get("port") instanceof String))
            throw new IllegalArgumentException("Invalid saved bridge settings");
        return validated(data.getBoolean("enabled"), data.getString("port"));
    }

    static BridgeSettings validated(boolean enabled, String port) {
        String text = port.trim();
        if (!text.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Port must be a number from 1024 to 65535.");
        int value = Integer.parseInt(text);
        if (value < 1024 || value > 65535) throw new IllegalArgumentException("Port must be a number from 1024 to 65535.");
        return new BridgeSettings(enabled, Integer.toString(value));
    }

    void save(Preferences preferences) throws BackingStoreException {
        String previous = preferences.get(KEY, null);
        try {
            preferences.put(KEY, new JSONObject().put("enabled", enabled).put("port", port).toString());
            preferences.flush();
        } catch (BackingStoreException | SecurityException e) {
            try {
                if (previous == null) preferences.remove(KEY); else preferences.put(KEY, previous);
            } catch (SecurityException ignored) {}
            throw e;
        }
    }
}
