package dev.tanaka.wynnaibridge.translation;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local Japanese terms for stable labels used by Wynncraft's Ability Tree tooltips. */
public final class AbilityTreeTranslationGlossary {
    private static final Map<String, String> FIXED = Map.ofEntries(
        Map.entry("You do not meet the requirements", "必要条件を満たしていません"),
        Map.entry("Class Requirements", "クラス要件"),
        Map.entry("Class Requirement", "クラス要件"),
        Map.entry("Class Req", "クラス要件"),
        Map.entry("Class Type", "クラス要件"),
        Map.entry("Combat Lv. Min", "最低戦闘レベル"),
        Map.entry("Fishing Lv. Min", "最低釣りレベル"),
        Map.entry("Woodcutting Lv. Min", "最低伐採レベル"),
        Map.entry("Thunder Main Attack Damage", "雷属性メイン攻撃ダメージ"),
        Map.entry("Main Attack Damage", "メイン攻撃ダメージ"),
        Map.entry("Fire Damage", "火属性ダメージ"),
        Map.entry("Combat Level", "戦闘レベル"),
        Map.entry("Fishing Level", "釣りレベル"),
        Map.entry("Woodcutting Level", "伐採レベル"),
        Map.entry("Ability Points", "アビリティポイント"),
        Map.entry("Required Ability", "必要アビリティ"),
        Map.entry("Total Damage", "総ダメージ"),
        Map.entry("Area of Effect", "効果範囲"),
        Map.entry("Mana Cost", "マナ消費"),
        Map.entry("Damage", "ダメージ"),
        Map.entry("Archer", "アーチャー"),
        Map.entry("Hunter", "ハンター"),
        Map.entry("Woodcutting", "伐採"),
        Map.entry("Fishing", "釣り"),
        Map.entry("Combat", "戦闘"),
        Map.entry("Mining", "採掘"),
        Map.entry("Farming", "農業"),
        Map.entry("Level", "レベル"),
        Map.entry("Water", "水"),
        Map.entry("Range", "射程"),
        Map.entry("Duration", "持続時間"),
        Map.entry("Cooldown", "クールダウン"),
        Map.entry("Archetype", "アーキタイプ"),
        Map.entry("Blocks", "ブロック"),
        Map.entry("Block", "ブロック")
    );
    private static final List<Map.Entry<String, String>> SORTED_TERMS = FIXED.entrySet().stream()
        .sorted(Map.Entry.<String, String>comparingByKey(Comparator.comparingInt(String::length).reversed()))
        .toList();
    private static final String ARCHETYPE_ALTERNATION = String.join("|", List.of(
        "Boltslinger", "Sharpshooter", "Battle Monk", "Light Bender", "Shadestepper",
        "Riftwalker", "Arcanist", "Trapper", "Fallen", "Paladin", "Trickster",
        "Acrobat", "Summoner", "Ritualist", "Acolyte"
    ));
    private static final Pattern MIN_ARCHETYPE = Pattern.compile(
        "(?i)\\bMin\\s+(" + ARCHETYPE_ALTERNATION + ")\\s+Archetype\\b"
    );

    private AbilityTreeTranslationGlossary() {}

    /** Exact full-line lookup, used by the shared service before its persistent cache. */
    public static Optional<String> translate(String normalizedText) {
        if (normalizedText == null) return Optional.empty();
        String key = normalizedText.strip().replaceFirst("\\s*:$", "").toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> term : FIXED.entrySet()) {
            if (term.getKey().toLowerCase(Locale.ROOT).equals(key)) return Optional.of(term.getValue());
        }
        Matcher matcher = MIN_ARCHETYPE.matcher(normalizedText.strip().replaceFirst("\\s*:$", ""));
        if (matcher.matches()) return Optional.of("必要な" + matcher.group(1) + "アーキタイプ数");
        return Optional.empty();
    }

    static Match matchAt(String text, int offset) {
        Matcher minimum = MIN_ARCHETYPE.matcher(text);
        minimum.region(offset, text.length());
        if (minimum.lookingAt() && hasWordBoundaryBefore(text, offset)
            && hasWordBoundaryAfter(text, minimum.end())) {
            return new Match(minimum.end(), "必要な" + minimum.group(1) + "アーキタイプ数");
        }

        for (Map.Entry<String, String> term : SORTED_TERMS) {
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
