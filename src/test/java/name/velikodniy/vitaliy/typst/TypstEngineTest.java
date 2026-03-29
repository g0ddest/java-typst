package name.velikodniy.vitaliy.typst;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TypstEngineTest {

    @Test
    void createAndCloseEngine() {
        TypstEngine engine = TypstEngine.builder().build();
        assertNotNull(engine);
        engine.close();
    }

    @Test
    void engineIsAutoCloseable() {
        try (var engine = TypstEngine.builder().build()) {
            assertNotNull(engine);
        }
    }

    @Test
    void doubleCloseDoesNotThrow() {
        TypstEngine engine = TypstEngine.builder().build();
        engine.close();
        assertDoesNotThrow(engine::close);
    }

    @Test
    void useAfterCloseThrows() {
        TypstEngine engine = TypstEngine.builder().build();
        engine.close();
        assertThrows(TypstEngineException.class, () ->
                engine.template("test", "= Hello").renderPdf());
    }

    @Test
    void builderWithNonexistentFontDirThrows() {
        assertThrows(TypstEngineException.class, () ->
                TypstEngine.builder()
                        .addFontDir(Path.of("/nonexistent/font/dir/that/does/not/exist"))
                        .build());
    }

    @Test
    void renderSimpleStringTemplate() {
        try (var engine = TypstEngine.builder().build()) {
            byte[] pdf = engine.template("hello", "= Hello, World!").renderPdf();
            PdfAssert.assertPdfMatchesReference(pdf, "hello-world");
        }
    }
}
