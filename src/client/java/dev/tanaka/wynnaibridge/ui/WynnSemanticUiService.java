package dev.tanaka.wynnaibridge.ui;

import dev.tanaka.wynnaibridge.capture.TextCaptureStore;
import dev.tanaka.wynnaibridge.state.ItemInspector;
import dev.tanaka.wynnaibridge.state.UiStateRevisionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic, fail-closed operations over locally observed Wynncraft GUI evidence. */
public final class WynnSemanticUiService {
    private static final Pattern SKILL_POINTS = Pattern.compile("(?i)^\\s*unassigned\\s+skill\\s+points?\\s*[:=]\\s*(\\d+)\\s*$");
    private static final Pattern ABILITY_POINTS = Pattern.compile("(?i)^\\s*(?:unused|available)\\s+ability\\s+points?\\s*[:=]\\s*(\\d+)\\s*$");
    private static final Pattern SKILL_VALUE = Pattern.compile("(?i)^\\s*(strength|dexterity|intelligence|defence|agility)\\s*(?:skill\\s+points?)?\\s*[:=]\\s*(\\d+)\\s*$");
    private static final Pattern BUTTON_VALUE = Pattern.compile("(?i)^\\s*(?:(?:current|assigned)\\s+)?(?:skill\\s+)?points?\\s*[:=]\\s*(\\d+)\\s*$");

    private WynnSemanticUiService() {}

    public static Snapshot capture(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            throw new UiActionService.UiActionException("NOT_CONNECTED", "Not connected to a Minecraft world");
        }
        var screen = minecraft.gui.screen();
        AbstractContainerMenu menu = screen instanceof AbstractContainerScreen<?> container
            ? container.getMenu() : null;
        String screenClass = screen == null ? null : screen.getClass().getName();
        String menuClass = menu == null ? null : menu.getClass().getName();
        String title = screen == null ? null : screen.getTitle().getString();
        int syncId = menu == null ? minecraft.player.containerMenu.containerId : menu.containerId;
        int stateId = menu == null ? minecraft.player.containerMenu.getStateId() : menu.getStateId();
        long revision = UiStateRevisionTracker.INSTANCE.refresh(minecraft);
        List<SlotView> slots = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        if (menu != null) {
            for (int index = 0; index < menu.slots.size(); index++) {
                Slot slot = menu.getSlot(index);
                ItemStack stack = slot.getItem();
                ItemInspector.ItemView item = ItemInspector.inspect(minecraft, stack, true, false);
                if (item != null) item.tooltip().forEach(line -> evidence.add(line.text()));
                slots.add(new SlotView(index, slot.isActive(), slot.mayPickup(minecraft.player), item));
            }
            ItemInspector.ItemView carried = ItemInspector.inspect(minecraft, menu.getCarried(), true, false);
            if (carried != null) carried.tooltip().forEach(line -> evidence.add(line.text()));
        }
        List<TextCaptureStore.CapturedText> captured = TextCaptureStore.INSTANCE.snapshotSince(
            0L, 1_500L, null, 1000, false).stream()
            .filter(text -> text.source().startsWith("gui.") || text.source().startsWith("tooltip"))
            .toList();
        captured.forEach(text -> evidence.add(text.text()));
        if (title != null) evidence.add(title);

