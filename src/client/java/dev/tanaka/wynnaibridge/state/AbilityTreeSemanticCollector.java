package dev.tanaka.wynnaibridge.state;

import dev.tanaka.wynnaibridge.knowledge.WynnAbilityDataService;
import dev.tanaka.wynnaibridge.capture.RenderedItemCaptureStore;
import dev.tanaka.wynnaibridge.capture.TextCaptureStore;
import dev.tanaka.wynnaibridge.state.ItemInspector.ItemView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Correlates official nodes with exact live item evidence; ambiguity stays UNKNOWN. */
public final class AbilityTreeSemanticCollector {
    private static final Pattern AVAILABLE_POINTS = Pattern.compile(
        "(?i)\\b(?:available|unused)\\s+ability\\s+points?\\s*[:=]\\s*(\\d+)\\b");
    private static final Pattern PAGE = Pattern.compile("(?i)\\bcurrent\\s+page\\s*[:=]\\s*(\\d+)\\b");

    private AbilityTreeSemanticCollector() {}

    public static Result correlate(
        WynnAbilityDataService.AbilityTree official,
        AbilityTreeDebugCollector.Result runtime
    ) {
        List<TextCaptureStore.CapturedText> text = runtime == null ? List.of() : runtime.visibleText();
        List<String> lines = text.stream().map(TextCaptureStore.CapturedText::text).toList();
        List<String> valueEvidence = new ArrayList<>(lines);
        if (runtime != null) {
            runtime.renderedItems().forEach(rendered -> rendered.item().tooltip().forEach(line -> valueEvidence.add(line.text())));
            if (runtime.openContainer() != null) runtime.openContainer().slots().stream()
                .filter(slot -> slot.item() != null)
                .forEach(slot -> slot.item().tooltip().forEach(line -> valueEvidence.add(line.text())));
        }
        String visible = ((runtime == null ? "" : String.valueOf(runtime.screenTitle())) + "\n" + String.join("\n", lines))
            .toLowerCase(Locale.ROOT);
        Integer points = captureInteger(AVAILABLE_POINTS, valueEvidence);
        Integer page = captureInteger(PAGE, lines);
        boolean titleCue = visible.contains("ability tree")
            || (visible.contains("hone your skill") && visible.contains("archetype"));

        Map<String, Integer> iconIdentityCounts = new HashMap<>();
        for (WynnAbilityDataService.AbilityNode node : official.nodes()) {
            String key = iconKey(node);
            if (key != null) iconIdentityCounts.merge(key, 1, Integer::sum);
        }
        for (ArchetypeMeta archetype : archetypes(official)) {
            if (archetype.iconKey() != null) iconIdentityCounts.merge(archetype.iconKey(), 1, Integer::sum);
        }

        List<ArchetypeView> archetypeViews = correlateArchetypes(official, runtime, titleCue, iconIdentityCounts);

        List<NodeView> nodes = new ArrayList<>();
        int exactMatches = 0;
        for (WynnAbilityDataService.AbilityNode node : official.nodes()) {
            List<OpenContainerCollector.SlotView> slotMatches = new ArrayList<>();
            if (runtime.openContainer() != null && runtime.openContainer().ok()) {
                for (OpenContainerCollector.SlotView slot : runtime.openContainer().slots()) {
                    if (matches(node, slot.item())) slotMatches.add(slot);
                }
            }
            List<RenderedItemCaptureStore.RenderedItemView> renderedMatches = runtime.renderedItems().stream()
                .filter(item -> matches(node, item.item())).toList();
            boolean uniqueSlot = slotMatches.size() == 1;
            boolean uniqueRendered = renderedMatches.size() == 1;
            String iconKey = iconKey(node);
            boolean officialIdentityUnique = iconKey != null && iconIdentityCounts.getOrDefault(iconKey, 0) == 1;
            boolean anyCandidate = !slotMatches.isEmpty() || !renderedMatches.isEmpty();
            boolean ambiguous = slotMatches.size() > 1 || renderedMatches.size() > 1 || (anyCandidate && !officialIdentityUnique);
            boolean exactMatch = officialIdentityUnique && uniqueSlot;
            boolean strongMatch = officialIdentityUnique && !uniqueSlot && uniqueRendered;
            String confidence = exactMatch ? "EXACT" : strongMatch ? "STRONG" : ambiguous ? "AMBIGUOUS" : "NONE";
            if (exactMatch || strongMatch) exactMatches++;

            List<String> tooltip = new ArrayList<>();
            for (OpenContainerCollector.SlotView slot : slotMatches) {
                if (slot.item() != null) slot.item().tooltip().forEach(line -> tooltip.add(line.text()));
            }
            for (RenderedItemCaptureStore.RenderedItemView item : renderedMatches) {
                item.item().tooltip().forEach(line -> tooltip.add(line.text()));
            }
            State state = titleCue && exactMatch ? explicitState(tooltip) : State.UNKNOWN;
            Integer menuSlot = uniqueSlot ? slotMatches.getFirst().menuSlot() : null;
            Integer screenX = uniqueRendered ? renderedMatches.getFirst().x() : null;
            Integer screenY = uniqueRendered ? renderedMatches.getFirst().y() : null;
            List<String> matchedBy = new ArrayList<>();
            if (uniqueSlot) matchedBy.add("slot");
            if (uniqueRendered) matchedBy.add("renderedItem");
            if (uniqueSlot || uniqueRendered) matchedBy.add("itemIdentity");
            if (tooltip.stream().anyMatch(line -> line.equalsIgnoreCase(node.name()))) matchedBy.add("tooltip");
            ItemView matchedItem = uniqueSlot && slotMatches.getFirst().item() != null
                ? slotMatches.getFirst().item()
                : uniqueRendered ? renderedMatches.getFirst().item() : null;
            if (matchedItem != null && (node.name().equals(matchedItem.name())
                || node.iconName() != null && node.iconName().equals(matchedItem.name()))) matchedBy.add("name");
            nodes.add(new NodeView(node.id(), node.name(), state.name(), confidence, List.copyOf(matchedBy),
                integer(node.requirements().get("ABILITY_POINTS")), menuSlot, screenX, screenY,
                node.page(), node.slot(), linkedCoordinates(node), node.requirements(), node.links(), node.locks(),
                node.iconItemId(), node.iconName(), node.iconModelIds(),
                matchedItem == null ? null : matchedItem.itemId(),
                matchedItem == null ? null : matchedItem.name(),
                matchedItem == null ? null : matchedItem.count(),
                matchedItem == null ? null : matchedItem.componentsHash(), List.copyOf(tooltip),
                exactMatch || strongMatch, exactMatch ? "Unique official icon identity matched one live menu slot."
                    : strongMatch ? "Unique official icon identity matched one current live GUI item; a menu slot was not confirmed."
                    : !officialIdentityUnique ? "The official icon identity is shared by multiple nodes; no node was inferred."
                    : ambiguous ? "Multiple live items matched this node; no target was inferred."
                    : "No live GUI item matched this official node."));
        }

        boolean recognized = titleCue && exactMatches > 0;
        boolean classVerified = runtime != null && lines.stream().anyMatch(line ->
            line.matches("(?i)^\\s*class\\s*[:=]\\s*" + Pattern.quote(official.classId()) + "\\s*$"));
        return new Result(runtime == null ? null : runtime.screenClass(), runtime == null ? null : runtime.syncId(),
            runtime == null ? null : runtime.menuStateId(), official.classId(), classVerified,
            recognized, points, page, runtime == null ? null : runtime.stateRevision(), nodes, archetypeViews,
            archetypeViews.stream().filter(view -> "SELECTED".equals(view.state())
                && ("EXACT".equals(view.matchConfidence()) || "STRONG".equals(view.matchConfidence())))
                .map(ArchetypeView::id).toList(),
            recognized ? "Official tree data was joined only to exact live icon identity; unmatched/ambiguous states remain UNKNOWN."
                : "The current screen was not sufficiently identified as this class's Ability Tree; no runtime node states were inferred.");
    }

