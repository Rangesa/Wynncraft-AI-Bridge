package dev.tanaka.wynnaibridge.knowledge;

import dev.tanaka.wynnaibridge.ModVersion;
import dev.tanaka.wynnaibridge.http.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Public, read-only Wynncraft API client for class and Ability Tree data. */
public final class WynnAbilityDataService {
    public static final WynnAbilityDataService INSTANCE = new WynnAbilityDataService();

    private static final String API_BASE = "https://api.wynncraft.com/v3";
    private static final Duration STATIC_TTL = Duration.ofHours(24);
    private static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final List<String> CLASSES = List.of("archer", "warrior", "mage", "assassin", "shaman");

    private final Map<String, Cached<Map<String, Object>>> staticCache = new ConcurrentHashMap<>();
    private final Map<String, PlayerAbilitiesResult> playerAbilitiesCache = new ConcurrentHashMap<>();
    private volatile Path cacheDirectory;
    private volatile Duration timeout = Duration.ofSeconds(8);
    private volatile HttpClient client = newClient(timeout);
    private volatile boolean enabled = true;
    private volatile String lastError = "";

    private WynnAbilityDataService() {}

    public void initialize(Path cacheDirectory, boolean enabled, Duration timeout) {
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        this.enabled = enabled;
        this.timeout = clamp(timeout, Duration.ofSeconds(3), Duration.ofSeconds(15));
        this.client = newClient(this.timeout);
    }

    public static List<String> classIds() {
        return CLASSES;
    }

    public DataResult<AbilityTree> getAbilityTree(String classId) {
        String normalized = normalizeClass(classId);
        String key = "tree:" + normalized;
        Cached<Map<String, Object>> cached = getStatic(key, "ability-tree-" + normalized + ".json",
            "/ability/tree/" + normalized, WynnAbilityDataService::validTree);
        if (cached == null) return DataResult.failure("Ability Tree data is not available from the official Wynncraft API or local cache.");
        try {
            return DataResult.success(toTree(normalized, cached.value()), cached.fetchedAt(), cached.stale(),
                cached.stale() ? "CACHE_STALE" : cached.source());
        } catch (RuntimeException e) {
            return DataResult.failure("The cached official Ability Tree response could not be interpreted.");
        }
    }

    public DataResult<Map<String, Object>> getClassInfo(String classId) {
        String normalized = normalizeClass(classId);
        Cached<Map<String, Object>> cached = getStatic("class:" + normalized, "class-info-" + normalized + ".json",
            "/classes/" + normalized, value -> !value.isEmpty());
        if (cached == null) return DataResult.failure("Class data is not available from the official Wynncraft API or local cache.");
        return DataResult.success(cached.value(), cached.fetchedAt(), cached.stale(),
            cached.stale() ? "CACHE_STALE" : cached.source());
    }

