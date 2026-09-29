package dev.tanaka.wynnaibridge.translation;

import java.util.Map;
import java.util.Optional;

/** Fixed, context-independent dialogue phrases checked before the dialogue cache and DeepL. */
public final class DialogueTranslationGlossary {
    private static final Map<String, String> FIXED = Map.of(
        "hello.", "こんにちは。",
        "thank you.", "ありがとうございます。",
        "goodbye.", "さようなら。"
    );

    private DialogueTranslationGlossary() {}

    public static Optional<String> translate(String normalizedText) {
        return Optional.ofNullable(FIXED.get(normalizedText.toLowerCase(java.util.Locale.ROOT)));
    }
}
