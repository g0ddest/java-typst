package name.velikodniy.vitaliy.typst;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Default {@link TypstPackageResolver} that downloads packages over HTTP
 * from a Typst-compatible registry.
 *
 * <p>URL format: {@code {registry}/{namespace}/{name}-{version}.tar.gz}
 */
final class HttpPackageResolver implements TypstPackageResolver, AutoCloseable {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String registry;

    HttpPackageResolver(String registry) {
        while (registry.endsWith("/")) {
            registry = registry.substring(0, registry.length() - 1);
        }
        this.registry = registry;
    }

    @Override
    public byte[] resolve(String namespace, String name, String version) {
        String url = "%s/%s/%s-%s.tar.gz".formatted(registry, namespace, name, version);
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 404) {
                throw new TypstPackageNotFoundException(namespace, name, version);
            }
            if (response.statusCode() / 100 != 2) {
                throw new TypstEngineException(
                        "Failed to download package @%s/%s:%s — HTTP %d"
                                .formatted(namespace, name, version, response.statusCode()));
            }
            return response.body();
        } catch (TypstPackageNotFoundException e) {
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

    @Override
    public void close() {
        httpClient.close();
    }
}
