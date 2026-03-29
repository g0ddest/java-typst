package name.velikodniy.vitaliy.typst;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TypstFontTest {

    private static final String FONT_RESOURCE = "/fonts/Roboto-Regular.ttf";

    @Test
    void addFontFromByteArray() throws IOException {
        byte[] fontData;
        try (InputStream is = getClass().getResourceAsStream(FONT_RESOURCE)) {
            assertNotNull(is, "Font resource must be found");
            fontData = is.readAllBytes();
        }

        try (var engine = TypstEngine.builder()
                .addFont(fontData)
                .build()) {
            byte[] pdf = engine.template("font-bytes-test",
                            "#set text(font: \"Roboto\")\n= Hello with Roboto font")
                    .renderPdf();
            PdfAssert.assertPdfMatchesReference(pdf, "font-bytes");
        }
    }

    @Test
    void addFontFromInputStream() {
        try (var is = getClass().getResourceAsStream(FONT_RESOURCE)) {
            assertNotNull(is, "Font resource must be found");
            try (var engine = TypstEngine.builder()
                    .addFont(is)
                    .build()) {
                byte[] pdf = engine.template("font-stream-test",
                                "#set text(font: \"Roboto\")\n= Hello with Roboto font")
                        .renderPdf();
            PdfAssert.assertPdfMatchesReference(pdf, "font-stream");
            }
        } catch (IOException e) {
            fail("IOException reading font: " + e.getMessage());
        }
    }

    @Test
    void addFontFromDirectory(@TempDir Path tempDir) throws IOException {
        // Copy font to temp dir
        try (InputStream is = getClass().getResourceAsStream(FONT_RESOURCE)) {
            assertNotNull(is, "Font resource must be found");
            Files.copy(is, tempDir.resolve("Roboto-Regular.ttf"));
        }

        try (var engine = TypstEngine.builder()
                .addFontDir(tempDir)
                .build()) {
            byte[] pdf = engine.template("font-dir-test",
                            "#set text(font: \"Roboto\")\n= Hello with Roboto font")
                    .renderPdf();
            PdfAssert.assertPdfMatchesReference(pdf, "font-dir");
        }
    }

    @Test
    void invalidFontDataThrows() {
        assertThrows(TypstEngineException.class, () ->
                TypstEngine.builder()
                        .addFont(new byte[]{1, 2, 3})
                        .build());
    }
}
