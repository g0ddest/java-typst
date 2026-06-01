package name.velikodniy.vitaliy.typst;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Default {@link TypstPackageResolver} that downloads packages over HTTP
 * from a Typst-compatible registry.
 *
 * <p>URL format: {@code {registry}/{namespace}/{name}-{version}.tar.gz}
 */
final class HttpPackageResolver implements TypstPackageResolver, AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    // Cap on the compressed download to prevent memory-exhaustion DoS from a
    // hostile or compromised registry. 256 MiB is far above any real package.
    private static final long DEFAULT_MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024;

    // Package coordinates are interpolated into the download URL, so they must be
    // strictly validated to prevent path traversal and SSRF via crafted imports.
    private static final Pattern COORDINATE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]*");
    private static final Pattern VERSION =
            Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+([-+][0-9A-Za-z.-]+)?");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final String registry;
    private final long maxDownloadBytes;

    HttpPackageResolver(String registry) {
        this(registry, DEFAULT_MAX_DOWNLOAD_BYTES);
    }

    HttpPackageResolver(String registry, long maxDownloadBytes) {
        while (registry.endsWith("/")) {
            registry = registry.substring(0, registry.length() - 1);
        }
        this.registry = registry;
        this.maxDownloadBytes = maxDownloadBytes;
    }

    @Override
    public byte[] resolve(String namespace, String name, String version) throws IOException {
        validateCoordinate("namespace", namespace, COORDINATE);
        validateCoordinate("name", name, COORDINATE);
        validateCoordinate("version", version, VERSION);

        String url = "%s/%s/%s-%s.tar.gz".formatted(registry, namespace, name, version);
        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 404) {
                throw new TypstPackageNotFoundException(namespace, name, version);
            }
            if (response.statusCode() / 100 != 2) {
                throw new TypstEngineException(
                        "Failed to download package @%s/%s:%s — HTTP %d"
                                .formatted(namespace, name, version, response.statusCode()));
            }

            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > maxDownloadBytes) {
                throw new TypstEngineException(
                        "Package @%s/%s:%s exceeds maximum download size (%d bytes)"
                                .formatted(namespace, name, version, maxDownloadBytes));
            }

            try (InputStream body = response.body()) {
                return readLimited(body, namespace, name, version);
            }
        } catch (TypstEngineException e) {
            // Includes TypstPackageNotFoundException; preserve the specific message.
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TypstEngineException(
                    "Download interrupted for @%s/%s:%s".formatted(namespace, name, version), e);
        } catch (Exception e) {
            throw new TypstEngineException(
                    "Failed to download package @%s/%s:%s".formatted(namespace, name, version), e);
        }
    }

    private byte[] readLimited(InputStream in, String namespace, String name, String version)
            throws IOException {
        var buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxDownloadBytes) {
                throw new TypstEngineException(
                        "Package @%s/%s:%s exceeds maximum download size (%d bytes)"
                                .formatted(namespace, name, version, maxDownloadBytes));
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static void validateCoordinate(String field, String value, Pattern allowed) {
        if (value == null || !allowed.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid package " + field + ": " + value);
        }
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
