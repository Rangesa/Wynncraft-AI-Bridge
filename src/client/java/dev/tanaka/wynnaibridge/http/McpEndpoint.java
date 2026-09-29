package dev.tanaka.wynnaibridge.http;

import com.sun.net.httpserver.HttpExchange;
import dev.tanaka.wynnaibridge.ModVersion;
import dev.tanaka.wynnaibridge.capture.MessageStore;
import dev.tanaka.wynnaibridge.capture.TextCaptureStore;
import dev.tanaka.wynnaibridge.config.BridgeConfig;
import dev.tanaka.wynnaibridge.knowledge.WynnKnowledgeService;
import dev.tanaka.wynnaibridge.state.StateCollector;
import dev.tanaka.wynnaibridge.state.TooltipCollector;
import dev.tanaka.wynnaibridge.state.OpenContainerCollector;
import dev.tanaka.wynnaibridge.state.PlayerInventoryCollector;
import dev.tanaka.wynnaibridge.state.VisibleUiCollector;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Embedded MCP Streamable HTTP endpoint.
 *
 * Supports the current stateless 2026-07-28 protocol and the legacy
 * 2025-11-25 initialize/session flow so current and older inspectors/hosts
 * can both talk to the mod.
 */
public final class McpEndpoint {
    public static final String PATH = "/mcp";
    public static final String MODERN_PROTOCOL = "2026-07-28";
    public static final String LEGACY_PROTOCOL = "2025-11-25";
    private static final String SERVER_NAME = "wynn-ai-bridge";
    private static final int MAX_MCP_BODY_BYTES = 64 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BridgeConfig config;
    private final Set<String> legacySessions = ConcurrentHashMap.newKeySet();

    public McpEndpoint(BridgeConfig config) {
        this.config = config;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            String sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            if (sessionId != null) legacySessions.remove(sessionId);
            noContent(exchange, 204);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST, DELETE");
            writeJson(exchange, 405, Map.of("error", "MCP Streamable HTTP endpoint accepts POST requests (and DELETE for legacy session close)"));
            return;
        }

        String raw;
        try {
            raw = readBody(exchange, MAX_MCP_BODY_BYTES);
        } catch (BodyTooLargeException e) {
            rpcError(exchange, 413, null, -32600, "MCP request body is too large", null, false);
            return;
        }

        final Map<String, Object> request;
        try {
            request = Json.object(Json.parse(raw));
        } catch (RuntimeException e) {
            rpcError(exchange, 400, null, -32700, "Parse error", e.getMessage(), false);
            return;
        }

        Object id = request.get("id");
        String jsonrpc = string(request.get("jsonrpc"));
        String method = string(request.get("method"));
        Map<String, Object> params = objectOrEmpty(request.get("params"));

        if (!"2.0".equals(jsonrpc) || method == null || method.isBlank()) {
            rpcError(exchange, 400, id, -32600, "Invalid Request", null, false);
            return;
        }

        boolean modern = isModern(exchange, method, params);
        if (modern && !validateModernHeaders(exchange, method, params)) {
            rpcError(exchange, 400, id, -32600, "Invalid modern MCP headers", null, true);
            return;
        }

        // JSON-RPC notifications do not receive a response body.
        if (id == null && method.startsWith("notifications/")) {
            noContent(exchange, 202);
            return;
        }