        Integer unassignedSkillPoints = uniqueValue(SKILL_POINTS, evidence);
        Integer unusedAbilityPoints = uniqueValue(ABILITY_POINTS, evidence);
        Map<String, Integer> skillValues = new LinkedHashMap<>();
        java.util.Set<String> ambiguousSkills = new java.util.HashSet<>();
        for (String line : evidence) {
            Matcher matcher = SKILL_VALUE.matcher(line);
            if (!matcher.matches()) continue;
            Integer value = parse(matcher.group(2));
            if (value == null) continue;
            String skill = normalizeSkill(matcher.group(1));
            if (ambiguousSkills.contains(skill)) continue;
            if (skillValues.containsKey(skill) && !skillValues.get(skill).equals(value)) {
                skillValues.remove(skill);
                ambiguousSkills.add(skill);
            } else skillValues.put(skill, value);
        }
        boolean hasSkillButton = slots.stream().anyMatch(slot -> slot.item() != null
            && Skill.isName(slot.item().name()));
        boolean characterInfoRecognized = characterInfoRecognized(title, hasSkillButton,
            unassignedSkillPoints, skillValues);
        String evidenceText = String.join("\n", evidence).toLowerCase(Locale.ROOT);
        boolean abilityTreeRecognized = title != null && title.toLowerCase(Locale.ROOT).contains("ability tree")
            || (evidenceText.contains("hone your skill") && evidenceText.contains("archetype"));
        boolean cursorEmpty = menu == null || menu.getCarried().isEmpty();
        return new Snapshot(System.currentTimeMillis(), screenClass, menuClass, title,
            screen == null ? 0 : System.identityHashCode(screen), syncId, stateId, revision,
            List.copyOf(slots), List.copyOf(evidence), unassignedSkillPoints, unusedAbilityPoints,
            Map.copyOf(skillValues), characterInfoRecognized, abilityTreeRecognized, cursorEmpty);
    }

    public static CharacterInfoPlan planOpenCharacterInfo(
        Minecraft minecraft, String expectedScreen, int expectedSyncId, long expectedRevision
    ) {
        Snapshot before = capture(minecraft);
        validateContext(before, expectedScreen, expectedSyncId, expectedRevision);
        if (!(minecraft.gui.screen() instanceof InventoryScreen inventoryScreen)
            || !(inventoryScreen.getMenu() instanceof InventoryMenu menu)) {
            throw new UiActionService.UiActionException("UNSAFE_SCREEN", "Open the vanilla player inventory before requesting Character Info");
        }
        if (!before.cursorEmpty()) throw new UiActionService.UiActionException("CURSOR_NOT_EMPTY", "The cursor must be empty before opening Character Info");
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < minecraft.player.getInventory().getNonEquipmentItems().size(); i++) {
            ItemStack stack = minecraft.player.getInventory().getNonEquipmentItems().get(i);
            if (isCharacterInfoItem(minecraft, stack)) candidates.add(i);
        }
        if (candidates.size() != 1) {
            throw new UiActionService.UiActionException("CHARACTER_INFO_ITEM_NOT_UNIQUE",
                "A unique Character Info compass item was not identified in the player inventory");
        }
        int inventoryIndex = candidates.getFirst();
        if (inventoryIndex >= 9 || inventoryIndex != minecraft.player.getInventory().getSelectedSlot()) {
            throw new UiActionService.UiActionException("CHARACTER_INFO_NOT_HELD",
                "The uniquely identified Character Info item must already be in the selected hotbar slot");
        }
        int menuSlot = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container == minecraft.player.getInventory() && slot.getContainerSlot() == inventoryIndex) {
                menuSlot = i;
                break;
            }
        }
        if (menuSlot < 0) throw new UiActionService.UiActionException("CHARACTER_INFO_ITEM_NOT_VISIBLE", "The identified item is not present in the current inventory screen");
        if (!isCharacterInfoItem(minecraft, menu.getSlot(menuSlot).getItem())) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "STATE_CHANGED: the visible inventory slot did not match the identified Character Info item");
        }
        return new CharacterInfoPlan(before, inventoryIndex, menuSlot);
    }

    public static void startOpenCharacterInfo(Minecraft minecraft, CharacterInfoPlan plan) {
        CharacterInfoPlan current = planOpenCharacterInfo(minecraft, plan.before().screenClass(),
            plan.before().syncId(), plan.before().stateRevision());
        if (current.before().screenIdentity() != plan.before().screenIdentity()
            || current.inventoryIndex() != plan.inventoryIndex() || current.menuSlot() != plan.menuSlot()) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "STATE_CHANGED: Character Info item or inventory screen changed");
        }
        // Let Minecraft's normal item-use interaction handle the Wynncraft menu item.
        minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND);
    }

    public static SkillPlan planSkillAssignment(
        Minecraft minecraft, String skill, int amount, String expectedScreen, int expectedSyncId, long expectedRevision
    ) {
        Skill selected = Skill.parse(skill);
        if (amount < 1 || amount > 5) throw new UiActionService.UiActionException("INVALID_AMOUNT", "amount must be between 1 and 5");
        Snapshot before = capture(minecraft);
        validateContext(before, expectedScreen, expectedSyncId, expectedRevision);
        if (!before.characterInfoRecognized()) {
            throw new UiActionService.UiActionException("UNRECOGNIZED_CHARACTER_INFO", "The current screen is not a verified Character Info screen");
        }
        if (before.unassignedSkillPoints() == null) {
            throw new UiActionService.UiActionException("SKILL_POINT_COUNT_UNKNOWN", "Unassigned Skill Points could not be read unambiguously");
        }
        Integer value = before.skillValues().get(selected.displayName());
        List<SlotView> buttons = skillButtons(before, selected);
        validateSkillAssignment(selected, amount, before.unassignedSkillPoints(), value, buttons.size());
        int button = buttons.getFirst().menuSlot();
        Integer buttonValue = parseButtonValue(before.slots().get(button).item().tooltip(), selected);
        if (!value.equals(buttonValue)) throw new UiActionService.UiActionException("SKILL_VALUE_MISMATCH", "The skill button value does not match the observed Character Info skill value");
        return new SkillPlan(before, selected, amount, value, before.unassignedSkillPoints(), button);
    }

    public static SkillClick startSkillPointClick(Minecraft minecraft, SkillPlan plan, Snapshot expectedBefore) {
        Snapshot current = capture(minecraft);
        if (!current.characterInfoRecognized() || current.screenIdentity() != expectedBefore.screenIdentity()
            || current.syncId() != expectedBefore.syncId() || current.stateRevision() != expectedBefore.stateRevision()
            || !java.util.Objects.equals(current.unassignedSkillPoints(), expectedBefore.unassignedSkillPoints())
            || !java.util.Objects.equals(current.skillValues().get(plan.skill().displayName()),
                expectedBefore.skillValues().get(plan.skill().displayName()))) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "STATE_CHANGED: Character Info or skill values changed before assignment");
        }
        int buttonIndex = findUniqueSkillButton(current, plan.skill());
        Integer buttonValue = parseButtonValue(current.slots().get(buttonIndex).item().tooltip(), plan.skill());
        if (!java.util.Objects.equals(buttonValue, current.skillValues().get(plan.skill().displayName()))) {
            throw new UiActionService.UiActionException("SKILL_VALUE_MISMATCH", "The skill button value does not match the observed skill value");
        }
        AbstractContainerMenu menu = currentMenu(minecraft);
        Slot button = menu.getSlot(buttonIndex);
        if (!button.isActive() || !button.mayPickup(minecraft.player)) {
            throw new UiActionService.UiActionException("SKILL_BUTTON_NOT_INTERACTABLE", "The recognized skill button is not clickable");
        }
        minecraft.gameMode.handleContainerInput(menu.containerId, buttonIndex, 0, ContainerInput.PICKUP, minecraft.player);
        return new SkillClick(expectedBefore, current(minecraft), plan.skill());
    }

    public static boolean skillClickVerified(Snapshot after, SkillClick click) {
        Integer beforeValue = click.before().skillValues().get(click.skill().displayName());
        Integer afterValue = after.skillValues().get(click.skill().displayName());
        Integer beforePoints = click.before().unassignedSkillPoints();
        Integer afterPoints = after.unassignedSkillPoints();
        return after.characterInfoRecognized()
            && after.screenIdentity() == click.before().screenIdentity()
            && after.syncId() == click.before().syncId()
            && after.stateId() != click.before().stateId()
            && after.stateRevision() != click.before().stateRevision()
            && beforeValue != null && afterValue != null && afterValue == beforeValue + 1
            && beforePoints != null && afterPoints != null && afterPoints == beforePoints - 1;
    }

    public static AbilityButtonPlan planOpenAbilityTree(
        Minecraft minecraft, String expectedScreen, int expectedSyncId, long expectedRevision
    ) {
        Snapshot before = capture(minecraft);
        validateContext(before, expectedScreen, expectedSyncId, expectedRevision);
        if (!before.characterInfoRecognized()) {
            throw new UiActionService.UiActionException("UNRECOGNIZED_CHARACTER_INFO", "Open a verified Character Info screen first");
        }
        List<SlotView> matches = before.slots().stream().filter(slot -> slot.item() != null
            && (slot.item().name().equalsIgnoreCase("Ability Tree")
                || slot.item().tooltip().stream().anyMatch(line -> line.text().strip().equalsIgnoreCase("Ability Tree"))))
            .toList();
        if (matches.size() != 1) throw new UiActionService.UiActionException("ABILITY_TREE_BUTTON_NOT_UNIQUE", "A unique Ability Tree button was not identified by item name or tooltip");
        SlotView selected = matches.getFirst();
        if (!selected.active() || !selected.mayPickup()) throw new UiActionService.UiActionException("ABILITY_TREE_BUTTON_NOT_INTERACTABLE", "The recognized Ability Tree button is not clickable");
        return new AbilityButtonPlan(before, selected.menuSlot());
    }

    public static void startOpenAbilityTree(Minecraft minecraft, AbilityButtonPlan plan) {
        Snapshot current = capture(minecraft);
        if (!current.characterInfoRecognized() || current.screenIdentity() != plan.before().screenIdentity()
            || current.syncId() != plan.before().syncId() || current.stateRevision() != plan.before().stateRevision()) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "STATE_CHANGED: Character Info changed before the Ability Tree action");
        }
        AbstractContainerMenu menu = currentMenu(minecraft);
        Slot button = menu.getSlot(plan.menuSlot());
        if (!button.isActive() || !button.mayPickup(minecraft.player)
            || !isAbilityTreeButton(minecraft, button.getItem())) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "STATE_CHANGED: the Ability Tree button changed before interaction");
        }
        minecraft.gameMode.handleContainerInput(menu.containerId, plan.menuSlot(), 0, ContainerInput.PICKUP, minecraft.player);
    }

    public static boolean abilityTreeOpened(Snapshot before, Snapshot after) {
        return after.abilityTreeRecognized()
            && (after.screenIdentity() != before.screenIdentity() || after.stateId() != before.stateId());
    }

    public static boolean characterInfoRecognized(
        String title, boolean hasSkillButton, Integer unassignedSkillPoints, Map<String, Integer> skillValues
    ) {
        return title != null && title.toLowerCase(Locale.ROOT).contains("character info")
            && hasSkillButton && (unassignedSkillPoints != null || skillValues.size() >= 2);
    }

    public static void validateSkillAssignment(
        Skill skill, int amount, Integer availablePoints, Integer currentValue, int uniqueButtonCount
    ) {
        if (skill == null) throw new UiActionService.UiActionException("UNKNOWN_SKILL", "Choose one of the five named Wynncraft skills");
        if (amount < 1 || amount > 5) throw new UiActionService.UiActionException("INVALID_AMOUNT", "amount must be between 1 and 5");
        if (availablePoints == null) throw new UiActionService.UiActionException("SKILL_POINT_COUNT_UNKNOWN", "Unassigned Skill Points could not be read unambiguously");
        if (amount > availablePoints) throw new UiActionService.UiActionException("INSUFFICIENT_SKILL_POINTS", "amount exceeds the currently observed unassigned Skill Points");
        if (currentValue == null) throw new UiActionService.UiActionException("SKILL_VALUE_UNKNOWN", "The current value for this skill could not be read unambiguously");
        if (uniqueButtonCount != 1) throw new UiActionService.UiActionException("SKILL_BUTTON_NOT_UNIQUE", "A unique skill button with an explicit current-point tooltip was not identified");
    }

    private static Snapshot current(Minecraft minecraft) { return capture(minecraft); }

    private static AbstractContainerMenu currentMenu(Minecraft minecraft) {
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            throw new UiActionService.UiActionException("UNSAFE_SCREEN", "A recognized container screen is required");
        }
        return screen.getMenu();
    }

    private static int findUniqueSkillButton(Snapshot snapshot, Skill skill) {
        List<SlotView> matches = skillButtons(snapshot, skill);
        if (matches.size() != 1) throw new UiActionService.UiActionException("SKILL_BUTTON_NOT_UNIQUE",
            "A unique skill button with an explicit current-point tooltip was not identified");
        return matches.getFirst().menuSlot();
    }

    private static List<SlotView> skillButtons(Snapshot snapshot, Skill skill) {
        return snapshot.slots().stream().filter(slot -> slot.item() != null
            && slot.item().name().equalsIgnoreCase(skill.displayName())
            && parseButtonValue(slot.item().tooltip(), skill) != null).toList();
    }

    private static Integer parseButtonValue(List<ItemInspector.TooltipLine> lines, Skill skill) {
        Integer value = null;
        for (ItemInspector.TooltipLine line : lines) {
            String text = line.text();
            Matcher skillMatcher = SKILL_VALUE.matcher(text);
            Matcher genericMatcher = BUTTON_VALUE.matcher(text);
            Integer parsed = null;
            if (skillMatcher.matches() && normalizeSkill(skillMatcher.group(1)).equals(skill.displayName())) parsed = parse(skillMatcher.group(2));
            else if (genericMatcher.matches()) parsed = parse(genericMatcher.group(1));
            if (parsed == null) continue;
            if (value != null && !value.equals(parsed)) return null;
            value = parsed;
        }
        return value;
    }

    private static boolean isCharacterInfoItem(Minecraft minecraft, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!"minecraft:compass".equals(id) || !stack.getHoverName().getString().strip().equalsIgnoreCase("Character Info")) return false;
        ItemInspector.ItemView view = ItemInspector.inspect(minecraft, stack, true, false);
        return view != null && view.tooltip().stream().anyMatch(line ->
            line.text().strip().equalsIgnoreCase("Character Info")
                || line.text().toLowerCase(Locale.ROOT).contains("character info"));
    }

    private static boolean isAbilityTreeButton(Minecraft minecraft, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getHoverName().getString().strip().equalsIgnoreCase("Ability Tree")) return true;
        ItemInspector.ItemView view = ItemInspector.inspect(minecraft, stack, true, false);
        return view != null && view.tooltip().stream().anyMatch(line ->
            line.text().strip().equalsIgnoreCase("Ability Tree"));
    }

    public static void validateContext(Snapshot actual, String expectedScreen, int expectedSyncId, long expectedRevision) {
        if (expectedScreen == null || !expectedScreen.equals(actual.screenClass())
            || actual.syncId() != expectedSyncId || actual.stateRevision() != expectedRevision) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "STATE_CHANGED: screen, syncId, or stateRevision no longer matches");
        }
    }

    private static Integer uniqueValue(Pattern pattern, List<String> lines) {
        Integer found = null;
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line == null ? "" : line);
            if (!matcher.matches()) continue;
            Integer value = parse(matcher.group(1));
            if (value == null) return null;
            if (found != null && !found.equals(value)) return null;
            found = value;
        }
        return found;
    }

    private static Integer parse(String value) {
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return null; }
    }

    private static String normalizeSkill(String skill) {
        if (skill == null || skill.isBlank()) return "";
        if (skill.equalsIgnoreCase("defense")) return "Defence";
        return skill.substring(0, 1).toUpperCase(Locale.ROOT) + skill.substring(1).toLowerCase(Locale.ROOT);
    }

    public enum Skill {
        STRENGTH("Strength"), DEXTERITY("Dexterity"), INTELLIGENCE("Intelligence"), DEFENCE("Defence"), AGILITY("Agility");
        private final String displayName;
        Skill(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
        public static boolean isName(String value) {
            if (value == null) return false;
            try { parse(value); return true; } catch (UiActionService.UiActionException ignored) { return false; }
        }
        public static Skill parse(String value) {
            if (value == null || value.isBlank()) throw new UiActionService.UiActionException("UNKNOWN_SKILL", "Choose strength, dexterity, intelligence, defence, or agility");
            return switch (normalizeSkill(value.strip()).toLowerCase(Locale.ROOT)) {
                case "strength" -> STRENGTH;
                case "dexterity" -> DEXTERITY;
                case "intelligence" -> INTELLIGENCE;
                case "defence" -> DEFENCE;
                case "agility" -> AGILITY;
                default -> throw new UiActionService.UiActionException("UNKNOWN_SKILL", "Choose strength, dexterity, intelligence, defence, or agility");
            };
        }
    }

    public record SlotView(int menuSlot, boolean active, boolean mayPickup, ItemInspector.ItemView item) {}
    public record Snapshot(long capturedAt, String screenClass, String menuClass, String title, int screenIdentity,
                           int syncId, int stateId, long stateRevision, List<SlotView> slots, List<String> evidence,
                           Integer unassignedSkillPoints, Integer unusedAbilityPoints, Map<String, Integer> skillValues,
                           boolean characterInfoRecognized, boolean abilityTreeRecognized, boolean cursorEmpty) {}
    public record CharacterInfoPlan(Snapshot before, int inventoryIndex, int menuSlot) {}
    public record SkillPlan(Snapshot before, Skill skill, int amount, int initialValue, int initialPoints, int menuSlot) {}
    public record SkillClick(Snapshot before, Snapshot afterClick, Skill skill) {}
    public record AbilityButtonPlan(Snapshot before, int menuSlot) {}
}
