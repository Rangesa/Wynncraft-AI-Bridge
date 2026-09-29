package dev.tanaka.wynnaibridge.http;

import com.sun.net.httpserver.HttpServer;
import dev.tanaka.wynnaibridge.config.BridgeConfig;
import dev.tanaka.wynnaibridge.ui.BankWithdrawService;
import dev.tanaka.wynnaibridge.ui.UiActionGate;
import dev.tanaka.wynnaibridge.ui.UiActionService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic gate, stale-state, tool-list, and local MCP authorization regression checks. */
public final class UiActionRegressionHarness {
    private static int assertions;

    private UiActionRegressionHarness() {}

    public static void main(String[] args) throws Exception {
        gateStartsDisarmedAndRequiresLocalArm();
        gateExpiresSerializesRateLimitsAndDeduplicates();
        staleScreenSyncAndRevisionAreRejected();
        itemAndStackValidationRejectUnsafeExpectedState();
        bankWithdrawIsLimitedToRecognizedPageTwoAndOneEmptyInventorySlot();
        toolListsKeepTheExistingFifteenReadTools();
        publicMcpKeepsTheExistingBearerGateAndRequestGuards();
        publicUiToolsRequireLocalArm();
        System.out.println("UI action regression checks passed: " + assertions);
    }

    private static void gateStartsDisarmedAndRequiresLocalArm() {
        FakeClock clock = new FakeClock();
        UiActionGate gate = clock.newGate();
        check(!gate.status().armed(), "a new UI action gate must start disarmed");
        expectGate("UI_ACTIONS_DISARMED", "UI actions are not locally armed",
            () -> gate.beginAction("unarmed", new Object(), true));

        Object world = new Object();
        gate.arm(world, 30);
        check(gate.status().armed(), "local arm should enable the in-memory gate");
        check(gate.status().bankWithdrawalsRemaining() == 0,
            "ordinary local arm must not authorize Bank withdrawals");
        gate.beginAction("active#1", world, true);
        expectGate("BANK_WITHDRAWAL_NOT_LOCALLY_AUTHORIZED", null, gate::consumeBankWithdrawalAllowance);
        gate.requireActionActive(world, true);
        gate.disarm();
        check(!gate.status().armed(), "local disarm should clear the in-memory gate");
        check(gate.status().bankWithdrawalsRemaining() == 0,
            "local disarm must clear any Bank withdrawal allowance");
        expectGate("UI_ACTIONS_DISARMED", "UI actions are not locally armed",
            () -> gate.requireActionActive(world, true));
        gate.endAction();

        gate.arm(world, 30, 2);
        check(gate.status().bankWithdrawalsRemaining() == 2,
            "a client-only Bank arm may authorize at most the explicitly requested number of withdrawals");
        gate.beginAction("bank#1", world, true);
        check(gate.consumeBankWithdrawalAllowance() == 1,
            "a Bank withdrawal must consume exactly one local allowance before the first click");
        gate.endAction();
        expectGate("INVALID_BANK_WITHDRAWAL_ALLOWANCE", null, () -> gate.arm(world, 30, 8));
    }

    private static void gateExpiresSerializesRateLimitsAndDeduplicates() {
        FakeClock clock = new FakeClock();
        UiActionGate gate = clock.newGate();
        Object world = new Object();
        gate.arm(world, 1);
        gate.beginAction("click#1", world, true);
        expectGate("ACTION_IN_PROGRESS", null, () -> gate.beginAction("move#2", world, true));
        gate.endAction();
        expectGate("RATE_LIMITED", null, () -> gate.beginAction("click#2", world, true));

        clock.advanceMillis(750L);
        gate.beginAction("click#2", world, true);
        gate.endAction();
        expectGate("DUPLICATE_ACTION", null, () -> gate.beginAction("click#2", world, true));

        gate.observeWorld(new Object(), true);
        check(!gate.status().armed(), "a world identity change must disarm UI actions");
        gate.arm(world, 1);
        gate.observeWorld(world, false);
        check(!gate.status().armed(), "disconnect must disarm UI actions");

        gate.arm(world, 1);
        clock.advanceMillis(1000L);
        check(!gate.status().armed(), "arm expiry must disarm UI actions");
        expectGate("UI_ACTIONS_DISARMED", "UI actions are not locally armed",
            () -> gate.beginAction("expired", world, true));
    }

