package dev.tanaka.wynnaibridge.translation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits semantic Ability Tree values from the English prose sent to DeepL. */
final class AbilityTreeTextPreprocessor {
    private static final Pattern NUMBER_OR_MEASURE = Pattern.compile(
        "[+\\-\\u2212\\u00B1]?\\d+(?:[.,]\\d+)?"
            + "(?:\\s*[-\\u2013\\u2014]\\s*\\d+(?:[.,]\\d+)?)?"
            + "(?:\\s*(?:%|ms|seconds?|s|m|h))?"
    );
    private static final Pattern PROPER_NAME = Pattern.compile(
        "[A-Z][A-Za-z0-9'’\\-]*(?:\\s+[A-Z][A-Za-z0-9'’\\-]*)+"
    );
    private static final Pattern STANDALONE_PROPER_NAME = Pattern.compile(
        "^(?:[A-Z][A-Za-z0-9'’\\-]*)(?:\\s+[A-Z][A-Za-z0-9'’\\-]*){1,5}$"
    );
    private static final List<String> ARCHETYPES = List.of(
        "Boltslinger", "Sharpshooter", "Battle Monk", "Light Bender", "Shadestepper",
        "Riftwalker", "Arcanist", "Trapper", "Fallen", "Paladin", "Trickster",
        "Acrobat", "Summoner", "Ritualist", "Acolyte"
    ).stream().sorted((left, right) -> Integer.compare(right.length(), left.length())).toList();
    private static final List<String> ABILITY_NAMES = List.of(
        "Bryophyte Roots", "Nimble String", "Phantom Ray", "Arrow Storm", "Arrow Bomb",
        "Arrow Shield", "Arrow Hurricane", "Guardian Angels", "Double Shots", "Shrapnel Bomb",
        "Grape Bomb", "Windstorm", "Main Attack", "Battle Monk", "Light Bender", "Shadestepper"
    ).stream().sorted((left, right) -> Integer.compare(right.length(), left.length())).toList();
    private static final List<String> TECHNICAL_TERMS = List.of("DPS", "AOE", "AP", "CC");
    private static final int PRIVATE_MARKER_BASE = 0xF0000;

    private AbilityTreeTextPreprocessor() {}

    static PreparedText prepare(String source, String abilityTitle) {
        if (source == null) source = "";
        String title = abilityTitle == null ? "" : abilityTitle.strip();
        StringBuilder request = new StringBuilder(source.length() + 16);
        Map<String, Replacement> replacements = new LinkedHashMap<>();

        if (isStandaloneProperName(source, title)) {
            appendPlaceholder(request, replacements, source, RunKind.PROTECTED);
            return new PreparedText(request.toString(), Map.copyOf(replacements), false);
        }

        for (int offset = 0; offset < source.length();) {
            AbilityTreeTranslationGlossary.Match fixed = AbilityTreeTranslationGlossary.matchAt(source, offset);
            if (fixed != null) {
                appendPlaceholder(request, replacements, fixed.translation(), RunKind.FIXED_JAPANESE);
                offset = fixed.end();
                continue;
            }

            TooltipTranslationGlossary.Match tooltipFixed = TooltipTranslationGlossary.matchAt(source, offset);
            if (tooltipFixed != null) {
                appendPlaceholder(request, replacements, tooltipFixed.translation(), RunKind.FIXED_JAPANESE);
                offset = tooltipFixed.end();
                continue;
            }

            Match protectedText = matchProtectedText(source, offset, title);
            if (protectedText != null) {
                appendPlaceholder(request, replacements, source.substring(offset, protectedText.end()), RunKind.PROTECTED);
                offset = protectedText.end();
                continue;
            }

            int numberEnd = numberEndAt(source, offset);
            if (numberEnd > offset) {
                appendPlaceholder(request, replacements, source.substring(offset, numberEnd), RunKind.PROTECTED);
                offset = numberEnd;
                continue;
            }

            int codePoint = source.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (isGlyphOrSymbol(type) || isSeparator(type) || Character.isDigit(codePoint)
                || isNonLatinLetter(codePoint) || isLegacyFontLetter(codePoint)) {
                int end = offset + Character.charCount(codePoint);
                appendPlaceholder(request, replacements, source.substring(offset, end), RunKind.PROTECTED);
                offset = end;
                continue;
            }

            request.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }

        String requestText = request.toString();
        boolean hasNaturalEnglish = Pattern.compile("[A-Za-z]{2,}").matcher(requestText).find();
        return new PreparedText(requestText, Map.copyOf(replacements), hasNaturalEnglish);
    }

