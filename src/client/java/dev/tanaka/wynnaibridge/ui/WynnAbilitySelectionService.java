package dev.tanaka.wynnaibridge.ui;

import dev.tanaka.wynnaibridge.knowledge.WynnAbilityDataService;
import dev.tanaka.wynnaibridge.state.AbilityTreeDebugCollector;
import dev.tanaka.wynnaibridge.state.AbilityTreeSemanticCollector;
import dev.tanaka.wynnaibridge.state.ItemInspector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Strict preflight for a single semantic Ability Tree node selection. */
public final class WynnAbilitySelectionService {
    private WynnAbilitySelectionService() {}

    public static SelectionPlan plan(
        WynnAbilityDataService.AbilityTree official,
        AbilityTreeSemanticCollector.Result live,
        String abilityId,
        String expectedClass,
        String expectedScreen,
        int expectedSyncId,
        long expectedRevision
    ) {
        if (live == null || !live.abilityTreeRecognized()) fail("WRONG_SCREEN", "The current GUI is not a recognized Ability Tree");
        if (expectedScreen == null || !expectedScreen.equals(live.screenClass())) fail("SCREEN_CHANGED", "The Ability Tree screen changed");
        if (live.stateRevision() == null || expectedRevision != live.stateRevision()) fail("STALE_REVISION", "The Ability Tree evidence revision is stale");
        if (live.syncId() == null || expectedSyncId != live.syncId()) fail("SYNC_ID_MISMATCH", "The Ability Tree container id changed");
        if (live.containerStateId() == null) fail("WRONG_SCREEN", "The Ability Tree has no verifiable container state id");
        if (official == null || !official.classId().equalsIgnoreCase(expectedClass)
            || !live.classId().equalsIgnoreCase(expectedClass) || !live.classVerified()) {
            fail("WRONG_CLASS", "The live Ability Tree class is not verified as the requested class");
        }
        if (abilityId == null || abilityId.isBlank()) fail("UNKNOWN_ABILITY", "An official abilityId is required");
        List<WynnAbilityDataService.AbilityNode> officialMatches = official.nodes().stream()
            .filter(node -> node.id().equals(abilityId)).toList();
        if (officialMatches.isEmpty()) fail("UNKNOWN_ABILITY", "The abilityId is not present in the official class tree");
        if (officialMatches.size() != 1) fail("AMBIGUOUS_ABILITY", "The abilityId is not unique in the official class tree");
        WynnAbilityDataService.AbilityNode node = officialMatches.getFirst();
        List<AbilityTreeSemanticCollector.NodeView> liveMatches = live.nodes().stream()
            .filter(candidate -> candidate.id().equals(abilityId)).toList();
        if (liveMatches.size() != 1) fail("AMBIGUOUS_ABILITY", "The live GUI did not identify exactly one matching node");
        AbilityTreeSemanticCollector.NodeView runtimeNode = liveMatches.getFirst();
        if (!("EXACT".equals(runtimeNode.matchConfidence()) || "STRONG".equals(runtimeNode.matchConfidence()))
            || !runtimeNode.exactRuntimeMatch()) {
            fail("AMBIGUOUS_ABILITY", "The live node identity is not exact or strong");
        }
        if (runtimeNode.menuSlot() == null || runtimeNode.runtimeItemId() == null || runtimeNode.runtimeItemName() == null
            || runtimeNode.runtimeItemCount() == null || runtimeNode.runtimeComponentsHash() == null) {
            fail("AMBIGUOUS_ABILITY", "The matching live node has no safe container slot identity");
        }
        if (runtimeNode.page() == null || live.page() == null || !runtimeNode.page().equals(live.page())) {
            fail("WRONG_PAGE", "The target node is not on the currently verified Ability Tree page");
        }
        if ("SELECTED".equals(runtimeNode.state())) fail("ABILITY_ALREADY_SELECTED", "The ability is already selected");
        if ("LOCKED".equals(runtimeNode.state())) fail("ABILITY_LOCKED", "The live Ability Tree marks this ability as locked");
        if (!"AVAILABLE".equals(runtimeNode.state())) fail("ABILITY_STATE_UNKNOWN", "The live Ability Tree does not mark this ability as available");

        Integer cost = number(node.requirements().get("ABILITY_POINTS"));
        Integer available = live.availablePoints();
        if (cost == null || available == null) fail("ABILITY_REQUIREMENT_UNKNOWN", "Ability cost or live available Ability Points are unknown");
        if (cost < 1 || available < cost) fail("INSUFFICIENT_AP", "Available Ability Points do not cover the official node cost");

        Set<String> selectedIds = live.nodes().stream().filter(view -> "SELECTED".equals(view.state()))
            .map(AbilityTreeSemanticCollector.NodeView::id).collect(Collectors.toUnmodifiableSet());
        verifyNodeRequirement(node.requirements(), selectedIds);
        verifyArchetypeRequirement(node.requirements(), live.selectedArchetypes());
        verifyLocks(node.locks(), live.nodes());
        if (node.slot() == null || !node.slot().equals(runtimeNode.menuSlot())) {
            fail("SCREEN_SLOT_MISMATCH", "Official node slot and unique live container slot do not agree");
        }
        return new SelectionPlan(node, runtimeNode, expectedScreen, expectedSyncId, live.containerStateId(),
            expectedRevision, available, cost);
    }