        try {
            switch (method) {
                case "server/discover" -> success(exchange, id, discoverResult(), true, null);
                case "initialize" -> initializeLegacy(exchange, id, params);
                case "ping" -> success(exchange, id, Map.of(), modern, null);
                case "tools/list" -> success(exchange, id, listToolsResult(modern), modern, null);
                case "tools/call" -> callTool(exchange, id, params, modern);
                default -> rpcError(exchange, 200, id, -32601, "Method not found", method, modern);
            }
        } catch (IllegalArgumentException e) {
            rpcError(exchange, 200, id, -32602, "Invalid params", e.getMessage(), modern);
        } catch (Exception e) {
            rpcError(exchange, 200, id, -32603, "Internal error", e.getClass().getSimpleName(), modern);
        }
    }

    private void initializeLegacy(HttpExchange exchange, Object id, Map<String, Object> params) throws IOException {
        String requested = string(params.get("protocolVersion"));
        String negotiated = LEGACY_PROTOCOL;
        if (requested != null && requested.compareTo(LEGACY_PROTOCOL) < 0) negotiated = requested;

        String sessionId = newSessionId();
        legacySessions.add(sessionId);
        exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);

        Map<String, Object> result = linked(
            "protocolVersion", negotiated,
            "capabilities", Map.of("tools", Map.of("listChanged", false)),
            "serverInfo", serverInfo(),
            "instructions", instructions()
        );
        success(exchange, id, result, false, null);
    }

    private Map<String, Object> discoverResult() {
        return linked(
            "resultType", "complete",
            "supportedVersions", List.of(MODERN_PROTOCOL, LEGACY_PROTOCOL),
            "capabilities", Map.of("tools", Map.of("listChanged", false)),
            "instructions", instructions(),
            "ttlMs", 5_000,
            "cacheScope", "private",
            "_meta", modernMeta()
        );
    }

    private Map<String, Object> listToolsResult(boolean modern) {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool(
            "minecraft_get_state",
            "Read the latest Minecraft client state: player position/health, open screen, visible container slots, crosshair target, and nearby entity/display text. Prefer this over asking the user to describe or screenshot the game.",
            emptyObjectSchema(),
            true,
            false
        ));
        tools.add(tool(
            "minecraft_get_recent_text",
            "Read text captured directly before Minecraft renders it, plus recent game/actionbar messages. This is not OCR and is useful for Wynncraft NPC dialogue, tracked quests, GUI labels, and HUD text. Player chat is excluded by default for privacy.",
            objectSchema(linked(
                "since", integerProperty("Only return records newer than this Unix time in milliseconds.", 0L, null),
                "maxAgeMs", integerProperty("Maximum age of visible text in milliseconds.", 1L, 60_000L),
                "source", stringProperty("Optional capture source filter; a trailing * means prefix match."),
                "kind", stringProperty("Optional message kind filter, for example game or actionbar/game."),
                "limit", integerProperty("Maximum number of records.", 1L, 500L),
                "includeChat", booleanProperty("Include ordinary player chat. Defaults to false to avoid leaking unrelated chat/DMs.")
            )),
            true,
            false
        ));
        tools.add(tool(
            "minecraft_get_slot_tooltip",
            "Read every tooltip/lore line for a slot in the currently open container GUI without OCR. Omit slot to inspect the slot currently under the mouse cursor.",
            objectSchema(linked(
                "slot", integerProperty("Menu slot index. Omit to use the currently hovered slot.", 0L, 1000L),
                "advanced", booleanProperty("Request the advanced Minecraft tooltip when available.")
            )),
            true,
            false
        ));
        tools.add(tool(
            "minecraft_get_context",
            "Get a compact combined snapshot for answering 'what am I doing?', 'what does this say?', and similar Wynncraft questions. Includes game state plus recent pre-render text and game/actionbar messages; ordinary player chat is excluded by default.",
            objectSchema(linked(
                "maxAgeMs", integerProperty("Maximum age of captured text in milliseconds.", 1L, 60_000L),
                "limit", integerProperty("Maximum text/message records.", 1L, 300L),
                "includeChat", booleanProperty("Include ordinary player chat. Defaults to false.")
            )),
            true,
            false
        ));
        tools.add(tool(
            "minecraft_get_open_container",
            "Read every slot in the container GUI that is currently open, including chest/ender-chest style screens, and optionally generate every item's full tooltip/lore without requiring mouse hover. Only currently open client-visible contents are returned.",
            objectSchema(linked(
                "includeEmpty", booleanProperty("Include empty GUI slots. Defaults to false."),
                "includeTooltips", booleanProperty("Generate full tooltip/lore for every non-empty slot. Defaults to true."),
                "advanced", booleanProperty("Use Minecraft advanced tooltips when generating lore.")
            )),
            true,
            false
        ));
        tools.add(tool(
            "minecraft_get_inventory",
            "Read the local player's own hotbar, inventory and equipped items, optionally with full tooltip/lore for every item. This is state the player can inspect from their inventory screen.",
            objectSchema(linked(
                "includeEmpty", booleanProperty("Include empty inventory/equipment slots. Defaults to false."),
                "includeTooltips", booleanProperty("Generate full tooltip/lore for every item. Defaults to true."),
                "advanced", booleanProperty("Use Minecraft advanced tooltips when generating lore.")
            )),
            true,
            false
        ));
        tools.add(tool(
            "minecraft_get_visible_ui",
            "Read the broadest non-OCR view of what the user can currently inspect: pre-render GUI/HUD text, item icons actually submitted to the GUI renderer (including custom/non-container screens), the currently open container with item lore, and optionally the player's inventory.",
            objectSchema(linked(
                "maxAgeMs", integerProperty("How recent rendered text/items must be, in milliseconds.", 1L, 10_000L),
                "textLimit", integerProperty("Maximum captured text records.", 1L, 1000L),
                "itemLimit", integerProperty("Maximum rendered item records.", 1L, 1000L),
                "includeTooltips", booleanProperty("Generate tooltip/lore for rendered/container/inventory items. Defaults to true."),
                "advanced", booleanProperty("Use Minecraft advanced tooltips when generating lore."),
                "includeInventory", booleanProperty("Also include the player's own inventory/equipment. Defaults to false to keep responses smaller."),
                "includeChat", booleanProperty("Include ordinary player chat rendered in the UI. Defaults to false for privacy.")
            )),
            true,
            false
        ));
        tools.add(tool(
            "wynn_inspect_hovered_item",
            "Inspect the currently hovered/open-container item and enrich its live tooltip with the official Wynncraft item record plus optional official-wiki search results. This is the preferred tool for user questions like 'what is this?', 'how do I upgrade this?', or 'where do I get/use this?' while hovering an item.",
            objectSchema(linked(
                "slot", integerProperty("Optional menu slot index. Omit to use the currently hovered slot.", 0L, 1000L),
                "advanced", booleanProperty("Use Minecraft advanced tooltip mode."),
                "includeWiki", booleanProperty("Search the official Wynncraft Wiki for the item's name. Defaults to true.")
            )),
            true,
            true
        ));
        tools.add(tool(
            "wynn_knowledge_search",
            "Search the local Wynncraft knowledge index and, optionally, the official Wynncraft Wiki. Use this for questions like what an item is for, where to go, where rings/accessories are sold, quest/merchant names, and likely upgrade or acquisition paths.",
            requiredObjectSchema("query", linked(
                "query", stringProperty("Natural-language or item/location/merchant/quest search query."),
                "limit", integerProperty("Maximum matches per category.", 1L, 25L),
                "includeWiki", booleanProperty("Also search the official Wynncraft Wiki. Defaults to true.")
            )),
            true,
            true
        ));
        tools.add(tool(
            "wynn_search_items",
            "Search the locally cached official Wynncraft item database by name/type/subtype/tier. The local index refreshes from api.wynncraft.com and avoids repeated web requests.",
            objectSchema(linked(
                "query", stringProperty("Partial or exact item name; may be empty when using filters."),
                "type", stringProperty("Optional official item type filter."),
                "subType", stringProperty("Optional official item subtype filter, e.g. ring, bow, helmet."),
                "tier", stringProperty("Optional tier/rarity filter."),
                "limit", integerProperty("Maximum results.", 1L, 100L)
            )),
            true,
            true
        ));
        tools.add(tool(
            "wynn_get_item",
            "Get the full official Wynncraft API record for an exact item name. Use this after item search when detailed stats, requirements, IDs, set membership, or restrictions matter.",
            requiredObjectSchema("name", linked(
                "name", stringProperty("Exact or near-exact Wynncraft item display/internal name.")
            )),
            true,
            true
        ));
        tools.add(tool(
            "wynn_search_locations",
            "Search locally cached official Wynncraft map markers. When the Minecraft player position is available, results include approximate distance from the player.",
            requiredObjectSchema("query", linked(
                "query", stringProperty("Location/service/name query."),
                "limit", integerProperty("Maximum results.", 1L, 100L)
            )),
            true,
            true
        ));
        tools.add(tool(
            "wynn_search_wiki",
            "Search the official Wynncraft Wiki through its MediaWiki API. Useful when official item/map APIs do not contain merchant inventories, quest walkthroughs, specific upgrade methods, or item uses.",
            requiredObjectSchema("query", linked(
                "query", stringProperty("Wiki search query."),
                "limit", integerProperty("Maximum search results.", 1L, 20L)
            )),
            true,
            true
        ));
        tools.add(tool(
            "wynn_get_wiki_page",
            "Fetch the plaintext extract of a specific official Wynncraft Wiki article returned by wynn_search_wiki.",
            requiredObjectSchema("title", linked(
                "title", stringProperty("Exact wiki article title."),
                "maxChars", integerProperty("Maximum plaintext characters returned.", 1000L, 40_000L)
            )),
            true,
            true
        ));
        tools.add(tool(
            "wynn_index_status",
            "Inspect the local Wynncraft knowledge index status, cache counts, refresh timestamps and last network error.",
            emptyObjectSchema(),
            true,
            false
        ));

        if (config.allowActions()) {
            tools.add(tool(
                "minecraft_send_command",
                "Send a Minecraft/server command as the local player. This changes game/server state and is only exposed when allowActions=true in the mod config.",
                requiredObjectSchema("command", linked(
                    "command", stringProperty("Command text, with or without the leading /. Maximum 2048 characters.")
                )),
                false,
                true
            ));
            tools.add(tool(
                "minecraft_send_chat",
                "Send a chat message as the local player. This is only exposed when allowActions=true in the mod config.",
                requiredObjectSchema("message", linked(
                    "message", stringProperty("Chat message. Maximum 256 characters.")
                )),
                false,
                true
            ));
        }

        Map<String, Object> result = linked("tools", List.copyOf(tools));
        if (modern) {
            result.put("resultType", "complete");
            result.put("ttlMs", 5_000);
            result.put("cacheScope", "private");
            result.put("_meta", modernMeta());
        }
        return result;
    }

    private void callTool(HttpExchange exchange, Object id, Map<String, Object> params, boolean modern) throws Exception {
        String name = string(params.get("name"));
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Missing tool name");
        Map<String, Object> args = objectOrEmpty(params.get("arguments"));

        switch (name) {
            case "minecraft_get_state" -> toolSuccess(exchange, id, StateCollector.INSTANCE.latest(),
                "Read the current Minecraft client state.", modern);

            case "minecraft_get_recent_text" -> {
                long since = longArg(args, "since", 0L, 0L, Long.MAX_VALUE - 1L);
                long maxAgeMs = longArg(args, "maxAgeMs", 5_000L, 1L, 60_000L);
                int limit = intArg(args, "limit", 150, 1, 500);
                String source = string(args.get("source"));
                String kind = string(args.get("kind"));
                boolean includeChat = boolArg(args, "includeChat", false);
                Map<String, Object> data = recentText(since, maxAgeMs, source, kind, limit, includeChat);
                toolSuccess(exchange, id, data, "Read recent Minecraft/Wynncraft text captured without OCR.", modern);
            }

            case "minecraft_get_slot_tooltip" -> {
                Integer slot = nullableIntArg(args, "slot", 0, 1000);
                boolean advanced = boolArg(args, "advanced", false);
                TooltipCollector.Result result = onClientThread(() ->
                    TooltipCollector.collect(Minecraft.getInstance(), slot, advanced));
                if (!result.ok()) {
                    toolError(exchange, id, result.error(), result, modern);
                } else {
                    toolSuccess(exchange, id, result, "Read item tooltip/lore from the open GUI.", modern);
                }
            }

            case "minecraft_get_context" -> {
                long maxAgeMs = longArg(args, "maxAgeMs", 5_000L, 1L, 60_000L);
                int limit = intArg(args, "limit", 120, 1, 300);
                boolean includeChat = boolArg(args, "includeChat", false);
                Map<String, Object> data = linked(
                    "capturedAt", System.currentTimeMillis(),
                    "state", StateCollector.INSTANCE.latest(),
                    "text", recentText(0L, maxAgeMs, null, null, limit, includeChat)
                );
                toolSuccess(exchange, id, data, "Read a compact live Minecraft/Wynncraft context snapshot.", modern);
            }

            case "minecraft_get_open_container" -> {
                boolean includeEmpty = boolArg(args, "includeEmpty", false);
                boolean includeTooltips = boolArg(args, "includeTooltips", true);
                boolean advanced = boolArg(args, "advanced", false);
                OpenContainerCollector.Result result = onClientThread(() ->
                    OpenContainerCollector.collect(Minecraft.getInstance(), includeEmpty, includeTooltips, advanced));
                if (!result.ok()) {
                    toolError(exchange, id, result.error(), result, modern);
                } else {
                    toolSuccess(exchange, id, result, "Read all currently open container slots and item lore visible to the local client.", modern);
                }
            }

            case "minecraft_get_inventory" -> {
                boolean includeEmpty = boolArg(args, "includeEmpty", false);
                boolean includeTooltips = boolArg(args, "includeTooltips", true);
                boolean advanced = boolArg(args, "advanced", false);
                PlayerInventoryCollector.Result result = onClientThread(() ->
                    PlayerInventoryCollector.collect(Minecraft.getInstance(), includeEmpty, includeTooltips, advanced));
                if (!result.ok()) {
                    toolError(exchange, id, result.error(), result, modern);
                } else {
                    toolSuccess(exchange, id, result, "Read the local player's inventory/equipment and item lore.", modern);
                }
            }

            case "minecraft_get_visible_ui" -> {
                long maxAgeMs = longArg(args, "maxAgeMs", 1_500L, 1L, 10_000L);
                int textLimit = intArg(args, "textLimit", 400, 1, 1000);
                int itemLimit = intArg(args, "itemLimit", 300, 1, 1000);
                boolean includeTooltips = boolArg(args, "includeTooltips", true);
                boolean advanced = boolArg(args, "advanced", false);
                boolean includeInventory = boolArg(args, "includeInventory", false);
                boolean includeChat = boolArg(args, "includeChat", false);
                VisibleUiCollector.Result result = onClientThread(() ->
                    VisibleUiCollector.collect(
                        Minecraft.getInstance(), maxAgeMs, textLimit, itemLimit,
                        includeTooltips, advanced, includeInventory, includeChat
                    ));
                toolSuccess(exchange, id, result, "Read the current user-visible Minecraft UI without OCR.", modern);
            }

            case "wynn_inspect_hovered_item" -> {
                Integer slot = nullableIntArg(args, "slot", 0, 1000);
                boolean advanced = boolArg(args, "advanced", false);
                boolean includeWiki = boolArg(args, "includeWiki", true);
                TooltipCollector.Result tooltip = onClientThread(() ->
                    TooltipCollector.collect(Minecraft.getInstance(), slot, advanced));
                if (!tooltip.ok()) {
                    toolError(exchange, id, tooltip.error(), tooltip, modern);
                } else {
                    var official = WynnKnowledgeService.INSTANCE.getItem(tooltip.name());
                    var wiki = includeWiki
                        ? WynnKnowledgeService.INSTANCE.searchWiki(tooltip.name(), 8)
                        : new WynnKnowledgeService.WikiSearchResult(false, tooltip.name(), List.of(), "Wiki lookup not requested", "https://wynncraft.wiki.gg/wiki/");
                    Map<String, Object> data = linked(
                        "liveTooltip", tooltip,
                        "officialItem", official,
                        "wiki", wiki,
                        "note", "Live tooltip is authoritative for the exact item instance; official API/wiki provide general game knowledge."
                    );
                    toolSuccess(exchange, id, data, "Inspected the hovered item and enriched it with Wynncraft knowledge.", modern);
                }
            }

            case "wynn_knowledge_search" -> {
                String query = requiredString(args, "query").strip();
                int limit = intArg(args, "limit", 10, 1, 25);
                boolean includeWiki = boolArg(args, "includeWiki", true);
                var snapshot = StateCollector.INSTANCE.latest();
                Double x = snapshot.player() == null ? null : snapshot.player().x();
                Double y = snapshot.player() == null ? null : snapshot.player().y();
                Double z = snapshot.player() == null ? null : snapshot.player().z();
                var result = WynnKnowledgeService.INSTANCE.searchKnowledge(query, limit, x, y, z, includeWiki);
                toolSuccess(exchange, id, result, "Searched indexed Wynncraft items/sets/locations and optional official wiki knowledge.", modern);
            }

            case "wynn_search_items" -> {
                String query = string(args.get("query"));
                String type = string(args.get("type"));
                String subType = string(args.get("subType"));
                String tier = string(args.get("tier"));
                int limit = intArg(args, "limit", 20, 1, 100);
                var result = WynnKnowledgeService.INSTANCE.searchItems(query, type, subType, tier, limit);
                toolSuccess(exchange, id, result, "Searched the local official Wynncraft item index.", modern);
            }

            case "wynn_get_item" -> {
                String nameArg = requiredString(args, "name").strip();
                var result = WynnKnowledgeService.INSTANCE.getItem(nameArg);
                if (!result.found()) toolError(exchange, id, "Exact item not found; suggestions are included.", result, modern);
                else toolSuccess(exchange, id, result, "Read the full official Wynncraft item record.", modern);
            }

            case "wynn_search_locations" -> {
                String query = requiredString(args, "query").strip();
                int limit = intArg(args, "limit", 20, 1, 100);
                var snapshot = StateCollector.INSTANCE.latest();
                Double x = snapshot.player() == null ? null : snapshot.player().x();
                Double y = snapshot.player() == null ? null : snapshot.player().y();
                Double z = snapshot.player() == null ? null : snapshot.player().z();
                var result = WynnKnowledgeService.INSTANCE.searchLocations(query, limit, x, y, z);
                toolSuccess(exchange, id, result, "Searched official Wynncraft map markers, with player-relative distance when available.", modern);
            }

            case "wynn_search_wiki" -> {
                String query = requiredString(args, "query").strip();
                int limit = intArg(args, "limit", 10, 1, 20);
                var result = WynnKnowledgeService.INSTANCE.searchWiki(query, limit);
                if (!result.ok()) toolError(exchange, id, result.error(), result, modern);
                else toolSuccess(exchange, id, result, "Searched the official Wynncraft Wiki.", modern);
            }

            case "wynn_get_wiki_page" -> {
                String title = requiredString(args, "title").strip();
                int maxChars = intArg(args, "maxChars", 12_000, 1000, 40_000);
                var result = WynnKnowledgeService.INSTANCE.getWikiPage(title, maxChars);
                if (!result.ok()) toolError(exchange, id, result.note(), result, modern);
                else toolSuccess(exchange, id, result, "Fetched the official Wynncraft Wiki article extract.", modern);
            }

            case "wynn_index_status" -> toolSuccess(
                exchange, id, WynnKnowledgeService.INSTANCE.status(), "Read Wynncraft knowledge-index status.", modern
            );

            case "minecraft_send_command" -> {
                ensureActions();
                String command = requiredString(args, "command").strip();
                if (command.startsWith("/")) command = command.substring(1);
                if (command.isBlank() || command.length() > 2048) throw new IllegalArgumentException("Invalid command length");
                String finalCommand = command;
                onClientThread(() -> {
                    var connection = Minecraft.getInstance().getConnection();
                    if (connection == null) throw new IllegalStateException("Not connected to a server");
                    connection.sendCommand(finalCommand);
                    return null;
                });
                toolSuccess(exchange, id, Map.of("queued", command), "Queued the Minecraft command.", modern);
            }

            case "minecraft_send_chat" -> {
                ensureActions();
                String message = requiredString(args, "message").strip();
                if (message.isBlank() || message.length() > 256) throw new IllegalArgumentException("Invalid chat message length");
                onClientThread(() -> {
                    var connection = Minecraft.getInstance().getConnection();
                    if (connection == null) throw new IllegalStateException("Not connected to a server");
                    connection.sendChat(message);
                    return null;
                });
                toolSuccess(exchange, id, Map.of("queued", message), "Queued the Minecraft chat message.", modern);
            }

            default -> rpcError(exchange, 200, id, -32601, "Unknown tool", name, modern);
        }
    }

    private Map<String, Object> recentText(long since, long maxAgeMs, String source, String kind, int limit, boolean includeChat) {
        List<MessageStore.Message> rawMessages = MessageStore.INSTANCE.snapshotSince(since, kind, Math.min(limit, 500));
        List<MessageStore.Message> messages;
        if (includeChat) {
            messages = rawMessages;
        } else {
            messages = rawMessages.stream().filter(m -> !"chat".equals(m.kind())).toList();
        }
        long now = System.currentTimeMillis();
        List<TextCaptureStore.CapturedText> visible = TextCaptureStore.INSTANCE.snapshotSince(
            since, maxAgeMs, source, limit, includeChat
        );
        return linked(
            "capturedAt", now,
            "nextSince", now,
            "visible", visible,
            "messages", messages,
            "dialogues", dev.tanaka.wynnaibridge.capture.WynnTextSemantics.dialogues(visible, messages),
            "chatIncluded", includeChat
        );
    }

    private void ensureActions() {
        if (!config.allowActions()) {
            throw new IllegalArgumentException("Actions are disabled in config/wynn-ai-bridge.properties");
        }
    }

    private <T> T onClientThread(Callable<T> task) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Minecraft client thread timed out", e);
        }
    }

    private void toolSuccess(HttpExchange exchange, Object id, Object data, String summary, boolean modern) throws IOException {
        Map<String, Object> result = linked(
            "content", List.of(Map.of("type", "text", "text", summary)),
            "structuredContent", data
        );
        if (modern) {
            result.put("resultType", "complete");
            result.put("_meta", modernMeta());
        }
        success(exchange, id, result, modern, null);
    }

    private void toolError(HttpExchange exchange, Object id, String message, Object data, boolean modern) throws IOException {
        Map<String, Object> result = linked(
            "content", List.of(Map.of("type", "text", "text", message == null ? "Tool call failed" : message)),
            "structuredContent", data,
            "isError", true
        );
        if (modern) {
            result.put("resultType", "complete");
            result.put("_meta", modernMeta());
        }
        success(exchange, id, result, modern, null);
    }

    private void success(HttpExchange exchange, Object id, Object result, boolean modern, String sessionId) throws IOException {
        if (modern) exchange.getResponseHeaders().set("MCP-Protocol-Version", MODERN_PROTOCOL);
        if (sessionId != null) exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
        writeJson(exchange, 200, linked("jsonrpc", "2.0", "id", id, "result", result));
    }

    private void rpcError(HttpExchange exchange, int httpStatus, Object id, int code, String message, Object data, boolean modern) throws IOException {
        Map<String, Object> error = linked("code", code, "message", message);
        if (data != null) error.put("data", data);
        if (modern) exchange.getResponseHeaders().set("MCP-Protocol-Version", MODERN_PROTOCOL);
        writeJson(exchange, httpStatus, linked("jsonrpc", "2.0", "id", id, "error", error));
    }

    private boolean isModern(HttpExchange exchange, String method, Map<String, Object> params) {
        if ("server/discover".equals(method)) return true;
        String header = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
        if (MODERN_PROTOCOL.equals(header)) return true;
        Map<String, Object> meta = objectOrEmpty(params.get("_meta"));
        return MODERN_PROTOCOL.equals(string(meta.get("io.modelcontextprotocol/protocolVersion")));
    }

    /**
     * Strictly check the SEP-2243 routing headers when the caller explicitly
     * announces the modern protocol through MCP-Protocol-Version. Discovery
     * probes without that header are accepted for graceful era negotiation.
     */
    private boolean validateModernHeaders(HttpExchange exchange, String method, Map<String, Object> params) {
        String protocol = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
        if (protocol == null) return true;
        if (!MODERN_PROTOCOL.equals(protocol)) return false;

        String methodHeader = exchange.getRequestHeaders().getFirst("Mcp-Method");
        if (!method.equals(methodHeader)) return false;

        if ("tools/call".equals(method)) {
            String expectedName = string(params.get("name"));
            String nameHeader = exchange.getRequestHeaders().getFirst("Mcp-Name");
            return expectedName != null && expectedName.equals(nameHeader);
        }
        return true;
    }

    private Map<String, Object> modernMeta() {
        return Map.of("io.modelcontextprotocol/serverInfo", serverInfo());
    }

    private Map<String, Object> serverInfo() {
        return linked(
            "name", SERVER_NAME,
            "title", "Wynn AI Bridge",
            "version", ModVersion.get(),
            "description", "Live Minecraft/Wynncraft client context exposed directly from a Fabric mod without OCR."
        );
    }

    private String instructions() {
        return "Use these tools to inspect the user's live Minecraft/Wynncraft client instead of asking for screenshots or repeated state descriptions. " +
            "Prefer minecraft_get_context for compact broad questions, minecraft_get_visible_ui when the exact current GUI/HUD matters, " +
            "minecraft_get_open_container for chest/ender-chest/container contents, minecraft_get_inventory for the player's items, " +
            "minecraft_get_recent_text for NPC/quest/HUD text, and minecraft_get_slot_tooltip for one specific item's lore. " +
            "For a hovered/selected Wynncraft item, prefer wynn_inspect_hovered_item. For broader game knowledge, use wynn_knowledge_search first; use wynn_get_item for exact official item data, " +
            "wynn_search_locations for official map markers, and wynn_search_wiki / wynn_get_wiki_page for merchant inventories, quest walkthroughs, acquisition and upgrade instructions not present in the public API. " +
            "Only client-visible/currently available Minecraft state should be treated as authoritative; a closed remote container is not readable. " +
            "Captured text is sanitized to remove Wynncraft rendering-only glyphs and duplicate draw paths. Use the dialogues field when present. Captured text can be transient; use recent data and do not treat stale lines as current. Ordinary player chat is excluded from both message and rendered-text paths by default for privacy.";
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema,
                                             boolean readOnly, boolean openWorld) {
        return linked(
            "name", name,
            "title", switch (name) {
                case "minecraft_get_state" -> "Get Minecraft State";
                case "minecraft_get_recent_text" -> "Get Minecraft Text";
                case "minecraft_get_slot_tooltip" -> "Get Item Tooltip";
                case "minecraft_get_context" -> "Get Wynncraft Context";
                case "minecraft_get_open_container" -> "Get Open Container";
                case "minecraft_get_inventory" -> "Get Player Inventory";
                case "minecraft_get_visible_ui" -> "Get Visible Minecraft UI";
                case "wynn_inspect_hovered_item" -> "Inspect Hovered Wynncraft Item";
                case "wynn_knowledge_search" -> "Search Wynncraft Knowledge";
                case "wynn_search_items" -> "Search Wynncraft Items";
                case "wynn_get_item" -> "Get Wynncraft Item";
                case "wynn_search_locations" -> "Search Wynncraft Locations";
                case "wynn_search_wiki" -> "Search Wynncraft Wiki";
                case "wynn_get_wiki_page" -> "Get Wynncraft Wiki Page";
                case "wynn_index_status" -> "Get Wynncraft Index Status";
                case "minecraft_send_command" -> "Send Minecraft Command";
                case "minecraft_send_chat" -> "Send Minecraft Chat";
                default -> name;
            },
            "description", description,
            "inputSchema", inputSchema,
            "annotations", linked(
                "readOnlyHint", readOnly,
                "destructiveHint", !readOnly,
                "idempotentHint", readOnly,
                "openWorldHint", openWorld
            )
        );
    }

    private static Map<String, Object> emptyObjectSchema() {
        return linked("type", "object", "properties", Map.of(), "additionalProperties", false);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return linked("type", "object", "properties", properties, "additionalProperties", false);
    }

    private static Map<String, Object> requiredObjectSchema(String required, Map<String, Object> properties) {
        return linked("type", "object", "properties", properties, "required", List.of(required), "additionalProperties", false);
    }

    private static Map<String, Object> stringProperty(String description) {
        return linked("type", "string", "description", description);
    }

    private static Map<String, Object> booleanProperty(String description) {
        return linked("type", "boolean", "description", description);
    }

    private static Map<String, Object> integerProperty(String description, Long minimum, Long maximum) {
        Map<String, Object> out = linked("type", "integer", "description", description);
        if (minimum != null) out.put("minimum", minimum);
        if (maximum != null) out.put("maximum", maximum);
        return out;
    }

    private static String requiredString(Map<String, Object> args, String name) {
        String value = string(args.get(name));
        if (value == null) throw new IllegalArgumentException("Missing required argument: " + name);
        return value;
    }

    private static long longArg(Map<String, Object> args, String name, long fallback, long min, long max) {
        Object value = args.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(name + " must be a number");
        long out = number.longValue();
        return Math.max(min, Math.min(out, max));
    }

    private static int intArg(Map<String, Object> args, String name, int fallback, int min, int max) {
        return (int) longArg(args, name, fallback, min, max);
    }

    private static Integer nullableIntArg(Map<String, Object> args, String name, int min, int max) {
        Object value = args.get(name);
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(name + " must be an integer");
        int out = number.intValue();
        if (out < min || out > max) throw new IllegalArgumentException(name + " out of range");
        return out;
    }

    private static boolean boolArg(Map<String, Object> args, String name, boolean fallback) {
        Object value = args.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Boolean bool)) throw new IllegalArgumentException(name + " must be boolean");
        return bool;
    }

    private static String string(Object value) {
        return value instanceof String s ? s : null;
    }

    private static Map<String, Object> objectOrEmpty(Object value) {
        if (value == null) return Map.of();
        try {
            return Json.object(value);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private static String newSessionId() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String readBody(HttpExchange exchange, int maxBytes) throws IOException, BodyTooLargeException {
        byte[] bytes = exchange.getRequestBody().readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) throw new BodyTooLargeException();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeJson(HttpExchange exchange, int status, Object object) throws IOException {
        byte[] bytes = Json.stringify(object).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void noContent(HttpExchange exchange, int status) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static Map<String, Object> linked(Object... pairs) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static final class BodyTooLargeException extends Exception {}
}