    private static boolean isStandaloneProperName(String text, String abilityTitle) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) return false;
        if (!abilityTitle.isBlank() && trimmed.equalsIgnoreCase(abilityTitle)) return true;
        if (!STANDALONE_PROPER_NAME.matcher(trimmed).matches()) return false;

        AbilityTreeTranslationGlossary.Match abilityTreeTerm = AbilityTreeTranslationGlossary.matchAt(trimmed, 0);
        if (abilityTreeTerm != null && abilityTreeTerm.end() == trimmed.length()) return false;
        TooltipTranslationGlossary.Match tooltipTerm = TooltipTranslationGlossary.matchAt(trimmed, 0);
        return tooltipTerm == null || tooltipTerm.end() != trimmed.length();
    }

    private static Match matchProtectedText(String text, int offset, String abilityTitle) {
        if (!abilityTitle.isBlank() && regionMatchesWord(text, offset, abilityTitle)) {
            return new Match(offset + abilityTitle.length());
        }

        for (String name : ABILITY_NAMES) {
            if (regionMatchesWord(text, offset, name)) return new Match(offset + name.length());
        }
        for (String archetype : ARCHETYPES) {
            if (regionMatchesWord(text, offset, archetype)) return new Match(offset + archetype.length());
        }
        for (String term : TECHNICAL_TERMS) {
            if (regionMatchesWord(text, offset, term)) return new Match(offset + term.length());
        }

        // A multi-word title-case phrase within a sentence is treated as a proper ability/item name.
        // The first word of a sentence is skipped so ordinary prose such as "Condense Arrow Storm" is
        // still translated while the actual ability name remains protected.
        if (isSentenceInitial(text, offset)) return null;
        Matcher properName = PROPER_NAME.matcher(text);
        properName.region(offset, text.length());
        if (properName.lookingAt() && hasWordBoundaryBefore(text, offset)
            && hasWordBoundaryAfter(text, properName.end())) {
            return new Match(properName.end());
        }

        return null;
    }

    private static int numberEndAt(String text, int offset) {
        if (offset > 0 && isWordCharacter(text.codePointBefore(offset))) return offset;
        Matcher matcher = NUMBER_OR_MEASURE.matcher(text);
        matcher.region(offset, text.length());
        if (!matcher.lookingAt()) return offset;
        int end = matcher.end();
        if (end < text.length() && isWordCharacter(text.codePointAt(end))) return offset;
        return end;
    }

    private static boolean isSentenceInitial(String text, int offset) {
        int previous = offset;
        while (previous > 0) {
            int codePoint = text.codePointBefore(previous);
            if (!Character.isWhitespace(codePoint)) return codePoint == '.' || codePoint == '!' || codePoint == '?';
            previous -= Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean regionMatchesWord(String text, int offset, String candidate) {
        int end = offset + candidate.length();
        return end <= text.length() && text.regionMatches(true, offset, candidate, 0, candidate.length())
            && hasWordBoundaryBefore(text, offset) && hasWordBoundaryAfter(text, end);
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

    private static boolean isGlyphOrSymbol(int type) {
        return type == Character.PRIVATE_USE || type == Character.FORMAT || type == Character.CONTROL
            || type == Character.MATH_SYMBOL || type == Character.CURRENCY_SYMBOL
            || type == Character.MODIFIER_SYMBOL || type == Character.OTHER_SYMBOL;
    }

    private static boolean isSeparator(int type) {
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
            || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
            || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
            || type == Character.OTHER_PUNCTUATION;
    }

    private static boolean isNonLatinLetter(int codePoint) {
        return Character.isLetter(codePoint)
            && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN;
    }

    /** Wynncraft also encodes custom glyphs in Latin-1 code points such as U+00C0. */
    private static boolean isLegacyFontLetter(int codePoint) {
        return codePoint > 0x7F && Character.isLetter(codePoint)
            && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }

    private static void appendPlaceholder(
        StringBuilder output,
        Map<String, Replacement> replacements,
        String value,
        RunKind kind
    ) {
        int privateCodePoint = PRIVATE_MARKER_BASE + replacements.size();
        String marker = new String(Character.toChars(privateCodePoint));
        replacements.put(marker, new Replacement(value, kind));
        output.append(marker);
    }

    private record Match(int end) {}

    enum RunKind { FIXED_JAPANESE, PROTECTED }

    record Replacement(String text, RunKind kind) {}

    record PreparedText(String requestText, Map<String, Replacement> replacements, boolean hasNaturalEnglish) {
        String restore(String translatedText) {
            String restored = translatedText;
            for (Map.Entry<String, Replacement> replacement : replacements.entrySet()) {
                String marker = replacement.getKey();
                int first = restored.indexOf(marker);
                if (first < 0 || restored.indexOf(marker, first + marker.length()) >= 0) {
                    throw new IllegalArgumentException("Ability Tree translation changed a protected run");
                }
                restored = restored.substring(0, first) + replacement.getValue().text()
                    + restored.substring(first + marker.length());
            }
            return restored;
        }
    }
}
