package dev.tanaka.wynnaibridge;

import dev.tanaka.wynnaibridge.capture.MessageStore;
import dev.tanaka.wynnaibridge.config.BridgeConfig;
import dev.tanaka.wynnaibridge.http.BridgeHttpServer;
import dev.tanaka.wynnaibridge.knowledge.WynnKnowledgeService;
import dev.tanaka.wynnaibridge.knowledge.WynnAbilityDataService;
import dev.tanaka.wynnaibridge.state.StateCollector;
import dev.tanaka.wynnaibridge.state.UiStateRevisionTracker;
import dev.tanaka.wynnaibridge.ui.UiActionGate;
import dev.tanaka.wynnaibridge.translation.DeepLTranslationProvider;
import dev.tanaka.wynnaibridge.translation.DialogueTranslationGlossary;
import dev.tanaka.wynnaibridge.translation.TranslationService;
import dev.tanaka.wynnaibridge.translation.TooltipTranslationService;
import dev.tanaka.wynnaibridge.translation.AbilityTreeTranslationGlossary;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.time.Duration;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WynnAiBridgeClient implements ClientModInitializer {
    public static final String MOD_ID = "wynn_ai_bridge";
    private static final int BRIDGE_RETRY_INTERVAL_TICKS = 200;
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static volatile WynnAiBridgeClient instance;
    private static volatile BridgeConfig activeConfig;
    private volatile BridgeHttpServer httpServer;
    private int tickCounter;
    private int diagnosticsTickCounter;
    private int knowledgeTickCounter;
    private int bridgeRetryTicks;

    @Override
    public void onInitializeClient() {
        instance = this;
        BridgeConfig config = BridgeConfig.load();
        activeConfig = config;

        DeepLTranslationProvider translationProvider = new DeepLTranslationProvider(
            FabricLoader.getInstance().getConfigDir().resolve("wynn-ai-bridge.properties")
        );
        TooltipTranslationService.INSTANCE.initialize(
            FabricLoader.getInstance().getConfigDir().resolve("wynn-ai-bridge-cache/tooltip-translations-ja.json"),
            translationProvider
        );
        TranslationService.INSTANCE.registerNamespace(
            TranslationService.DIALOGUE_NAMESPACE,
            FabricLoader.getInstance().getConfigDir().resolve("wynn-ai-bridge-cache/dialogue-translations-ja.json"),
            translationProvider,
            DialogueTranslationGlossary::translate,
            true
        );
        TranslationService.INSTANCE.registerNamespace(
            TranslationService.ABILITY_TREE_NAMESPACE,
            FabricLoader.getInstance().getConfigDir().resolve("wynn-ai-bridge-cache/ability-tree-translations-ja.json"),
            translationProvider,
            AbilityTreeTranslationGlossary::translate,
            true
        );

        WynnKnowledgeService.INSTANCE.initialize(
            FabricLoader.getInstance().getConfigDir().resolve("wynn-ai-bridge-cache"),
            config.knowledgeEnabled(),
            config.wikiEnabled(),
            Duration.ofMinutes(config.knowledgeRefreshMinutes()),
            Duration.ofSeconds(config.knowledgeHttpTimeoutSeconds())
        );
        WynnAbilityDataService.INSTANCE.initialize(
            FabricLoader.getInstance().getConfigDir().resolve("wynn-ai-bridge-cache/official-ability"),
            config.knowledgeEnabled(),
            Duration.ofSeconds(config.knowledgeHttpTimeoutSeconds())
        );

        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
            MessageStore.INSTANCE.add(overlay ? "actionbar/game" : "game", message.getString())
        );

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
            MessageStore.INSTANCE.add("chat", message.getString())
        );

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var actions = ClientCommands.literal("actions");
            var arm = ClientCommands.literal("arm").executes(context -> {
                var client = context.getSource().getClient();
                if (!config.allowUiActions()) {
                    context.getSource().sendError(Component.literal("UI actions are disabled in the mod config."));
                    return 0;
                }
                if (client.player == null || client.level == null || client.getConnection() == null) {
                    context.getSource().sendError(Component.literal("Join a Minecraft world before arming UI actions."));
                    return 0;
                }
                try {
                    long expiresAt = UiActionGate.INSTANCE.arm(client.level, config.uiActionsMaxArmSeconds());
                    context.getSource().sendFeedback(Component.literal(
                        "Wynn AI Bridge inventory actions armed for 1 action and " +
                            config.uiActionsMaxArmSeconds() + " seconds (expires at " + expiresAt + ")."
                    ));
                    return 1;
                } catch (UiActionGate.GateException e) {
                    context.getSource().sendError(Component.literal(e.getMessage()));
                    return 0;
                }
            });
            for (UiActionGate.Category category : UiActionGate.Category.values()) {
                UiActionGate.Category selected = category;
                arm.then(ClientCommands.literal(selected.commandName()).then(
                    ClientCommands.argument("count", IntegerArgumentType.integer(1, selected.maximum()))
                        .executes(context -> {
                            var client = context.getSource().getClient();
                            if (!config.allowUiActions()) {
                                context.getSource().sendError(Component.literal("UI actions are disabled in the mod config."));
                                return 0;
                            }
                            if (client.player == null || client.level == null || client.getConnection() == null) {
                                context.getSource().sendError(Component.literal("Join a Minecraft world before arming UI actions."));
                                return 0;
                            }
                            int count = IntegerArgumentType.getInteger(context, "count");
                            try {
                                long expiresAt = UiActionGate.INSTANCE.arm(
                                    client.level, config.uiActionsMaxArmSeconds(), selected, count
                                );
                                context.getSource().sendFeedback(Component.literal(
                                    "Wynn AI Bridge " + selected.commandName() + " actions armed for " + count +
                                        " action(s) and " + config.uiActionsMaxArmSeconds() +
                                        " seconds (expires at " + expiresAt + ")."
                                ));
                                return 1;
                            } catch (UiActionGate.GateException e) {
                                context.getSource().sendError(Component.literal(e.getMessage()));
                                return 0;
                            }
                        })));
            }
            actions.then(arm).then(ClientCommands.literal("disarm").executes(context -> {
                UiActionGate.INSTANCE.disarm();
                context.getSource().sendFeedback(Component.literal("Wynn AI Bridge UI actions disarmed."));
                return 1;
            }));
            dispatcher.register(ClientCommands.literal("wynnbridge").then(actions));
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> UiActionGate.INSTANCE.disarm());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            UiStateRevisionTracker.INSTANCE.refresh(client);
            UiActionGate.INSTANCE.observeWorld(
                client.level,
                client.level != null && client.player != null && client.getConnection() != null
            );

            if (++tickCounter >= 4) {
                tickCounter = 0;
                StateCollector.INSTANCE.capture(client);
            }

            // Re-check index freshness once per minute. Network fetches only occur when stale.
            if (++knowledgeTickCounter >= 1200) {
                knowledgeTickCounter = 0;
                WynnKnowledgeService.INSTANCE.refreshAsync(false);
            }

            // A lightweight periodic hint for the common "require=0 silently matched nothing" failure.
            if (++diagnosticsTickCounter >= 600) {
                diagnosticsTickCounter = 0;
                if (client.player != null && dev.tanaka.wynnaibridge.capture.TextCaptureStore.INSTANCE.stats().isEmpty()) {
                    LOGGER.warn("No GUI text capture source has fired yet. Check GET /health captureSources; a 26.2 mapping/signature change may have disabled the text mixins.");
                }
            }

            if (httpServer == null && ++bridgeRetryTicks >= BRIDGE_RETRY_INTERVAL_TICKS) {
                bridgeRetryTicks = 0;
                startHttpServer(config, true);
            }
        });

        startHttpServer(config, false);
    }

    public static BridgeConfig currentConfig() { return activeConfig; }
    public static boolean bridgeRunning() { return instance != null && instance.httpServer != null; }

    private void startHttpServer(BridgeConfig config, boolean retry) {
        try {
            BridgeHttpServer candidate = new BridgeHttpServer(config);
            candidate.start();
            httpServer = candidate;
            if (config.tokenAutoGenerated()) {
                LOGGER.info("Generated an action bearer token and saved it to config/wynn-ai-bridge.properties");
            }
            LOGGER.info(
                "Wynn AI Bridge listening on http://127.0.0.1:{} (MCP: /mcp, actions={}, tokenConfigured={}, knowledge={}, wiki={})",
                config.port(), config.allowActions(), !config.token().isEmpty(), config.knowledgeEnabled(), config.wikiEnabled()
            );
        } catch (Exception e) {
            if (retry) {
                LOGGER.warn("Wynn AI Bridge HTTP server is still unavailable; will retry in 10 seconds: {}", e.toString());
            } else {
                LOGGER.error("Failed to start Wynn AI Bridge HTTP server", e);
            }
        }
    }
}
