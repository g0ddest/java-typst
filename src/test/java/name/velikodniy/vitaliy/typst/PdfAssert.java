package name.velikodniy.vitaliy.typst;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PDF assertion utilities for tests.
 * Supports byte-by-byte comparison after stripping variable parts
 * (creation date, modification date, IDs, producer metadata).
 */
final class PdfAssert {

    private PdfAssert() {}

    /** Validate that bytes represent a structurally valid PDF. */
    static void assertValidPdf(byte[] pdf) {
        assertNotNull(pdf, "PDF must not be null");
        assertTrue(pdf.length > 100, "PDF should be more than 100 bytes, was " + pdf.length);
        String header = new String(pdf, 0, 5);
        assertEquals("%PDF-", header, "PDF must start with %PDF- header");
        // Check for %%EOF marker near the end
        String tail = new String(pdf, Math.max(0, pdf.length - 64), 64);
        assertTrue(tail.contains("%%EOF"), "PDF must contain %%EOF marker near the end");
    }

    /**
     * Compare two PDFs byte-by-byte after stripping variable parts.
     * Variable parts: /CreationDate, /ModDate, /ID, /Producer, /ModDate timestamps.
     */
    static void assertPdfEquals(byte[] expected, byte[] actual) {
        assertValidPdf(expected);
        assertValidPdf(actual);

        byte[] normalizedExpected = stripVariables(expected);
        byte[] normalizedActual = stripVariables(actual);

        if (Arrays.equals(normalizedExpected, normalizedActual)) {
            return; // identical
        }

        // Find first difference for a helpful error message
        int minLen = Math.min(normalizedExpected.length, normalizedActual.length);
        for (int i = 0; i < minLen; i++) {
            if (normalizedExpected[i] != normalizedActual[i]) {
                int contextStart = Math.max(0, i - 20);
                int contextEnd = Math.min(minLen, i + 20);
                String expectedContext = safeAscii(normalizedExpected, contextStart, contextEnd);
                String actualContext = safeAscii(normalizedActual, contextStart, contextEnd);
                fail("PDFs differ at byte " + i + " (of " + minLen + ").\n"
                        + "Expected context: ..." + expectedContext + "...\n"
                        + "Actual context:   ..." + actualContext + "...");
            }
        }
        if (normalizedExpected.length != normalizedActual.length) {
            fail("PDFs have different lengths after normalization: expected "
                    + normalizedExpected.length + ", actual " + normalizedActual.length);
        }
    }

    /**
     * Compare actual PDF against a reference file stored in test resources.
     * If the reference file doesn't exist yet, creates it (first run generates references).
     */
    static void assertPdfMatchesReference(byte[] actual, String referenceName) {
        assertValidPdf(actual);

        Path refDir = Path.of("src/test/resources/reference-pdfs");
        Path refFile = refDir.resolve(referenceName + ".pdf");

        if (!Files.exists(refFile)) {
            // First run: save as reference
            try {
                Files.createDirectories(refDir);
                Files.write(refFile, actual);
            } catch (IOException e) {
                fail("Failed to write reference PDF: " + e.getMessage());
            }
            return; // No comparison on first run
        }

        try {
            byte[] expected = Files.readAllBytes(refFile);
            assertPdfEquals(expected, actual);
        } catch (IOException e) {
            fail("Failed to read reference PDF: " + e.getMessage());
        }
    }

    /**
     * Strip variable parts from PDF bytes so deterministic comparison is possible.
     * Replaces:
     *  - /CreationDate (D:YYYYMMDDHHmmSS...)
     *  - /ModDate (D:YYYYMMDDHHmmSS...)
     *  - /ID [<hex> <hex>]
     *  - /Producer (...)
     */
    static byte[] stripVariables(byte[] pdf) {
        // Work with the raw bytes as a string for regex matching
        // PDF is mostly ASCII with some binary streams
        String content = new String(pdf, StandardCharsets.ISO_8859_1);

        // Replace /CreationDate (D:20260329152955+03'00')  →  /CreationDate (D:00000000000000+00'00')
        content = replacePattern(content,
                "/CreationDate\\s*\\(D:[^)]*\\)",
                "/CreationDate (D:00000000000000+00'00')");

        // Replace /ModDate (D:...)
        content = replacePattern(content,
                "/ModDate\\s*\\(D:[^)]*\\)",
                "/ModDate (D:00000000000000+00'00')");

        // Replace /ID [<hex> <hex>]
        content = replacePattern(content,
                "/ID\\s*\\[\\s*<[0-9A-Fa-f]*>\\s*<[0-9A-Fa-f]*>\\s*]",
                "/ID [<00000000000000000000000000000000> <00000000000000000000000000000000>]");

        // Replace /Producer (Typst ...)
        content = replacePattern(content,
                "/Producer\\s*\\([^)]*\\)",
                "/Producer (normalized)");

        // Normalize xref byte offsets — these change if any variable-length field changes.
        // We zero-out the startxref pointer and the xref offset values.
        content = replacePattern(content,
                "startxref\\s+\\d+",
                "startxref 0000000000");

        return content.getBytes();
    }

    private static String replacePattern(String input, String regex, String replacement) {
        return Pattern.compile(regex).matcher(input).replaceAll(Matcher.quoteReplacement(replacement));
    }

    private static String safeAscii(byte[] data, int from, int to) {
        var sb = new StringBuilder();
        for (int i = from; i < to && i < data.length; i++) {
            byte b = data[i];
            if (b >= 32 && b < 127) {
                sb.append((char) b);
            } else {
                sb.append(String.format("\\x%02x", b & 0xff));
            }
        }
        return sb.toString();
    }
}
