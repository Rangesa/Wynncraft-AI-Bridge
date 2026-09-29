package dev.tanaka.wynnaibridge.translation;

import java.nio.file.Path;

/** Compatibility entry point for the item-tooltip translator. */
public final class TooltipTranslationService {
    public static final TooltipTranslationService INSTANCE = new TooltipTranslationService();

    private TooltipTranslationService() {}

    public void initialize(Path persistentCacheFile, TranslationProvider translationProvider) {
        TranslationService.INSTANCE.registerNamespace(
            TranslationService.TOOLTIP_NAMESPACE,
            persistentCacheFile,
            translationProvider,
            TooltipTranslationGlossary::translate,
            true
        );
    }

    /** Returns a tooltip translation immediately or queues one shared asynchronous request. */
    public String findCachedOrQueue(String sourceText) {
        return TranslationService.INSTANCE.findCachedOrQueue(TranslationService.TOOLTIP_NAMESPACE, sourceText);
    }

    /** Uses a stable cache key and separately protected provider text for the component-run translator. */
    public String findCachedOrQueue(String sourceText, String providerText) {
        return TranslationService.INSTANCE.findCachedOrQueue(
            TranslationService.TOOLTIP_NAMESPACE, sourceText, providerText
        );
    }

    public static String normalize(String sourceText) {
        return TranslationService.normalize(sourceText);
    }
}