    /** Re-captures and revalidates every identifying field immediately before one vanilla pickup click. */
    public static AbilityTreeSemanticCollector.Result start(
        Minecraft minecraft,
        WynnAbilityDataService.AbilityTree official,
        SelectionPlan expected
    ) {
        return start(minecraft, official, expected, () -> {});
    }

    public static AbilityTreeSemanticCollector.Result start(
        Minecraft minecraft,
        WynnAbilityDataService.AbilityTree official,
        SelectionPlan expected,
        Runnable immediatelyBeforeClick
    ) {
        AbilityTreeSemanticCollector.Result current = correlate(minecraft, official);
        SelectionPlan checked = plan(official, current, expected.officialNode().id(), official.classId(),
            expected.screenClass(), expected.syncId(), expected.stateRevision());
        if (current.containerStateId() != expected.containerStateId()
            || !sameRuntimeIdentity(expected.runtimeNode(), checked.runtimeNode())) {
            fail("STATE_CHANGED", "The Ability Tree node identity changed before selection");
        }
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?>)
            || minecraft.player == null || minecraft.gameMode == null
            || ((AbstractContainerScreen<?>) minecraft.gui.screen()).getMenu().containerId != expected.syncId()
            || expected.runtimeNode().menuSlot() < 0
            || expected.runtimeNode().menuSlot() >= ((AbstractContainerScreen<?>) minecraft.gui.screen()).getMenu().slots.size()) {
            fail("SCREEN_CHANGED", "The Ability Tree container changed before the node click");
        }
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) minecraft.gui.screen();
        Slot slot = screen.getMenu().getSlot(expected.runtimeNode().menuSlot());
        ItemStack stack = slot.getItem();
        ItemInspector.ItemView actual = ItemInspector.inspect(minecraft, stack, true, false);
        if (!slot.isActive() || !slot.mayPickup(minecraft.player) || actual == null
            || !expected.runtimeNode().runtimeItemId().equals(actual.itemId())
            || !expected.runtimeNode().runtimeItemName().equals(actual.name())
            || expected.runtimeNode().runtimeItemCount() != actual.count()
            || !expected.runtimeNode().runtimeComponentsHash().equals(actual.componentsHash())
            || !expected.runtimeNode().runtimeTooltip().equals(actual.tooltip().stream().map(ItemInspector.TooltipLine::text).toList())) {
            fail("ITEM_MISMATCH", "The Ability Tree node item no longer matches the inspected identity and tooltip");
        }
        immediatelyBeforeClick.run();
        minecraft.gameMode.handleContainerInput(expected.syncId(), expected.runtimeNode().menuSlot(), 0,
            ContainerInput.PICKUP, minecraft.player);
        return correlate(minecraft, official);
    }

    public static AbilityTreeSemanticCollector.Result correlate(Minecraft minecraft, WynnAbilityDataService.AbilityTree official) {
        AbilityTreeDebugCollector.Result runtime = AbilityTreeDebugCollector.collect(minecraft, 2_000L, 500, 1_000);
        return AbilityTreeSemanticCollector.correlate(official, runtime);
    }

    public static boolean selectionVerified(AbilityTreeSemanticCollector.Result after, SelectionPlan plan) {
        if (after == null || !after.abilityTreeRecognized() || !after.classVerified()
            || after.syncId() == null || after.syncId() != plan.syncId()
            || after.containerStateId() == null || after.containerStateId() == plan.containerStateId()
            || after.stateRevision() == null || after.stateRevision() == plan.stateRevision()
            || after.availablePoints() == null || after.availablePoints() != plan.availablePoints() - plan.cost()) return false;
        return after.nodes().stream().anyMatch(node -> node.id().equals(plan.officialNode().id())
            && "SELECTED".equals(node.state())
            && ("EXACT".equals(node.matchConfidence()) || "STRONG".equals(node.matchConfidence())));
    }

    private static boolean sameRuntimeIdentity(
        AbilityTreeSemanticCollector.NodeView expected, AbilityTreeSemanticCollector.NodeView current
    ) {
        return expected.id().equals(current.id()) && expected.state().equals(current.state())
            && expected.matchConfidence().equals(current.matchConfidence())
            && expected.menuSlot().equals(current.menuSlot())
            && expected.runtimeItemId().equals(current.runtimeItemId())
            && expected.runtimeItemName().equals(current.runtimeItemName())
            && expected.runtimeItemCount().equals(current.runtimeItemCount())
            && expected.runtimeComponentsHash().equals(current.runtimeComponentsHash())
            && expected.runtimeTooltip().equals(current.runtimeTooltip());
    }

    private static void verifyNodeRequirement(Map<String, Object> requirements, Set<String> selectedIds) {
        if (!requirements.containsKey("NODE")) return;
        List<String> needed = stringRequirements(requirements.get("NODE"));
        if (needed.isEmpty() || !selectedIds.containsAll(needed)) {
            fail("NODE_REQUIREMENT_NOT_MET", "The live GUI does not verify every official NODE prerequisite as selected");
        }
    }

    private static void verifyArchetypeRequirement(Map<String, Object> requirements, List<String> selectedArchetypes) {
        if (!requirements.containsKey("ARCHETYPE")) return;
        List<String> needed = stringRequirements(requirements.get("ARCHETYPE"));
        if (needed.isEmpty()) fail("ARCHETYPE_REQUIREMENT_NOT_MET", "The official ARCHETYPE requirement could not be interpreted safely");
        boolean matched = needed.stream().allMatch(required -> selectedArchetypes.stream()
            .anyMatch(selected -> selected.equalsIgnoreCase(required)));
        if (!matched) fail("ARCHETYPE_REQUIREMENT_NOT_MET", "The live GUI does not verify the required archetype as selected");
    }

    private static void verifyLocks(List<String> locks, List<AbilityTreeSemanticCollector.NodeView> liveNodes) {
        if (locks == null || locks.isEmpty()) return;
        for (String lockedId : locks) {
            List<AbilityTreeSemanticCollector.NodeView> candidates = liveNodes.stream()
                .filter(view -> view.id().equals(lockedId)).toList();
            if (candidates.size() != 1 || "UNKNOWN".equals(candidates.getFirst().state())) {
                fail("LOCK_STATE_UNVERIFIED", "A node referenced by the official locks list has no verified live state");
            }
            if ("SELECTED".equals(candidates.getFirst().state())) {
                fail("ABILITY_LOCKED", "A conflicting locked node is already selected");
            }
        }
    }

    private static List<String> stringRequirements(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof String string && !string.isBlank()) result.add(string);
        else if (value instanceof List<?> list) {
            for (Object entry : list) if (entry instanceof String string && !string.isBlank()) result.add(string);
        }
        return result;
    }

    private static Integer number(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string) {
            try { return Integer.parseInt(string); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static void fail(String code, String message) {
        throw new UiActionService.UiActionException(code, message);
    }

    public record SelectionPlan(
        WynnAbilityDataService.AbilityNode officialNode,
        AbilityTreeSemanticCollector.NodeView runtimeNode,
        String screenClass,
        int syncId,
        int containerStateId,
        long stateRevision,
        int availablePoints,
        int cost
    ) {}
}
