package dev.tanaka.wynnaibridge.translation;

import com.mojang.logging.LogUtils;
import dev.tanaka.wynnaibridge.http.Json;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Shared asynchronous translation worker with an independent persistent cache per feature. */
public final class TranslationService {
    public static final TranslationService INSTANCE = new TranslationService();
    public static final String TOOLTIP_NAMESPACE = "tooltip";
    public static final String DIALOGUE_NAMESPACE = "dialogue";
    public static final String ABILITY_TREE_NAMESPACE = "ability_tree";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ConcurrentMap<String, Namespace> namespaces = new ConcurrentHashMap<>();
    private volatile TranslationProvider provider;
    private volatile ExecutorService executor;

    private TranslationService() {}

    public synchronized void registerNamespace(
        String namespaceId,
        Path persistentCacheFile,
        TranslationProvider translationProvider,
        Function<String, java.util.Optional<String>> glossary,
        boolean glossaryBeforeCache
    ) {
        if (namespaceId == null || namespaceId.isBlank()) throw new IllegalArgumentException("Translation namespace is required");
        if (namespaces.containsKey(namespaceId)) return;

        if (provider == null) provider = translationProvider;
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(task -> {
                Thread worker = new Thread(task, "Wynn-AI-Bridge-translation");
                worker.setDaemon(true);
                return worker;
            });
        }