    private static List<ArchetypeMeta> archetypes(WynnAbilityDataService.AbilityTree official) {
        List<ArchetypeMeta> result = new ArrayList<>();
        official.archetypes().forEach((id, value) -> {
            Map<String, Object> raw = object(value);
            Map<String, Object> icon = object(raw.get("icon"));
            Map<String, Object> iconValue = object(icon.get("value"));
            Map<String, Object> model = object(iconValue.get("customModelData"));
            String iconItemId = string(iconValue.get("id"));
            String iconName = string(iconValue.get("name"));
            result.add(new ArchetypeMeta(id, plainName(string(raw.get("name"))), integer(raw.get("slot")),
                iconItemId, iconName, integerList(model.get("rangeDispatch"))));
        });
        return result;
    }

    private static List<ArchetypeView> correlateArchetypes(
        WynnAbilityDataService.AbilityTree official,
        AbilityTreeDebugCollector.Result runtime,
        boolean titleCue,
        Map<String, Integer> iconIdentityCounts
    ) {
        List<ArchetypeView> result = new ArrayList<>();
        for (ArchetypeMeta meta : archetypes(official)) {
            List<OpenContainerCollector.SlotView> slots = runtime == null || runtime.openContainer() == null
                ? List.of() : runtime.openContainer().slots().stream().filter(slot -> matches(meta, slot.item())).toList();
            List<RenderedItemCaptureStore.RenderedItemView> rendered = runtime == null
                ? List.of() : runtime.renderedItems().stream().filter(item -> matches(meta, item.item())).toList();
            boolean uniqueSlot = slots.size() == 1;
            boolean uniqueRendered = rendered.size() == 1;
            boolean uniqueIcon = meta.iconKey() != null && iconIdentityCounts.getOrDefault(meta.iconKey(), 0) == 1;
            boolean ambiguous = slots.size() > 1 || rendered.size() > 1
                || ((!slots.isEmpty() || !rendered.isEmpty()) && !uniqueIcon);
            String confidence = uniqueIcon && uniqueSlot ? "EXACT"
                : uniqueIcon && uniqueRendered ? "STRONG" : ambiguous ? "AMBIGUOUS" : "NONE";
            List<String> tooltip = new ArrayList<>();
            slots.stream().filter(slot -> slot.item() != null)
                .forEach(slot -> slot.item().tooltip().forEach(line -> tooltip.add(line.text())));
            rendered.forEach(item -> item.item().tooltip().forEach(line -> tooltip.add(line.text())));
            State state = titleCue && "EXACT".equals(confidence) ? explicitState(tooltip) : State.UNKNOWN;
            ItemView item = uniqueSlot && slots.getFirst().item() != null ? slots.getFirst().item()
                : uniqueRendered ? rendered.getFirst().item() : null;
            result.add(new ArchetypeView(meta.id(), meta.name(), state.name(), confidence,
                meta.officialSlot(), uniqueSlot ? slots.getFirst().menuSlot() : null,
                meta.iconItemId(), meta.iconName(), meta.iconModelIds(),
                item == null ? null : item.itemId(), item == null ? null : item.name(), List.copyOf(tooltip)));
        }
        return List.copyOf(result);
    }