    private static void staleScreenSyncAndRevisionAreRejected() {
        UiActionService.GuiSnapshot current = new UiActionService.GuiSnapshot(
            1L, "InventoryScreen", "InventoryMenu", "Inventory", 5, 0, 9, 44L, List.of(),
            new UiActionService.ItemSnapshot(true, "", "", 0, "", 0)
        );
        UiActionService.validateExpectedContext(current, "InventoryScreen", 0, 44L);
        expectUiCode("STATE_CHANGED", () -> UiActionService.validateExpectedContext(current, "ChestScreen", 0, 44L));
        expectUiCode("STATE_CHANGED", () -> UiActionService.validateExpectedContext(current, "InventoryScreen", 1, 44L));
        expectUiCode("STATE_CHANGED", () -> UiActionService.validateExpectedContext(current, "InventoryScreen", 0, 43L));
    }

    private static void itemAndStackValidationRejectUnsafeExpectedState() {
        UiActionService.ItemSnapshot source = new UiActionService.ItemSnapshot(false,
            "minecraft:stone", "Test Stone", 16, "{custom_name=none}", 64);
        UiActionService.ItemSnapshot compatible = new UiActionService.ItemSnapshot(false,
            "minecraft:stone", "Test Stone", 32, "{custom_name=none}", 64);
        UiActionService.ItemSnapshot different = new UiActionService.ItemSnapshot(false,
            "minecraft:dirt", "Test Dirt", 1, "{}", 64);
        UiActionService.ItemSnapshot insufficient = new UiActionService.ItemSnapshot(false,
            "minecraft:stone", "Test Stone", 60, "{custom_name=none}", 64);
        UiActionService.ItemSnapshot empty = new UiActionService.ItemSnapshot(true, "", "", 0, "", 0);

        UiActionService.validateExpectedItemName(source, "Test Stone");
        expectUiCode("EXPECTED_ITEM_MISMATCH", () -> UiActionService.validateExpectedItemName(source, "Other"));
        UiActionService.validateMoveDestination(source, empty);
        UiActionService.validateMoveDestination(source, compatible);
        expectUiCode("UNSAFE_DESTINATION", () -> UiActionService.validateMoveDestination(source, different));
        expectUiCode("STACK_WOULD_SPLIT", () -> UiActionService.validateMoveDestination(source, insufficient));
    }

    private static void bankWithdrawIsLimitedToRecognizedPageTwoAndOneEmptyInventorySlot() {
        UiActionService.GuiSnapshot before = bankPageTwoSnapshot();
        BankWithdrawService.BankWithdrawPlan plan = BankWithdrawService.plan(before, 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L);
        check(plan.bankMenuSlot() == 10 && plan.inventoryMenuSlot() == 59,
            "Bank withdrawals must map the chosen player inventory index to its actual ChestMenu slot");
        check(BankWithdrawService.recognizedPage2(before),
            "the Wynncraft Bank page 2 marker layout should be recognized");

        expectUiCode("UNRECOGNIZED_BANK_PAGE", () -> BankWithdrawService.plan(before, 1, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L));
        expectUiCode("UNSAFE_BANK_SLOT", () -> BankWithdrawService.plan(before, 2, 51, 14,
            "minecraft:paper", "Page 1 <<<<<", before.screenClass(), 9, 165L));
        expectUiCode("STATE_CHANGED", () -> BankWithdrawService.plan(before, 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 8, 165L));
        expectUiCode("STATE_CHANGED", () -> BankWithdrawService.plan(before, 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 164L));
        expectUiCode("EXPECTED_ITEM_MISMATCH", () -> BankWithdrawService.plan(before, 2, 10, 14,
            "minecraft:stone", "Test Helmet", before.screenClass(), 9, 165L));

        List<UiActionService.SlotSnapshot> wrongPageSlots = new ArrayList<>(before.slots());
        wrongPageSlots.set(52, slot(52, -1, "minecraft:paper", "Page 4 >>>>>", 1));
        expectUiCode("UNRECOGNIZED_BANK_PAGE", () -> BankWithdrawService.plan(
            snapshot(before, wrongPageSlots, before.carriedItem()), 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L));

        List<UiActionService.SlotSnapshot> nonBankSlots = new ArrayList<>(before.slots());
        nonBankSlots.set(47, slot(47, -1, "minecraft:paper", "Merchant", 1));
        expectUiCode("UNRECOGNIZED_BANK_PAGE", () -> BankWithdrawService.plan(
            snapshot(before, nonBankSlots, before.carriedItem()), 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L));

        List<UiActionService.SlotSnapshot> stackedSource = new ArrayList<>(before.slots());
        stackedSource.set(10, slot(10, -1, "minecraft:leather_helmet", "Test Helmet", 2));
        expectUiCode("BANK_STACK_NOT_SUPPORTED", () -> BankWithdrawService.plan(
            snapshot(before, stackedSource, before.carriedItem()), 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L));

        List<UiActionService.SlotSnapshot> occupiedDestination = new ArrayList<>(before.slots());
        occupiedDestination.set(59, slot(59, 14, "minecraft:stone", "Occupied", 1));
        expectUiCode("DESTINATION_NOT_EMPTY", () -> BankWithdrawService.plan(
            snapshot(before, occupiedDestination, before.carriedItem()), 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L));

        UiActionService.ItemSnapshot cursor = new UiActionService.ItemSnapshot(false,
            "minecraft:stone", "Carried", 1, "{}", 64);
        expectUiCode("CURSOR_NOT_EMPTY", () -> BankWithdrawService.plan(
            snapshot(before, before.slots(), cursor), 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L));
    }

