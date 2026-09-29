package dev.tanaka.wynnaibridge.capture;

import java.util.Locale;

/**
 * Removes Wynncraft/Minecraft rendering-only glyph noise while preserving the
 * user-visible natural-language content. This is intentionally conservative:
 * ordinary Unicode (including Japanese) is preserved; only formatting/control,
 * private-use, tag and variation-selector code points are treated as noise.
 */
public final class WynnTextSanitizer {
    private WynnTextSanitizer() {}

    public static String sanitize(String input) {
        if (input == null || input.isBlank()) return "";

        StringBuilder out = new StringBuilder(input.length());
        boolean pendingSpace = false;

        for (int offset = 0; offset < input.length();) {
            int cp = input.codePointAt(offset);
            offset += Character.charCount(cp);

            // Legacy Minecraft formatting code: § + one format/color code point.
            if (cp == '\u00A7') {
                if (offset < input.length()) {
                    int next = input.codePointAt(offset);
                    offset += Character.charCount(next);
                }
                continue;
            }

            if (cp == '\n' || cp == '\r' || cp == '\t' || Character.isWhitespace(cp)) {
                pendingSpace = out.length() > 0;
                continue;
            }

            if (isRenderingNoise(cp)) {
                // Treat a run of custom glyphs as a separator. This prevents
                // useful text on either side from being accidentally joined.
                pendingSpace = out.length() > 0;
                continue;
            }

            if (pendingSpace && out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                out.append(' ');
            }
            pendingSpace = false;
            out.appendCodePoint(cp);
        }

        return collapseSpaces(out.toString()).strip();
    }

    public static String comparisonKey(String input) {
        return sanitize(input).toLowerCase(Locale.ROOT);
    }

    public static boolean hadRenderingNoise(String input) {
        if (input == null) return false;
        for (int offset = 0; offset < input.length();) {
            int cp = input.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '\u00A7' || isRenderingNoise(cp)) return true;
        }
        return false;
    }

    private static boolean isRenderingNoise(int cp) {
        // BMP private-use area.
        if (cp >= 0xE000 && cp <= 0xF8FF) return true;
        // Supplementary private-use areas A/B.
        if (cp >= 0xF0000 && cp <= 0xFFFFD) return true;
        if (cp >= 0x100000 && cp <= 0x10FFFD) return true;
        // Variation Selectors and Variation Selectors Supplement.
        if (cp >= 0xFE00 && cp <= 0xFE0F) return true;
        if (cp >= 0xE0100 && cp <= 0xE01EF) return true;
        // Unicode tag characters are frequently used as invisible layout data.
        if (cp >= 0xE0000 && cp <= 0xE007F) return true;

        int type = Character.getType(cp);
        return type == Character.CONTROL
            || type == Character.FORMAT
            || type == Character.PRIVATE_USE
            || type == Character.SURROGATE
            || type == Character.UNASSIGNED;
    }

    private static String collapseSpaces(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean previousSpace = false;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            boolean space = Character.isWhitespace(cp);
            if (space) {
                if (!previousSpace && out.length() > 0) out.append(' ');
            } else {
                out.appendCodePoint(cp);
            }
            previousSpace = space;
        }
        return out.toString();
    }
}
