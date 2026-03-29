package name.velikodniy.vitaliy.typst;

import name.velikodniy.vitaliy.typst.internal.DataSerializer;
import name.velikodniy.vitaliy.typst.internal.TypstNative;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a Typst template bound to data. Supports fluent data binding
 * and rendering to PDF.
 *
 * <p>Usage:
 * <pre>{@code
 * byte[] pdf = engine.template("invoice.typ")
 *         .data("customer", customerRecord)
 *         .data("total", 42.50)
 *         .renderPdf();
 * }</pre>
 *
 * <p>Not thread-safe. Create a new template instance per render call.
 */
public final class TypstTemplate {

    private final TypstEngine engine;
    private final String templateKey;
    private final String source;
    private final DataSerializer.Builder dataBuilder = new DataSerializer.Builder();

    TypstTemplate(TypstEngine engine, String templateKey, String source) {
        this.engine = engine;
        this.templateKey = templateKey;
        this.source = source;
    }

    /**
     * Add a key-value pair to the template data. The value is serialized to JSON
     * using {@link DataSerializer}.
     *
     * @param key   the data key (accessible in Typst as a variable)
     * @param value the value (String, Number, Boolean, Record, Collection, Map, etc.)
     * @return this template for chaining
     */
    public TypstTemplate data(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        dataBuilder.put(key, value);
        return this;
    }

    /**
     * Expand a Java record's fields as top-level template data keys.
     * For example, a record {@code Invoice(String customer, double total)} would add
     * keys "customer" and "total".
     *
     * @param record a Java record instance
     * @return this template for chaining
     */
    public TypstTemplate data(Object record) {
        Objects.requireNonNull(record, "record must not be null");
        dataBuilder.putRecord(record);
        return this;
    }

    /**
     * Merge a raw JSON object string into the template data. The JSON must be
     * a top-level object (e.g. {@code {"key": "value"}}).
     *
     * @param json a JSON object string
     * @return this template for chaining
     */
    public TypstTemplate dataJson(String json) {
        Objects.requireNonNull(json, "json must not be null");
        dataBuilder.putRawJson(json);
        return this;
    }

    /**
     * Compile the template with the bound data and return the PDF bytes.
     *
     * @return PDF document as a byte array
     * @throws TypstCompilationException if the template has errors
     * @throws TypstNativeException      if a native call fails
     */
    public byte[] renderPdf() {
        String dataJson = dataBuilder.toJson();
        MemorySegment enginePtr = engine.enginePtr();
        MemorySegment resultPtr = null;

        try (var arena = Arena.ofConfined()) {
            resultPtr = TypstNative.compile(arena, enginePtr, templateKey, source, dataJson);

            if (resultPtr == null || resultPtr.equals(MemorySegment.NULL)) {
                throw new TypstNativeException("Compilation returned null result");
            }

            if (!TypstNative.resultIsOk(resultPtr)) {
                String errorsJson = TypstNative.resultErrors(resultPtr);
                String warningsJson = TypstNative.resultWarnings(resultPtr);
                List<TypstDiagnostic> diagnostics = parseDiagnostics(errorsJson, warningsJson);
                String message = buildErrorMessage(diagnostics);
                throw new TypstCompilationException(message, diagnostics);
            }

            byte[] pdf = TypstNative.resultPdf(arena, resultPtr);
            if (pdf == null || pdf.length == 0) {
                throw new TypstNativeException("Compilation succeeded but produced no PDF output");
            }
            return pdf;
        } finally {
            if (resultPtr != null && !resultPtr.equals(MemorySegment.NULL)) {
                TypstNative.resultFree(resultPtr);
            }
        }
    }

    // --- Diagnostics parsing ---

    /**
     * Parse the JSON arrays of errors and warnings into TypstDiagnostic objects.
     * Uses a minimal JSON parser to avoid external dependencies.
     */
    static List<TypstDiagnostic> parseDiagnostics(String errorsJson, String warningsJson) {
        List<TypstDiagnostic> result = new ArrayList<>();
        parseDiagnosticArray(errorsJson, TypstDiagnostic.Severity.ERROR, result);
        parseDiagnosticArray(warningsJson, TypstDiagnostic.Severity.WARNING, result);
        return result;
    }

