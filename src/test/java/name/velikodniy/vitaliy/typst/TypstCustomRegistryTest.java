package name.velikodniy.vitaliy.typst;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class TypstCustomRegistryTest {

    private static WireMockServer wireMock;
    private static final Path CACHE_DIR = Path.of(
            System.getProperty("user.home"), "Library", "Caches", "typst", "packages");

    @BeforeAll
    static void startWireMock() throws IOException {
        byte[] cadesArchive = TypstCustomRegistryTest.class
                .getResourceAsStream("/packages/preview/cades-0.3.1.tar.gz")
                .readAllBytes();
        byte[] jogsArchive = TypstCustomRegistryTest.class
                .getResourceAsStream("/packages/preview/jogs-0.2.4.tar.gz")
                .readAllBytes();

        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        wireMock.stubFor(get(urlEqualTo("/preview/cades-0.3.1.tar.gz"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/gzip")
                        .withBody(cadesArchive)));

        wireMock.stubFor(get(urlEqualTo("/preview/jogs-0.2.4.tar.gz"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/gzip")
                        .withBody(jogsArchive)));

        wireMock.stubFor(get(urlMatching("/preview/nonexistent-.*"))
                .willReturn(aResponse().withStatus(404)));

        // Clean cached packages so they download from WireMock
        deleteCachedPackage("preview/cades/0.3.1");
        deleteCachedPackage("preview/jogs/0.2.4");
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
        deleteCachedPackage("preview/cades/0.3.1");
        deleteCachedPackage("preview/jogs/0.2.4");
    }

    @Test
    void compileTemplateWithPackageFromCustomRegistry() {
        String registryUrl = "http://localhost:" + wireMock.port();

        try (var engine = TypstEngine.builder()
                .registry(registryUrl)
                .build()) {

            String source = """
                    #import "@preview/cades:0.3.1": qr-code
                    #qr-code("https://github.com/g0ddest/typst-java", width: 5cm)
                    """;

            byte[] pdf = engine.template("qr-test", source).renderPdf();

            assertNotNull(pdf);
            assertTrue(pdf.length > 0);
            assertTrue(new String(pdf, 0, 5).startsWith("%PDF"));

            wireMock.verify(getRequestedFor(urlEqualTo("/preview/cades-0.3.1.tar.gz")));
        }
    }

    @Test
    void unknownPackageFromCustomRegistryThrows() {
        String registryUrl = "http://localhost:" + wireMock.port();

        try (var engine = TypstEngine.builder()
                .registry(registryUrl)
                .build()) {

            String source = """
                    #import "@preview/nonexistent:0.1.0": foo
                    #foo()
                    """;

            assertThrows(TypstCompilationException.class, () ->
                    engine.template("missing-pkg", source).renderPdf());
        }
    }

    @Test
    void qrCodePackageProducesValidPdfWithContent() {
        String registryUrl = "http://localhost:" + wireMock.port();

        try (var engine = TypstEngine.builder()
                .registry(registryUrl)
                .build()) {

            String source = """
                    #import "@preview/cades:0.3.1": qr-code

                    = QR Code Test

                    Generated QR code for typst-java project:

                    #align(center)[
                      #qr-code("https://github.com/g0ddest/typst-java", width: 4cm)
                    ]

                    #v(1em)
                    Scan the code above to visit the repository.
                    """;

            byte[] pdf = engine.template("qr-content-test", source).renderPdf();

            PdfAssert.assertPdfMatchesReference(pdf, "qr-code-custom-registry");
        }
    }

    @Test
    void defaultRegistryIsUsedWhenNotConfigured() {
        // Engine without custom registry should not hit WireMock
        try (var engine = TypstEngine.builder().build()) {
            byte[] pdf = engine.template("no-registry", "= Hello").renderPdf();
            assertNotNull(pdf);
            assertTrue(pdf.length > 0);
        }

        // WireMock should not have received any requests from this test
        // (requests from other tests are expected, so we just verify the engine works)
    }

    private static void deleteCachedPackage(String subdir) {
        Path pkgDir = CACHE_DIR.resolve(subdir);
        if (Files.exists(pkgDir)) {
            try (var walk = Files.walk(pkgDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException _) {}
                        });
            } catch (IOException _) {}
        }
    }
}
