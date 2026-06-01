package name.velikodniy.vitaliy.typst;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpPackageResolverTest {

    // An unroutable registry so that, if validation is ever bypassed, the test
    // fails fast on connection refusal rather than hitting the network.
    private static final String UNROUTABLE = "http://127.0.0.1:1";

    @Test
    void rejectsNamespaceWithPathTraversal() {
        var resolver = new HttpPackageResolver(UNROUTABLE);
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("../evil", "name", "1.0.0"));
    }

    @Test
    void rejectsNameWithSlash() {
        var resolver = new HttpPackageResolver(UNROUTABLE);
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("preview", "a/b", "1.0.0"));
    }

    @Test
    void rejectsVersionWithUrlInjection() {
        var resolver = new HttpPackageResolver(UNROUTABLE);
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("preview", "name", "1.0.0?@evil.com/x"));
    }

    @Test
    void rejectsNamespaceWithScheme() {
        var resolver = new HttpPackageResolver(UNROUTABLE);
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("http://evil.com", "name", "1.0.0"));
    }

    @Test
    void rejectsDownloadExceedingMaxSize() {
        var wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        try {
            wireMock.stubFor(get(urlEqualTo("/preview/big-1.0.0.tar.gz"))
                    .willReturn(aResponse().withStatus(200).withBody(new byte[8192])));

            // 1 KiB cap, server returns 8 KiB.
            var resolver = new HttpPackageResolver(
                    "http://localhost:" + wireMock.port(), 1024);

            assertThrows(TypstEngineException.class,
                    () -> resolver.resolve("preview", "big", "1.0.0"));
        } finally {
            wireMock.stop();
        }
    }
}
