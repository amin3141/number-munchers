package com.pairsys.numbermunchers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;

public final class GameDebugServer {
    private final NumberMunchersApp app;
    private final int port;
    private HttpServer server;

    public GameDebugServer(NumberMunchersApp app, int port) {
        this.app = app;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/health", this::handleHealth);
        server.createContext("/state", this::handleState);
        server.createContext("/command", new CommandHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        sendJson(exchange, 200, runOnFxThread(app::debugHealthJson));
    }

    private void handleState(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        sendJson(exchange, 200, runOnFxThread(app::debugStateJson));
    }

    private void handleCommand(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }

        URI uri = exchange.getRequestURI();
        Map<String, String> query = parseQuery(uri.getRawQuery());
        String path = uri.getPath();
        String[] parts = path.split("/");
        if (parts.length < 3 || parts[2].isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"missing_command\"}");
            return;
        }

        String command = parts[2];
        String response;
        try {
            response = runOnFxThread(() -> app.handleDebugCommand(command, query));
        } catch (IllegalArgumentException ex) {
            sendJson(exchange, 400, "{\"error\":\"" + escape(ex.getMessage()) + "\"}");
            return;
        } catch (Exception ex) {
            sendJson(exchange, 500, "{\"error\":\"" + escape(ex.getMessage()) + "\"}");
            return;
        }

        sendJson(exchange, 200, response);
    }

    private <T> T runOnFxThread(FxTask<T> task) throws IOException {
        if (Platform.isFxApplicationThread()) {
            try {
                return task.run();
            } catch (Exception ex) {
                if (ex instanceof IOException ioEx) {
                    throw ioEx;
                }
                throw new IOException("Debug command failed.", ex);
            }
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                result.set(task.run());
            } catch (Exception ex) {
                error.set(ex);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for JavaFX thread.", ex);
        }

        if (error.get() != null) {
            if (error.get() instanceof IOException ioEx) {
                throw ioEx;
            }
            throw new IOException("Debug command failed.", error.get());
        }

        return result.get();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }

        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            String key = decode(keyValue[0]);
            String value = keyValue.length > 1 ? decode(keyValue[1]) : "";
            result.put(key, value);
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    @FunctionalInterface
    private interface FxTask<T> {
        T run() throws Exception;
    }

    private final class CommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCommand(exchange);
        }
    }
}