    private static void toolListsKeepTheExistingFifteenReadTools() {
        Map<String, Object> readOnly = listTools(false, false);
        List<Map<String, Object>> readTools = tools(readOnly);
        check(readTools.size() == 15, "default mode must expose exactly the existing 15 tools");
        Set<String> expected = Set.of(
            "minecraft_get_state", "minecraft_get_recent_text", "minecraft_get_slot_tooltip",
            "minecraft_get_context", "minecraft_get_open_container", "minecraft_get_inventory",
            "minecraft_get_visible_ui", "wynn_inspect_hovered_item", "wynn_knowledge_search",
            "wynn_search_items", "wynn_get_item", "wynn_search_locations", "wynn_search_wiki",
            "wynn_get_wiki_page", "wynn_index_status"
        );
        check(names(readTools).equals(expected), "default tool names must remain identical");
        check(readTools.stream().noneMatch(t -> String.valueOf(t.get("name")).startsWith("minecraft_send_")),
            "allowActions=false must keep command and chat hidden");
        check(readTools.stream().noneMatch(t -> String.valueOf(t.get("name")).contains("gui_slot")
                || String.valueOf(t.get("name")).contains("move_inventory")),
            "allowUiActions=false must hide UI write tools");
        check(!names(readTools).contains("wynn_get_ability_tree"),
            "allowUiActions=false must also hide the Ability Tree runtime diagnostic");

        List<Map<String, Object>> uiEnabled = tools(listTools(false, true));
        check(uiEnabled.size() == 20, "allowUiActions=true must add four UI writes and one read-only diagnostic");
        check(names(uiEnabled).containsAll(Set.of("minecraft_click_gui_slot", "minecraft_move_inventory_item", "minecraft_equip_item", "minecraft_withdraw_bank_item")),
            "all UI write tools must be listed independently of allowActions");
        check(names(uiEnabled).contains("wynn_get_ability_tree"),
            "the read-only runtime Ability Tree diagnostic must appear only when UI tooling is enabled");
        check(!names(uiEnabled).contains("minecraft_send_command") && !names(uiEnabled).contains("minecraft_send_chat"),
            "UI actions must not enable legacy command or chat actions");
        check(annotation(uiEnabled, "minecraft_click_gui_slot").equals(Map.of(
                "readOnlyHint", false, "destructiveHint", true, "idempotentHint", false, "openWorldHint", false)),
            "slot click annotations must describe a non-idempotent destructive write");
        check(annotation(uiEnabled, "minecraft_move_inventory_item").equals(Map.of(
                "readOnlyHint", false, "destructiveHint", false, "idempotentHint", false, "openWorldHint", false)),
            "inventory move annotations must describe a non-destructive non-idempotent write");
        check(annotation(uiEnabled, "minecraft_equip_item").equals(Map.of(
                "readOnlyHint", false, "destructiveHint", false, "idempotentHint", false, "openWorldHint", false)),
            "equipment changes must describe a non-destructive non-idempotent write");
        check(annotation(uiEnabled, "minecraft_withdraw_bank_item").equals(Map.of(
                "readOnlyHint", false, "destructiveHint", false, "idempotentHint", false, "openWorldHint", false)),
            "bounded Bank withdrawal must describe a non-destructive non-idempotent write");

        List<Map<String, Object>> legacyWrites = tools(listTools(true, false));
        check(legacyWrites.size() == 17 && names(legacyWrites).containsAll(Set.of("minecraft_send_command", "minecraft_send_chat")),
            "allowActions=true must preserve the two legacy action tools");
        check(annotation(legacyWrites, "minecraft_send_command").equals(Map.of(
                "readOnlyHint", false, "destructiveHint", true, "idempotentHint", false, "openWorldHint", true)),
            "legacy command annotations must remain unchanged");
        check(names(legacyWrites).stream().noneMatch(name -> name.startsWith("minecraft_click_gui")),
            "legacy allowActions must not turn on UI actions");
    }

