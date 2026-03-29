package name.velikodniy.vitaliy.typst;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TypstErrorTest {

    @Test
    void invalidTemplateThrowsCompilationException() {
        try (var engine = TypstEngine.builder().build()) {
            TypstCompilationException ex = assertThrows(TypstCompilationException.class, () ->
                    engine.template("error-test", "#unknown_function()").renderPdf());
            assertNotNull(ex.getDiagnostics());
            assertFalse(ex.getDiagnostics().isEmpty(), "Diagnostics should not be empty");
        }
    }

    @Test
    void diagnosticHasLineAndColumn() {
        try (var engine = TypstEngine.builder().build()) {
            // Multi-line template with error on third line
            String source = "= Title\n\n#unknown_function()";
            TypstCompilationException ex = assertThrows(TypstCompilationException.class, () ->
                    engine.template("diag-test", source).renderPdf());

            assertFalse(ex.getDiagnostics().isEmpty());
            TypstDiagnostic firstError = ex.getDiagnostics().stream()
                    .filter(d -> d.severity() == TypstDiagnostic.Severity.ERROR)
                    .findFirst()
                    .orElse(null);
            assertNotNull(firstError, "Expected at least one ERROR diagnostic");
            assertNotNull(firstError.message());
            assertFalse(firstError.message().isEmpty(), "Error message should not be empty");
            // Line should be > 0 since error is not on the first line
            assertTrue(firstError.line() > 0, "Error line should be > 0, was " + firstError.line());
        }
    }

    @Test
    void emptyTemplateProducesPdf() {
        try (var engine = TypstEngine.builder().build()) {
            byte[] pdf = engine.template("empty-test", "").renderPdf();
            PdfAssert.assertPdfMatchesReference(pdf, "empty");
        }
    }
}
