package tgrv.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.parseValue();
        p.skipWhitespace();
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        return (Map<String, Object>) parse(text);
    }

    public static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public static long asLong(Object o, long defaultValue) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        return defaultValue;
    }

    private static final class Parser {
        private final String s;
        private int i;
        private final int len;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
        }

        void skipWhitespace() {
            while (i < len && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        char peek() {
            return s.charAt(i);
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        void expect(String literal) {
            if (!s.regionMatches(i, literal, 0, literal.length())) {
                throw new IllegalArgumentException("Expected '" + literal + "' at position " + i);
            }
            i += literal.length();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++;
            skipWhitespace();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw new IllegalArgumentException("Expected ':' at position " + i);
                }
                i++;
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    i++;
                } else if (c == '}') {
                    i++;
                    break;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' at position " + i);
                }
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            i++;
            skipWhitespace();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    i++;
                } else if (c == ']') {
                    i++;
                    break;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' at position " + i);
                }
            }
            return list;
        }

        String parseString() {
            if (peek() != '"') {
                throw new IllegalArgumentException("Expected '\"' at position " + i);
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            String hex = s.substring(i, i + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                            break;
                        default:
                            sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object parseNumber() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            while (i < len && Character.isDigit(s.charAt(i))) {
                i++;
            }
            boolean isDouble = false;
            if (i < len && s.charAt(i) == '.') {
                isDouble = true;
                i++;
                while (i < len && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
            if (i < len && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                isDouble = true;
                i++;
                if (i < len && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                while (i < len && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
            String num = s.substring(start, i);
            if (isDouble) {
                return Double.parseDouble(num);
            }
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                return Double.parseDouble(num);
            }
        }
    }

    public static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int idx = 0; idx < value.length(); idx++) {
            char c = value.charAt(idx);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