    private static boolean matches(WynnAbilityDataService.AbilityNode node, dev.tanaka.wynnaibridge.state.ItemInspector.ItemView item) {
        if (item == null || node.iconItemId() == null || node.iconName() == null) return false;
        return node.iconItemId().equals(item.itemId())
            && (node.iconName().equals(item.name()) || node.name().equals(item.name()));
    }

    private static String iconKey(WynnAbilityDataService.AbilityNode node) {
        if (node.iconItemId() == null || node.iconName() == null) return null;
        return node.iconItemId() + "\u001f" + node.iconName();
    }

    private static boolean matches(ArchetypeMeta archetype, ItemView item) {
        if (item == null || archetype.iconItemId() == null || !archetype.iconItemId().equals(item.itemId())) return false;
        return archetype.iconName() != null && archetype.iconName().equals(item.name())
            || archetype.name() != null && (archetype.name().equalsIgnoreCase(item.name())
                || item.tooltip().stream().anyMatch(line -> archetype.name().equalsIgnoreCase(line.text())));
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, entry) -> result.put(String.valueOf(key), entry));
        return result;
    }

    private static String string(Object value) { return value == null ? null : String.valueOf(value); }

    private static String plainName(String value) {
        return value == null ? null : value.replaceAll("(?i)[&§][0-9a-fk-or]", "").strip();
    }

    private static List<Integer> integerList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Integer> result = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Number number) result.add(number.intValue());
            else if (entry instanceof String text) {
                try { result.add(Integer.parseInt(text)); } catch (NumberFormatException ignored) {}
            }
        }
        return List.copyOf(result);
    }

    private static State explicitState(List<String> lines) {
        boolean selected = false;
        boolean available = false;
        boolean locked = false;
        for (String line : lines) {
            String normalized = line == null ? "" : line.strip().toLowerCase(Locale.ROOT);
            if (normalized.equals("selected") || normalized.equals("unlocked")) selected = true;
            if (normalized.equals("available") || normalized.equals("available to unlock")) available = true;
            if (normalized.equals("locked") || normalized.startsWith("locked:")) locked = true;
        }
        int signals = (selected ? 1 : 0) + (available ? 1 : 0) + (locked ? 1 : 0);
        if (signals != 1) return State.UNKNOWN;
        if (selected) return State.SELECTED;
        if (available) return State.AVAILABLE;
        return State.LOCKED;
    }

    private static Integer captureInteger(Pattern pattern, List<String> lines) {
        Integer result = null;
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line == null ? "" : line.strip());
            if (!matcher.find()) continue;
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (result != null && result != value) return null;
                result = value;
            } catch (NumberFormatException ignored) { return null; }
        }
        return result;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string) {
            try { return Integer.parseInt(string); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Map<String, Object> linkedCoordinates(WynnAbilityDataService.AbilityNode node) {
        java.util.LinkedHashMap<String, Object> coordinates = new java.util.LinkedHashMap<>();
        coordinates.put("x", node.x());
        coordinates.put("y", node.y());
        return coordinates;
    }

    private record ArchetypeMeta(String id, String name, Integer officialSlot, String iconItemId,
                                 String iconName, List<Integer> iconModelIds) {
        String iconKey() { return iconItemId == null || iconName == null ? null : iconItemId + "\u001f" + iconName; }
    }

    private enum State { SELECTED, AVAILABLE, LOCKED, UNKNOWN }

    public record NodeView(
        String id, String name, String state, String matchConfidence, List<String> matchedBy,
        Integer cost, Integer menuSlot, Integer screenX, Integer screenY,
        Integer page, Integer officialSlot, Map<String, Object> officialCoordinates,
        Map<String, Object> requirements, List<String> links, List<String> locks,
        String officialIconItemId, String officialIconName, List<Integer> officialModelIds,
        String runtimeItemId, String runtimeItemName, Integer runtimeItemCount,
        String runtimeComponentsHash, List<String> runtimeTooltip,
        boolean exactRuntimeMatch, String matchReason
    ) {}

    public record Result(
        String screenClass, Integer syncId, Integer containerStateId, String classId,
        boolean classVerified, boolean abilityTreeRecognized,
        Integer availablePoints, Integer page, Long stateRevision, List<NodeView> nodes,
        List<ArchetypeView> archetypes, List<String> selectedArchetypes, String note
    ) {}

    public record ArchetypeView(String id, String name, String state, String matchConfidence,
                                Integer officialSlot, Integer screenSlot, String officialIconItemId,
                                String officialIconName, List<Integer> officialModelIds,
                                String runtimeItemId, String runtimeItemName, List<String> runtimeTooltip) {}
}