    private static void parseDiagnosticArray(String json, TypstDiagnostic.Severity severity,
                                              List<TypstDiagnostic> out) {
        if (json == null || json.isBlank()) return;
        json = json.trim();
        if (!json.startsWith("[") || json.equals("[]")) return;

        // Minimal parser: extract each object from the array
        int pos = 1; // skip '['
        while (pos < json.length()) {
            pos = skipWs(json, pos);
            if (pos >= json.length() || json.charAt(pos) == ']') break;
            if (json.charAt(pos) == ',') { pos++; continue; }
            if (json.charAt(pos) == '{') {
                int objStart = pos;
                pos = skipBraced(json, pos);
                String objStr = json.substring(objStart, pos);
                out.add(parseSingleDiagnostic(objStr, severity));
            } else {
                pos++;
            }
        }
    }

    private static TypstDiagnostic parseSingleDiagnostic(String obj, TypstDiagnostic.Severity severity) {
        String message = extractStringField(obj, "message");
        String file = extractStringField(obj, "file");
        int line = extractIntField(obj, "line");
        int column = extractIntField(obj, "column");
        String hint = extractStringField(obj, "hint");
        return new TypstDiagnostic(severity, message, file, line, column, hint);
    }

    private static String extractStringField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + key.length());
        if (colonIdx < 0) return null;
        int valStart = skipWs(json, colonIdx + 1);
        if (valStart >= json.length()) return null;
        char c = json.charAt(valStart);
        if (c == 'n' && json.startsWith("null", valStart)) return null;
        if (c != '"') return null;
        int strStart = valStart + 1;
        int strEnd = findClosingQuote(json, strStart);
        return unescapeJsonString(json.substring(strStart, strEnd));
    }

    private static int extractIntField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return 0;
        int colonIdx = json.indexOf(':', idx + key.length());
        if (colonIdx < 0) return 0;
        int valStart = skipWs(json, colonIdx + 1);
        if (valStart >= json.length()) return 0;
        char c = json.charAt(valStart);
        if (c == 'n') return 0; // null
        int end = valStart;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == valStart) return 0;
        try {
            return Integer.parseInt(json.substring(valStart, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String unescapeJsonString(String s) {
        if (s.indexOf('\\') < 0) return s;
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            String hex = s.substring(i + 1, i + 5);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                    }
                    default -> { sb.append('\\'); sb.append(next); }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int skipWs(String s, int pos) {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        return pos;
    }

    private static int findClosingQuote(String s, int pos) {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '\\') {
                pos += 2;
            } else if (c == '"') {
                return pos;
            } else {
                pos++;
            }
        }
        return s.length();
    }

    private static int skipBraced(String s, int pos) {
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
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) return pos + 1;
                }
            }
            pos++;
        }
        return pos;
    }

    private static String buildErrorMessage(List<TypstDiagnostic> diagnostics) {
        long errorCount = diagnostics.stream()
                .filter(d -> d.severity() == TypstDiagnostic.Severity.ERROR)
                .count();
        long warningCount = diagnostics.stream()
                .filter(d -> d.severity() == TypstDiagnostic.Severity.WARNING)
                .count();

        var sb = new StringBuilder("Typst compilation failed");
        if (errorCount > 0) {
            sb.append(" with ").append(errorCount).append(" error");
            if (errorCount > 1) sb.append("s");
        }
        if (warningCount > 0) {
            if (errorCount > 0) sb.append(" and");
            sb.append(" ").append(warningCount).append(" warning");
            if (warningCount > 1) sb.append("s");
        }

        // Include first error message for convenience
        diagnostics.stream()
                .filter(d -> d.severity() == TypstDiagnostic.Severity.ERROR)
                .findFirst()
                .ifPresent(d -> sb.append(": ").append(d.message()));

        return sb.toString();
    }
}