    /** This endpoint is intentionally uncached because it describes a player's current tree. */
    public PlayerAbilitiesResult getPlayerAbilities(String username, String characterUuid) {
        String safeUsername = validateUsername(username);
        String safeUuid = validateUuid(characterUuid);
        String cacheKey = safeUsername.toLowerCase(Locale.ROOT) + ":" + safeUuid.toLowerCase(Locale.ROOT);
        PlayerAbilitiesResult cached = playerAbilitiesCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.fetchedAt() < 30_000L) return cached;
        try {
            String path = "/player/" + encodePath(safeUsername) + "/characters/" + safeUuid + "/abilities";
            Object parsed = Json.parse(get(path));
            List<Map<String, Object>> nodes = normalizePlayerAbilities(parsed);
            PlayerAbilitiesResult result = new PlayerAbilitiesResult(true, "", safeUsername, safeUuid, now, nodes);
            playerAbilitiesCache.put(cacheKey, result);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "Official Wynncraft API request was interrupted.";
            return new PlayerAbilitiesResult(false, "Official Wynncraft API request was interrupted.", safeUsername,
                safeUuid, System.currentTimeMillis(), List.of());
        } catch (Exception e) {
            lastError = safeApiError(e);
            return new PlayerAbilitiesResult(false, safeApiError(e), safeUsername, safeUuid,
                System.currentTimeMillis(), List.of());
        }
    }

    public CacheStatus cacheStatus() {
        long now = System.currentTimeMillis();
        List<CacheEntryStatus> entries = staticCache.entrySet().stream().map(entry ->
            new CacheEntryStatus(entry.getKey(), entry.getValue().fetchedAt(),
                Math.max(0L, now - entry.getValue().fetchedAt()), entry.getValue().stale(),
                entry.getValue().stale() ? "CACHE_STALE" : entry.getValue().source())
        ).sorted(Comparator.comparing(CacheEntryStatus::key)).toList();
        long oldest = entries.stream().mapToLong(CacheEntryStatus::fetchedAt).filter(value -> value > 0L).min().orElse(0L);
        return new CacheStatus(enabled, entries.size(), oldest == 0L ? 0L : Math.max(0L, now - oldest), entries, lastError);
    }

    public synchronized void clearCache() throws IOException {
        staticCache.clear();
        playerAbilitiesCache.clear();
        Path directory = cacheDirectory;
        if (directory != null && Files.isDirectory(directory)) {
            for (String classId : CLASSES) {
                Files.deleteIfExists(directory.resolve("ability-tree-" + classId + ".json"));
                Files.deleteIfExists(directory.resolve("class-info-" + classId + ".json"));
            }
        }
        lastError = "";
    }

    private synchronized Cached<Map<String, Object>> getStatic(
        String key, String fileName, String apiPath, Validator validator
    ) {
        long now = System.currentTimeMillis();
        Cached<Map<String, Object>> memory = staticCache.get(key);
        if (memory != null && !memory.stale() && now - memory.fetchedAt() < STATIC_TTL.toMillis()) return memory;

        Path file = cacheDirectory == null ? null : cacheDirectory.resolve(fileName);
        Cached<Map<String, Object>> disk = memory;
        if (disk == null && file != null) disk = readStatic(file, validator);
        if (disk != null && now - disk.fetchedAt() < STATIC_TTL.toMillis()) {
            Cached<Map<String, Object>> fresh = new Cached<>(disk.value(), disk.fetchedAt(), false, disk.source());
            staticCache.put(key, fresh);
            return fresh;
        }

        if (!enabled) {
            if (disk != null) {
                Cached<Map<String, Object>> stale = new Cached<>(disk.value(), disk.fetchedAt(), true, "CACHE");
                staticCache.put(key, stale);
                return stale;
            }
            return null;
        }

        try {
            String raw = get(apiPath);
            Map<String, Object> parsed = Json.object(Json.parse(raw));
            if (!validator.valid(parsed)) throw new IOException("Unexpected official API response shape");
            long fetchedAt = System.currentTimeMillis();
            if (file != null) writeStatic(file, raw);
            Cached<Map<String, Object>> result = new Cached<>(parsed, fetchedAt, false, "NETWORK");
            staticCache.put(key, result);
            return result;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            lastError = safeApiError(e);
            if (disk != null) {
                Cached<Map<String, Object>> stale = new Cached<>(disk.value(), disk.fetchedAt(), true, "CACHE");
                staticCache.put(key, stale);
                return stale;
            }
            return null;
        }
    }

    private Cached<Map<String, Object>> readStatic(Path file, Validator validator) {
        try {
            if (!Files.isRegularFile(file)) return null;
            Map<String, Object> value = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            if (!validator.valid(value)) return null;
            return new Cached<>(value, Files.getLastModifiedTime(file).toMillis(), false, "CACHE");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeStatic(Path file, String raw) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, raw, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + path))
            .GET()
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("User-Agent", "Wynn-AI-Bridge/" + ModVersion.get() + " (read-only official Wynncraft API client)")
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Official Wynncraft API returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static AbilityTree toTree(String classId, Map<String, Object> response) {
        Map<String, Object> archetypes = object(response.get("archetypes"));
        Map<String, Object> pagesObject = object(response.get("pages"));
        List<AbilityNode> nodes = new ArrayList<>();
        for (var pageEntry : pagesObject.entrySet()) {
            Integer page = integer(pageEntry.getKey());
            if (page == null) continue;
            for (var nodeEntry : object(pageEntry.getValue()).entrySet()) {
                Map<String, Object> raw = object(nodeEntry.getValue());
                if (raw.isEmpty()) continue;
                Map<String, Object> icon = object(raw.get("icon"));
                Map<String, Object> iconValue = object(icon.get("value"));
                Map<String, Object> model = object(iconValue.get("customModelData"));
                Map<String, Object> coordinates = object(raw.get("coordinates"));
                nodes.add(new AbilityNode(
                    nodeEntry.getKey(), plainText(string(raw.get("name"))),
                    integer(raw.get("slot")), integer(coordinates.get("x")), integer(coordinates.get("y")), page,
                    strings(raw.get("description")), object(raw.get("requirements")),
                    strings(raw.get("links")), strings(raw.get("locks")), string(iconValue.get("id")),
                    string(iconValue.get("name")), integerList(model.get("rangeDispatch"))
                ));
            }
        }
        nodes.sort(Comparator.comparingInt(AbilityNode::page).thenComparingInt(node -> node.slot() == null ? Integer.MAX_VALUE : node.slot()));
        return new AbilityTree(classId.toUpperCase(Locale.ROOT), System.currentTimeMillis(),
            archetypes, nodes, pagesObject.keySet().stream().sorted().toList());
    }

    private static boolean validTree(Map<String, Object> value) {
        if (!(value.get("pages") instanceof Map<?, ?> pages) || pages.isEmpty()) return false;
        return pages.values().stream().anyMatch(page -> page instanceof Map<?, ?> map && !map.isEmpty());
    }

    public static List<Map<String, Object>> normalizePlayerAbilities(Object parsed) {
        if (parsed instanceof Map<?, ?> map) {
            Map<String, Object> root = Json.object(map);
            if (root.get("abilities") instanceof Collection<?> collection) {
                return normalizeNodeCollection(collection, null);
            }
            List<Map<String, Object>> grouped = new ArrayList<>();
            for (var pageEntry : root.entrySet()) {
                Integer page = integer(pageEntry.getKey());
                if (page == null || !(pageEntry.getValue() instanceof Collection<?> collection)) continue;
                grouped.addAll(normalizeNodeCollection(collection, page));
            }
            return List.copyOf(grouped);
        }
        if (!(parsed instanceof Collection<?> collection)) return List.of();
        return normalizeNodeCollection(collection, null);
    }

    private static List<Map<String, Object>> normalizeNodeCollection(Collection<?> collection, Integer page) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Object value : collection) {
            if (!(value instanceof Map<?, ?>)) continue;
            Map<String, Object> raw = Json.object(value);
            Map<String, Object> meta = object(raw.get("meta"));
            Map<String, Object> normalized = new LinkedHashMap<>(raw);
            if (!normalized.containsKey("id") && meta.get("id") != null) normalized.put("id", meta.get("id"));
            if (!normalized.containsKey("page") && meta.get("page") != null) normalized.put("page", meta.get("page"));
            if (!normalized.containsKey("page") && page != null) normalized.put("page", page);
            nodes.add(java.util.Collections.unmodifiableMap(normalized));
        }
        return List.copyOf(nodes);
    }

    private static String normalizeClass(String classId) {
        String value = classId == null ? "" : classId.strip().toLowerCase(Locale.ROOT);
        if (!CLASSES.contains(value)) throw new IllegalArgumentException("classId must be one of archer, warrior, mage, assassin, shaman");
        return value;
    }

    private static String validateUsername(String username) {
        String value = username == null ? "" : username.strip();
        if (value.isEmpty() || value.length() > 36
            || (!value.matches("[A-Za-z0-9_]{1,32}") && !UUID.matcher(value).matches())) {
            throw new IllegalArgumentException("username must be a Wynncraft player username or UUID");
        }
        return value;
    }

    private static String validateUuid(String uuid) {
        String value = uuid == null ? "" : uuid.strip();
        if (!UUID.matcher(value).matches()) throw new IllegalArgumentException("characterUuid must be a UUID");
        return value;
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String plainText(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)<[^>]*>", "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .strip();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : collection) if (item != null) result.add(plainText(String.valueOf(item)));
        return List.copyOf(result);
    }

    private static List<Integer> integerList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        List<Integer> result = new ArrayList<>();
        for (Object item : collection) {
            Integer parsed = integer(item);
            if (parsed != null) result.add(parsed);
        }
        return List.copyOf(result);
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string) {
            try { return Integer.parseInt(string); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? Json.object(value) : Map.of();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String safeApiError(Exception error) {
        if (error instanceof IOException && error.getMessage() != null && error.getMessage().startsWith("Official Wynncraft API")) {
            return error.getMessage();
        }
        return "Official Wynncraft API request failed (" + error.getClass().getSimpleName() + ").";
    }

    private static HttpClient newClient(Duration timeout) {
        return HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    private static Duration clamp(Duration value, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0) return min;
        return value.compareTo(max) > 0 ? max : value;
    }

    @FunctionalInterface
    private interface Validator {
        boolean valid(Map<String, Object> value);
    }

    private record Cached<T>(T value, long fetchedAt, boolean stale, String source) {}

    public record AbilityTree(
        String classId,
        long fetchedAt,
        Map<String, Object> archetypes,
        List<AbilityNode> nodes,
        List<String> pages
    ) {}

    public record AbilityNode(
        String id,
        String name,
        Integer slot,
        Integer x,
        Integer y,
        Integer page,
        List<String> description,
        Map<String, Object> requirements,
        List<String> links,
        List<String> locks,
        String iconItemId,
        String iconName,
        List<Integer> iconModelIds
    ) {}

    public record DataResult<T>(boolean ok, String error, long cachedAt, boolean stale, String source, T data) {
        static <T> DataResult<T> success(T data, long cachedAt, boolean stale, String source) {
            return new DataResult<>(true, "", cachedAt, stale, source, data);
        }
        static <T> DataResult<T> failure(String error) {
            return new DataResult<>(false, error, 0L, false, "unavailable", null);
        }
    }

    public record PlayerAbilitiesResult(
        boolean ok,
        String error,
        String username,
        String characterUuid,
        long fetchedAt,
        List<Map<String, Object>> abilities
    ) {}

    public record CacheStatus(boolean enabled, int staticEntries, long oldestCacheAgeMillis,
                              List<CacheEntryStatus> entries, String lastError) {}

    public record CacheEntryStatus(String key, long fetchedAt, long ageMillis, boolean stale, String source) {}
}
