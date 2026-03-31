package name.velikodniy.vitaliy.typst;

/**
 * Resolves Typst packages by their coordinates.
 *
 * <p>The default implementation downloads packages as tar.gz archives from
 * {@code https://packages.typst.org}. The archive is automatically unpacked
 * and cached by the engine.
 *
 * <p>Provide a custom implementation to fetch packages from a private registry,
 * S3, local filesystem, or any other source:
 * <pre>{@code
 * // From local filesystem
 * TypstEngine.builder()
 *     .packageResolver((ns, name, version) -> Files.readAllBytes(
 *         Path.of("/packages", ns, name + "-" + version + ".tar.gz")))
 *     .build();
 *
 * // From S3
 * TypstEngine.builder()
 *     .packageResolver((ns, name, version) ->
 *         s3Client.getObjectAsBytes(req -> req
 *             .bucket("typst-packages")
 *             .key(ns + "/" + name + "-" + version + ".tar.gz"))
 *         .asByteArray())
 *     .build();
 * }</pre>
 */
@FunctionalInterface
public interface TypstPackageResolver {

    /**
     * Resolve a package and return its archive bytes (tar.gz format).
     *
     * <p>The returned bytes must be a gzip-compressed tar archive containing the
     * package files. The engine handles unpacking and caching automatically.
     *
     * @param namespace package namespace (e.g. "preview")
     * @param name      package name (e.g. "cades")
     * @param version   package version (e.g. "0.3.1")
     * @return package archive bytes in tar.gz format
     * @throws TypstPackageNotFoundException if the package does not exist
     */
    byte[] resolve(String namespace, String name, String version);
}
