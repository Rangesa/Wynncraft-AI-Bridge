package dev.tanaka.wynnaibridge.translation;

import dev.tanaka.wynnaibridge.http.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** DeepL's text-translation endpoint. The API key is read from configuration or the environment. */
public final class DeepLTranslationProvider implements TranslationProvider {
    private static final String DEFAULT_PRO_ENDPOINT = "https://api.deepl.com/v2/translate";
    private static final String DEFAULT_FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate";

    private final String apiKey;
    private final URI endpoint;
    private final HttpClient httpClient;

    public DeepLTranslationProvider(Path propertiesFile) {
        Properties properties = new Properties();
        if (propertiesFile != null && Files.isRegularFile(propertiesFile)) {
            try (InputStream input = Files.newInputStream(propertiesFile)) {
                properties.load(input);
            } catch (IOException ignored) {
                // An unreadable optional config file leaves the provider unconfigured.
            }
        }

        this.apiKey = firstNonBlank(
            System.getenv("DEEPL_AUTH_KEY"),
            System.getenv("DEEPL_API_KEY"),
            properties.getProperty("deepl.authKey")
        );
        String endpointSetting = firstNonBlank(
            System.getenv("DEEPL_API_URL"),
            properties.getProperty("deepl.endpoint")
        );
        String defaultEndpoint = apiKey != null && apiKey.endsWith(":fx")
            ? DEFAULT_FREE_ENDPOINT
            : DEFAULT_PRO_ENDPOINT;
        this.endpoint = URI.create(endpointSetting == null ? defaultEndpoint : endpointSetting);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null;
    }

    @Override
    public String translate(String sourceText, String targetLanguage) throws IOException, InterruptedException {
        if (!isConfigured()) throw new IllegalStateException("DeepL API key is not configured");

        String body = Json.stringify(Map.of(
            "text", List.of(sourceText),
            "target_lang", targetLanguage
        ));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(25))
            .header("Authorization", "DeepL-Auth-Key " + apiKey)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("DeepL returned HTTP " + response.statusCode());
        }

        Map<String, Object> payload = Json.object(Json.parse(response.body()));
        Object rawTranslations = payload.get("translations");
        if (!(rawTranslations instanceof List<?> translations) || translations.isEmpty()) {
            throw new IOException("DeepL response did not contain a translation");
        }
        Map<String, Object> translation = Json.object(translations.getFirst());
        Object text = translation.get("text");
        if (!(text instanceof String translated) || translated.isBlank()) {
            throw new IOException("DeepL response contained an empty translation");
        }
        return translated;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
