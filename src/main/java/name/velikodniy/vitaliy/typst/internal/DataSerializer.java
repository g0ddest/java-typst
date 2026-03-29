package name.velikodniy.vitaliy.typst.internal;

import name.velikodniy.vitaliy.typst.TypstEngineException;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.AbstractMap;

/**
 * Custom JSON serializer for Java objects. No external dependencies.
 * Converts Java data objects to JSON strings for passing to Typst templates.
 */
public final class DataSerializer {

    private static final DateTimeFormatter LOCAL_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private DataSerializer() {}

    /**
     * Serialize any Java object to a JSON string.
     *
     * @param value the object to serialize (may be null)
     * @return the JSON string representation
     */
    public static String toJson(Object value) {
        var sb = new StringBuilder();
        serialize(value, sb);
        return sb.toString();
    }

    private static void serialize(Object value, StringBuilder sb) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeString(s, sb);
            case Boolean b -> sb.append(b ? "true" : "false");
            case BigDecimal bd -> writeString(bd.toPlainString(), sb);
            case Number n -> sb.append(numberToString(n));
            case LocalDate ld -> writeString(ld.toString(), sb);
            case LocalDateTime ldt -> writeString(ldt.format(LOCAL_DATE_TIME_FORMAT), sb);
            case Enum<?> e -> writeString(e.name(), sb);
            case Map<?, ?> map -> writeMap(map, sb);
            case Collection<?> coll -> writeCollection(coll, sb);
            default -> {
                if (value.getClass().isArray()) {
                    writeArray(value, sb);
                } else if (value.getClass().isRecord()) {
                    writeRecord(value, sb);
                } else {
                    writePojo(value, sb);
                }
            }
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static String numberToString(Number n) {
        if (n instanceof Double d) {
            // Avoid trailing ".0" for whole numbers? No — Double.toString handles it.
            // Actually 3.14 -> "3.14", which is correct.
            return d.toString();
        }
        if (n instanceof Float f) {
            return f.toString();
        }
        // Integer, Long, Short, Byte — all produce integral strings
        return n.toString();
    }

