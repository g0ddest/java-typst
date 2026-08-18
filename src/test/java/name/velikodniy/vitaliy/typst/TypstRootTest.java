package name.velikodniy.vitaliy.typst;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the explicit root directory: {@code TypstEngine.Builder.root(Path)}
 * and {@code TypstTemplate.root(Path)}.
 */
class TypstRootTest {

    private static final String HELPER = "#let greet(name) = [Hello, #name!]\n";
    private static final String IMPORTING_SOURCE =
            "#import \"helper.typ\": greet\n#greet(\"Root\")\n";

    @Test
    void builderRootResolvesStringTemplateImport(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("helper.typ"), HELPER);

        try (var engine = TypstEngine.builder().root(tempDir).build()) {
            byte[] pdf = engine.template("builder-root", IMPORTING_SOURCE).renderPdf();
            PdfAssert.assertValidPdf(pdf);
        }
    }

    @Test
    void templateRootResolvesStringTemplateImport(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("helper.typ"), HELPER);

        try (var engine = TypstEngine.builder().build()) {
            byte[] pdf = engine.template("template-root", IMPORTING_SOURCE)
                    .root(tempDir)
                    .renderPdf();
            PdfAssert.assertValidPdf(pdf);
        }
    }

    @Test
    void templateRootOverridesBuilderRoot(@TempDir Path dirA, @TempDir Path dirB)
            throws IOException {
        Files.writeString(dirA.resolve("helper.typ"), "#let word = [AAA]\n");
        Files.writeString(dirB.resolve("helper.typ"), "#let word = [BBB]\n");
        String source = "#import \"helper.typ\": word\n#word\n";

        try (var engine = TypstEngine.builder()
                .root(dirA)
                .enableTemplateCache(false)
                .build()) {
            byte[] fromDefault = engine.template("override-a", source).renderPdf();
            byte[] fromOverride = engine.template("override-b", source).root(dirB).renderPdf();

            assertFalse(
                    Arrays.equals(
                            PdfAssert.stripVariables(fromDefault),
                            PdfAssert.stripVariables(fromOverride)),
                    "The per-template root must win over the engine default");
        }
    }

    @Test
    void fileTemplateSiblingImportWorksWithAncestorRoot(@TempDir Path tempDir)
            throws IOException {
        Path sub = Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(sub.resolve("helper.typ"), HELPER);
        Path main = sub.resolve("main.typ");
        Files.writeString(main, IMPORTING_SOURCE);

        try (var engine = TypstEngine.builder().root(tempDir).build()) {
            byte[] pdf = engine.template(main).renderPdf();
            PdfAssert.assertValidPdf(pdf);
        }
    }

    @Test
    void fileTemplateLegacySiblingImportStillWorks(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("helper.typ"), HELPER);
        Path main = tempDir.resolve("main.typ");
        Files.writeString(main, IMPORTING_SOURCE);

        try (var engine = TypstEngine.builder().build()) {
            byte[] pdf = engine.template(main).renderPdf();
            PdfAssert.assertValidPdf(pdf);
        }
    }

    @Test
    void fileTemplateOutsideRootFailsWithClearDiagnostic(
            @TempDir Path rootDir, @TempDir Path otherDir) throws IOException {
        Path main = otherDir.resolve("main.typ");
        Files.writeString(main, "Hello\n");

        try (var engine = TypstEngine.builder().root(rootDir).build()) {
            var ex = assertThrows(TypstCompilationException.class,
                    () -> engine.template(main).renderPdf());
            assertTrue(ex.getMessage().contains("must be contained in the root directory"),
                    "Unexpected message: " + ex.getMessage());
        }
    }

    @Test
    void traversalOutsideRootFailsWithoutLeakingContent(@TempDir Path parent)
            throws IOException {
        Path root = Files.createDirectory(parent.resolve("root"));
        String secret = "SUPER-SECRET-CONTENT-42";
        Files.writeString(parent.resolve("secret.typ"), "#let leak = [" + secret + "]\n");

        try (var engine = TypstEngine.builder().root(root).build()) {
            var ex = assertThrows(TypstCompilationException.class,
                    () -> engine.template("escape",
                                    "#import \"../secret.typ\": leak\n#leak\n")
                            .renderPdf());
            assertFalse(ex.getMessage().contains(secret),
                    "Diagnostics must not leak file content outside the root");
        }
    }

    @Test
    void subdirectoryAndAbsoluteImportsResolveUnderRoot(@TempDir Path tempDir)
            throws IOException {
        Path sub = Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(sub.resolve("helper.typ"), HELPER);
        String source = "#import \"/sub/helper.typ\": greet\n#greet(\"Absolute\")\n";

        try (var engine = TypstEngine.builder().root(tempDir).build()) {
            byte[] pdf = engine.template("absolute-import", source).renderPdf();
            PdfAssert.assertValidPdf(pdf);
        }
    }

    @Test
    void rootWithSpacesAndNonAsciiName(@TempDir Path tempDir) throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("spä ce dir"));
        Files.writeString(root.resolve("helper.typ"), HELPER);

        try (var engine = TypstEngine.builder().root(root).build()) {
            byte[] pdf = engine.template("space-root", IMPORTING_SOURCE).renderPdf();
            PdfAssert.assertValidPdf(pdf);
        }
    }

    @Test
    void boundDataShadowsRealDataJsonUnderRoot(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("data.json"), "{\"name\": \"disk\"}");
        String source = "#let d = json(\"data.json\")\nHello, #d.name!\n";

        try (var engine = TypstEngine.builder()
                .root(tempDir)
                .enableTemplateCache(false)
                .build()) {
            byte[] bound = engine.template("shadow-a", source)
                    .data("name", "virtual")
                    .renderPdf();
            byte[] unbound = engine.template("shadow-b", source).renderPdf();

            assertFalse(
                    Arrays.equals(
                            PdfAssert.stripVariables(bound),
                            PdfAssert.stripVariables(unbound)),
                    "Bound data must shadow the on-disk data.json; without bound data "
                            + "the real file must be readable");
        }
    }

    @Test
    void nullRootsThrowNpe() {
        assertThrows(NullPointerException.class,
                () -> TypstEngine.builder().root(null));
        try (var engine = TypstEngine.builder().build()) {
            assertThrows(NullPointerException.class,
                    () -> engine.template("npe", "Hello").root(null));
        }
    }
}
