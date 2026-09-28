package haven.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.json.JSONException;
import org.json.JSONObject;

/** The dispatcher must serialize commands on the UI thread, never on an HTTP worker. */
public final class BridgeServer implements AutoCloseable {
    public static final class Config {
        final int port;
        final String token;
        public Config(int port, String token) { this.port = port; this.token = token; }
    }
    private final HttpServer server;
    private final ExecutorService workers;
    private final Executor dispatcher;
    private final Function<BridgeRequest, JSONObject> handler;
    private final byte[] authorization;
    private volatile boolean closed;

    public BridgeServer(Config config, Function<BridgeRequest, JSONObject> handler, Executor dispatcher) throws IOException {
        String token = config.token;
        if (token == null || token.length() < 32 || !token.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("HURRICANE_BRIDGE_TOKEN needs at least 32 URL-safe characters");
        this.handler = handler;
        this.dispatcher = dispatcher;
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.port), 8);
        workers = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "Hurricane bridge HTTP");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext("/api", this::handle);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(authorization, auth.getBytes(StandardCharsets.UTF_8))) {
                reply(exchange, 401, error("unauthorized", "A valid bridge token is required"));
                return;
            }
            if (exchange.getRequestHeaders().containsKey("Origin")) {
                reply(exchange, 403, error("browser_request", "Browser origins are not supported"));
                return;
            }
            if (!exchange.getRequestURI().toString().equals("/api")) {
                reply(exchange, 404, error("not_found", "Use /api"));
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 405, error("method_not_allowed", "Use POST"));
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.split(";", 2)[0].equalsIgnoreCase("application/json")) {
                reply(exchange, 415, error("content_type", "Use application/json"));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(16_385);
            if (body.length > 16_384) {
                reply(exchange, 413, error("body_too_large", "Maximum body size is 16384 bytes"));
                return;
            }
            BridgeRequest request;
            try {
                request = BridgeRequest.parse(new JSONObject(new String(body, StandardCharsets.UTF_8)));
            } catch (JSONException | IllegalArgumentException e) {
                reply(exchange, 400, error("invalid_request", e.getMessage()));
                return;
            }
            FutureTask<JSONObject> task = new FutureTask<>(() -> closed
                ? error("closing", "Bridge is closing") : handler.apply(request));
            try {
                if (closed) throw new RejectedExecutionException();
                dispatcher.execute(task);
            } catch (RejectedExecutionException e) {
                reply(exchange, 503, error("unavailable", "Bridge is closed or busy"));
                return;
            }
            try {
                reply(exchange, 200, task.get(3, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                task.cancel(false);
                reply(exchange, 504, error("ui_timeout", "UI did not respond. Read state before retrying an action."));
            } catch (InterruptedException e) {
                task.cancel(false);
                Thread.currentThread().interrupt();
                reply(exchange, 503, error("closing", "Bridge is closing"));
            } catch (java.util.concurrent.CancellationException e) {
                reply(exchange, 503, error("closing", "Bridge is closing"));
            } catch (ExecutionException e) {
                new haven.Warning(e.getCause(), "Hurricane bridge command failed").issue();
                reply(exchange, 500, error("internal_error", "Client command failed; check client diagnostics"));
            }
        }
    }

    public static JSONObject error(String code, String message) {
        return new JSONObject().put("ok", false)
            .put("error", new JSONObject().put("code", code).put("message", message));
    }

    private static void reply(HttpExchange exchange, int status, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    public void close() {
        closed = true;
        server.stop(0);
        workers.shutdownNow();
    }
}
