package dev.tanaka.wynnaibridge.http;

import com.sun.net.httpserver.HttpExchange;
import dev.tanaka.wynnaibridge.ModVersion;
import dev.tanaka.wynnaibridge.capture.MessageStore;
import dev.tanaka.wynnaibridge.capture.TextCaptureStore;
import dev.tanaka.wynnaibridge.config.BridgeConfig;
import dev.tanaka.wynnaibridge.knowledge.WynnAbilityDataService;
import dev.tanaka.wynnaibridge.knowledge.WynnKnowledgeService;
import dev.tanaka.wynnaibridge.state.StateCollector;
import dev.tanaka.wynnaibridge.state.TooltipCollector;
import dev.tanaka.wynnaibridge.state.OpenContainerCollector;
import dev.tanaka.wynnaibridge.state.PlayerInventoryCollector;
import dev.tanaka.wynnaibridge.state.VisibleUiCollector;
import dev.tanaka.wynnaibridge.state.AbilityTreeDebugCollector;
import dev.tanaka.wynnaibridge.state.AbilityTreeSemanticCollector;
import dev.tanaka.wynnaibridge.ui.UiActionGate;
import dev.tanaka.wynnaibridge.ui.UiActionService;
import dev.tanaka.wynnaibridge.ui.BankWithdrawService;
import dev.tanaka.wynnaibridge.ui.BankDepositService;
import dev.tanaka.wynnaibridge.ui.WynnAbilitySelectionService;
import dev.tanaka.wynnaibridge.ui.WynnSemanticUiService;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    public static int toolCountForConfig(BridgeConfig config) {
        return 20 + (config.allowActions() ? 2 : 0) + (config.allowUiActions() ? 10 : 0);
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

    Map<String, Object> listToolsResult(boolean modern) {
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
        tools.add(tool(
            "wynn_get_official_ability_tree",
            "Read the official Wynncraft Ability Tree for one class, including ids, names, page, slots, coordinates, requirements, links, locks and icon metadata. Uses a 24-hour local cache and may return stale cached data when the public API is unavailable.",
            requiredObjectSchema("classId", linked("classId", enumStringProperty("Wynncraft class id.", WynnAbilityDataService.classIds()))),
            true,
            false
        ));
        tools.add(tool(
            "wynn_get_class_info",
            "Read official Wynncraft class and archetype data for one class from the public API with a 24-hour local cache.",
            requiredObjectSchema("classId", linked("classId", enumStringProperty("Wynncraft class id.", WynnAbilityDataService.classIds()))),
            true,
            false
        ));
        tools.add(tool(
            "wynn_get_player_abilities",
            "Read currently unlocked Ability Tree nodes from the official public player API. Requires the player's username and character UUID; private or unavailable profiles return an error.",
            requiredObjectSchema(List.of("username", "characterUuid"), linked(
                "username", stringProperty("Public Wynncraft player username."),
                "characterUuid", stringProperty("Character UUID."))),
            true,
            false
        ));
        tools.add(tool(
            "wynn_get_skill_points",
            "Read skill points only from a verified Character Info screen. Missing, conflicting, or stale labels remain unknown; this tool never changes points.",
            emptyObjectSchema(),
            true,
            false
        ));
        tools.add(tool(
                "wynn_get_ability_tree",
                "Read-only Ability Tree analysis that joins official node/archetype ids, page, slot, coordinates, requirements, links and locks with current rendered items, menu slots, item identities, tooltips and captured GUI text. Returns matchConfidence and matchedBy; ambiguous or unrecognized state remains UNKNOWN. Rendered-only STRONG matches have no actionable menu slot.",
            objectSchema(linked(
                "classId", enumStringProperty("Optional Wynncraft class id for the official tree to compare with the currently visible GUI.", WynnAbilityDataService.classIds()),
                "maxAgeMs", integerProperty("Maximum age of captured screen evidence in milliseconds.", 1L, 10_000L),
                "itemLimit", integerProperty("Maximum rendered item records.", 1L, 500L),
                "textLimit", integerProperty("Maximum captured text records.", 1L, 1000L)
            )),
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

        if (config.allowUiActions()) {
            tools.add(tool(
                "minecraft_click_gui_slot",
                "Perform one left/right pickup click on a populated slot in the vanilla player inventory screen. Requires the exact screen class, sync id, state revision, and item name from a fresh read. Arbitrary GUI screens and non-player slots are refused.",
                requiredObjectSchema(List.of("slot", "button", "expectedItemName", "expectedScreen", "expectedSyncId", "expectedRevision"), linked(
                    "slot", integerProperty("Current menu slot index from minecraft_get_open_container.", 0L, 1000L),
                    "button", enumStringProperty("Minecraft pickup button.", List.of("left", "right")),
                    "expectedItemName", stringProperty("Exact item display name currently in the slot."),
                    "expectedScreen", stringProperty("Exact screenClass from the current read."),
                    "expectedSyncId", integerProperty("containerId from the current read.", 0L, 100000L),
                    "expectedRevision", integerProperty("stateRevision from the current read.", 0L, Long.MAX_VALUE)
                )),
                false,
                true,
                false,
                false
            ));
            tools.add(tool(
                "minecraft_move_inventory_item",
                "Move one entire stack between two slots in the vanilla player inventory. The destination must be empty or have enough room for the exact same item/components; swaps and partial merges are refused. Reads and verifies server-synchronized inventory state between the two standard pickup clicks.",
                requiredObjectSchema(List.of("fromSlot", "toSlot", "expectedItemName", "expectedScreen", "expectedSyncId", "expectedRevision"), linked(
                    "fromSlot", integerProperty("Player inventory index from 0 through 35.", 0L, 35L),
                    "toSlot", integerProperty("Player inventory index from 0 through 35.", 0L, 35L),
                    "expectedItemName", stringProperty("Exact item display name currently in fromSlot."),
                    "expectedScreen", stringProperty("Exact screenClass from minecraft_get_inventory."),
                    "expectedSyncId", integerProperty("containerSyncId from minecraft_get_inventory.", 0L, 100000L),
                    "expectedRevision", integerProperty("stateRevision from minecraft_get_inventory.", 0L, Long.MAX_VALUE)
                )),
                false,
                false,
                false,
                false
            ));
            tools.add(tool(
                "minecraft_withdraw_bank_item",
                "Withdraw one single-count item from the currently recognized Wynncraft Bank page into an empty player inventory slot. Only Bank content slots 0-44 are eligible; page controls, stacks, deposits, merchants, trades, and other container screens are refused. Supply the current page and fresh revision from the read tool.",
                requiredObjectSchema(List.of("bankSlot", "inventorySlot", "expectedItemId", "expectedItemName", "expectedBankPage", "expectedScreen", "expectedSyncId", "expectedRevision"), linked(
                    "bankSlot", integerProperty("Bank content menu slot from minecraft_get_open_container; only 0 through 44.", 0L, 44L),
                    "inventorySlot", integerProperty("Empty player inventory index from 0 through 35.", 0L, 35L),
                    "expectedItemId", stringProperty("Exact itemId from the current Bank slot."),
                    "expectedItemName", stringProperty("Exact item display name from the current Bank slot."),
                    "expectedBankPage", integerProperty("The recognized current Wynncraft Bank page number.", 1L, 100L),
                    "expectedScreen", stringProperty("Exact screenClass from minecraft_get_open_container."),
                    "expectedSyncId", integerProperty("containerId from minecraft_get_open_container.", 1L, 100000L),
                    "expectedRevision", integerProperty("stateRevision from minecraft_get_open_container.", 0L, Long.MAX_VALUE)
                )),
                false,
                false,
                false,
                false
            ));
            tools.add(tool(
                "wynn_deposit_bank_item",
                "Deposit exactly one single-count item from the local player inventory into the currently recognized Wynncraft Bank page. Requires the local bank arm category, a fresh Bank page/revision, and exact item identity. The Bridge selects an empty Bank content slot internally; stacks, shift-click, page controls, merchants, and other screens are refused.",
                requiredObjectSchema(List.of("inventorySlot", "expectedItemId", "expectedItemName", "expectedBankPage", "expectedScreen", "expectedSyncId", "expectedRevision"), linked(
                    "inventorySlot", integerProperty("Player inventory index from 0 through 35 in the open Bank container.", 0L, 35L),
                    "expectedItemId", stringProperty("Exact itemId from the current player inventory slot."),
                    "expectedItemName", stringProperty("Exact item display name currently in inventorySlot."),
                    "expectedBankPage", integerProperty("The recognized current Wynncraft Bank page number.", 1L, 100L),
                    "expectedScreen", stringProperty("Exact screenClass from minecraft_get_open_container."),
                    "expectedSyncId", integerProperty("containerId from minecraft_get_open_container.", 1L, 100000L),
                    "expectedRevision", integerProperty("stateRevision from minecraft_get_open_container.", 0L, Long.MAX_VALUE)
                )),
                false,
                false,
                false,
                false
            ));
            tools.add(tool(
                "wynn_open_character_info",
                "Open Character Info by uniquely recognizing the named Character Info compass in the player's inventory. The item must already be held in the selected hotbar slot. The caller supplies only fresh screen/sync/revision evidence; no slot number is accepted.",
                requiredObjectSchema(List.of("expectedScreen", "expectedSyncId", "expectedRevision"), linked(
                    "expectedScreen", stringProperty("Exact screenClass from minecraft_get_inventory."),
                    "expectedSyncId", integerProperty("containerSyncId from minecraft_get_inventory.", 0L, 100000L),
                    "expectedRevision", integerProperty("stateRevision from minecraft_get_inventory.", 0L, Long.MAX_VALUE)
                )),
                false,
                false,
                false,
                false
            ));
            tools.add(tool(
                "wynn_assign_skill_points",
                "Assign 1-5 skill points by semantic skill name on a verified Character Info screen. Requires fresh screen/sync/revision, explicit available and assigned values, and one unique skill button whose tooltip matches the current value. Each point is clicked and server-verified before the next.",
                requiredObjectSchema(List.of("skill", "amount", "expectedScreen", "expectedSyncId", "expectedRevision"), linked(
                    "skill", enumStringProperty("Skill name.", List.of("strength", "dexterity", "intelligence", "defence", "agility")),
                    "amount", integerProperty("Number of points to assign; bounded by local skills arm allowance.", 1L, 5L),
                    "expectedScreen", stringProperty("Exact screenClass from wynn_get_skill_points."),
                    "expectedSyncId", integerProperty("containerSyncId from wynn_get_skill_points.", 0L, 100000L),
                    "expectedRevision", integerProperty("stateRevision from wynn_get_skill_points.", 0L, Long.MAX_VALUE)
                )),
                false,
                true,
                false,
                false
            ));
            tools.add(tool(
                "wynn_open_ability_tree",
                "Open the Ability Tree by uniquely identifying the Ability Tree item name/tooltip on a verified Character Info screen, then verify the screen transition. No slot number is accepted.",
                requiredObjectSchema(List.of("expectedScreen", "expectedSyncId", "expectedRevision"), linked(
                    "expectedScreen", stringProperty("Exact screenClass from wynn_get_skill_points."),
                    "expectedSyncId", integerProperty("containerSyncId from wynn_get_skill_points.", 0L, 100000L),
                    "expectedRevision", integerProperty("stateRevision from wynn_get_skill_points.", 0L, Long.MAX_VALUE)
                )),
                false,
                false,
                false,
                false
            ));
            tools.add(tool(
                "wynn_open_bank_page",
                "Move exactly one page forward or backward in a recognized Wynncraft Bank. The target must be adjacent to the current page; repeat with a fresh read/revision to reach a distant page. This uses only the Bank's recognized page navigation controls.",
                requiredObjectSchema(List.of("page", "expectedScreen", "expectedSyncId", "expectedRevision"), linked(
                    "page", integerProperty("Adjacent target page number, exactly currentPage + 1 or currentPage - 1.", 1L, 100L),
                    "expectedScreen", stringProperty("Exact screenClass from minecraft_get_open_container."),
                    "expectedSyncId", integerProperty("containerId from minecraft_get_open_container.", 1L, 100000L),
                    "expectedRevision", integerProperty("stateRevision from minecraft_get_open_container.", 0L, Long.MAX_VALUE)
                )),
                false,
                false,
                false,
                false
            ));
            tools.add(tool(
                "minecraft_equip_item",
                "Equip one item from the local player inventory into an empty vanilla helmet, chestplate, leggings, or boots slot. The item's Equippable component must match the requested target. Replacing equipped items and Wynncraft accessory/custom equipment slots are not supported.",
                requiredObjectSchema(List.of("inventorySlot", "expectedItemName", "target", "expectedScreen", "expectedSyncId", "expectedRevision"), linked(
                    "inventorySlot", integerProperty("Player inventory index from 0 through 35.", 0L, 35L),
                    "expectedItemName", stringProperty("Exact item display name currently in inventorySlot."),
                    "target", enumStringProperty("Vanilla armor slot.", List.of("helmet", "chestplate", "leggings", "boots")),
                    "expectedScreen", stringProperty("Exact screenClass from minecraft_get_inventory."),
                    "expectedSyncId", integerProperty("containerSyncId from minecraft_get_inventory.", 0L, 100000L),
                    "expectedRevision", integerProperty("stateRevision from minecraft_get_inventory.", 0L, Long.MAX_VALUE)
                )),
                false,
                false,
                false,
                false
            ));
            tools.add(tool(
                "wynn_select_ability",
                "Select exactly one official Wynncraft Ability Tree node. Requires the local ability arm category, a verified current Ability Tree/class/page, available AP, satisfied official NODE/ARCHETYPE/locks checks, and an EXACT or otherwise uniquely actionable live menu-slot identity from a fresh read. Provide abilityId and classId, never a raw slot or coordinate. Rendered-only matches without a menu slot are refused. One node is attempted per call; AP and selected state are checked after server synchronization.",
                requiredObjectSchema(List.of("abilityId", "classId", "expectedScreen", "expectedSyncId", "expectedRevision"), linked(
                    "abilityId", stringProperty("Exact official Ability Tree node id from wynn_get_official_ability_tree."),
                    "classId", enumStringProperty("Class whose official tree was read and whose current GUI identity must match.", WynnAbilityDataService.classIds()),
                    "expectedScreen", stringProperty("Exact screenClass from wynn_get_ability_tree runtimeEvidence."),
                    "expectedSyncId", integerProperty("syncId from the fresh Ability Tree diagnostic.", 1L, 100000L),
                    "expectedRevision", integerProperty("stateRevision from the fresh Ability Tree diagnostic.", 0L, Long.MAX_VALUE)
                )),
                false,
                true,
                false,
                false
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

            case "wynn_get_official_ability_tree" -> {
                String classId = requiredString(args, "classId");
                var result = WynnAbilityDataService.INSTANCE.getAbilityTree(classId);
                if (!result.ok()) toolError(exchange, id, result.error(), result, modern);
                else toolSuccess(exchange, id, result, "Read the official Ability Tree data and local cache state.", modern);
            }

            case "wynn_get_class_info" -> {
                String classId = requiredString(args, "classId");
                var result = WynnAbilityDataService.INSTANCE.getClassInfo(classId);
                if (!result.ok()) toolError(exchange, id, result.error(), result, modern);
                else toolSuccess(exchange, id, result, "Read official Wynncraft class and archetype metadata.", modern);
            }

            case "wynn_get_player_abilities" -> {
                String username = requiredString(args, "username");
                String characterUuid = requiredString(args, "characterUuid");
                var result = WynnAbilityDataService.INSTANCE.getPlayerAbilities(username, characterUuid);
                if (!result.ok()) toolError(exchange, id, result.error(), result, modern);
                else toolSuccess(exchange, id, result, "Read current unlocked Ability Tree nodes from the official public player API.", modern);
            }

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

            case "wynn_get_ability_tree" -> {
                long maxAgeMs = longArg(args, "maxAgeMs", 2_000L, 1L, 10_000L);
                int itemLimit = intArg(args, "itemLimit", 300, 1, 500);
                int textLimit = intArg(args, "textLimit", 500, 1, 1000);
                AbilityTreeDebugCollector.Result result = onClientThread(() -> AbilityTreeDebugCollector.collect(
                    Minecraft.getInstance(), maxAgeMs, itemLimit, textLimit
                ));
                String classId = string(args.get("classId"));
                if (classId == null || classId.isBlank()) {
                    toolSuccess(exchange, id, result,
                        "Read current Ability Tree screen evidence without changing game state. Provide classId to compare against official node data.", modern);
                } else {
                    var official = WynnAbilityDataService.INSTANCE.getAbilityTree(classId);
                    if (!official.ok()) {
                        toolSuccess(exchange, id, linked("runtimeEvidence", result,
                                "officialTreeAvailable", false, "officialTreeError", official.error(),
                                "note", "Official API failure does not prevent the local runtime diagnostic."),
                            "Read runtime evidence; official Ability Tree data was unavailable.", modern);
                    } else {
                        AbilityTreeSemanticCollector.Result correlated = AbilityTreeSemanticCollector.correlate(
                            official.data(), result);
                        toolSuccess(exchange, id, linked("runtimeEvidence", result,
                                "officialTree", official, "tree", correlated),
                            "Joined official Ability Tree data with exact visible GUI item evidence; ambiguity remains UNKNOWN.", modern);
                    }
                }
            }

            case "wynn_get_skill_points" -> {
                WynnSemanticUiService.Snapshot result = onClientThreadOnce(() ->
                    WynnSemanticUiService.capture(Minecraft.getInstance()));
                toolSuccess(exchange, id, result,
                    "Read current character skill/ability point evidence; unknown values remain unset.", modern);
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

            case "minecraft_click_gui_slot" -> clickGuiSlot(exchange, id, args, modern);

            case "minecraft_move_inventory_item" -> moveInventoryItem(exchange, id, args, modern);

            case "minecraft_withdraw_bank_item" -> withdrawBankItem(exchange, id, args, modern);

            case "wynn_deposit_bank_item" -> depositBankItem(exchange, id, args, modern);

            case "wynn_select_ability" -> selectAbility(exchange, id, args, modern);

            case "wynn_open_bank_page" -> openBankPage(exchange, id, args, modern);

            case "wynn_open_character_info" -> openCharacterInfo(exchange, id, args, modern);

            case "wynn_assign_skill_points" -> assignSkillPoints(exchange, id, args, modern);

            case "wynn_open_ability_tree" -> openAbilityTree(exchange, id, args, modern);

            case "minecraft_equip_item" -> equipItem(exchange, id, args, modern);

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

    private void clickGuiSlot(HttpExchange exchange, Object id, Map<String, Object> args, boolean modern) throws IOException {
        int slot = requiredIntArg(args, "slot", 0, 1000);
        String buttonName = requiredString(args, "button");
        int button = switch (buttonName) {
            case "left" -> 0;
            case "right" -> 1;
            default -> throw new IllegalArgumentException("button must be left or right");
        };
        String expectedItemName = requiredString(args, "expectedItemName");
        String expectedScreen = requiredString(args, "expectedScreen");
        int expectedSyncId = requiredIntArg(args, "expectedSyncId", 0, 100000);
        long expectedRevision = requiredLongArg(args, "expectedRevision", 0L, Long.MAX_VALUE);

        AtomicBoolean admitted = new AtomicBoolean();
        UiActionService.ClickPair pair = null;
        UiActionService.GuiSnapshot observed = null;
        try {
            beginUiAction(id, "minecraft_click_gui_slot", admitted);
            pair = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                return UiActionService.click(
                    Minecraft.getInstance(), slot, button, expectedItemName,
                    expectedScreen, expectedSyncId, expectedRevision
                );
            });
            observed = awaitClickAcknowledgement(pair, expectedScreen);
            if (observed.stateId() != pair.before().stateId() && UiActionService.clickResultMatches(observed, pair)) {
                toolSuccess(exchange, id, linked(
                    "ok", true,
                    "action", "minecraft_click_gui_slot",
                    "before", pair.before(),
                    "after", observed,
                    "verified", true
                ), "Clicked the expected inventory slot and verified the synchronized GUI state.", modern);
            } else {
                toolError(exchange, id, "SERVER_STATE_NOT_VERIFIED: the server-synchronized slot/cursor state did not match the expected click result",
                    linked("ok", false, "code", "SERVER_STATE_NOT_VERIFIED", "action", "minecraft_click_gui_slot",
                        "before", pair.before(), "after", observed, "verified", false), modern);
            }
        } catch (UiActionGate.GateException e) {
            toolError(exchange, id, e.getMessage(), uiActionError(e.code(), e.getMessage(), pair, observed), modern);
        } catch (UiActionService.UiActionException e) {
            toolError(exchange, id, e.getMessage(), uiActionError(e.code(), e.getMessage(), pair, observed), modern);
        } catch (Exception e) {
            toolError(exchange, id, "UI action failed: " + safeError(e), uiActionError("UI_ACTION_FAILED", safeError(e), pair, observed), modern);
        } finally {
            if (admitted.get()) UiActionGate.INSTANCE.endAction();
        }
    }

    private void moveInventoryItem(HttpExchange exchange, Object id, Map<String, Object> args, boolean modern) throws IOException {
        int fromSlot = requiredIntArg(args, "fromSlot", 0, 35);
        int toSlot = requiredIntArg(args, "toSlot", 0, 35);
        String expectedItemName = requiredString(args, "expectedItemName");
        String expectedScreen = requiredString(args, "expectedScreen");
        int expectedSyncId = requiredIntArg(args, "expectedSyncId", 0, 100000);
        long expectedRevision = requiredLongArg(args, "expectedRevision", 0L, Long.MAX_VALUE);

        AtomicBoolean admitted = new AtomicBoolean();
        UiActionService.MoveStart start = null;
        UiActionService.GuiSnapshot afterSource = null;
        UiActionService.GuiSnapshot after = null;
        try {
            beginUiAction(id, "minecraft_move_inventory_item", admitted);
            start = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                return UiActionService.startMove(
                    Minecraft.getInstance(), fromSlot, toSlot, expectedItemName,
                    expectedScreen, expectedSyncId, expectedRevision
                );
            });
            afterSource = awaitMoveSourceAcknowledgement(start);
            if (afterSource == null) {
                UiActionService.GuiSnapshot last = lastObservedMoveState(start, expectedScreen);
                toolError(exchange, id, "SERVER_STATE_NOT_VERIFIED: source pickup was not acknowledged; no destination click was sent",
                    linked("ok", false, "code", "SERVER_STATE_NOT_VERIFIED", "action", "minecraft_move_inventory_item",
                        "before", start.plan().before(), "after", last, "changed", last != null, "verified", false), modern);
                return;
            }
            UiActionService.MovePlan plan = start.plan();
            UiActionService.GuiSnapshot sourceAcknowledged = afterSource;
            after = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                return UiActionService.placeMoveDestination(Minecraft.getInstance(), plan);
            });
            UiActionService.GuiSnapshot verified = awaitMoveDestinationAcknowledgement(plan, sourceAcknowledged);
            if (verified != null) {
                toolSuccess(exchange, id, linked(
                    "ok", true,
                    "action", "minecraft_move_inventory_item",
                    "sourceInventorySlot", fromSlot,
                    "destinationInventorySlot", toSlot,
                    "before", plan.before(),
                    "after", verified,
                    "changed", true,
                    "verified", true
                ), "Moved the full stack and verified both server-synchronized inventory slots.", modern);
            } else {
                UiActionService.GuiSnapshot last = lastObservedMoveState(start, expectedScreen);
                toolError(exchange, id, "SERVER_STATE_NOT_VERIFIED: the destination state was not acknowledged; inspect the current cursor and inventory before continuing",
                    linked("ok", false, "code", "SERVER_STATE_NOT_VERIFIED", "action", "minecraft_move_inventory_item",
                        "before", plan.before(), "after", last == null ? after : last, "changed", true, "verified", false), modern);
            }
        } catch (UiActionGate.GateException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "before", start == null ? null : start.plan().before(), "after", after, "verified", false), modern);
        } catch (UiActionService.UiActionException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "before", start == null ? null : start.plan().before(), "after", afterSource, "verified", false), modern);
        } catch (Exception e) {
            toolError(exchange, id, "UI action failed: " + safeError(e), linked("ok", false, "code", "UI_ACTION_FAILED",
                "error", safeError(e), "before", start == null ? null : start.plan().before(),
                "after", after, "verified", false), modern);
        } finally {
            if (admitted.get()) UiActionGate.INSTANCE.endAction();
        }
    }

    private void equipItem(HttpExchange exchange, Object id, Map<String, Object> args, boolean modern) throws IOException {
        int inventorySlot = requiredIntArg(args, "inventorySlot", 0, 35);
        String expectedItemName = requiredString(args, "expectedItemName");
        String target = requiredString(args, "target");
        String expectedScreen = requiredString(args, "expectedScreen");
        int expectedSyncId = requiredIntArg(args, "expectedSyncId", 0, 100000);
        long expectedRevision = requiredLongArg(args, "expectedRevision", 0L, Long.MAX_VALUE);

        AtomicBoolean admitted = new AtomicBoolean();
        UiActionService.EquipStart start = null;
        UiActionService.GuiSnapshot afterSource = null;
        UiActionService.GuiSnapshot after = null;
        try {
            beginUiAction(id, "minecraft_equip_item", admitted);
            start = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                return UiActionService.startEquip(Minecraft.getInstance(), inventorySlot, expectedItemName, target,
                    expectedScreen, expectedSyncId, expectedRevision);
            });
            afterSource = awaitEquipSourceAcknowledgement(start);
            if (afterSource == null) {
                UiActionService.GuiSnapshot last = lastObservedEquipState(start, expectedScreen);
                toolError(exchange, id, "SERVER_STATE_NOT_VERIFIED: source pickup was not acknowledged; no armor-slot click was sent",
                    linked("ok", false, "code", "SERVER_STATE_NOT_VERIFIED", "action", "minecraft_equip_item",
                        "before", start.plan().before(), "after", last, "verified", false), modern);
                return;
            }

            UiActionService.EquipPlan plan = start.plan();
            UiActionService.GuiSnapshot sourceAcknowledged = afterSource;
            after = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                return UiActionService.placeEquipTarget(Minecraft.getInstance(), plan);
            });
            UiActionService.GuiSnapshot verified = awaitEquipTargetAcknowledgement(plan, sourceAcknowledged);
            if (verified != null) {
                toolSuccess(exchange, id, linked(
                    "ok", true,
                    "action", "minecraft_equip_item",
                    "inventorySlot", inventorySlot,
                    "target", target,
                    "before", plan.before(),
                    "after", verified,
                    "verified", true
                ), "Equipped the item in the requested empty vanilla armor slot and verified synchronized state.", modern);
            } else {
                UiActionService.GuiSnapshot last = lastObservedEquipState(start, expectedScreen);
                toolError(exchange, id, "SERVER_STATE_NOT_VERIFIED: equipment state was not acknowledged; inspect the current cursor and inventory before continuing",
                    linked("ok", false, "code", "SERVER_STATE_NOT_VERIFIED", "action", "minecraft_equip_item",
                        "before", plan.before(), "after", last == null ? after : last, "verified", false), modern);
            }
        } catch (UiActionGate.GateException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "before", start == null ? null : start.plan().before(), "after", after, "verified", false), modern);
        } catch (UiActionService.UiActionException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "before", start == null ? null : start.plan().before(), "after", afterSource, "verified", false), modern);
        } catch (Exception e) {
            toolError(exchange, id, "UI action failed: " + safeError(e), linked("ok", false, "code", "UI_ACTION_FAILED",
                "error", safeError(e), "before", start == null ? null : start.plan().before(),
                "after", after, "verified", false), modern);
        } finally {
            if (admitted.get()) UiActionGate.INSTANCE.endAction();
        }
    }

    private void withdrawBankItem(HttpExchange exchange, Object id, Map<String, Object> args, boolean modern) throws IOException {
        int bankSlot = requiredIntArg(args, "bankSlot", 0, 44);
        int inventorySlot = requiredIntArg(args, "inventorySlot", 0, 35);
        String expectedItemId = requiredString(args, "expectedItemId");
        String expectedItemName = requiredString(args, "expectedItemName");
        int expectedBankPage = requiredIntArg(args, "expectedBankPage", BankWithdrawService.MIN_BANK_PAGE, BankWithdrawService.MAX_BANK_PAGE);
        String expectedScreen = requiredString(args, "expectedScreen");
        int expectedSyncId = requiredIntArg(args, "expectedSyncId", 1, 100000);
        long expectedRevision = requiredLongArg(args, "expectedRevision", 0L, Long.MAX_VALUE);

        AtomicBoolean admitted = new AtomicBoolean();
        BankWithdrawService.BankWithdrawStart start = null;
        UiActionService.GuiSnapshot afterSource = null;
        UiActionService.GuiSnapshot afterDestination = null;
        try {
            beginUiAction(id, "minecraft_withdraw_bank_item", UiActionGate.Category.BANK, 1, admitted);
            start = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                Minecraft minecraft = Minecraft.getInstance();
                UiActionService.GuiSnapshot before = BankWithdrawService.capture(minecraft, expectedScreen);
                BankWithdrawService.BankWithdrawPlan plan = BankWithdrawService.plan(
                    before, expectedBankPage, bankSlot, inventorySlot, expectedItemId,
                    expectedItemName, expectedScreen, expectedSyncId, expectedRevision
                );
                return BankWithdrawService.start(minecraft, plan);
            });

            afterSource = awaitBankWithdrawSourceAcknowledgement(start);
            if (afterSource == null) {
                UiActionService.GuiSnapshot last = lastObservedBankState(start, expectedScreen);
                toolError(exchange, id,
                    "SERVER_STATE_NOT_VERIFIED: the Bank source pickup was not acknowledged; the inventory destination was not clicked",
                    linked("ok", false, "code", "SERVER_STATE_NOT_VERIFIED", "action", "minecraft_withdraw_bank_item",
                        "before", BankWithdrawService.summary(start.plan().before(), start.plan()),
                        "after", bankStateSummary(last, start.plan()), "verified", false), modern);
                return;
            }

            BankWithdrawService.BankWithdrawPlan plan = start.plan();
            UiActionService.GuiSnapshot sourceAcknowledged = afterSource;
            afterDestination = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                return BankWithdrawService.placeDestination(Minecraft.getInstance(), plan);
            });
            UiActionService.GuiSnapshot verified = awaitBankWithdrawDestinationAcknowledgement(plan, sourceAcknowledged);
            if (verified != null) {
                toolSuccess(exchange, id, linked(
                    "ok", true,
                    "action", "minecraft_withdraw_bank_item",
                    "bankPage", expectedBankPage,
                    "bankSlot", bankSlot,
                    "inventorySlot", inventorySlot,
                    "before", BankWithdrawService.summary(plan.before(), plan),
                    "after", BankWithdrawService.summary(verified, plan),
                    "changed", true,
                    "verified", true,
                    "bankWithdrawalsRemainingThisArm", UiActionGate.INSTANCE.status().bankWithdrawalsRemaining()
                ), "Withdrew one single-count item from the recognized Bank page and verified both synchronized slots.", modern);
            } else {
                UiActionService.GuiSnapshot last = lastObservedBankState(start, expectedScreen);
                toolError(exchange, id,
                    "SERVER_STATE_NOT_VERIFIED: the Bank withdrawal destination was not acknowledged; inspect the cursor and both slots before continuing",
                    linked("ok", false, "code", "SERVER_STATE_NOT_VERIFIED", "action", "minecraft_withdraw_bank_item",
                        "before", BankWithdrawService.summary(plan.before(), plan),
                        "after", bankStateSummary(last == null ? afterDestination : last, plan),
                        "changed", true, "verified", false), modern);
            }
        } catch (UiActionGate.GateException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "before", start == null ? null : BankWithdrawService.summary(start.plan().before(), start.plan()),
                "after", bankStateSummary(afterSource, start == null ? null : start.plan()), "verified", false), modern);
        } catch (UiActionService.UiActionException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "before", start == null ? null : BankWithdrawService.summary(start.plan().before(), start.plan()),
                "after", bankStateSummary(afterSource, start == null ? null : start.plan()), "verified", false), modern);
        } catch (Exception e) {
            toolError(exchange, id, "UI action failed: " + safeError(e), linked("ok", false, "code", "UI_ACTION_FAILED",
                "error", safeError(e), "before", start == null ? null : BankWithdrawService.summary(start.plan().before(), start.plan()),
                "after", bankStateSummary(afterDestination, start == null ? null : start.plan()), "verified", false), modern);
        } finally {
            if (admitted.get()) UiActionGate.INSTANCE.endAction();
        }
    }

    private void openBankPage(HttpExchange exchange, Object id, Map<String, Object> args, boolean modern) throws IOException {
        int targetPage = requiredIntArg(args, "page", BankWithdrawService.MIN_BANK_PAGE, BankWithdrawService.MAX_BANK_PAGE);
        String expectedScreen = requiredString(args, "expectedScreen");
        int expectedSyncId = requiredIntArg(args, "expectedSyncId", 1, 100000);
        long expectedRevision = requiredLongArg(args, "expectedRevision", 0L, Long.MAX_VALUE);
        AtomicBoolean admitted = new AtomicBoolean();
        BankWithdrawService.BankPageStepPlan plan = null;
        UiActionService.GuiSnapshot afterClick = null;
        try {
            beginUiAction(id, "wynn_open_bank_page", UiActionGate.Category.BANK, 1, admitted);
            plan = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                Minecraft minecraft = Minecraft.getInstance();
                UiActionService.GuiSnapshot before = BankWithdrawService.capture(minecraft, expectedScreen);
                BankWithdrawService.BankPageStepPlan checked = BankWithdrawService.planPageStep(
                    before, targetPage, expectedScreen, expectedSyncId, expectedRevision
                );
                return checked;
            });
            BankWithdrawService.BankPageStepPlan checked = plan;
            afterClick = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                return BankWithdrawService.startPageStep(Minecraft.getInstance(), checked);
            });
            UiActionService.GuiSnapshot verified = awaitBankPageAcknowledgement(plan);
            if (verified != null) {
                toolSuccess(exchange, id, linked(
                    "ok", true,
                    "action", "wynn_open_bank_page",
                    "beforePage", plan.currentPage(),
                    "afterPage", targetPage,
                    "before", bankPageSummary(plan.before()),
                    "after", bankPageSummary(verified),
                    "changed", true,
                    "verified", true
                ), "Moved one page in the recognized Wynncraft Bank and verified the server-synchronized page.", modern);
            } else {
                UiActionService.GuiSnapshot last = lastObservedBankPageState(expectedScreen, afterClick);
                toolError(exchange, id, "SERVER_STATE_NOT_VERIFIED: the requested Bank page was not acknowledged",
                    linked("ok", false, "code", "SERVER_STATE_NOT_VERIFIED", "action", "wynn_open_bank_page",
                        "beforePage", plan.currentPage(), "expectedPage", targetPage,
                        "before", bankPageSummary(plan.before()), "after", last == null ? null : bankPageSummary(last),
                        "verified", false), modern);
            }
        } catch (UiActionGate.GateException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "beforePage", plan == null ? null : plan.currentPage(), "verified", false), modern);
        } catch (UiActionService.UiActionException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "beforePage", plan == null ? null : plan.currentPage(), "verified", false), modern);
        } catch (Exception e) {
            toolError(exchange, id, "Bank page navigation failed: " + safeError(e), linked("ok", false,
                "code", "UI_ACTION_FAILED", "error", safeError(e), "beforePage", plan == null ? null : plan.currentPage(),
                "after", afterClick == null ? null : bankPageSummary(afterClick), "verified", false), modern);
        } finally {
            if (admitted.get()) UiActionGate.INSTANCE.endAction();
        }
    }

    private void depositBankItem(HttpExchange exchange, Object id, Map<String, Object> args, boolean modern) throws IOException {
        int inventorySlot = requiredIntArg(args, "inventorySlot", 0, 35);
        String expectedItemId = requiredString(args, "expectedItemId");
        String expectedItemName = requiredString(args, "expectedItemName");
        int expectedBankPage = requiredIntArg(args, "expectedBankPage", BankWithdrawService.MIN_BANK_PAGE, BankWithdrawService.MAX_BANK_PAGE);
        String expectedScreen = requiredString(args, "expectedScreen");
        int expectedSyncId = requiredIntArg(args, "expectedSyncId", 1, 100000);
        long expectedRevision = requiredLongArg(args, "expectedRevision", 0L, Long.MAX_VALUE);

        AtomicBoolean admitted = new AtomicBoolean();
        BankDepositService.DepositStart start = null;
        UiActionService.GuiSnapshot afterSource = null;
        UiActionService.GuiSnapshot afterDestination = null;
        try {
            beginUiAction(id, "wynn_deposit_bank_item", UiActionGate.Category.BANK, 1, admitted);
            start = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                Minecraft minecraft = Minecraft.getInstance();
                UiActionService.GuiSnapshot before = BankDepositService.capture(minecraft, expectedScreen);
                BankDepositService.DepositPlan plan = BankDepositService.plan(before, expectedBankPage,
                    inventorySlot, expectedItemId, expectedItemName, expectedScreen, expectedSyncId, expectedRevision);
                return BankDepositService.start(minecraft, plan);
            });
            afterSource = awaitBankDepositSourceAcknowledgement(start);
            if (afterSource == null) {
                toolError(exchange, id,
                    "SERVER_STATE_NOT_VERIFIED: the inventory pickup was not acknowledged; the Bank destination was not clicked",
                    linked("ok", false, "code", "SERVER_STATE_NOT_VERIFIED", "action", "wynn_deposit_bank_item",
                        "before", BankDepositService.summary(start.plan().before(), start.plan()),
                        "after", BankDepositService.summary(lastObservedDepositState(start, expectedScreen), start.plan()),
                        "verified", false), modern);
                return;
            }
            BankDepositService.DepositPlan plan = start.plan();
            UiActionService.GuiSnapshot sourceAcknowledged = afterSource;
            afterDestination = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                return BankDepositService.placeDestination(Minecraft.getInstance(), plan);
            });
            UiActionService.GuiSnapshot verified = awaitBankDepositDestinationAcknowledgement(plan, sourceAcknowledged);
            if (verified != null) {
                toolSuccess(exchange, id, linked(
                    "ok", true, "action", "wynn_deposit_bank_item", "bankPage", expectedBankPage,
                    "inventorySlot", inventorySlot, "bankSlot", plan.bankMenuSlot(),
                    "before", BankDepositService.summary(plan.before(), plan),
                    "after", BankDepositService.summary(verified, plan), "changed", true, "verified", true
                ), "Deposited one single-count item into an internally selected empty Bank slot and verified both synchronized slots.", modern);
            } else {
                UiActionService.GuiSnapshot last = lastObservedDepositState(start, expectedScreen);
                toolError(exchange, id,
                    "POSTCONDITION_FAILED: the Bank deposit did not reach the expected synchronized state; inspect the cursor and both slots before continuing",
                    linked("ok", false, "code", "POSTCONDITION_FAILED", "action", "wynn_deposit_bank_item",
                        "before", BankDepositService.summary(plan.before(), plan),
                        "after", BankDepositService.summary(last == null ? afterDestination : last, plan),
                        "changed", true, "verified", false), modern);
            }
        } catch (UiActionGate.GateException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "before", start == null ? null : BankDepositService.summary(start.plan().before(), start.plan()),
                "after", start == null ? null : BankDepositService.summary(afterSource, start.plan()), "verified", false), modern);
        } catch (UiActionService.UiActionException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "before", start == null ? null : BankDepositService.summary(start.plan().before(), start.plan()),
                "after", start == null ? null : BankDepositService.summary(afterSource, start.plan()), "verified", false), modern);
        } catch (Exception e) {
            toolError(exchange, id, "Bank deposit failed: " + safeError(e), linked("ok", false, "code", "UI_ACTION_FAILED",
                "error", safeError(e), "before", start == null ? null : BankDepositService.summary(start.plan().before(), start.plan()),
                "after", start == null ? null : BankDepositService.summary(afterDestination, start.plan()), "verified", false), modern);
        } finally {
            if (admitted.get()) UiActionGate.INSTANCE.endAction();
        }
    }

    private void selectAbility(HttpExchange exchange, Object id, Map<String, Object> args, boolean modern) throws IOException {
        String abilityId = requiredString(args, "abilityId").strip();
        String classId = requiredString(args, "classId");
        String expectedScreen = requiredString(args, "expectedScreen");
        int expectedSyncId = requiredIntArg(args, "expectedSyncId", 1, 100000);
        long expectedRevision = requiredLongArg(args, "expectedRevision", 0L, Long.MAX_VALUE);
        AtomicBoolean admitted = new AtomicBoolean();
        AtomicBoolean clickAttempted = new AtomicBoolean();
        WynnAbilitySelectionService.SelectionPlan plan = null;
        AbilityTreeSemanticCollector.Result before = null;
        AbilityTreeSemanticCollector.Result after = null;
        try {
            beginDeferredAbilityAction(id, admitted);
            WynnAbilityDataService.DataResult<WynnAbilityDataService.AbilityTree> data =
                WynnAbilityDataService.INSTANCE.getAbilityTree(classId);
            if (!data.ok()) throw new UiActionService.UiActionException("OFFICIAL_TREE_UNAVAILABLE", data.error());
            var official = data.data();
            before = onClientThreadOnce(() -> WynnAbilitySelectionService.correlate(Minecraft.getInstance(), official));
            plan = WynnAbilitySelectionService.plan(official, before, abilityId, classId,
                expectedScreen, expectedSyncId, expectedRevision);
            WynnAbilitySelectionService.SelectionPlan checkedPlan = plan;
            after = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                return WynnAbilitySelectionService.start(Minecraft.getInstance(), official, checkedPlan, () -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    UiActionGate.INSTANCE.chargeAllowanceBeforeClick(minecraft.level,
                        minecraft.level != null && minecraft.player != null && minecraft.getConnection() != null, 1);
                    clickAttempted.set(true);
                });
            });
            AbilityTreeSemanticCollector.Result verified = awaitAbilitySelectionAcknowledgement(official, plan, after);
            if (verified != null) {
                toolSuccess(exchange, id, linked("ok", true, "verified", true, "action", "wynn_select_ability",
                    "before", abilitySelectionSummary(before, plan), "after", abilityTreeSummary(verified),
                    "apiVerification", "NOT_REQUESTED", "allowanceConsumed", true),
                    "Selected one Ability Tree node and verified the changed points and live node state after synchronization.", modern);
            } else {
                AbilityTreeSemanticCollector.Result last = lastObservedAbilityTree(official, after);
                toolError(exchange, id, "POSTCONDITION_FAILED: the selected node and AP change were not both verified after synchronization",
                    linked("ok", false, "code", "POSTCONDITION_FAILED", "action", "wynn_select_ability",
                        "before", abilitySelectionSummary(before, plan), "after", abilityTreeSummary(last),
                        "apiVerification", "NOT_REQUESTED", "allowanceConsumed", clickAttempted.get(), "verified", false), modern);
            }
        } catch (UiActionGate.GateException | UiActionService.UiActionException e) {
            String code = e instanceof UiActionGate.GateException gate ? gate.code()
                : ((UiActionService.UiActionException) e).code();
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", code, "error", e.getMessage(),
                "before", abilitySelectionSummary(before, plan), "after", abilityTreeSummary(after),
                "allowanceConsumed", clickAttempted.get(), "verified", false), modern);
        } catch (Exception e) {
            toolError(exchange, id, "Ability selection failed: " + safeError(e), linked("ok", false, "code", "UI_ACTION_FAILED",
                "error", safeError(e), "before", abilitySelectionSummary(before, plan), "after", abilityTreeSummary(after),
                "allowanceConsumed", clickAttempted.get(), "verified", false), modern);
        } finally {
            if (admitted.get()) UiActionGate.INSTANCE.endAction();
        }
    }

    private void openCharacterInfo(HttpExchange exchange, Object id, Map<String, Object> args, boolean modern) throws IOException {
        String expectedScreen = requiredString(args, "expectedScreen");
        int expectedSyncId = requiredIntArg(args, "expectedSyncId", 0, 100000);
        long expectedRevision = requiredLongArg(args, "expectedRevision", 0L, Long.MAX_VALUE);
        AtomicBoolean admitted = new AtomicBoolean();
        WynnSemanticUiService.CharacterInfoPlan plan = null;
        WynnSemanticUiService.Snapshot after = null;
        try {
            beginUiAction(id, "wynn_open_character_info", UiActionGate.Category.SKILLS, 1, admitted);
            plan = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                return WynnSemanticUiService.planOpenCharacterInfo(
                    Minecraft.getInstance(), expectedScreen, expectedSyncId, expectedRevision);
            });
            WynnSemanticUiService.CharacterInfoPlan checked = plan;
            onClientThreadOnce(() -> {
                requireUiActionStillActive();
                WynnSemanticUiService.startOpenCharacterInfo(Minecraft.getInstance(), checked);
                return null;
            });
            after = awaitCharacterInfoAcknowledgement(plan);
            if (after != null) {
                toolSuccess(exchange, id, linked("ok", true, "action", "wynn_open_character_info",
                    "before", semanticUiSummary(plan.before()), "after", semanticUiSummary(after),
                    "verified", true), "Opened and verified the Character Info screen using its uniquely identified held item.", modern);
            } else {
                WynnSemanticUiService.Snapshot last = captureSemanticUiOrNull(plan.before());
                toolError(exchange, id, "SCREEN_TRANSITION_NOT_VERIFIED: a Character Info screen was not identified after item use",
                    linked("ok", false, "code", "SCREEN_TRANSITION_NOT_VERIFIED", "action", "wynn_open_character_info",
                        "before", semanticUiSummary(plan.before()), "after", last == null ? null : semanticUiSummary(last),
                        "verified", false), modern);
            }
        } catch (UiActionGate.GateException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "before", plan == null ? null : semanticUiSummary(plan.before()), "verified", false), modern);
        } catch (UiActionService.UiActionException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "before", plan == null ? null : semanticUiSummary(plan.before()),
                "after", after == null ? null : semanticUiSummary(after), "verified", false), modern);
        } catch (Exception e) {
            toolError(exchange, id, "Character Info action failed: " + safeError(e), linked("ok", false,
                "code", "UI_ACTION_FAILED", "error", safeError(e),
                "before", plan == null ? null : semanticUiSummary(plan.before()), "verified", false), modern);
        } finally {
            if (admitted.get()) UiActionGate.INSTANCE.endAction();
        }
    }

    private void assignSkillPoints(HttpExchange exchange, Object id, Map<String, Object> args, boolean modern) throws IOException {
        String skillName = requiredString(args, "skill");
        int amount = requiredIntArg(args, "amount", 1, 5);
        String expectedScreen = requiredString(args, "expectedScreen");
        int expectedSyncId = requiredIntArg(args, "expectedSyncId", 0, 100000);
        long expectedRevision = requiredLongArg(args, "expectedRevision", 0L, Long.MAX_VALUE);
        AtomicBoolean admitted = new AtomicBoolean();
        WynnSemanticUiService.SkillPlan plan = null;
        WynnSemanticUiService.Snapshot current = null;
        List<Map<String, Object>> steps = new ArrayList<>();
        try {
            WynnSemanticUiService.Skill skill = WynnSemanticUiService.Skill.parse(skillName);
            beginUiAction(id, "wynn_assign_skill_points", UiActionGate.Category.SKILLS, amount, admitted);
            plan = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                return WynnSemanticUiService.planSkillAssignment(Minecraft.getInstance(), skillName, amount,
                    expectedScreen, expectedSyncId, expectedRevision);
            });
            current = plan.before();
            WynnSemanticUiService.SkillPlan checkedPlan = plan;
            for (int index = 0; index < amount; index++) {
                WynnSemanticUiService.Snapshot beforeStep = current;
                WynnSemanticUiService.SkillClick click = onClientThreadOnce(() -> {
                    requireUiActionStillActive();
                    return WynnSemanticUiService.startSkillPointClick(Minecraft.getInstance(), checkedPlan, beforeStep);
                });
                WynnSemanticUiService.Snapshot verified = awaitSkillPointAcknowledgement(click);
                if (verified == null) {
                    WynnSemanticUiService.Snapshot last = captureSemanticUiOrNull(beforeStep);
                    toolError(exchange, id, "SERVER_STATE_NOT_VERIFIED: skill value and unassigned points did not both change as expected; no further clicks were sent",
                        linked("ok", false, "code", "SERVER_STATE_NOT_VERIFIED", "action", "wynn_assign_skill_points",
                            "skill", skill.displayName(), "requestedAmount", amount, "completedAmount", steps.size(),
                            "before", skillSummary(plan.before(), skill),
                            "after", last == null ? null : skillSummary(last, skill), "steps", List.copyOf(steps),
                            "verified", false), modern);
                    return;
                }
                steps.add(linked("before", skillSummary(beforeStep, skill), "after", skillSummary(verified, skill), "verified", true));
                current = verified;
            }
            toolSuccess(exchange, id, linked("ok", true, "action", "wynn_assign_skill_points",
                "skill", skill.displayName(), "requestedAmount", amount, "completedAmount", amount,
                "before", skillSummary(plan.before(), skill), "after", skillSummary(current, skill),
                "steps", List.copyOf(steps), "verified", true),
                "Assigned each requested skill point and verified both the skill value and remaining points after each server sync.", modern);
        } catch (UiActionGate.GateException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "completedAmount", steps.size(), "steps", List.copyOf(steps),
                "before", plan == null ? null : skillSummary(plan.before(), plan.skill()),
                "after", current == null || plan == null ? null : skillSummary(current, plan.skill()), "verified", false), modern);
        } catch (UiActionService.UiActionException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "completedAmount", steps.size(), "steps", List.copyOf(steps),
                "before", plan == null ? null : skillSummary(plan.before(), plan.skill()),
                "after", current == null || plan == null ? null : skillSummary(current, plan.skill()), "verified", false), modern);
        } catch (Exception e) {
            toolError(exchange, id, "Skill assignment failed: " + safeError(e), linked("ok", false,
                "code", "UI_ACTION_FAILED", "error", safeError(e), "completedAmount", steps.size(),
                "steps", List.copyOf(steps), "verified", false), modern);
        } finally {
            if (admitted.get()) UiActionGate.INSTANCE.endAction();
        }
    }

    private void openAbilityTree(HttpExchange exchange, Object id, Map<String, Object> args, boolean modern) throws IOException {
        String expectedScreen = requiredString(args, "expectedScreen");
        int expectedSyncId = requiredIntArg(args, "expectedSyncId", 0, 100000);
        long expectedRevision = requiredLongArg(args, "expectedRevision", 0L, Long.MAX_VALUE);
        AtomicBoolean admitted = new AtomicBoolean();
        WynnSemanticUiService.AbilityButtonPlan plan = null;
        WynnSemanticUiService.Snapshot after = null;
        try {
            beginUiAction(id, "wynn_open_ability_tree", UiActionGate.Category.ABILITY, 1, admitted);
            plan = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                return WynnSemanticUiService.planOpenAbilityTree(
                    Minecraft.getInstance(), expectedScreen, expectedSyncId, expectedRevision);
            });
            WynnSemanticUiService.AbilityButtonPlan checked = plan;
            onClientThreadOnce(() -> {
                requireUiActionStillActive();
                WynnSemanticUiService.startOpenAbilityTree(Minecraft.getInstance(), checked);
                return null;
            });
            after = awaitAbilityTreeAcknowledgement(plan);
            if (after != null) {
                toolSuccess(exchange, id, linked("ok", true, "action", "wynn_open_ability_tree",
                    "before", semanticUiSummary(plan.before()), "after", semanticUiSummary(after), "verified", true),
                    "Opened and verified the Ability Tree using its uniquely identified in-screen control.", modern);
            } else {
                WynnSemanticUiService.Snapshot last = captureSemanticUiOrNull(plan.before());
                toolError(exchange, id, "SCREEN_TRANSITION_NOT_VERIFIED: an Ability Tree screen was not identified after the control click",
                    linked("ok", false, "code", "SCREEN_TRANSITION_NOT_VERIFIED", "action", "wynn_open_ability_tree",
                        "before", semanticUiSummary(plan.before()), "after", last == null ? null : semanticUiSummary(last),
                        "verified", false), modern);
            }
        } catch (UiActionGate.GateException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "before", plan == null ? null : semanticUiSummary(plan.before()), "verified", false), modern);
        } catch (UiActionService.UiActionException e) {
            toolError(exchange, id, e.getMessage(), linked("ok", false, "code", e.code(), "error", e.getMessage(),
                "before", plan == null ? null : semanticUiSummary(plan.before()),
                "after", after == null ? null : semanticUiSummary(after), "verified", false), modern);
        } catch (Exception e) {
            toolError(exchange, id, "Ability Tree open action failed: " + safeError(e), linked("ok", false,
                "code", "UI_ACTION_FAILED", "error", safeError(e), "before", plan == null ? null : semanticUiSummary(plan.before()),
                "verified", false), modern);
        } finally {
            if (admitted.get()) UiActionGate.INSTANCE.endAction();
        }
    }

    private void beginUiAction(Object id, String toolName, AtomicBoolean admitted) throws Exception {
        beginUiAction(id, toolName, UiActionGate.Category.INVENTORY, 1, admitted);
    }

    private void beginUiAction(
        Object id, String toolName, UiActionGate.Category category, int units, AtomicBoolean admitted
    ) throws Exception {
        if (!config.allowUiActions()) {
            throw new UiActionGate.GateException("UI_ACTIONS_DISABLED", "UI actions are disabled in config/wynn-ai-bridge.properties");
        }
        if (id == null) {
            throw new UiActionGate.GateException("MISSING_REQUEST_ID", "A unique MCP request id is required for UI actions");
        }
        if (!UiActionGate.INSTANCE.status().armed()) {
            throw new UiActionGate.GateException("UI_ACTIONS_DISARMED", "UI actions are not locally armed");
        }
        // MCP request ids are global across tools; do not namespace and accidentally allow cross-tool replay.
        String actionKey = String.valueOf(id);
        onClientThreadOnce(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            UiActionGate.INSTANCE.beginAction(actionKey, minecraft.level,
                minecraft.level != null && minecraft.player != null && minecraft.getConnection() != null,
                category, units);
            admitted.set(true);
            return null;
        });
    }

    private static void requireUiActionStillActive() {
        Minecraft minecraft = Minecraft.getInstance();
        UiActionGate.INSTANCE.requireActionActive(
            minecraft.level,
            minecraft.level != null && minecraft.player != null && minecraft.getConnection() != null
        );
    }

    private UiActionService.GuiSnapshot awaitClickAcknowledgement(
        UiActionService.ClickPair pair,
        String expectedScreen
    ) throws Exception {
        UiActionService.GuiSnapshot latest = pair.after();
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100L);
            latest = onClientThreadOnce(() -> UiActionService.capture(Minecraft.getInstance(), expectedScreen));
            if (latest.stateId() != pair.before().stateId() && UiActionService.clickResultMatches(latest, pair)) return latest;
        }
        return latest;
    }

    private UiActionService.GuiSnapshot awaitMoveSourceAcknowledgement(UiActionService.MoveStart start) throws Exception {
        UiActionService.GuiSnapshot latest = start.afterSourcePickup();
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100L);
            latest = onClientThreadOnce(() -> UiActionService.capture(Minecraft.getInstance(), start.plan().before().screenClass()));
            if (latest.stateId() != start.plan().before().stateId()
                && UiActionService.moveSourcePickupMatches(latest, start.plan())) return latest;
        }
        return null;
    }

    private UiActionService.GuiSnapshot awaitMoveDestinationAcknowledgement(
        UiActionService.MovePlan plan,
        UiActionService.GuiSnapshot afterSource
    ) throws Exception {
        UiActionService.GuiSnapshot latest = afterSource;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100L);
            latest = onClientThreadOnce(() -> UiActionService.capture(Minecraft.getInstance(), plan.before().screenClass()));
            if (latest.stateId() != afterSource.stateId() && UiActionService.moveResultMatches(latest, plan)) return latest;
        }
        return null;
    }

    private UiActionService.GuiSnapshot awaitEquipSourceAcknowledgement(UiActionService.EquipStart start) throws Exception {
        UiActionService.GuiSnapshot latest = start.afterSourcePickup();
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100L);
            latest = onClientThreadOnce(() -> UiActionService.capture(
                Minecraft.getInstance(), start.plan().before().screenClass()
            ));
            if (latest.stateId() != start.plan().before().stateId()
                && UiActionService.equipSourcePickupMatches(latest, start.plan())) return latest;
        }
        return null;
    }

    private UiActionService.GuiSnapshot awaitBankWithdrawSourceAcknowledgement(
        BankWithdrawService.BankWithdrawStart start
    ) throws Exception {
        UiActionService.GuiSnapshot latest = start.afterSourcePickup();
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100L);
            latest = onClientThreadOnce(() -> BankWithdrawService.capture(
                Minecraft.getInstance(), start.plan().before().screenClass()
            ));
            if (latest.stateId() != start.plan().before().stateId()
                && BankWithdrawService.sourcePickupMatches(latest, start.plan())) return latest;
        }
        return null;
    }

    private UiActionService.GuiSnapshot awaitBankWithdrawDestinationAcknowledgement(
        BankWithdrawService.BankWithdrawPlan plan,
        UiActionService.GuiSnapshot afterSource
    ) throws Exception {
        UiActionService.GuiSnapshot latest = afterSource;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100L);
            latest = onClientThreadOnce(() -> BankWithdrawService.capture(
                Minecraft.getInstance(), plan.before().screenClass()
            ));
            if (latest.stateId() != afterSource.stateId()
                && BankWithdrawService.withdrawResultMatches(latest, plan)) return latest;
        }
        return null;
    }

    private UiActionService.GuiSnapshot awaitBankDepositSourceAcknowledgement(
        BankDepositService.DepositStart start
    ) throws Exception {
        UiActionService.GuiSnapshot latest = start.afterSourcePickup();
        for (int i = 0; i < 20; i++) {
            if (latest.stateId() != start.plan().before().stateId()
                && BankDepositService.sourcePickupMatches(latest, start.plan())) return latest;
            Thread.sleep(100L);
            latest = onClientThreadOnce(() -> BankDepositService.capture(
                Minecraft.getInstance(), start.plan().before().screenClass()));
        }
        return null;
    }

    private UiActionService.GuiSnapshot awaitBankDepositDestinationAcknowledgement(
        BankDepositService.DepositPlan plan,
        UiActionService.GuiSnapshot afterSource
    ) throws Exception {
        UiActionService.GuiSnapshot latest = afterSource;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100L);
            latest = onClientThreadOnce(() -> BankDepositService.capture(Minecraft.getInstance(), plan.before().screenClass()));
            if (latest.stateId() != afterSource.stateId() && BankDepositService.depositResultMatches(latest, plan)) return latest;
        }
        return null;
    }

    private UiActionService.GuiSnapshot lastObservedDepositState(
        BankDepositService.DepositStart start, String expectedScreen
    ) {
        try {
            return onClientThreadOnce(() -> BankDepositService.capture(Minecraft.getInstance(), expectedScreen));
        } catch (Exception ignored) {
            return start.afterSourcePickup();
        }
    }

    private void beginDeferredAbilityAction(Object id, AtomicBoolean admitted) throws Exception {
        if (!config.allowUiActions()) {
            throw new UiActionGate.GateException("UI_ACTIONS_DISABLED", "UI actions are disabled in config/wynn-ai-bridge.properties");
        }
        if (id == null) throw new UiActionGate.GateException("MISSING_REQUEST_ID", "A unique MCP request id is required for UI actions");
        if (!UiActionGate.INSTANCE.status().armed()) {
            throw new UiActionGate.GateException("UI_ACTIONS_DISARMED", "UI actions are not locally armed");
        }
        String actionKey = String.valueOf(id);
        onClientThreadOnce(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            UiActionGate.INSTANCE.beginActionDeferredCharge(actionKey, minecraft.level,
                minecraft.level != null && minecraft.player != null && minecraft.getConnection() != null,
                UiActionGate.Category.ABILITY, 1);
            admitted.set(true);
            return null;
        });
    }

    private AbilityTreeSemanticCollector.Result awaitAbilitySelectionAcknowledgement(
        WynnAbilityDataService.AbilityTree official,
        WynnAbilitySelectionService.SelectionPlan plan,
        AbilityTreeSemanticCollector.Result afterClick
    ) throws Exception {
        if (WynnAbilitySelectionService.selectionVerified(afterClick, plan)) return afterClick;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100L);
            AbilityTreeSemanticCollector.Result latest = onClientThreadOnce(() -> {
                requireUiActionStillActive();
                return WynnAbilitySelectionService.correlate(Minecraft.getInstance(), official);
            });
            if (WynnAbilitySelectionService.selectionVerified(latest, plan)) return latest;
        }
        return null;
    }

    private AbilityTreeSemanticCollector.Result lastObservedAbilityTree(
        WynnAbilityDataService.AbilityTree official, AbilityTreeSemanticCollector.Result fallback
    ) {
        try {
            return onClientThreadOnce(() -> WynnAbilitySelectionService.correlate(Minecraft.getInstance(), official));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Map<String, Object> abilityTreeSummary(AbilityTreeSemanticCollector.Result result) {
        if (result == null) return null;
        return linked("capturedAt", System.currentTimeMillis(), "screenClass", result.screenClass(),
            "syncId", result.syncId(), "containerStateId", result.containerStateId(),
            "stateRevision", result.stateRevision(), "classId", result.classId(),
            "classVerified", result.classVerified(), "abilityTreeRecognized", result.abilityTreeRecognized(),
            "availablePoints", result.availablePoints(), "page", result.page());
    }

    private static Map<String, Object> abilitySelectionSummary(
        AbilityTreeSemanticCollector.Result result, WynnAbilitySelectionService.SelectionPlan plan
    ) {
        if (result == null && plan == null) return null;
        return linked("screenClass", result == null ? null : result.screenClass(),
            "syncId", result == null ? null : result.syncId(),
            "containerStateId", result == null ? null : result.containerStateId(),
            "stateRevision", result == null ? null : result.stateRevision(),
            "classId", result == null ? null : result.classId(),
            "availablePoints", result == null ? null : result.availablePoints(),
            "abilityId", plan == null ? null : plan.officialNode().id(),
            "abilityName", plan == null ? null : plan.officialNode().name(),
            "nodeState", plan == null ? null : plan.runtimeNode().state(),
            "matchConfidence", plan == null ? null : plan.runtimeNode().matchConfidence(),
            "screenSlot", plan == null ? null : plan.runtimeNode().menuSlot(),
            "cost", plan == null ? null : plan.cost());
    }

    private UiActionService.GuiSnapshot awaitBankPageAcknowledgement(
        BankWithdrawService.BankPageStepPlan plan
    ) throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100L);
            UiActionService.GuiSnapshot latest = onClientThreadOnce(() ->
                BankWithdrawService.capture(Minecraft.getInstance(), plan.before().screenClass()));
            if (BankWithdrawService.pageStepMatches(latest, plan)) return latest;
        }
        return null;
    }

    private WynnSemanticUiService.Snapshot awaitCharacterInfoAcknowledgement(
        WynnSemanticUiService.CharacterInfoPlan plan
    ) throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100L);
            WynnSemanticUiService.Snapshot latest = onClientThreadOnce(() ->
                WynnSemanticUiService.capture(Minecraft.getInstance()));
            if (latest.screenIdentity() != plan.before().screenIdentity() && latest.characterInfoRecognized()) return latest;
        }
        return null;
    }

    private WynnSemanticUiService.Snapshot awaitAbilityTreeAcknowledgement(
        WynnSemanticUiService.AbilityButtonPlan plan
    ) throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100L);
            WynnSemanticUiService.Snapshot latest = onClientThreadOnce(() ->
                WynnSemanticUiService.capture(Minecraft.getInstance()));
            if (WynnSemanticUiService.abilityTreeOpened(plan.before(), latest)) return latest;
        }
        return null;
    }

    private WynnSemanticUiService.Snapshot awaitSkillPointAcknowledgement(
        WynnSemanticUiService.SkillClick click
    ) throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100L);
            WynnSemanticUiService.Snapshot latest = onClientThreadOnce(() ->
                WynnSemanticUiService.capture(Minecraft.getInstance()));
            if (latest.screenIdentity() != click.before().screenIdentity()
                || latest.syncId() != click.before().syncId() || !latest.characterInfoRecognized()) return null;
            if (WynnSemanticUiService.skillClickVerified(latest, click)) return latest;
        }
        return null;
    }

    private WynnSemanticUiService.Snapshot captureSemanticUiOrNull(
        WynnSemanticUiService.Snapshot fallback
    ) {
        try {
            return onClientThreadOnce(() -> WynnSemanticUiService.capture(Minecraft.getInstance()));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Map<String, Object> semanticUiSummary(WynnSemanticUiService.Snapshot snapshot) {
        return linked("capturedAt", snapshot.capturedAt(), "screenClass", snapshot.screenClass(),
            "screenTitle", snapshot.title(), "menuClass", snapshot.menuClass(), "syncId", snapshot.syncId(),
            "stateId", snapshot.stateId(), "stateRevision", snapshot.stateRevision(),
            "characterInfoRecognized", snapshot.characterInfoRecognized(),
            "abilityTreeRecognized", snapshot.abilityTreeRecognized(),
            "unassignedSkillPoints", snapshot.unassignedSkillPoints(),
            "unusedAbilityPoints", snapshot.unusedAbilityPoints(), "skillValues", snapshot.skillValues());
    }

    private static Map<String, Object> skillSummary(
        WynnSemanticUiService.Snapshot snapshot, WynnSemanticUiService.Skill skill
    ) {
        return linked("screenClass", snapshot.screenClass(), "screenTitle", snapshot.title(),
            "menuClass", snapshot.menuClass(), "syncId", snapshot.syncId(), "stateId", snapshot.stateId(),
            "stateRevision", snapshot.stateRevision(), "characterInfoRecognized", snapshot.characterInfoRecognized(),
            "skill", skill.displayName(), "skillValue", snapshot.skillValues().get(skill.displayName()),
            "unassignedSkillPoints", snapshot.unassignedSkillPoints());
    }

    private UiActionService.GuiSnapshot lastObservedBankPageState(
        String expectedScreen, UiActionService.GuiSnapshot fallback
    ) {
        try {
            return onClientThreadOnce(() -> BankWithdrawService.capture(Minecraft.getInstance(), expectedScreen));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Map<String, Object> bankPageSummary(UiActionService.GuiSnapshot snapshot) {
        var page = BankWithdrawService.recognizeBankPage(snapshot);
        return linked("screenClass", snapshot.screenClass(), "menuClass", snapshot.menuClass(),
            "syncId", snapshot.syncId(), "stateId", snapshot.stateId(), "stateRevision", snapshot.stateRevision(),
            "bankRecognized", page.recognized(), "currentPage", page.currentPage(),
            "pageCount", page.pageCount(), "reason", page.reason());
    }

    private UiActionService.GuiSnapshot lastObservedBankState(
        BankWithdrawService.BankWithdrawStart start,
        String expectedScreen
    ) throws Exception {
        try {
            return onClientThreadOnce(() -> BankWithdrawService.capture(Minecraft.getInstance(), expectedScreen));
        } catch (Exception ignored) {
            return start.afterSourcePickup();
        }
    }

    private static BankWithdrawService.BankState bankStateSummary(
        UiActionService.GuiSnapshot snapshot,
        BankWithdrawService.BankWithdrawPlan plan
    ) {
        return snapshot == null || plan == null ? null : BankWithdrawService.summary(snapshot, plan);
    }

    private UiActionService.GuiSnapshot awaitEquipTargetAcknowledgement(
        UiActionService.EquipPlan plan,
        UiActionService.GuiSnapshot afterSource
    ) throws Exception {
        UiActionService.GuiSnapshot latest = afterSource;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100L);
            latest = onClientThreadOnce(() -> UiActionService.capture(Minecraft.getInstance(), plan.before().screenClass()));
            if (latest.stateId() != afterSource.stateId() && UiActionService.equipResultMatches(latest, plan)) return latest;
        }
        return null;
    }

    private UiActionService.GuiSnapshot lastObservedEquipState(
        UiActionService.EquipStart start,
        String expectedScreen
    ) throws Exception {
        try {
            return onClientThreadOnce(() -> UiActionService.capture(Minecraft.getInstance(), expectedScreen));
        } catch (Exception ignored) {
            return start.afterSourcePickup();
        }
    }

    private UiActionService.GuiSnapshot lastObservedMoveState(
        UiActionService.MoveStart start,
        String expectedScreen
    ) throws Exception {
        try {
            return onClientThreadOnce(() -> UiActionService.capture(Minecraft.getInstance(), expectedScreen));
        } catch (Exception ignored) {
            return start.afterSourcePickup();
        }
    }

    private static Map<String, Object> uiActionError(
        String code,
        String message,
        UiActionService.ClickPair pair,
        UiActionService.GuiSnapshot after
    ) {
        return linked("ok", false, "code", code, "error", message,
            "before", pair == null ? null : pair.before(),
            "after", after == null && pair != null ? pair.after() : after,
            "verified", false);
    }

    private static String safeError(Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static long requiredLongArg(Map<String, Object> args, String name, long min, long max) {
        if (!args.containsKey(name)) throw new IllegalArgumentException("Missing " + name);
        Object value = args.get(name);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(name + " must be an integer");
        long parsed = number.longValue();
        if (parsed < min || parsed > max) throw new IllegalArgumentException(name + " is out of range");
        return parsed;
    }

    private static int requiredIntArg(Map<String, Object> args, String name, int min, int max) {
        return Math.toIntExact(requiredLongArg(args, name, min, max));
    }

    private <T> T onClientThreadOnce(Callable<T> task) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        AtomicInteger phase = new AtomicInteger(0); // queued, running, finished, cancelled
        Minecraft.getInstance().execute(() -> {
            if (!phase.compareAndSet(0, 1)) return;
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                phase.set(2);
            }
        });
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            if (phase.compareAndSet(0, 3)) {
                throw new IllegalStateException("Minecraft client thread timed out before the UI action started; it was cancelled", timeout);
            }
            try {
                return future.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException secondTimeout) {
                throw new IllegalStateException("Minecraft client thread timed out after the UI action started; do not retry automatically", secondTimeout);
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException(cause);
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
        return tool(name, description, inputSchema, readOnly, !readOnly, readOnly, openWorld);
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema,
                                             boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {
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
                case "wynn_get_official_ability_tree" -> "Get Official Wynncraft Ability Tree";
                case "wynn_get_class_info" -> "Get Official Wynncraft Class Info";
                case "wynn_get_player_abilities" -> "Get Official Player Abilities";
                case "wynn_get_skill_points" -> "Get Wynncraft Skill Points";
                case "minecraft_send_command" -> "Send Minecraft Command";
                case "minecraft_send_chat" -> "Send Minecraft Chat";
                case "minecraft_click_gui_slot" -> "Click Player Inventory Slot";
                case "minecraft_move_inventory_item" -> "Move Player Inventory Item";
                case "wynn_get_ability_tree" -> "Inspect Ability Tree Screen Evidence";
                case "wynn_open_character_info" -> "Open Character Info";
                case "wynn_assign_skill_points" -> "Assign Wynncraft Skill Points";
                case "wynn_open_ability_tree" -> "Open Wynncraft Ability Tree";
                case "wynn_open_bank_page" -> "Open Wynncraft Bank Page";
                default -> name;
            },
            "description", description,
            "inputSchema", inputSchema,
            "annotations", linked(
                "readOnlyHint", readOnly,
                "destructiveHint", destructive,
                "idempotentHint", idempotent,
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

    private static Map<String, Object> requiredObjectSchema(List<String> required, Map<String, Object> properties) {
        return linked("type", "object", "properties", properties, "required", List.copyOf(required), "additionalProperties", false);
    }

    private static Map<String, Object> stringProperty(String description) {
        return linked("type", "string", "description", description);
    }

    private static Map<String, Object> enumStringProperty(String description, List<String> values) {
        return linked("type", "string", "description", description, "enum", List.copyOf(values));
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