        Namespace namespace = new Namespace(namespaceId, persistentCacheFile, glossary, glossaryBeforeCache);
        namespace.loadCache();
        namespaces.put(namespaceId, namespace);
        LOGGER.info(
            "[WAB {}] translation namespace ready; DeepL configured={}, cached translations loaded={}",
            namespace.label,
            provider != null && provider.isConfigured(),
            namespace.cache.size()
        );
    }

    /** Returns a fixed glossary/cache hit immediately, or queues at most one DeepL request per namespaced key. */
    public String findCachedOrQueue(String namespaceId, String sourceText) {
        return findCachedOrQueue(namespaceId, sourceText, sourceText);
    }

    /**
     * Looks up translations by the original source while sending a separately protected string to the provider.
     * This lets feature-specific preprocessors keep styles, glyphs, values, and terminology out of API requests.
     */
    public String findCachedOrQueue(String namespaceId, String sourceText, String providerText) {
        if (sourceText == null || sourceText.isBlank()) return null;
        if (providerText == null || providerText.isBlank()) return null;
        Namespace namespace = namespaces.get(namespaceId);
        if (namespace == null) return null;

        String key = normalize(sourceText);
        if (key.isEmpty()) return null;

        if (namespace.glossaryBeforeCache) {
            String fixed = glossaryTranslation(namespace, key);
            if (fixed != null) return fixed;
        }

        String translated = namespace.cache.get(key);
        if (translated != null) {
            if (namespace.loggedCacheHits.add(key)) {
                LOGGER.info("[WAB {}] cache hit source={}", namespace.label, key);
            }
            return translated;
        }

        if (!namespace.glossaryBeforeCache) {
            String fixed = glossaryTranslation(namespace, key);
            if (fixed != null) return fixed;
        }

        TranslationProvider currentProvider = provider;
        ExecutorService currentExecutor = executor;
        if (currentProvider == null || currentExecutor == null) return null;
        if (!currentProvider.isConfigured()) {
            if (namespace.warnedAboutMissingKey.compareAndSet(false, true)) {
                LOGGER.warn("[WAB {}] DeepL is not configured; set DEEPL_AUTH_KEY or deepl.authKey for uncached text", namespace.label);
            }
            return null;
        }

        if (namespace.attempted.contains(key) || !namespace.pending.add(key)) return null;
        try {
            currentExecutor.execute(() -> translateInBackground(namespace, key, providerText, currentProvider));
        } catch (RuntimeException rejected) {
            namespace.pending.remove(key);
            LOGGER.warn("[WAB {}] translation queue rejected source={}", namespace.label, key);
        }
        return null;
    }

    private static String glossaryTranslation(Namespace namespace, String key) {
        if (namespace.glossary == null) return null;
        return namespace.glossary.apply(key).filter(value -> !value.isBlank()).orElse(null);
    }

    private void translateInBackground(
        Namespace namespace,
        String key,
        String providerText,
        TranslationProvider currentProvider
    ) {
        try {
            if (namespace.cache.containsKey(key) || !namespace.attempted.add(key)) return;
            // Save the attempted key before HTTP so the same text is never retried after restart.
            if (!namespace.saveCache()) return;

            TranslationTextProtector.ProtectedText protectedText = TranslationTextProtector.protect(providerText);
            long requestNumber = namespace.deepLRequests.incrementAndGet();
            LOGGER.info("[WAB {}] DeepL request #{} source={}", namespace.label, requestNumber, key);
            String apiText = currentProvider.translate(protectedText.text(), "JA");
            String translated = protectedText.restore(apiText);
            if (translated.isBlank() || translated.equals(providerText)) {
                LOGGER.warn("[WAB {}] DeepL returned no changed text for source={}", namespace.label, key);
                return;
            }

            namespace.cache.put(key, translated);
            namespace.saveCache();
            LOGGER.info("[WAB {}] translation cached source={} target={}", namespace.label, key, translated);
        } catch (Exception error) {
            LOGGER.warn(
                "[WAB {}] DeepL request failed; original text remains visible and will not be resent: {}",
                namespace.label,
                error.getClass().getSimpleName()
            );
        } finally {
            namespace.pending.remove(key);
        }
    }

    public static String normalize(String sourceText) {
        return Normalizer.normalize(sourceText, Normalizer.Form.NFKC).replaceAll("\\s+", " ").strip();
    }

    private static final class Namespace {
        private final String id;
        private final String label;
        private final Path cacheFile;
        private final Function<String, java.util.Optional<String>> glossary;
        private final boolean glossaryBeforeCache;
        private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();
        private final java.util.Set<String> attempted = ConcurrentHashMap.newKeySet();
        private final java.util.Set<String> pending = ConcurrentHashMap.newKeySet();
        private final java.util.Set<String> loggedCacheHits = ConcurrentHashMap.newKeySet();
        private final java.util.concurrent.atomic.AtomicBoolean warnedAboutMissingKey = new java.util.concurrent.atomic.AtomicBoolean();
        private final AtomicLong deepLRequests = new AtomicLong();

        private Namespace(
            String id,
            Path cacheFile,
            Function<String, java.util.Optional<String>> glossary,
            boolean glossaryBeforeCache
        ) {
            this.id = id;
            this.label = Character.toUpperCase(id.charAt(0)) + id.substring(1);
            this.cacheFile = cacheFile;
            this.glossary = glossary;
            this.glossaryBeforeCache = glossaryBeforeCache;
        }

        private void loadCache() {
            if (cacheFile == null || !Files.isRegularFile(cacheFile)) return;
            try {
                Map<String, Object> root = Json.object(Json.parse(Files.readString(cacheFile, StandardCharsets.UTF_8)));
                Object rawTranslations = root.get("translations");
                if (rawTranslations instanceof Map<?, ?> translations) {
                    for (Map.Entry<?, ?> entry : translations.entrySet()) {
                        if (entry.getKey() instanceof String source && entry.getValue() instanceof String target
                            && !source.isBlank() && !target.isBlank()) {
                            cache.put(normalize(source), target);
                        }
                    }
                }
                Object rawAttempts = root.get("attemptedSources");
                if (rawAttempts instanceof java.util.List<?> attempts) {
                    for (Object attempt : attempts) {
                        if (attempt instanceof String source && !source.isBlank()) attempted.add(normalize(source));
                    }
                }
            } catch (Exception error) {
                LOGGER.warn("[WAB {}] could not read translation cache; starting empty: {}", label, error.getClass().getSimpleName());
            }
        }

        private boolean saveCache() {
            if (cacheFile == null) return false;
            Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            try {
                Files.createDirectories(cacheFile.getParent());
                Map<String, Object> document = Map.of(
                    "schemaVersion", 1,
                    "targetLanguage", "JA",
                    "translations", new TreeMap<>(cache),
                    "attemptedSources", attempted.stream().sorted().toList()
                );
                Files.writeString(temporary, Json.stringify(document), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (IOException error) {
                LOGGER.warn("[WAB {}] could not save translation cache: {}", label, error.getClass().getSimpleName());
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A stale temporary file does not affect cache loading.
                }
                return false;
            }
        }
    }
}