    private static void writeMap(Map<?, ?> map, StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(String.valueOf(entry.getKey()), sb);
            sb.append(':');
            serialize(entry.getValue(), sb);
        }
        sb.append('}');
    }

    private static void writeCollection(Collection<?> coll, StringBuilder sb) {
        sb.append('[');
        boolean first = true;
        for (var item : coll) {
            if (!first) sb.append(',');
            first = false;
            serialize(item, sb);
        }
        sb.append(']');
    }

    private static void writeArray(Object array, StringBuilder sb) {
        int len = Array.getLength(array);
        sb.append('[');
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(',');
            serialize(Array.get(array, i), sb);
        }
        sb.append(']');
    }

    private static void writeRecord(Object record, StringBuilder sb) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        sb.append('{');
        for (int i = 0; i < components.length; i++) {
            if (i > 0) sb.append(',');
            writeString(components[i].getName(), sb);
            sb.append(':');
            try {
                var accessor = components[i].getAccessor();
                accessor.trySetAccessible();
                Object val = accessor.invoke(record);
                serialize(val, sb);
            } catch (ReflectiveOperationException e) {
                throw new TypstEngineException("Failed to read record component: " + components[i].getName(), e);
            }
        }
        sb.append('}');
    }

    private static void writePojo(Object pojo, StringBuilder sb) {
        var entries = extractPojoEntries(pojo);
        sb.append('{');
        boolean first = true;
        for (var entry : entries) {
            if (!first) sb.append(',');
            first = false;
            writeString(entry.getKey(), sb);
            sb.append(':');
            serialize(entry.getValue(), sb);
        }
        sb.append('}');
    }

    private static List<Map.Entry<String, Object>> extractPojoEntries(Object pojo) {
        var entries = new ArrayList<Map.Entry<String, Object>>();
        Set<String> objectMethods = Set.of(
            "getClass", "hashCode", "toString", "notify", "notifyAll", "wait"
        );

        Method[] methods = pojo.getClass().getMethods();
        // Sort for deterministic order
        Arrays.sort(methods, Comparator.comparing(Method::getName));

        for (Method method : methods) {
            if (method.getParameterCount() != 0) continue;
            if (objectMethods.contains(method.getName())) continue;

            String name = method.getName();
            String fieldName = null;

            if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
                fieldName = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            } else if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))
                       && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                fieldName = Character.toLowerCase(name.charAt(2)) + name.substring(3);
            }

            if (fieldName != null) {
                try {
                    Object val = method.invoke(pojo);
                    entries.add(new AbstractMap.SimpleEntry<>(fieldName, val));
                } catch (Exception e) {
                    throw new TypstEngineException("Failed to invoke getter: " + name, e);
                }
            }
        }
        return entries;
    }

    /**
     * Builder for merging multiple data sources into a single JSON object.
     * Supports key-value pairs, records, and raw JSON strings.
     */
    public static final class Builder {
        private final LinkedHashMap<String, Object> data = new LinkedHashMap<>();

        /**
         * Add a key-value pair. Last write wins on conflict.
         *
         * @param key   the JSON field name
         * @param value the value to serialize
         */
        public void put(String key, Object value) {
            data.put(key, value);
        }

        /**
         * Expand a record's components as top-level keys.
         *
         * @param record the Java record whose components are merged into this builder
         */
        public void putRecord(Object record) {
            if (!record.getClass().isRecord()) {
                throw new IllegalArgumentException("Expected a record, got: " + record.getClass());
            }
            RecordComponent[] components = record.getClass().getRecordComponents();
            for (var component : components) {
                try {
                    var accessor = component.getAccessor();
                    accessor.trySetAccessible();
                    Object val = accessor.invoke(record);
                    data.put(component.getName(), val);
                } catch (ReflectiveOperationException e) {
                    throw new TypstEngineException("Failed to read record component: " + component.getName(), e);
                }
            }
        }

        /**
         * Parse top-level keys from a raw JSON object string and merge them.
         * Only supports flat or nested JSON objects at the top level.
         *
         * @param json the raw JSON object string to parse and merge
         */
        public void putRawJson(String json) {
            // Minimal JSON object parser: extract top-level key-value pairs
            json = json.trim();
            if (!json.startsWith("{") || !json.endsWith("}")) {
                throw new IllegalArgumentException("putRawJson expects a JSON object");
            }
            // Strip outer braces
            String inner = json.substring(1, json.length() - 1).trim();
            if (inner.isEmpty()) return;

            int pos = 0;
            while (pos < inner.length()) {
                // Skip whitespace
                pos = skipWhitespace(inner, pos);
                if (pos >= inner.length()) break;

                // Parse key
                if (inner.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected '\"' at position " + pos);
                }
                int keyStart = pos + 1;
                int keyEnd = findClosingQuote(inner, keyStart);
                String key = inner.substring(keyStart, keyEnd);
                pos = keyEnd + 1;

                // Skip colon
                pos = skipWhitespace(inner, pos);
                if (pos >= inner.length() || inner.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Expected ':' at position " + pos);
                }
                pos++;
                pos = skipWhitespace(inner, pos);

                // Parse value as raw string — find its extent
                int valueStart = pos;
                pos = skipJsonValue(inner, pos);
                String rawValue = inner.substring(valueStart, pos).trim();

                // Convert raw value to Java object
                data.put(key, parseJsonValue(rawValue));

                // Skip comma
                pos = skipWhitespace(inner, pos);
                if (pos < inner.length() && inner.charAt(pos) == ',') {
                    pos++;
                }
            }
        }

        /**
         * Serialize merged data as a JSON object.
         *
         * @return the merged JSON object string
         */
        public String toJson() {
            var sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (var entry : data.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(entry.getKey(), sb);
                sb.append(':');
                serialize(entry.getValue(), sb);
            }
            sb.append('}');
            return sb.toString();
        }

        // --- Minimal JSON parsing helpers ---

        private static int skipWhitespace(String s, int pos) {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
            return pos;
        }

        private static int findClosingQuote(String s, int pos) {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '\\') {
                    pos += 2; // skip escaped char
                } else if (c == '"') {
                    return pos;
                } else {
                    pos++;
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private static int skipJsonValue(String s, int pos) {
            if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end of input");
            char c = s.charAt(pos);
            if (c == '"') {
                // String
                int end = findClosingQuote(s, pos + 1);
                return end + 1;
            } else if (c == '{') {
                return skipBraced(s, pos, '{', '}');
            } else if (c == '[') {
                return skipBraced(s, pos, '[', ']');
            } else {
                // number, boolean, null — scan to delimiter
                while (pos < s.length() && ",}] \t\n\r".indexOf(s.charAt(pos)) < 0) pos++;
                return pos;
            }
        }

        private static int skipBraced(String s, int pos, char open, char close) {
            int depth = 0;
            boolean inString = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (inString) {
                    if (c == '\\') {
                        pos++;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else {
                    if (c == '"') {
                        inString = true;
                    } else if (c == open) {
                        depth++;
                    } else if (c == close) {
                        depth--;
                        if (depth == 0) return pos + 1;
                    }
                }
                pos++;
            }
            throw new IllegalArgumentException("Unterminated " + open);
        }

        private static String unescapeJsonString(String s) {
            if (s.indexOf('\\') < 0) return s;
            var sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char next = s.charAt(++i);
                    switch (next) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'u'  -> {
                            if (i + 4 < s.length()) {
                                String hex = s.substring(i + 1, i + 5);
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } else {
                                sb.append('\\').append('u');
                            }
                        }
                        default -> sb.append('\\').append(next);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private static Object parseJsonValue(String raw) {
            if (raw.startsWith("\"")) {
                // String — remove quotes and unescape
                return unescapeJsonString(raw.substring(1, raw.length() - 1));
            } else if (raw.equals("null")) {
                return null;
            } else if (raw.equals("true")) {
                return true;
            } else if (raw.equals("false")) {
                return false;
            } else if (raw.contains(".")) {
                return Double.parseDouble(raw);
            } else {
                long l = Long.parseLong(raw);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            }
        }
    }
}
