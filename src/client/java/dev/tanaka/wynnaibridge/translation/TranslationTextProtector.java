package dev.tanaka.wynnaibridge.translation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Protects quantities and Minecraft/Wynncraft private glyph code points from translation. */
final class TranslationTextProtector {
    private static final Pattern NUMBER = Pattern.compile(
        "(?<![\\p{L}\\p{N}])[+\\-−]?\\d+(?:[.,]\\d+)?(?:\\s*[-–]\\s*\\d+(?:[.,]\\d+)?)?\\s*%?(?![\\p{L}\\p{N}])"
    );
    private static final Pattern PLAYER_AUTHOR = Pattern.compile(
        "(?i)\\b(?:crafted|found|identified|obtained|looted)\\s+by\\s+([\\p{L}\\p{N}_-]{2,32})"
    );

    private TranslationTextProtector() {}

    static ProtectedText protect(String text) {
        List<Range> ranges = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) ranges.add(new Range(matcher.start(), matcher.end(), matcher.group()));
        matcher = PLAYER_AUTHOR.matcher(text);
        while (matcher.find()) {
            ranges.add(new Range(matcher.start(1), matcher.end(1), matcher.group(1)));
        }
        ranges.sort(java.util.Comparator.comparingInt(Range::start));

        StringBuilder protectedText = new StringBuilder(text.length() + 32);
        List<Token> tokens = new ArrayList<>();
        int cursor = 0;
        for (Range range : ranges) {
            if (range.start() < cursor) continue;
            protectGlyphs(text, cursor, range.start(), protectedText, tokens);
            protectedText.append(addToken(range.value(), tokens));
            cursor = range.end();
        }
        protectGlyphs(text, cursor, text.length(), protectedText, tokens);
        return new ProtectedText(protectedText.toString(), List.copyOf(tokens));
    }

    private static void protectGlyphs(String text, int start, int end, StringBuilder output, List<Token> tokens) {
        int cursor = start;
        while (cursor < end) {
            int codePoint = text.codePointAt(cursor);
            int next = cursor + Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            boolean glyph = type == Character.PRIVATE_USE || type == Character.FORMAT
                || type == Character.CONTROL;
            if (glyph) {
                output.append(text, start, cursor);
                output.append(addToken(text.substring(cursor, next), tokens));
                start = next;
            }
            cursor = next;
        }
        output.append(text, start, end);
    }

    private static String addToken(String value, List<Token> tokens) {
        String token = "WABXQ" + tokens.size() + "QXBW";
        tokens.add(new Token(token, value));
        return token;
    }

    record ProtectedText(String text, List<Token> tokens) {
        String restore(String translated) {
            String restored = translated;
            for (Token token : tokens) {
                int first = restored.indexOf(token.marker());
                if (first < 0 || restored.indexOf(token.marker(), first + token.marker().length()) >= 0) {
                    throw new IllegalArgumentException("Translator changed a protected number or glyph");
                }
                restored = restored.substring(0, first) + token.value()
                    + restored.substring(first + token.marker().length());
            }
            return restored;
        }
    }

    record Token(String marker, String value) {}

    private record Range(int start, int end, String value) {}
}
