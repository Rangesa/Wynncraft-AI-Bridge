package dev.tanaka.wynnaibridge.translation;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable Wynncraft terminology translated locally before any remote request is considered. */
final class TooltipTranslationGlossary {
    private static final Pattern TIER = Pattern.compile("(?i)^Tier\\s+(\\d+)$");
    private static final Pattern EFFECTIVENESS = Pattern.compile("(?i)^Ingredient Effectiveness:(?:\\s*(\\d+(?:[.,]\\d+)?%))?$");
    private static final Pattern EARTH_DAMAGE = Pattern.compile("(?i)^([+\\-−]?\\d+(?:-\\d+)?%?)\\s+Earth Damage$");
    private static final Pattern NEUTRAL_TO_EARTH = Pattern.compile("(?i)^([+\\-−]?\\d+(?:[.,]\\d+)?%)\\s+Neutral to Earth$");
    private static final List<Map.Entry<String, String>> TERMS = List.of(
        Map.entry("Ingredient Effectiveness", "素材効果"),
        Map.entry("Effect on Weapons", "武器への効果"),
        Map.entry("Effect on Armour", "防具への効果"),
        Map.entry("Neutral to Earth", "無属性から地属性へ"),
        Map.entry("Earth Damage", "地属性ダメージ"),
        Map.entry("Tier", "ティア")
    );

    private TooltipTranslationGlossary() {}

    static Optional<String> translate(String normalizedText) {
        if (normalizedText == null) return Optional.empty();
        if (normalizedText.startsWith("tooltip-v2|")) {
            normalizedText = normalizedText.substring("tooltip-v2|".length());
        }

        Optional<String> abilityTreeTerm = AbilityTreeTranslationGlossary.translate(normalizedText);
        if (abilityTreeTerm.isPresent()) return abilityTreeTerm;

        Matcher matcher = TIER.matcher(normalizedText);
        if (matcher.matches()) return Optional.of("ティア " + matcher.group(1));
        if (normalizedText.equalsIgnoreCase("Tier")) return Optional.of("ティア");

        if (normalizedText.equalsIgnoreCase("Effect on Weapons:")) return Optional.of("武器への効果:");
        if (normalizedText.equalsIgnoreCase("Effect on Armour:")) return Optional.of("防具への効果:");

        matcher = EFFECTIVENESS.matcher(normalizedText);
        if (matcher.matches()) {
            return Optional.of("素材効果:" + (matcher.group(1) == null ? "" : " " + matcher.group(1)));
        }

        if (normalizedText.equalsIgnoreCase(
            "Hold this and right-click on a piece of equipment to socket it. Powders are refunded when removed."
        )) {
            return Optional.of("これを手に持ち、装備品を右クリックするとパウダーを装着できます。取り外すと、パウダーは返却されます。");
        }

        matcher = EARTH_DAMAGE.matcher(normalizedText);
        if (matcher.matches()) return Optional.of(matcher.group(1) + " 地属性ダメージ");

        matcher = NEUTRAL_TO_EARTH.matcher(normalizedText);
        if (matcher.matches()) return Optional.of(matcher.group(1) + " 無属性から地属性に変換");

        return Optional.empty();
    }

    static Match matchAt(String text, int offset) {
        for (Map.Entry<String, String> term : TERMS) {
            String english = term.getKey();
            int end = offset + english.length();
            if (end <= text.length() && text.regionMatches(true, offset, english, 0, english.length())
                && hasWordBoundaryBefore(text, offset) && hasWordBoundaryAfter(text, end)) {
                return new Match(end, term.getValue());
            }
        }
        return null;
    }

    private static boolean hasWordBoundaryBefore(String text, int offset) {
        return offset == 0 || !isWordCharacter(text.codePointBefore(offset));
    }

    private static boolean hasWordBoundaryAfter(String text, int offset) {
        return offset >= text.length() || !isWordCharacter(text.codePointAt(offset));
    }

    private static boolean isWordCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    record Match(int end, String translation) {}
}
