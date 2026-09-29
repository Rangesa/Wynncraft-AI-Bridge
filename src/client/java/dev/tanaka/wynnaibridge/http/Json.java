package dev.tanaka.wynnaibridge.http;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {}

    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder(4096);
        append(out, value);
        return out.toString();
    }

    /**
     * Small dependency-free JSON parser for the MCP JSON-RPC endpoint.
     * It deliberately accepts only ordinary JSON values and rejects trailing data.
     */
    public static Object parse(String json) {
        if (json == null) throw new IllegalArgumentException("JSON is null");
        Parser parser = new Parser(json);
        Object value = parser.value();
        parser.ws();
        if (!parser.end()) throw parser.error("Trailing data");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Expected JSON object");
        Map<String, Object> out = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Object key is not a string");
            out.put(key, entry.getValue());
        }
        return out;
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Double d) {
            out.append(Double.isFinite(d) ? d : "null");
        } else if (value instanceof Float f) {
            out.append(Float.isFinite(f) ? f : "null");
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof String s) {
            string(out, s);
        } else if (value instanceof Enum<?> e) {
            string(out, e.name());
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                string(out, String.valueOf(entry.getKey()));
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?> collection) {
            out.append('[');
            boolean first = true;
            for (Object item : collection) {
                if (!first) out.append(',');
                first = false;
                append(out, item);
            }
            out.append(']');
        } else if (value.getClass().isArray()) {
            out.append('[');
            int len = Array.getLength(value);
            for (int i = 0; i < len; i++) {
                if (i > 0) out.append(',');
                append(out, Array.get(value, i));
            }
            out.append(']');
        } else if (value.getClass().isRecord()) {
            out.append('{');
            RecordComponent[] components = value.getClass().getRecordComponents();
            for (int i = 0; i < components.length; i++) {
                if (i > 0) out.append(',');
                RecordComponent component = components[i];
                string(out, component.getName());
                out.append(':');
                try {
                    append(out, component.getAccessor().invoke(value));
                } catch (ReflectiveOperationException e) {
                    out.append("null");
                }
            }
            out.append('}');
        } else {
            string(out, String.valueOf(value));
        }
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String text;
        private int pos;

        private Parser(String text) {
            this.text = text;
        }

        private Object value() {
            ws();
            if (end()) throw error("Expected value");
            return switch (text.charAt(pos)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            ws();
            Map<String, Object> out = new LinkedHashMap<>();
            if (take('}')) return out;
            while (true) {
                ws();
                if (end() || text.charAt(pos) != '"') throw error("Expected object key");
                String key = string();
                ws();
                expect(':');
                out.put(key, value());
                ws();
                if (take('}')) return out;
                expect(',');
            }
        }

        private List<Object> array() {
            expect('[');
            ws();
            List<Object> out = new ArrayList<>();
            if (take(']')) return out;
            while (true) {
                out.add(value());
                ws();
                if (take(']')) return out;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!end()) {
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    if (c < 0x20) throw error("Control character in string");
                    out.append(c);
                    continue;
                }
                if (end()) throw error("Unterminated escape");
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"', '\\', '/' -> out.append(esc);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(unicode());
                    default -> throw error("Invalid escape");
                }
            }
            throw error("Unterminated string");
        }

        private char unicode() {
            if (pos + 4 > text.length()) throw error("Invalid unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char c = text.charAt(pos++);
                int digit = Character.digit(c, 16);
                if (digit < 0) throw error("Invalid unicode escape");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Object number() {
            int start = pos;
            if (take('-')) {}
            if (take('0')) {
                // leading zero consumed
            } else {
                digits();
            }
            boolean floating = false;
            if (take('.')) {
                floating = true;
                digits();
            }
            if (!end() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                floating = true;
                pos++;
                if (!end() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                digits();
            }
            if (pos == start) throw error("Expected number");
            String raw = text.substring(start, pos);
            try {
                if (floating) return Double.parseDouble(raw);
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw error("Invalid number");
            }
        }

        private void digits() {
            int start = pos;
            while (!end() && Character.isDigit(text.charAt(pos))) pos++;
            if (pos == start) throw error("Expected digit");
        }

        private Object literal(String literal, Object value) {
            if (!text.startsWith(literal, pos)) throw error("Invalid literal");
            pos += literal.length();
            return value;
        }

        private void ws() {
            while (!end()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                else break;
            }
        }

        private boolean take(char expected) {
            if (!end() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw error("Expected '" + expected + "'");
        }

        private boolean end() {
            return pos >= text.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + pos);
        }
    }
}