    private static void publicMcpKeepsTheExistingBearerGateAndRequestGuards() throws Exception {
        int noAuthPort = freeLoopbackPort();
        try (BridgeHttpServer server = new BridgeHttpServer(config(noAuthPort, false, "", false))) {
            server.start();
            HttpResponse list = request(noAuthPort, "127.0.0.1:" + noAuthPort, null, null, listRequest());
            check(list.status() == 200, "read-only MCP list should remain unauthenticated when no token is configured");
            check(toolNames(list.body()).size() == 15, "live MCP tools/list should return the existing 15 tools");
            HttpResponse hiddenAction = request(noAuthPort, "127.0.0.1:" + noAuthPort, null, null, disabledClickRequest());
            check(hiddenAction.status() == 200 && hiddenAction.body().contains("UI_ACTIONS_DISABLED"),
                "a direct tools/call must still reject UI writes while allowUiActions=false");
            HttpResponse hiddenAbilityTree = request(noAuthPort, "127.0.0.1:" + noAuthPort, null, null, abilityTreeRequest());
            check(hiddenAbilityTree.status() == 200 && hiddenAbilityTree.body().contains("UI_ACTIONS_DISABLED"),
                "a direct tools/call must reject the hidden runtime diagnostic while allowUiActions=false");
            HttpResponse hiddenBank = request(noAuthPort, "127.0.0.1:" + noAuthPort, null, null, bankWithdrawRequest());
            check(hiddenBank.status() == 200 && hiddenBank.body().contains("UI_ACTIONS_DISABLED"),
                "a direct Bank withdrawal call must reject while allowUiActions=false");
        }

        int authPort = freeLoopbackPort();
        String token = "regression-test-token";
        try (BridgeHttpServer server = new BridgeHttpServer(config(authPort, true, token, true))) {
            server.start();
            HttpResponse missingToken = request(authPort, "127.0.0.1:" + authPort, null, null, listRequest());
            check(missingToken.status() == 401, "allowActions=true must require Bearer authorization for /mcp");
            HttpResponse validToken = request(authPort, "127.0.0.1:" + authPort, null, "Bearer " + token, listRequest());
            check(validToken.status() == 200, "the configured Bearer token should pass the unchanged MCP auth gate");
            Set<String> names = toolNames(validToken.body());
            check(names.containsAll(Set.of("minecraft_send_command", "minecraft_send_chat")),
                "the authorized legacy MCP list should still expose command/chat");
            check(names.containsAll(Set.of("minecraft_click_gui_slot", "minecraft_move_inventory_item", "minecraft_equip_item", "minecraft_withdraw_bank_item", "wynn_get_ability_tree")),
                "UI tools must remain behind the unchanged global MCP Bearer gate");

            HttpResponse badHost = request(authPort, "attacker.invalid", null, "Bearer " + token, listRequest());
            check(badHost.status() == 403, "invalid Host must continue to be rejected before MCP processing");
            HttpResponse origin = request(authPort, "127.0.0.1:" + authPort, "Origin: https://example.invalid\r\n", "Bearer " + token, listRequest());
            check(origin.status() == 403, "Origin-bearing browser requests must continue to be rejected");
        }
    }

