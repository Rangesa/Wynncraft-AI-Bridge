package dev.tanaka.wynnaibridge.translation;

/** Translates one protected tooltip text fragment into a requested language. */
public interface TranslationProvider {
    boolean isConfigured();

    String translate(String sourceText, String targetLanguage) throws Exception;
}
