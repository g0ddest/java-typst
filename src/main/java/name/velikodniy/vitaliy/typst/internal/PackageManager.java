package name.velikodniy.vitaliy.typst.internal;

import name.velikodniy.vitaliy.typst.TypstEngineException;
import name.velikodniy.vitaliy.typst.TypstPackageResolver;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal package manager that caches resolved packages on disk
 * and delegates downloading to a {@link TypstPackageResolver}.
 */
public final class PackageManager {

    private static final Path CACHE_DIR = Path.of(
            System.getProperty("java.io.tmpdir"),
            "typst",
            "packages"
    );

    // Cap on total uncompressed bytes written per package, to defend against
    // decompression bombs from a hostile or compromised registry.
    private static final long DEFAULT_MAX_UNPACK_BYTES = 512L * 1024 * 1024;

    // Per-package locks to prevent concurrent downloads of the same package.
    // Instance-scoped so the map is GC'd when this engine is closed instead of
    // accumulating entries across the JVM lifetime.
    private final ConcurrentHashMap<String, Object> packageLocks = new ConcurrentHashMap<>();

    private final TypstPackageResolver resolver;
    private final long maxUnpackBytes;

    public PackageManager(TypstPackageResolver resolver) {
        this(resolver, DEFAULT_MAX_UNPACK_BYTES);
    }

    PackageManager(TypstPackageResolver resolver, long maxUnpackBytes) {
        this.resolver = resolver;
        this.maxUnpackBytes = maxUnpackBytes;
    }

    /**
     * Resolve a package to a local directory path.
     * Returns a cached path if available, otherwise downloads and unpacks.
     *
     * @throws IOException if the resolver fails to fetch the package
     */
    public String resolveToPath(String namespace, String name, String version) throws IOException {
        Path packageDir = CACHE_DIR.resolve(Path.of(namespace, name, version));

        if (Files.isDirectory(packageDir)) {
            return packageDir.toString();
        }

        String lockKey = namespace + "/" + name + "/" + version;
        Object lock = packageLocks.computeIfAbsent(lockKey, _ -> new Object());

        synchronized (lock) {
            // Double-check after acquiring the lock
            if (Files.isDirectory(packageDir)) {
                return packageDir.toString();
            }

            byte[] tarGz = resolver.resolve(namespace, name, version);
            unpackTarGz(tarGz, packageDir);
            return packageDir.toString();
        }
    }

    private void unpackTarGz(byte[] tarGz, Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            try (var gzipIn = new GzipCompressorInputStream(new ByteArrayInputStream(tarGz));
                 var tarIn = new TarArchiveInputStream(gzipIn)) {

                long totalBytes = 0;
                TarArchiveEntry entry;
                while ((entry = tarIn.getNextEntry()) != null) {
                    // Reject link entries: a symlink inside the package could be
                    // followed by a later entry writing through it to escape the
                    // target directory (zip-slip via symlink).
                    if (entry.isSymbolicLink() || entry.isLink()) {
                        throw new IOException("Link entries are not allowed: " + entry.getName());
                    }
                    Path entryPath = targetDir.resolve(entry.getName()).normalize();
                    if (!entryPath.startsWith(targetDir)) {
                        throw new IOException("Tar entry outside target dir: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(entryPath);
                    } else {
                        Files.createDirectories(entryPath.getParent());
                        totalBytes = copyEntry(tarIn, entryPath, totalBytes);
                    }
                }
            }
        } catch (IOException e) {
            deleteRecursive(targetDir);
            throw new TypstEngineException("Failed to unpack package: " + e.getMessage(), e);
        }
    }

    /**
     * Stream one tar entry to disk, enforcing the cumulative uncompressed-size cap.
     *
     * @return the running total of bytes written across all entries so far
     */
    private long copyEntry(TarArchiveInputStream tarIn, Path entryPath, long totalBytes)
            throws IOException {
        try (var out = Files.newOutputStream(entryPath)) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = tarIn.read(chunk)) != -1) {
                totalBytes += read;
                if (totalBytes > maxUnpackBytes) {
                    throw new IOException(
                            "Package exceeds maximum uncompressed size (" + maxUnpackBytes + " bytes)");
                }
                out.write(chunk, 0, read);
            }
        }
        return totalBytes;
    }

    private static void deleteRecursive(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException _) {
                            // ignore, not critical for cache cleanup
                        }
                    });
        } catch (IOException _) {
            // ignore, not critical for cache cleanup
        }
    }
}
