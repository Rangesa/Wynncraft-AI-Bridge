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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Read-only Wynncraft knowledge layer.
 *
 * The service keeps a small local index of official Wynncraft API data and can
 * perform on-demand searches against the official Wynncraft Wiki's MediaWiki
 * API. All outbound hosts are fixed constants; callers cannot supply arbitrary
 * URLs.
 */
public final class WynnKnowledgeService implements AutoCloseable {
    public static final WynnKnowledgeService INSTANCE = new WynnKnowledgeService();

    private static final String API_BASE = "https://api.wynncraft.com/v3";
    private static final String WIKI_API = "https://wynncraft.wiki.gg/api.php";
    private static final String WIKI_BASE = "https://wynncraft.wiki.gg/wiki/";
    private static final String USER_AGENT = "Wynn-AI-Bridge/" + ModVersion.get()
        + " (Minecraft Fabric client mod; read-only knowledge lookup)";

    private static final String ITEMS_CACHE = "items.json";
    private static final String SETS_CACHE = "item-sets.json";
    private static final String MARKERS_CACHE = "map-markers.json";
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    private final AtomicBoolean initialized = new AtomicBoolean();
    private final AtomicBoolean refreshRunning = new AtomicBoolean();
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("wynn-ai-knowledge-", 0).factory());

    private volatile Path cacheDir;
    private volatile boolean enabled = true;
    private volatile boolean wikiEnabled = true;
    private volatile Duration refreshTtl = Duration.ofHours(1);
    private volatile Duration requestTimeout = Duration.ofSeconds(12);
    private volatile HttpClient httpClient = newHttpClient(Duration.ofSeconds(12));

    private volatile List<Map<String, Object>> items = List.of();
    private volatile Map<String, Map<String, Object>> itemSets = Map.of();
    private volatile List<Map<String, Object>> markers = List.of();

    private volatile long itemsLoadedAt;
    private volatile long setsLoadedAt;
    private volatile long markersLoadedAt;
    private volatile long lastRefreshAttemptAt;
    private volatile long lastRefreshSuccessAt;
    private volatile String lastError = "";

    private WynnKnowledgeService() {}

    public void initialize(Path cacheDir, boolean enabled, boolean wikiEnabled, Duration refreshTtl, Duration requestTimeout) {
        Objects.requireNonNull(cacheDir, "cacheDir");
        this.cacheDir = cacheDir;
        this.enabled = enabled;
        this.wikiEnabled = wikiEnabled;
        this.refreshTtl = clamp(refreshTtl, Duration.ofMinutes(10), Duration.ofHours(24));
        this.requestTimeout = clamp(requestTimeout, Duration.ofSeconds(3), Duration.ofSeconds(60));
        this.httpClient = newHttpClient(this.requestTimeout);

        if (!initialized.compareAndSet(false, true)) return;

        if (!enabled) return;
        try {
            Files.createDirectories(cacheDir);
            loadCaches();
        } catch (IOException e) {
            lastError = "Cache initialization failed: " + e.getClass().getSimpleName();
        }
        refreshAsync(false);
    }

    public CompletableFuture<RefreshResult> refreshAsync(boolean force) {
        if (!enabled) {
            return CompletableFuture.completedFuture(new RefreshResult(false, false, "Knowledge index is disabled", status()));
        }
        if (!refreshRunning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new RefreshResult(true, false, "Refresh already running", status()));
        }

        return CompletableFuture.supplyAsync(() -> {
            boolean changed = false;
            lastRefreshAttemptAt = System.currentTimeMillis();
            try {
                if (force || stale(ITEMS_CACHE, refreshTtl) || items.isEmpty()) {
                    String raw = get(API_BASE + "/item/database?fullResult");
                    List<Map<String, Object>> parsed = normalizeList(Json.parse(raw));
                    if (!parsed.isEmpty()) {
                        writeCache(ITEMS_CACHE, raw);
                        items = List.copyOf(parsed);
                        itemsLoadedAt = System.currentTimeMillis();
                        changed = true;
                    }
                }

                if (force || stale(SETS_CACHE, refreshTtl) || itemSets.isEmpty()) {
                    String raw = get(API_BASE + "/item/sets");
                    Map<String, Map<String, Object>> parsed = normalizeMapOfObjects(Json.parse(raw));
                    if (!parsed.isEmpty()) {
                        writeCache(SETS_CACHE, raw);
                        itemSets = Map.copyOf(parsed);
                        setsLoadedAt = System.currentTimeMillis();
                        changed = true;
                    }
                }

                if (force || stale(MARKERS_CACHE, refreshTtl) || markers.isEmpty()) {
                    String raw = get(API_BASE + "/map/locations/markers");
                    List<Map<String, Object>> parsed = normalizeList(Json.parse(raw));
                    if (!parsed.isEmpty()) {
                        writeCache(MARKERS_CACHE, raw);
                        markers = List.copyOf(parsed);
                        markersLoadedAt = System.currentTimeMillis();
                        changed = true;
                    }
                }

                lastRefreshSuccessAt = System.currentTimeMillis();
                lastError = "";
                return new RefreshResult(true, changed, changed ? "Index refreshed" : "Index already fresh", status());
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + safeMessage(e);
                return new RefreshResult(false, changed, lastError, status());
            } finally {
                refreshRunning.set(false);
            }
        }, executor);
    }

    public Status status() {
        return new Status(
            enabled,
            wikiEnabled,
            refreshRunning.get(),
            items.size(),
            itemSets.size(),
            markers.size(),
            itemsLoadedAt,
            setsLoadedAt,
            markersLoadedAt,
            lastRefreshAttemptAt,
            lastRefreshSuccessAt,
            lastError,
            cacheDir == null ? "" : cacheDir.toString()
        );
    }

    public synchronized void clearCache() throws IOException {
        if (refreshRunning.get()) throw new IOException("Knowledge cache refresh is currently running");
        Path directory = cacheDir;
        if (directory != null && Files.isDirectory(directory)) {
            Files.deleteIfExists(directory.resolve(ITEMS_CACHE));
            Files.deleteIfExists(directory.resolve(SETS_CACHE));
            Files.deleteIfExists(directory.resolve(MARKERS_CACHE));
        }
        items = List.of();
        itemSets = Map.of();
        markers = List.of();
        itemsLoadedAt = 0L;
        setsLoadedAt = 0L;
        markersLoadedAt = 0L;
        lastRefreshSuccessAt = 0L;
        lastError = "";
    }

    public ItemSearchResult searchItems(String query, String type, String subType, String tier, int limit) {
        String q = normalize(query);
        String typeFilter = normalize(type);
        String subTypeFilter = normalize(subType);
        String tierFilter = normalize(tier);
        int cap = clamp(limit, 1, 100);

        List<Scored<Map<String, Object>>> found = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (!fieldMatches(item, "type", typeFilter)) continue;
            if (!fieldMatches(item, "subType", subTypeFilter)) continue;
            if (!fieldMatches(item, "tier", tierFilter)) continue;

            int score = itemScore(item, q);
            if (q.isEmpty() || score > 0) found.add(new Scored<>(score, item));
        }

        found.sort(Comparator.<Scored<Map<String, Object>>>comparingInt(Scored::score).reversed()
            .thenComparing(s -> displayName(s.value()), String.CASE_INSENSITIVE_ORDER));

        List<Map<String, Object>> out = found.stream().limit(cap).map(s -> compactItem(s.value())).toList();
        return new ItemSearchResult(query == null ? "" : query, out.size(), items.size(), out, apiSource("/item/database"));
    }

    public ItemLookupResult getItem(String name) {
        String q = normalize(name);
        if (q.isEmpty()) return new ItemLookupResult(false, name, null, List.of(), apiSource("/item/database"));

        Map<String, Object> exact = null;
        List<Scored<Map<String, Object>>> candidates = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String display = normalize(string(item.get("displayName")));
            String internal = normalize(string(item.get("internalName")));
            if (q.equals(display) || q.equals(internal)) {
                exact = item;
                break;
            }
            int score = itemScore(item, q);
            if (score > 0) candidates.add(new Scored<>(score, item));
        }

        candidates.sort(Comparator.<Scored<Map<String, Object>>>comparingInt(Scored::score).reversed()
            .thenComparing(s -> displayName(s.value()), String.CASE_INSENSITIVE_ORDER));

        if (exact != null) {
            return new ItemLookupResult(true, name, exact, List.of(), apiSource("/item/database"));
        }
        List<Map<String, Object>> suggestions = candidates.stream().limit(8).map(s -> compactItem(s.value())).toList();
        return new ItemLookupResult(false, name, null, suggestions, apiSource("/item/database"));
    }

    public SetSearchResult searchSets(String query, int limit) {
        String q = normalize(query);
        int cap = clamp(limit, 1, 50);
        List<Scored<Map.Entry<String, Map<String, Object>>>> matches = new ArrayList<>();

        for (var entry : itemSets.entrySet()) {
            int score = textScore(entry.getKey(), q);
            Object parts = entry.getValue().get("parts");
            if (parts instanceof List<?> list) {
                for (Object part : list) score = Math.max(score, textScore(String.valueOf(part), q) - 5);
            }
            if (q.isEmpty() || score > 0) matches.add(new Scored<>(score, entry));
        }
        matches.sort(Comparator.<Scored<Map.Entry<String, Map<String, Object>>>>comparingInt(Scored::score).reversed()
            .thenComparing(s -> s.value().getKey(), String.CASE_INSENSITIVE_ORDER));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Scored<Map.Entry<String, Map<String, Object>>> scored : matches.stream().limit(cap).toList()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("name", scored.value().getKey());
            row.putAll(scored.value().getValue());
            out.add(row);
        }
        return new SetSearchResult(query == null ? "" : query, out.size(), itemSets.size(), List.copyOf(out), apiSource("/item/sets"));
    }

    public LocationSearchResult searchLocations(String query, int limit, Double playerX, Double playerY, Double playerZ) {
        String q = normalize(query);
        int cap = clamp(limit, 1, 100);
        List<Scored<Map<String, Object>>> found = new ArrayList<>();

        for (Map<String, Object> marker : markers) {
            String name = string(marker.get("name"));
            String icon = string(marker.get("icon"));
            int score = Math.max(textScore(name, q), textScore(icon, q) - 10);
            if (q.isEmpty() || score > 0) found.add(new Scored<>(score, marker));
        }

        found.sort((a, b) -> {
            int scoreCmp = Integer.compare(b.score(), a.score());
            if (scoreCmp != 0) return scoreCmp;
            if (playerX != null && playerZ != null) {
                double da = distance2d(a.value(), playerX, playerZ);
                double db = distance2d(b.value(), playerX, playerZ);
                int dCmp = Double.compare(da, db);
                if (dCmp != 0) return dCmp;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(string(a.value().get("name")), string(b.value().get("name")));
        });

        List<Map<String, Object>> out = new ArrayList<>();
        for (Scored<Map<String, Object>> scored : found.stream().limit(cap).toList()) {
            LinkedHashMap<String, Object> marker = new LinkedHashMap<>(scored.value());
            if (playerX != null && playerZ != null) {
                double distance2d = distance2d(scored.value(), playerX, playerZ);
                if (Double.isFinite(distance2d)) marker.put("distance2d", Math.round(distance2d * 10.0) / 10.0);
                Double y = number(scored.value().get("y"));
                if (playerY != null && y != null) {
                    Double x = number(scored.value().get("x"));
                    Double z = number(scored.value().get("z"));
                    if (x != null && z != null) {
                        double dx = x - playerX;
                        double dy = y - playerY;
                        double dz = z - playerZ;
                        marker.put("distance3d", Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz) * 10.0) / 10.0);
                    }
                }
            }
            out.add(marker);
        }

        return new LocationSearchResult(query == null ? "" : query, out.size(), markers.size(), List.copyOf(out), apiSource("/map/locations/markers"));
    }

    public WikiSearchResult searchWiki(String query, int limit) {
        if (!wikiEnabled) return new WikiSearchResult(false, query, List.of(), "Wiki lookup is disabled", WIKI_BASE);
        String q = query == null ? "" : query.strip();
        if (q.isBlank()) return new WikiSearchResult(false, q, List.of(), "Query is empty", WIKI_BASE);
        int cap = clamp(limit, 1, 20);

        try {
            String url = WIKI_API + "?action=query&list=search&format=json&utf8=1&srlimit=" + cap + "&srsearch=" + encode(q);
            Map<String, Object> root = Json.object(Json.parse(get(url)));
            Map<String, Object> queryObj = object(root.get("query"));
            List<?> raw = queryObj.get("search") instanceof List<?> list ? list : List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object value : raw) {
                if (!(value instanceof Map<?, ?>)) continue;
                Map<String, Object> row = Json.object(value);
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                String title = string(row.get("title"));
                result.put("title", title);
                result.put("snippet", cleanSnippet(string(row.get("snippet"))));
                copyIfPresent(row, result, "pageid", "wordcount", "timestamp");
                result.put("url", wikiPageUrl(title));
                out.add(result);
            }
            return new WikiSearchResult(true, q, List.copyOf(out), "", WIKI_BASE);
        } catch (Exception e) {
            return new WikiSearchResult(false, q, List.of(), e.getClass().getSimpleName() + ": " + safeMessage(e), WIKI_BASE);
        }
    }

    public WikiPageResult getWikiPage(String title, int maxChars) {
        if (!wikiEnabled) return new WikiPageResult(false, title, "", "", "Wiki lookup is disabled");
        String requested = title == null ? "" : title.strip();
        if (requested.isBlank()) return new WikiPageResult(false, requested, "", "", "Title is empty");
        int cap = clamp(maxChars, 1000, 40_000);

        try {
            String url = WIKI_API + "?action=query&prop=extracts&explaintext=1&exsectionformat=plain&redirects=1&format=json&utf8=1&titles=" + encode(requested);
            Map<String, Object> root = Json.object(Json.parse(get(url)));
            Map<String, Object> queryObj = object(root.get("query"));
            Map<String, Object> pages = object(queryObj.get("pages"));
            for (Object pageValue : pages.values()) {
                if (!(pageValue instanceof Map<?, ?>)) continue;
                Map<String, Object> page = Json.object(pageValue);
                if (page.containsKey("missing")) continue;
                String resolved = Optional.ofNullable(string(page.get("title"))).orElse(requested);
                String extract = Optional.ofNullable(string(page.get("extract"))).orElse("");
                boolean truncated = extract.length() > cap;
                if (truncated) extract = extract.substring(0, cap) + "\n…[truncated]";
                return new WikiPageResult(true, resolved, extract, wikiPageUrl(resolved), truncated ? "truncated" : "");
            }
            return new WikiPageResult(false, requested, "", wikiPageUrl(requested), "Page not found");
        } catch (Exception e) {
            return new WikiPageResult(false, requested, "", wikiPageUrl(requested), e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
    }

    public KnowledgeSearchResult searchKnowledge(String query, int limit, Double playerX, Double playerY, Double playerZ, boolean includeWiki) {
        int cap = clamp(limit, 1, 25);
        ItemSearchResult itemResult = searchItems(query, "", "", "", Math.min(cap, 12));
        SetSearchResult setResult = searchSets(query, Math.min(cap, 8));
        LocationSearchResult locationResult = searchLocations(query, Math.min(cap, 12), playerX, playerY, playerZ);
        WikiSearchResult wiki = includeWiki ? searchWiki(query, Math.min(cap, 10)) : new WikiSearchResult(false, query, List.of(), "Wiki lookup not requested", WIKI_BASE);

        List<String> followups = new ArrayList<>();
        if (!itemResult.results().isEmpty()) followups.add("Use wynn_get_item for the exact full official item record before making a detailed stat comparison.");
        if (!wiki.results().isEmpty()) followups.add("Use wynn_get_wiki_page on the most relevant result for exact quest, merchant, upgrade, or acquisition instructions.");
        if (wiki.results().isEmpty() && looksLikeHowToQuery(query)) followups.add("The official item/map API does not contain all merchant inventories or quest walkthroughs; a web/wiki lookup may still be needed.");

        return new KnowledgeSearchResult(
            query == null ? "" : query,
            itemResult.results(),
            setResult.results(),
            locationResult.results(),
            wiki,
            List.copyOf(followups),
            status()
        );
    }

    private void loadCaches() throws IOException {
        List<Map<String, Object>> cachedItems = readListCache(ITEMS_CACHE);
        if (!cachedItems.isEmpty()) {
            items = List.copyOf(cachedItems);
            itemsLoadedAt = modifiedAt(ITEMS_CACHE);
        }

        Map<String, Map<String, Object>> cachedSets = readMapCache(SETS_CACHE);
        if (!cachedSets.isEmpty()) {
            itemSets = Map.copyOf(cachedSets);
            setsLoadedAt = modifiedAt(SETS_CACHE);
        }

        List<Map<String, Object>> cachedMarkers = readListCache(MARKERS_CACHE);
        if (!cachedMarkers.isEmpty()) {
            markers = List.copyOf(cachedMarkers);
            markersLoadedAt = modifiedAt(MARKERS_CACHE);
        }
    }

    private List<Map<String, Object>> readListCache(String file) {
        try {
            Path path = cacheDir.resolve(file);
            if (!Files.isRegularFile(path)) return List.of();
            return normalizeList(Json.parse(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Map<String, Object>> readMapCache(String file) {
        try {
            Path path = cacheDir.resolve(file);
            if (!Files.isRegularFile(path)) return Map.of();
            return normalizeMapOfObjects(Json.parse(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void writeCache(String file, String raw) throws IOException {
        Files.createDirectories(cacheDir);
        Path target = cacheDir.resolve(file);
        Path temp = cacheDir.resolve(file + ".tmp");
        Files.writeString(temp, raw, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean stale(String file, Duration ttl) {
        try {
            Path path = cacheDir.resolve(file);
            if (!Files.isRegularFile(path)) return true;
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis();
            return age > ttl.toMillis();
        } catch (IOException e) {
            return true;
        }
    }

    private long modifiedAt(String file) {
        try {
            Path path = cacheDir.resolve(file);
            return Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " from " + URI.create(url).getHost());
        }
        return response.body();
    }

    private static HttpClient newHttpClient(Duration timeout) {
        return HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    private static List<Map<String, Object>> normalizeList(Object parsed) {
        Object value = parsed;
        if (parsed instanceof Map<?, ?>) {
            Map<String, Object> object = Json.object(parsed);
            if (object.get("results") instanceof List<?> results) value = results;
        }
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : list) {
            if (row instanceof Map<?, ?>) out.add(Json.object(row));
        }
        return out;
    }

    private static Map<String, Map<String, Object>> normalizeMapOfObjects(Object parsed) {
        if (!(parsed instanceof Map<?, ?>)) return Map.of();
        Map<String, Object> object = Json.object(parsed);
        LinkedHashMap<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (var entry : object.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?>) out.put(entry.getKey(), Json.object(entry.getValue()));
        }
        return out;
    }

    private static Map<String, Object> compactItem(Map<String, Object> item) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(item, out,
            "displayName", "internalName", "type", "subType", "tier", "level", "requirements",
            "attackSpeed", "powderSlots", "elements", "set", "quest", "dropType", "restrictions", "identified"
        );
        if (out.isEmpty()) out.putAll(item);
        return out;
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private static boolean fieldMatches(Map<String, Object> item, String field, String normalizedFilter) {
        if (normalizedFilter.isEmpty()) return true;
        return normalizedFilter.equals(normalize(string(item.get(field))));
    }

    private static int itemScore(Map<String, Object> item, String query) {
        if (query.isEmpty()) return 1;
        int score = 0;
        score = Math.max(score, textScore(string(item.get("displayName")), query));
        score = Math.max(score, textScore(string(item.get("internalName")), query) - 2);
        score = Math.max(score, textScore(string(item.get("type")), query) - 30);
        score = Math.max(score, textScore(string(item.get("subType")), query) - 20);
        score = Math.max(score, textScore(string(item.get("tier")), query) - 25);
        return score;
    }

    private static int textScore(String candidate, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isEmpty()) return 1;
        String c = normalize(candidate);
        if (c.isEmpty()) return 0;
        if (c.equals(normalizedQuery)) return 100;
        if (c.startsWith(normalizedQuery)) return 85;
        if (c.contains(normalizedQuery)) return 70;
        int matched = 0;
        for (String token : normalizedQuery.split("\\s+")) {
            if (!token.isBlank() && c.contains(token)) matched++;
        }
        return matched == 0 ? 0 : 35 + matched * 5;
    }

    private static String displayName(Map<String, Object> item) {
        String display = string(item.get("displayName"));
        return display == null ? Optional.ofNullable(string(item.get("internalName"))).orElse("") : display;
    }

    private static double distance2d(Map<String, Object> marker, double px, double pz) {
        Double x = number(marker.get("x"));
        Double z = number(marker.get("z"));
        if (x == null || z == null) return Double.POSITIVE_INFINITY;
        return Math.hypot(x - px, z - pz);
    }

    private static Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) return Map.of();
        return Json.object(value);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String wikiPageUrl(String title) {
        if (title == null || title.isBlank()) return WIKI_BASE;
        return WIKI_BASE + encode(title.replace(' ', '_')).replace("+", "%20");
    }

    private static String cleanSnippet(String snippet) {
        if (snippet == null) return "";
        String cleaned = TAGS.matcher(snippet).replaceAll("");
        return cleaned
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">");
    }

    private static String apiSource(String path) {
        return API_BASE + path;
    }

    private static boolean looksLikeHowToQuery(String query) {
        String q = normalize(query);
        return q.contains("where") || q.contains("how") || q.contains("merchant") || q.contains("shop") ||
            q.contains("upgrade") || q.contains("quest") || q.contains("ring") || q.contains("buy") ||
            q.contains("sell") || q.contains("どこ") || q.contains("強化") || q.contains("売") || q.contains("買") || q.contains("使い道");
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) return "no message";
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static Duration clamp(Duration value, Duration min, Duration max) {
        if (value == null) return min;
        if (value.compareTo(min) < 0) return min;
        if (value.compareTo(max) > 0) return max;
        return value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private record Scored<T>(int score, T value) {}

    public record Status(
        boolean enabled,
        boolean wikiEnabled,
        boolean refreshRunning,
        int itemCount,
        int itemSetCount,
        int markerCount,
        long itemsLoadedAt,
        long setsLoadedAt,
        long markersLoadedAt,
        long lastRefreshAttemptAt,
        long lastRefreshSuccessAt,
        String lastError,
        String cacheDirectory
    ) {}

    public record RefreshResult(boolean ok, boolean changed, String message, Status status) {}
    public record ItemSearchResult(String query, int returned, int indexed, List<Map<String, Object>> results, String source) {}
    public record ItemLookupResult(boolean found, String query, Map<String, Object> item, List<Map<String, Object>> suggestions, String source) {}
    public record SetSearchResult(String query, int returned, int indexed, List<Map<String, Object>> results, String source) {}
    public record LocationSearchResult(String query, int returned, int indexed, List<Map<String, Object>> results, String source) {}
    public record WikiSearchResult(boolean ok, String query, List<Map<String, Object>> results, String error, String source) {}
    public record WikiPageResult(boolean ok, String title, String extract, String url, String note) {}
    public record KnowledgeSearchResult(
        String query,
        List<Map<String, Object>> items,
        List<Map<String, Object>> itemSets,
        List<Map<String, Object>> locations,
        WikiSearchResult wiki,
        List<String> followups,
        Status index
    ) {}
}
