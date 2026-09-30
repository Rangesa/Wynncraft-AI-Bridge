package dev.tanaka.wynnaibridge;

import dev.tanaka.wynnaibridge.config.BridgeConfig;
import dev.tanaka.wynnaibridge.http.McpEndpoint;
import dev.tanaka.wynnaibridge.knowledge.WynnAbilityDataService;
import dev.tanaka.wynnaibridge.knowledge.WynnKnowledgeService;
import dev.tanaka.wynnaibridge.ui.UiActionGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Small standard-widget settings and diagnostics screen. Arming remains a local client command. */
public final class WynnAiBridgeSettingsScreen extends Screen {
    private final Screen parent;
    private String feedback = "";

    public WynnAiBridgeSettingsScreen(Screen parent) {
        super(Component.literal("Wynn AI Bridge"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(170, (this.width - 32) / 2);
        int gap = 8;
        int left = (this.width - buttonWidth * 2 - gap) / 2;
        int row1 = this.height - 88;
        int row2 = this.height - 60;
        int row3 = this.height - 32;
        addRenderableWidget(Button.builder(Component.literal("Toggle UI actions"), button -> toggleUiActions())
            .bounds(left, row1, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Arm timeout -30s"), button -> changeTimeout(-30))
            .bounds(left + buttonWidth + gap, row1, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Arm timeout +30s"), button -> changeTimeout(30))
            .bounds(left, row2, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear knowledge cache"), button -> clearCache())
            .bounds(left + buttonWidth + gap, row2, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Copy diagnostic summary"), button -> copySummary())
            .bounds(left, row3, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> Minecraft.getInstance().gui.setScreen(parent))
            .bounds(left + buttonWidth + gap, row3, buttonWidth, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, this.height, 0xD0101018);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int x = 16;
        int y = 16;
        graphics.text(this.font, this.title, x, y, 0xFFFFFFFF);
        y += 20;
        for (String line : diagnostics()) {
            graphics.text(this.font, Component.literal(line), x, y, 0xFFE0E0E0);
            y += 12;
        }
        if (!feedback.isBlank()) graphics.text(this.font, Component.literal(feedback), x, y + 3, 0xFFFFD080);
    }

    private void toggleUiActions() {
        BridgeConfig config = WynnAiBridgeClient.currentConfig();
        if (config == null) { feedback = "Bridge configuration is not ready."; return; }
        save(config, !config.allowUiActions(), config.uiActionsMaxArmSeconds());
    }

    private void changeTimeout(int delta) {
        BridgeConfig config = WynnAiBridgeClient.currentConfig();
        if (config == null) { feedback = "Bridge configuration is not ready."; return; }
        save(config, config.allowUiActions(), config.uiActionsMaxArmSeconds() + delta);
    }

    private void save(BridgeConfig config, boolean allowUiActions, int timeout) {
        try {
            config.updateUiSettings(allowUiActions, timeout);
            feedback = "Saved. UI arm state was cleared when actions were disabled.";
        } catch (IOException error) {
            feedback = "Could not save settings: " + error.getClass().getSimpleName();
        }
    }

    private void clearCache() {
        try {
            WynnKnowledgeService.INSTANCE.clearCache();
            WynnAbilityDataService.INSTANCE.clearCache();
            feedback = "Wynncraft index, class, Ability Tree, and player ability caches cleared.";
        } catch (IOException error) {
            feedback = "Could not clear cache: " + error.getClass().getSimpleName();
        }
    }

    private void copySummary() {
        Minecraft.getInstance().keyboardHandler.setClipboard(String.join("\n", diagnostics()));
        feedback = "Diagnostic summary copied to clipboard.";
    }

    private List<String> diagnostics() {
        BridgeConfig config = WynnAiBridgeClient.currentConfig();
        if (config == null) return List.of("Bridge configuration is not ready.");
        UiActionGate.Status gate = UiActionGate.INSTANCE.status();
        WynnAbilityDataService.CacheStatus cache = WynnAbilityDataService.INSTANCE.cacheStatus();
        WynnKnowledgeService.Status knowledge = WynnKnowledgeService.INSTANCE.status();
        String category = gate.category() == null ? "none" : gate.category().commandName();
        List<String> lines = new ArrayList<>();
        lines.add("Version: " + ModVersion.get() + "    Bridge: " + (WynnAiBridgeClient.bridgeRunning() ? "running" : "stopped"));
        lines.add("Port: " + config.port() + "    Bind: 127.0.0.1    MCP tools: " + McpEndpoint.toolCountForConfig(config));
        lines.add("Bearer actions: " + config.allowActions() + "    UI actions: " + config.allowUiActions());
        lines.add("Max arm time: " + config.uiActionsMaxArmSeconds() + "s    Armed: " + gate.armed() + "    Category: " + category);
        lines.add("Allowance remaining: inventory " + remaining(gate, UiActionGate.Category.INVENTORY)
            + ", bank " + remaining(gate, UiActionGate.Category.BANK)
            + ", skills " + remaining(gate, UiActionGate.Category.SKILLS)
            + ", ability " + remaining(gate, UiActionGate.Category.ABILITY));
        lines.add("Wynn API: " + (knowledge.enabled() ? "enabled" : "disabled") + "    last successful index refresh: "
            + knowledge.lastRefreshSuccessAt() + "    indexed items: " + knowledge.itemCount());
        lines.add("Ability Tree cache: " + (cache.enabled() ? "enabled" : "disabled") + "    entries: " + cache.staticEntries()
            + "    oldest age: " + cache.oldestCacheAgeMillis() + "ms");
        String apiError = !cache.lastError().isBlank() ? cache.lastError() : knowledge.lastError();
        lines.add("Last API error: " + (apiError.isBlank() ? "none" : apiError));
        if (!feedback.isBlank()) lines.add("Status: " + feedback);
        return lines;
    }

    private static int remaining(UiActionGate.Status status, UiActionGate.Category category) {
        return status.armed() && status.category() == category ? status.categoryActionsRemaining() : 0;
    }
}
