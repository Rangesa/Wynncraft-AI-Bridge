package dev.tanaka.wynnaibridge.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.tanaka.wynnaibridge.ModVersion;
import dev.tanaka.wynnaibridge.capture.MessageStore;
import dev.tanaka.wynnaibridge.capture.TextCaptureStore;
import dev.tanaka.wynnaibridge.capture.RenderedItemCaptureStore;
import dev.tanaka.wynnaibridge.config.BridgeConfig;
import dev.tanaka.wynnaibridge.knowledge.WynnKnowledgeService;
import dev.tanaka.wynnaibridge.state.StateCollector;
import dev.tanaka.wynnaibridge.state.TooltipCollector;
import dev.tanaka.wynnaibridge.state.OpenContainerCollector;
import dev.tanaka.wynnaibridge.state.PlayerInventoryCollector;
import dev.tanaka.wynnaibridge.state.VisibleUiCollector;
import dev.tanaka.wynnaibridge.ui.UiActionGate;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class BridgeHttpServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 8192;
    private static final int MAX_QUERY_LIMIT = 2000;

    private final BridgeConfig config;
    private final HttpServer server;
    private final McpEndpoint mcpEndpoint;

    public BridgeHttpServer(BridgeConfig config) throws IOException {
        this.config = config;
        this.mcpEndpoint = new McpEndpoint(config);
        // Deliberately pin to IPv4 loopback. This also makes Host validation deterministic.
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.port()), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/health", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            UiActionGate.Status uiStatus = UiActionGate.INSTANCE.status();
            json(exchange, 200, Map.ofEntries(
                Map.entry("ok", true),
                Map.entry("name", "wynn-ai-bridge"),
                Map.entry("version", ModVersion.get()),
                Map.entry("actionsEnabled", config.allowActions()),
                Map.entry("tokenConfigured", !config.token().isEmpty()),
                Map.entry("uiActionsEnabled", config.allowUiActions()),
                Map.entry("uiActionsArmed", config.allowUiActions() && uiStatus.armed()),
                Map.entry("uiActionsArmExpiresAt", config.allowUiActions() ? uiStatus.expiresAtEpochMillis() : 0L),
                Map.entry("uiActionsArmCategory", config.allowUiActions() && uiStatus.armed() && uiStatus.category() != null
                    ? uiStatus.category().commandName() : ""),
                Map.entry("uiActionsCategoryActionsRemaining", config.allowUiActions() && uiStatus.armed()
                    ? uiStatus.categoryActionsRemaining() : 0),
                Map.entry("uiActionsBankWithdrawalsRemaining", config.allowUiActions() && uiStatus.armed()
                    ? uiStatus.bankWithdrawalsRemaining() : 0),
                Map.entry("mcp", Map.of(
                    "path", McpEndpoint.PATH,
                    "protocols", java.util.List.of(McpEndpoint.MODERN_PROTOCOL, McpEndpoint.LEGACY_PROTOCOL)
                )),
                Map.entry("captureSources", TextCaptureStore.INSTANCE.stats()),
                Map.entry("renderedItems", RenderedItemCaptureStore.INSTANCE.stats()),
                Map.entry("messages", MessageStore.INSTANCE.stats()),
                Map.entry("knowledge", WynnKnowledgeService.INSTANCE.status())
            ));
        });

        server.createContext(McpEndpoint.PATH, exchange -> {
            if (!requestGuard(exchange)) return;
            if (!authorized(exchange)) return;
            mcpEndpoint.handle(exchange);
        });

        server.createContext("/v1/state", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            if (!authorized(exchange)) return;
            json(exchange, 200, StateCollector.INSTANCE.latest());
        });

        server.createContext("/v1/text", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            if (!authorized(exchange)) return;

            Query query = Query.parse(exchange);
            long since = query.longValue("since", 0L, 0L, Long.MAX_VALUE - 1L);
            long maxAgeMs = query.longValue("maxAgeMs", 5000L, 1L, 60_000L);
            int limit = query.intValue("limit", 250, 1, MAX_QUERY_LIMIT);
            String source = query.stringValue("source");
            String kind = query.stringValue("kind");
            boolean includeChat = query.booleanValue("includeChat", false);
            long now = System.currentTimeMillis();

            var rawMessages = MessageStore.INSTANCE.snapshotSince(since, kind, Math.min(limit, 500));
            var messages = includeChat
                ? rawMessages
                : rawMessages.stream().filter(m -> !"chat".equals(m.kind())).toList();
            var visible = TextCaptureStore.INSTANCE.snapshotSince(since, maxAgeMs, source, limit, includeChat);

            json(exchange, 200, Map.of(
                "capturedAt", now,
                "nextSince", now,
                "visible", visible,
                "messages", messages,
                "dialogues", dev.tanaka.wynnaibridge.capture.WynnTextSemantics.dialogues(visible, messages),
                "chatIncluded", includeChat
            ));
        });

        server.createContext("/v1/slot", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            if (!authorized(exchange)) return;
            handleSlotTooltip(exchange);
        });

        server.createContext("/v1/container", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            if (!authorized(exchange)) return;
            Query query = Query.parse(exchange);
            boolean includeEmpty = query.booleanValue("includeEmpty", false);
            boolean includeTooltips = query.booleanValue("includeTooltips", true);
            boolean advanced = query.booleanValue("advanced", false);
            clientJson(exchange, () -> OpenContainerCollector.collect(
                Minecraft.getInstance(), includeEmpty, includeTooltips, advanced
            ));
        });

        server.createContext("/v1/inventory", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            if (!authorized(exchange)) return;
            Query query = Query.parse(exchange);
            boolean includeEmpty = query.booleanValue("includeEmpty", false);
            boolean includeTooltips = query.booleanValue("includeTooltips", true);
            boolean advanced = query.booleanValue("advanced", false);
            clientJson(exchange, () -> PlayerInventoryCollector.collect(
                Minecraft.getInstance(), includeEmpty, includeTooltips, advanced
            ));
        });

        server.createContext("/v1/visible-ui", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            if (!authorized(exchange)) return;
            Query query = Query.parse(exchange);
            long maxAgeMs = query.longValue("maxAgeMs", 1500L, 1L, 10_000L);
            int textLimit = query.intValue("textLimit", 400, 1, 1000);
            int itemLimit = query.intValue("itemLimit", 300, 1, 1000);
            boolean includeTooltips = query.booleanValue("includeTooltips", true);
            boolean advanced = query.booleanValue("advanced", false);
            boolean includeInventory = query.booleanValue("includeInventory", false);
            boolean includeChat = query.booleanValue("includeChat", false);
            clientJson(exchange, () -> VisibleUiCollector.collect(
                Minecraft.getInstance(), maxAgeMs, textLimit, itemLimit,
                includeTooltips, advanced, includeInventory, includeChat
            ));
        });

        server.createContext("/v1/wynn/index", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            if (!authorized(exchange)) return;
            json(exchange, 200, WynnKnowledgeService.INSTANCE.status());
        });

        server.createContext("/v1/wynn/items", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            if (!authorized(exchange)) return;
            Query query = Query.parse(exchange);
            String q = query.stringValue("q");
            String type = query.stringValue("type");
            String subType = query.stringValue("subType");
            String tier = query.stringValue("tier");
            int limit = query.intValue("limit", 20, 1, 100);
            json(exchange, 200, WynnKnowledgeService.INSTANCE.searchItems(q, type, subType, tier, limit));
        });

        server.createContext("/v1/wynn/item", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            if (!authorized(exchange)) return;
            Query query = Query.parse(exchange);
            String name = query.stringValue("name");
            if (name == null || name.isBlank()) {
                json(exchange, 400, Map.of("ok", false, "error", "Missing ?name="));
                return;
            }
            var result = WynnKnowledgeService.INSTANCE.getItem(name);
            json(exchange, result.found() ? 200 : 404, result);
        });

        server.createContext("/v1/wynn/locations", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            if (!authorized(exchange)) return;
            Query query = Query.parse(exchange);
            String q = query.stringValue("q");
            int limit = query.intValue("limit", 20, 1, 100);
            var snapshot = StateCollector.INSTANCE.latest();
            Double x = snapshot.player() == null ? null : snapshot.player().x();
            Double y = snapshot.player() == null ? null : snapshot.player().y();
            Double z = snapshot.player() == null ? null : snapshot.player().z();
            json(exchange, 200, WynnKnowledgeService.INSTANCE.searchLocations(q, limit, x, y, z));
        });

        server.createContext("/v1/wynn/wiki/search", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            if (!authorized(exchange)) return;
            Query query = Query.parse(exchange);
            String q = query.stringValue("q");
            if (q == null || q.isBlank()) {
                json(exchange, 400, Map.of("ok", false, "error", "Missing ?q="));
                return;
            }
            int limit = query.intValue("limit", 10, 1, 20);
            var result = WynnKnowledgeService.INSTANCE.searchWiki(q, limit);
            json(exchange, result.ok() ? 200 : 502, result);
        });

        server.createContext("/v1/wynn/wiki/page", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            if (!authorized(exchange)) return;
            Query query = Query.parse(exchange);
            String title = query.stringValue("title");
            if (title == null || title.isBlank()) {
                json(exchange, 400, Map.of("ok", false, "error", "Missing ?title="));
                return;
            }
            int maxChars = query.intValue("maxChars", 12_000, 1000, 40_000);
            var result = WynnKnowledgeService.INSTANCE.getWikiPage(title, maxChars);
            json(exchange, result.ok() ? 200 : 502, result);
        });

        server.createContext("/v1/wynn/knowledge", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "GET")) return;
            if (!authorized(exchange)) return;
            Query query = Query.parse(exchange);
            String q = query.stringValue("q");
            if (q == null || q.isBlank()) {
                json(exchange, 400, Map.of("ok", false, "error", "Missing ?q="));
                return;
            }
            int limit = query.intValue("limit", 10, 1, 25);
            boolean includeWiki = query.booleanValue("includeWiki", true);
            var snapshot = StateCollector.INSTANCE.latest();
            Double x = snapshot.player() == null ? null : snapshot.player().x();
            Double y = snapshot.player() == null ? null : snapshot.player().y();
            Double z = snapshot.player() == null ? null : snapshot.player().z();
            json(exchange, 200, WynnKnowledgeService.INSTANCE.searchKnowledge(q, limit, x, y, z, includeWiki));
        });

        server.createContext("/v1/action/command", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "POST")) return;
            if (!authorized(exchange)) return;
            if (!config.allowActions()) {
                json(exchange, 403, Map.of("ok", false, "error", "Actions are disabled in config/wynn-ai-bridge.properties"));
                return;
            }

            String command;
            try {
                command = readBody(exchange).strip();
            } catch (BodyTooLargeException e) {
                json(exchange, 413, Map.of("ok", false, "error", "Request body too large"));
                return;
            }
            if (command.startsWith("/")) command = command.substring(1);
            if (command.isBlank() || command.length() > 2048) {
                json(exchange, 400, Map.of("ok", false, "error", "Invalid command"));
                return;
            }

            String finalCommand = command;
            Minecraft.getInstance().execute(() -> {
                var connection = Minecraft.getInstance().getConnection();
                if (connection != null) connection.sendCommand(finalCommand);
            });
            json(exchange, 202, Map.of("ok", true, "queued", command));
        });

        server.createContext("/v1/action/chat", exchange -> {
            if (!requestGuard(exchange)) return;
            if (!method(exchange, "POST")) return;
            if (!authorized(exchange)) return;
            if (!config.allowActions()) {
                json(exchange, 403, Map.of("ok", false, "error", "Actions are disabled in config/wynn-ai-bridge.properties"));
                return;
            }

            String message;
            try {
                message = readBody(exchange).strip();
            } catch (BodyTooLargeException e) {
                json(exchange, 413, Map.of("ok", false, "error", "Request body too large"));
                return;
            }
            if (message.isBlank() || message.length() > 256) {
                json(exchange, 400, Map.of("ok", false, "error", "Invalid chat message"));
                return;
            }

            String finalMessage = message;
            Minecraft.getInstance().execute(() -> {
                var connection = Minecraft.getInstance().getConnection();
                if (connection != null) connection.sendChat(finalMessage);
            });
            json(exchange, 202, Map.of("ok", true, "queued", message));
        });
    }

    public void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleSlotTooltip(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String prefix = "/v1/slot/";
        if (!path.startsWith(prefix) || !path.endsWith("/tooltip")) {
            json(exchange, 404, Map.of("ok", false, "error", "Use /v1/slot/{n}/tooltip or /v1/slot/hovered/tooltip"));
            return;
        }

        String slotPart = path.substring(prefix.length(), path.length() - "/tooltip".length());
        Integer requestedSlot = null;
        if (!slotPart.equals("hovered")) {
            try {
                requestedSlot = Integer.parseInt(slotPart);
            } catch (NumberFormatException e) {
                json(exchange, 400, Map.of("ok", false, "error", "Invalid slot"));
                return;
            }
        }

        Query query = Query.parse(exchange);
        boolean advanced = query.booleanValue("advanced", false);
        Integer finalRequestedSlot = requestedSlot;

        CompletableFuture<TooltipCollector.Result> future = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            try {
                future.complete(TooltipCollector.collect(minecraft, finalRequestedSlot, advanced));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            TooltipCollector.Result result = future.get(2, TimeUnit.SECONDS);
            json(exchange, result.ok() ? 200 : 409, result);
        } catch (TimeoutException e) {
            json(exchange, 503, Map.of("ok", false, "error", "Minecraft client thread timed out"));
        } catch (Exception e) {
            json(exchange, 500, Map.of("ok", false, "error", "Tooltip collection failed", "detail", e.getClass().getSimpleName()));
        }
    }

    private void clientJson(HttpExchange exchange, java.util.concurrent.Callable<?> task) throws IOException {
        CompletableFuture<Object> future = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            json(exchange, 200, future.get(3, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            json(exchange, 503, Map.of("ok", false, "error", "Minecraft client thread timed out"));
        } catch (Exception e) {
            json(exchange, 500, Map.of("ok", false, "error", "Client state collection failed", "detail", e.getClass().getSimpleName()));
        }
    }

    /**
     * Reject browser-originated and DNS-rebinding-shaped requests before auth or side effects.
     */
    private boolean requestGuard(HttpExchange exchange) throws IOException {
        String host = exchange.getRequestHeaders().getFirst("Host");
        String expectedIp = "127.0.0.1:" + config.port();
        String expectedLocalhost = "localhost:" + config.port();
        String configuredTailscaleHost = config.tailscaleHost();
        boolean localHost = host != null && (host.equalsIgnoreCase(expectedIp) || host.equalsIgnoreCase(expectedLocalhost));
        boolean tailscaleHost = host != null && !configuredTailscaleHost.isBlank()
            && host.equalsIgnoreCase(configuredTailscaleHost);
        if (!localHost && !tailscaleHost) {
            json(exchange, 403, Map.of("ok", false, "error", "Invalid Host header"));
            return false;
        }

        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null) {
            json(exchange, 403, Map.of("ok", false, "error", "Browser Origin requests are not accepted"));
            return false;
        }
        return true;
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        if (config.token().isEmpty()) return true;

        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            unauthorized(exchange);
            return false;
        }

        String supplied = auth.substring("Bearer ".length());
        byte[] expectedBytes = config.token().getBytes(StandardCharsets.UTF_8);
        byte[] suppliedBytes = supplied.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(expectedBytes, suppliedBytes)) return true;

        unauthorized(exchange);
        return false;
    }

    private static void unauthorized(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        json(exchange, 401, Map.of("ok", false, "error", "Unauthorized"));
    }

    private static boolean method(HttpExchange exchange, String expected) throws IOException {
        if (expected.equalsIgnoreCase(exchange.getRequestMethod())) return true;
        exchange.getResponseHeaders().set("Allow", expected);
        json(exchange, 405, Map.of("ok", false, "error", "Method not allowed"));
        return false;
    }

    private static String readBody(HttpExchange exchange) throws IOException, BodyTooLargeException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new BodyTooLargeException();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void json(HttpExchange exchange, int status, Object object) throws IOException {
        byte[] bytes = Json.stringify(object).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class BodyTooLargeException extends Exception {}

    private record Query(Map<String, String> values) {
        static Query parse(HttpExchange exchange) {
            Map<String, String> out = new LinkedHashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw == null || raw.isBlank()) return new Query(out);

            for (String part : raw.split("&")) {
                if (part.isBlank()) continue;
                int equals = part.indexOf('=');
                String key = decode(equals >= 0 ? part.substring(0, equals) : part);
                String value = decode(equals >= 0 ? part.substring(equals + 1) : "");
                out.putIfAbsent(key, value);
            }
            return new Query(out);
        }

        String stringValue(String key) {
            String value = values.get(key);
            return value == null || value.isBlank() ? null : value;
        }

        long longValue(String key, long fallback, long min, long max) {
            try {
                long value = Long.parseLong(values.getOrDefault(key, Long.toString(fallback)));
                return Math.max(min, Math.min(value, max));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        int intValue(String key, int fallback, int min, int max) {
            try {
                int value = Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)));
                return Math.max(min, Math.min(value, max));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        boolean booleanValue(String key, boolean fallback) {
            String value = values.get(key);
            return value == null ? fallback : Boolean.parseBoolean(value);
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }
}
