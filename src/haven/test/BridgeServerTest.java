package haven.test;

import haven.bridge.BridgeRequest;
import haven.bridge.BridgeServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class BridgeServerTest {
    private static final String TOKEN = "bridge-test-token-00000000000000000000";
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static int checks;

    public static void main(String[] args) throws Exception {
        var dispatcher = Executors.newSingleThreadExecutor(task -> new Thread(task, "test-ui"));
        AtomicInteger executed = new AtomicInteger();
        try (var server = new BridgeServer(new BridgeServer.Config(0, TOKEN), request -> {
            if (!Thread.currentThread().getName().equals("test-ui")) throw new AssertionError("wrong thread");
            executed.incrementAndGet();
            return new JSONObject().put("ok", true).put("result", new JSONObject().put("method", request.method()));
        }, dispatcher)) {
            URI uri = URI.create("http://127.0.0.1:" + server.port() + "/api");
            var valid = new JSONObject().put("method", "get_state").put("arguments", new JSONObject()).toString();
            check(send(uri, valid, "wrong").statusCode() == 401, "wrong token rejected");
            check(send(uri, "broken", TOKEN).statusCode() == 400, "malformed JSON rejected");
            check(send(uri, "x".repeat(16_385), TOKEN).statusCode() == 413, "oversize request rejected");
            check(send(uri, "{\"method\":\"eval\",\"arguments\":{}}", TOKEN).statusCode() == 400, "unknown command rejected");
            check(send(uri, "{\"method\":\"get_state\",\"arguments\":{\"extra\":1}}", TOKEN).statusCode() == 400, "extra args rejected");
            var originRequest = request(uri, TOKEN).header("Origin", "https://example.com")
                .POST(HttpRequest.BodyPublishers.ofString(valid)).build();
            check(CLIENT.send(originRequest, HttpResponse.BodyHandlers.ofString()).statusCode() == 403, "browser origin rejected");
            check(executed.get() == 0, "invalid requests never reach UI");
            var result = send(uri, valid, TOKEN);
            check(result.statusCode() == 200 && new JSONObject(result.body()).getBoolean("ok"), "valid command executes on UI dispatcher");
            check(executed.get() == 1, "command executes once");
        } finally {
            dispatcher.shutdownNow();
        }
        ArrayBlockingQueue<Runnable> queued = new ArrayBlockingQueue<>(1);
        try (var server = new BridgeServer(new BridgeServer.Config(0, TOKEN), request -> {
            executed.incrementAndGet();
            return new JSONObject();
        }, queued::add)) {
            URI uri = URI.create("http://127.0.0.1:" + server.port() + "/api");
            var result = send(uri, "{\"method\":\"get_state\",\"arguments\":{}}", TOKEN);
            check(result.statusCode() == 504, "stalled UI times out");
            queued.remove().run();
            check(executed.get() == 1, "expired queued command cannot execute later");
        }
        for (String arguments : new String[] {
            "{\"session_id\":\"s\",\"x\":\"12\",\"y\":0}",
            "{\"session_id\":\"s\",\"x\":100000001,\"y\":0}"
        }) {
            boolean rejected = false;
            try {
                BridgeRequest.parse(new JSONObject("{\"method\":\"move_to\",\"arguments\":" + arguments + "}"));
            } catch (IllegalArgumentException e) {
                rejected = true;
            }
            check(rejected, "invalid coordinate rejected");
        }
        check(BridgeRequest.parse(new JSONObject("{\"method\":\"get_quests\",\"arguments\":{}}"))
            .method().equals("get_quests"), "quest reads require no session or arguments");
        boolean extraQuestArgument = false;
        try {
            BridgeRequest.parse(new JSONObject("{\"method\":\"get_quests\",\"arguments\":{\"quest_id\":12}}"));
        } catch (IllegalArgumentException e) {
            extraQuestArgument = true;
        }
        check(extraQuestArgument, "quest reads cannot select a different quest");
        for (String window : new String[] {"character", "quests", "inventory", "equipment", "map"}) {
            JSONObject open = new JSONObject().put("method", "open_window")
                .put("arguments", new JSONObject().put("session_id", "s").put("window", window));
            check(BridgeRequest.parse(open).method().equals("open_window"), "named window accepted: " + window);
        }
        for (String arguments : new String[] {
            "{\"window\":\"quests\"}",
            "{\"session_id\":\"s\",\"window\":\"arbitrary-widget\"}",
            "{\"session_id\":\"s\",\"window\":12}",
            "{\"session_id\":\"s\",\"window\":\"quests\",\"widget_id\":12}"
        }) {
            boolean rejected = false;
            try {
                BridgeRequest.parse(new JSONObject("{\"method\":\"open_window\",\"arguments\":" + arguments + "}"));
            } catch (IllegalArgumentException e) {
                rejected = true;
            }
            check(rejected, "window command requires a session and a supported name, with no extra fields");
        }
        System.out.println("BridgeServerTest: " + checks + " checks passed");
    }

    private static HttpRequest.Builder request(URI uri, String token) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
    }

    private static HttpResponse<String> send(URI uri, String body, String token) throws Exception {
        return CLIENT.send(request(uri, token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
