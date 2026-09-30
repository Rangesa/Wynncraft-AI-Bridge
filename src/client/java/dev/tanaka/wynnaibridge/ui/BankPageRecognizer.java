package dev.tanaka.wynnaibridge.ui;

import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.ChestMenu;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Multi-signal recognizer for the currently visible Wynncraft Bank page. */
public final class BankPageRecognizer {
    private static final int SLOT_COUNT = 90;
    private static final int BANK_CONTENT_END = 44;
    private static final int QUICK_ACTIONS_SLOT = 46;
    private static final int STORAGE_TYPE_SLOT = 47;
    private static final int PREVIOUS_PAGE_SLOT = 51;
    private static final int NEXT_PAGE_SLOT = 52;
    private static final Pattern PREVIOUS_LABEL = Pattern.compile("(?i)^page\\s+(\\d+)\\s*<<<<<$");
    private static final Pattern NEXT_LABEL = Pattern.compile("(?i)^page\\s+(\\d+)\\s*>>>>>$");
    private static final Pattern PAGE_COUNT = Pattern.compile("(?i)\\bpage\\s+(\\d+)\\s*(?:/|of)\\s*(\\d+)\\b");

    private BankPageRecognizer() {}

    public static PageInfo recognize(
        String screenClass,
        String menuClass,
        String title,
        int syncId,
        int totalSlots,
        List<SlotEvidence> slots,
        Collection<String> capturedText
    ) {
        if (!ContainerScreen.class.getName().equals(screenClass)
            || !ChestMenu.class.getName().equals(menuClass)
            || syncId <= 0 || totalSlots != SLOT_COUNT || slots == null || slots.size() != SLOT_COUNT) {
            return PageInfo.unrecognized("screen, menu, sync id, or slot count did not match the recognized Bank layout");
        }

        Map<Integer, SlotEvidence> bySlot = slots.stream().collect(Collectors.toMap(
            SlotEvidence::menuSlot, value -> value, (left, right) -> left
        ));
        if (bySlot.size() != SLOT_COUNT) return PageInfo.unrecognized("slot indices were incomplete or duplicated");
        for (int i = 0; i < SLOT_COUNT; i++) if (!bySlot.containsKey(i)) {
            return PageInfo.unrecognized("slot indices were incomplete");
        }
        for (int i = 0; i <= BANK_CONTENT_END; i++) {
            if (!"OPEN_CONTAINER".equals(bySlot.get(i).section())) {
                return PageInfo.unrecognized("Bank content slot ownership did not match the expected container layout");
            }
        }
        for (int i = 54; i < SLOT_COUNT; i++) {
            if (!"PLAYER_INVENTORY".equals(bySlot.get(i).section())) {
                return PageInfo.unrecognized("player inventory slot ownership did not match the expected container layout");
            }
        }
        if (!"Quick Actions".equals(normalize(bySlot.get(QUICK_ACTIONS_SLOT).itemName()))
            || !"Storage Type".equals(normalize(bySlot.get(STORAGE_TYPE_SLOT).itemName()))) {
            return PageInfo.unrecognized("Bank control items were not present at their expected positions");
        }

        Integer previousTarget = pageTarget(bySlot.get(PREVIOUS_PAGE_SLOT).itemName(), PREVIOUS_LABEL);
        Integer nextTarget = pageTarget(bySlot.get(NEXT_PAGE_SLOT).itemName(), NEXT_LABEL);
        Integer textPage = null;
        Integer pageCount = null;
        for (String text : combinedText(title, capturedText)) {
            Matcher matcher = PAGE_COUNT.matcher(normalize(text));
            if (matcher.find()) {
                int parsedPage = parse(matcher.group(1));
                int parsedCount = parse(matcher.group(2));
                if (parsedPage > 0 && parsedCount >= parsedPage) {
                    if (textPage != null && !textPage.equals(parsedPage)) {
                        return PageInfo.unrecognized("conflicting visible Bank page text was captured");
                    }
                    textPage = parsedPage;
                    pageCount = parsedCount;
                }
            }
        }

        Integer currentPage = null;
        if (textPage != null) {
            currentPage = textPage;
            boolean previousMatches = previousTarget == null || previousTarget == currentPage - 1
                || (currentPage == 1 && previousTarget.equals(currentPage));
            boolean nextMatches = nextTarget == null || nextTarget == currentPage + 1
                || (pageCount != null && currentPage.equals(pageCount) && nextTarget.equals(currentPage));
            if (!previousMatches || !nextMatches) {
                return PageInfo.unrecognized("visible Bank page text disagreed with the navigation controls");
            }
        } else if (previousTarget != null && nextTarget != null) {
            int fromPrevious = previousTarget + 1;
            int fromNext = nextTarget - 1;
            if (fromPrevious != fromNext) return PageInfo.unrecognized("Bank navigation labels disagree about the current page");
            currentPage = fromPrevious;
        }
        if (currentPage == null || currentPage < 1 || (pageCount != null && currentPage > pageCount)) {
            return PageInfo.unrecognized("current Bank page could not be established from navigation or page text");
        }

        return new PageInfo(true, currentPage, pageCount, previousTarget, nextTarget,
            "recognized from ContainerScreen, ChestMenu, 90-slot ownership, control items, and page navigation evidence");
    }

    private static List<String> combinedText(String title, Collection<String> capturedText) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        if (title != null) result.add(title);
        if (capturedText != null) result.addAll(capturedText);
        return result;
    }

    private static Integer pageTarget(String label, Pattern pattern) {
        Matcher matcher = pattern.matcher(normalize(label));
        if (!matcher.matches()) return null;
        int parsed = parse(matcher.group(1));
        return parsed > 0 ? parsed : null;
    }

    private static int parse(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return -1; }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    public record SlotEvidence(int menuSlot, String section, String itemName, List<String> tooltip) {}

    public record PageInfo(
        boolean recognized,
        Integer currentPage,
        Integer pageCount,
        Integer previousPageTarget,
        Integer nextPageTarget,
        String reason
    ) {
        public static PageInfo unrecognized(String reason) {
            return new PageInfo(false, null, null, null, null, reason);
        }
    }
}
