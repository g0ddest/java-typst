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

    // Per-package locks to prevent concurrent downloads of the same package.
    private static final ConcurrentHashMap<String, Object> PACKAGE_LOCKS = new ConcurrentHashMap<>();

    private final TypstPackageResolver resolver;

    public PackageManager(TypstPackageResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Resolve a package to a local directory path.
     * Returns a cached path if available, otherwise downloads and unpacks.
     */
    public String resolveToPath(String namespace, String name, String version) {
        Path packageDir = CACHE_DIR.resolve(Path.of(namespace, name, version));

        if (Files.isDirectory(packageDir)) {
            return packageDir.toString();
        }

        String lockKey = namespace + "/" + name + "/" + version;
        Object lock = PACKAGE_LOCKS.computeIfAbsent(lockKey, _ -> new Object());

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

    private static void unpackTarGz(byte[] tarGz, Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            try (var gzipIn = new GzipCompressorInputStream(new ByteArrayInputStream(tarGz));
                 var tarIn = new TarArchiveInputStream(gzipIn)) {

                TarArchiveEntry entry;
                while ((entry = tarIn.getNextEntry()) != null) {
                    Path entryPath = targetDir.resolve(entry.getName()).normalize();
                    if (!entryPath.startsWith(targetDir)) {
                        throw new IOException("Tar entry outside target dir: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(entryPath);
                    } else {
                        Files.createDirectories(entryPath.getParent());
                        Files.write(entryPath, tarIn.readAllBytes());
                    }
                }
            }
        } catch (IOException e) {
            deleteRecursive(targetDir);
            throw new TypstEngineException("Failed to unpack package: " + e.getMessage(), e);
        }
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
