package name.velikodniy.vitaliy.typst;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TypstCacheTest {

    @Test
    void cachedTemplateProducesSameResult() {
        try (var engine = TypstEngine.builder().enableTemplateCache(true).build()) {
            byte[] pdf1 = engine.template("cache-test", "= Cached Document").renderPdf();
            byte[] pdf2 = engine.template("cache-test", "= Cached Document").renderPdf();
            PdfAssert.assertPdfEquals(pdf1, pdf2);
        }
    }

    @Test
    void differentDataProducesDifferentPdf() {
        try (var engine = TypstEngine.builder().enableTemplateCache(true).build()) {
            String source = "#let data = json(\"data.json\")\n= Hello, #data.name!";
            byte[] pdf1 = engine.template("diff-data-test", source)
                    .data("name", "Alice")
                    .renderPdf();
            byte[] pdf2 = engine.template("diff-data-test", source)
                    .data("name", "Bob")
                    .renderPdf();
            PdfAssert.assertValidPdf(pdf1);
            PdfAssert.assertValidPdf(pdf2);
            // Content differs — normalized PDFs should NOT be equal
            byte[] n1 = PdfAssert.stripVariables(pdf1);
            byte[] n2 = PdfAssert.stripVariables(pdf2);
            assertFalse(Arrays.equals(n1, n2),
                    "Different data should produce different PDFs");
        }
    }

    @Test
    void invalidateTemplateByName() {
        try (var engine = TypstEngine.builder().enableTemplateCache(true).build()) {
            byte[] pdf1 = engine.template("inv-test", "= Version 1").renderPdf();
            PdfAssert.assertValidPdf(pdf1);

            engine.invalidateTemplate("inv-test");

            byte[] pdf2 = engine.template("inv-test", "= Version 2").renderPdf();
            PdfAssert.assertValidPdf(pdf2);
        }
    }

    @Test
    void invalidateAllTemplates() {
        try (var engine = TypstEngine.builder().enableTemplateCache(true).build()) {
            byte[] pdf1 = engine.template("inv-all-a", "= Doc A").renderPdf();
            byte[] pdf2 = engine.template("inv-all-b", "= Doc B").renderPdf();
            PdfAssert.assertValidPdf(pdf1);
            PdfAssert.assertValidPdf(pdf2);

            engine.invalidateAllTemplates();

            byte[] pdf3 = engine.template("inv-all-a", "= Doc A v2").renderPdf();
            byte[] pdf4 = engine.template("inv-all-b", "= Doc B v2").renderPdf();
            PdfAssert.assertValidPdf(pdf3);
            PdfAssert.assertValidPdf(pdf4);
        }
    }

    @Test
    void invalidateByDifferentPathSpellingHitsSameCacheEntry(@TempDir Path tempDir)
            throws IOException {
        Path sub = Files.createDirectory(tempDir.resolve("sub"));
        Path main = tempDir.resolve("main.typ");
        Path roundabout = sub.resolve("..").resolve("main.typ");
        Files.writeString(main, "= Version 1");

        try (var engine = TypstEngine.builder().enableTemplateCache(true).build()) {
            byte[] pdf1 = engine.template(roundabout).renderPdf();
            PdfAssert.assertValidPdf(pdf1);

            Files.writeString(main, "= Version 2");
            // Different spelling of the same file must invalidate the same key.
            engine.invalidateTemplate(main);

            byte[] pdf2 = engine.template(roundabout).renderPdf();
            assertFalse(Arrays.equals(
                            PdfAssert.stripVariables(pdf1),
                            PdfAssert.stripVariables(pdf2)),
                    "Render after invalidation must pick up the new file content");
        }
    }

    @Test
    void cacheDisabledStillWorks() {
        try (var engine = TypstEngine.builder().enableTemplateCache(false).build()) {
            byte[] pdf1 = engine.template("no-cache-test", "= No Cache").renderPdf();
            byte[] pdf2 = engine.template("no-cache-test", "= No Cache").renderPdf();
            PdfAssert.assertPdfEquals(pdf1, pdf2);
        }
    }
}