    private static void publicUiToolsRequireLocalArm() throws Exception {
        UiActionGate.INSTANCE.disarm();
        int port = freeLoopbackPort();
        try (BridgeHttpServer server = new BridgeHttpServer(config(port, false, "", true))) {
            server.start();
            HttpResponse list = request(port, "127.0.0.1:" + port, null, null, listRequest());
            check(list.status() == 200 && toolNames(list.body()).size() == 20,
                "allowUiActions=true should list the UI tools through the existing no-auth MCP connection");
            HttpResponse disarmed = request(port, "127.0.0.1:" + port, null, null, clickRequest());
            check(disarmed.status() == 200 && disarmed.body().contains("UI actions are not locally armed"),
                "public no-auth UI writes must fail closed before the local arm command");
            HttpResponse disarmedBank = request(port, "127.0.0.1:" + port, null, null, bankWithdrawRequest());
            check(disarmedBank.status() == 200 && disarmedBank.body().contains("UI actions are not locally armed"),
                "Bank withdrawals must also fail closed before the explicit local arm command");

            var healthResponse = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/health"))
                    .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
            );
            Map<String, Object> health = Json.object(Json.parse(healthResponse.body()));
            check(healthResponse.statusCode() == 200
                    && Boolean.TRUE.equals(health.get("uiActionsEnabled"))
                    && Boolean.FALSE.equals(health.get("uiActionsArmed"))
                    && Long.valueOf(0L).equals(health.get("uiActionsArmExpiresAt"))
                    && Long.valueOf(0L).equals(health.get("uiActionsBankWithdrawalsRemaining")),
                "health may report UI enable/arm state without revealing secrets");
            check(!health.containsKey("token"), "health must not expose an authentication secret");
        }
    }

    private static BridgeConfig config(int port, boolean allowActions, String token, boolean allowUiActions) {
        return new BridgeConfig(port, "", allowActions, token, false, allowUiActions, 300,
            false, false, 60, 12);
    }

    private static Map<String, Object> listTools(boolean allowActions, boolean allowUiActions) {
        int port = 8765;
        return new McpEndpoint(config(port, allowActions, allowActions ? "test-token" : "", allowUiActions))
            .listToolsResult(false);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tools(Map<String, Object> result) {
        return (List<Map<String, Object>>) (List<?>) result.get("tools");
    }

    private static Set<String> names(List<Map<String, Object>> tools) {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> tool : tools) names.add((String) tool.get("name"));
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> annotation(List<Map<String, Object>> tools, String name) {
        for (Map<String, Object> tool : tools) {
            if (name.equals(tool.get("name"))) return (Map<String, Object>) tool.get("annotations");
        }
        throw new AssertionError("Missing tool " + name);
    }

    private static byte[] listRequest() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] disabledClickRequest() {
        return ("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{" +
            "\"name\":\"minecraft_click_gui_slot\",\"arguments\":{" +
            "\"slot\":1,\"button\":\"left\",\"expectedItemName\":\"test\"," +
            "\"expectedScreen\":\"InventoryScreen\",\"expectedSyncId\":0,\"expectedRevision\":1}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] clickRequest() {
        return ("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{" +
            "\"name\":\"minecraft_click_gui_slot\",\"arguments\":{" +
            "\"slot\":1,\"button\":\"left\",\"expectedItemName\":\"test\"," +
            "\"expectedScreen\":\"InventoryScreen\",\"expectedSyncId\":0,\"expectedRevision\":1}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] abilityTreeRequest() {
        return ("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{" +
            "\"name\":\"wynn_get_ability_tree\",\"arguments\":{}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bankWithdrawRequest() {
        return ("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{" +
            "\"name\":\"minecraft_withdraw_bank_item\",\"arguments\":{" +
            "\"bankSlot\":10,\"inventorySlot\":14,\"expectedItemId\":\"minecraft:leather_helmet\"," +
            "\"expectedItemName\":\"Test Helmet\",\"expectedBankPage\":2," +
            "\"expectedScreen\":\"net.minecraft.client.gui.screens.inventory.ContainerScreen\"," +
            "\"expectedSyncId\":9,\"expectedRevision\":165}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static UiActionService.GuiSnapshot bankPageTwoSnapshot() {
        List<UiActionService.SlotSnapshot> slots = new ArrayList<>(90);
        for (int menuSlot = 0; menuSlot < 90; menuSlot++) {
            int inventorySlot = menuSlot < 54 ? -1 : menuSlot < 81 ? menuSlot - 45 : menuSlot - 81;
            slots.add(new UiActionService.SlotSnapshot(menuSlot, inventorySlot, true, true,
                new UiActionService.ItemSnapshot(true, "", "", 0, "", 0)));
        }
        slots.set(10, slot(10, -1, "minecraft:leather_helmet", "Test Helmet", 1));
        slots.set(46, slot(46, -1, "minecraft:paper", "Quick Actions", 1));
        slots.set(47, slot(47, -1, "minecraft:paper", "Storage Type", 1));
        slots.set(51, slot(51, -1, "minecraft:paper", "Page 1 <<<<<", 1));
        slots.set(52, slot(52, -1, "minecraft:paper", "Page 3 >>>>>", 1));
        return new UiActionService.GuiSnapshot(1L,
            "net.minecraft.client.gui.screens.inventory.ContainerScreen",
            "net.minecraft.world.inventory.ChestMenu", "", 5, 9, 48, 165L, List.copyOf(slots),
            new UiActionService.ItemSnapshot(true, "", "", 0, "", 0));
    }

    private static UiActionService.SlotSnapshot slot(int menuSlot, int inventorySlot, String itemId, String name, int count) {
        return new UiActionService.SlotSnapshot(menuSlot, inventorySlot, true, true,
            new UiActionService.ItemSnapshot(false, itemId, name, count, "{}", 64));
    }

    private static UiActionService.GuiSnapshot snapshot(
        UiActionService.GuiSnapshot original,
        List<UiActionService.SlotSnapshot> slots,
        UiActionService.ItemSnapshot carried
    ) {
        return new UiActionService.GuiSnapshot(original.capturedAt(), original.screenClass(), original.menuClass(),
            original.title(), original.screenIdentity(), original.syncId(), original.stateId(), original.stateRevision(),
            List.copyOf(slots), carried);
    }

    private static Set<String> toolNames(String body) {
        Map<String, Object> rpc = Json.object(Json.parse(body));
        Map<String, Object> result = Json.object(rpc.get("result"));
        List<Map<String, Object>> toolList = tools(result);
        return names(toolList);
    }

    private static HttpResponse request(int port, String host, String extraHeader, String authorization, byte[] body) throws IOException {
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            socket.setSoTimeout(3000);
            var out = socket.getOutputStream();
            StringBuilder headers = new StringBuilder()
                .append("POST /mcp HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n");
            if (extraHeader != null) headers.append(extraHeader);
            if (authorization != null) headers.append("Authorization: ").append(authorization).append("\r\n");
            headers.append("\r\n");
            out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(response);
            String raw = response.toString(StandardCharsets.UTF_8);
            int firstLineEnd = raw.indexOf("\r\n");
            String statusLine = firstLineEnd < 0 ? raw : raw.substring(0, firstLineEnd);
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            int bodyStart = raw.indexOf("\r\n\r\n");
            return new HttpResponse(status, bodyStart < 0 ? "" : raw.substring(bodyStart + 4));
        }
    }

    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static void expectGate(String code, String message, Runnable action) {
        try {
            action.run();
        } catch (UiActionGate.GateException e) {
            check(code.equals(e.code()), "expected UI gate code " + code);
            if (message != null) check(message.equals(e.getMessage()), "expected safe gate message");
            return;
        }
        throw new AssertionError("Expected UI gate exception " + code);
    }

    private static void expectUiCode(String code, Runnable action) {
        try {
            action.run();
        } catch (UiActionService.UiActionException e) {
            check(code.equals(e.code()), "expected UI validation code " + code);
            return;
        }
        throw new AssertionError("Expected UI action validation exception " + code);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private record HttpResponse(int status, String body) {}

    private static final class FakeClock {
        private final AtomicLong nanos = new AtomicLong();
        private final AtomicLong millis = new AtomicLong(1_000_000L);

        UiActionGate newGate() {
            return new UiActionGate(nanos::get, millis::get, 750L);
        }

        void advanceMillis(long amount) {
            nanos.addAndGet(amount * 1_000_000L);
            millis.addAndGet(amount);
        }
    }
}
