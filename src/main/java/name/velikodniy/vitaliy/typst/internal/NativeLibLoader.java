package name.velikodniy.vitaliy.typst.internal;

import name.velikodniy.vitaliy.typst.TypstNativeException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads the typst-java native library from the classpath or a development build directory.
 *
 * <p>Extracted libraries are cached in a content-addressed directory keyed by the first 16 hex
 * characters of the embedded resource's SHA-256. The cache lives under
 * {@code ${user.home}/.cache/typst-java} (or the equivalent {@code LOCALAPPDATA} location on
 * Windows) and persists across JVM restarts.
 */
public final class NativeLibLoader {
    private static final AtomicReference<SymbolLookup> LOOKUP = new AtomicReference<>();
    private static final int CACHE_KEY_LENGTH = 16;

    private NativeLibLoader() {}

    /**
     * Load the native library and return a {@link SymbolLookup} for its symbols.
     *
     * @return the symbol lookup for the loaded native library
     */
    public static SymbolLookup load() {
        SymbolLookup existing = LOOKUP.get();
        if (existing != null) return existing;
        synchronized (NativeLibLoader.class) {
            existing = LOOKUP.get();
            if (existing != null) return existing;
            String platform = detectPlatform();
            String resourcePath = resourcePath(platform);

            try (InputStream is = NativeLibLoader.class.getResourceAsStream(resourcePath)) {
                if (is == null) {
                    // Development mode: look in native/target/release
                    Path devLib = findDevLib(platform);
                    if (devLib != null) {
                        LOOKUP.set(SymbolLookup.libraryLookup(devLib, Arena.ofAuto()));
                        return LOOKUP.get();
                    }
                    throw new TypstNativeException(
                        "Native library not found for " + platform + " (resource: " + resourcePath + ")");
                }
                byte[] bytes = is.readAllBytes();
                String fileName = libraryFileName(platform);
                Path cached = ensureCached(bytes, fileName);
                LOOKUP.set(SymbolLookup.libraryLookup(cached, Arena.ofAuto()));
                return LOOKUP.get();
            } catch (IOException e) {
                throw new TypstNativeException("Failed to extract native library", e);
            }
        }
    }

    private static Path findDevLib(String platform) {
        String fileName = libraryFileName(platform);
        for (String base : new String[]{
                "native/target/release",
                System.getProperty("user.dir") + "/native/target/release"}) {
            Path p = Path.of(base, fileName);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    static String detectPlatform() {
        return detectPlatform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static String detectPlatform(String os, String arch) {
        String osLower = os == null ? "" : os.toLowerCase(Locale.ROOT);
        String archLower = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
        String osName;
        if (osLower.contains("linux")) {
            osName = "linux";
        } else if (osLower.contains("mac") || osLower.contains("darwin")) {
            osName = "macos";
        } else if (osLower.contains("win")) {
            osName = "windows";
        } else {
            throw new TypstNativeException("Unsupported OS: " + os);
        }
        String archName;
        if (archLower.equals("amd64") || archLower.equals("x86_64")) {
            archName = "x86_64";
        } else if (archLower.equals("aarch64") || archLower.equals("arm64")) {
            archName = "aarch64";
        } else {
            throw new TypstNativeException("Unsupported arch: " + arch);
        }
        return osName + "-" + archName;
    }

    static String libraryFileName(String platform) {
        if (platform.startsWith("windows")) return "typst_java.dll";
        if (platform.startsWith("macos")) return "libtypst_java.dylib";
        return "libtypst_java.so";
    }

    static String resourcePath(String platform) {
        return "/native/" + platform + "/" + libraryFileName(platform);
    }

    /**
     * Computes the SHA-256 of {@code bytes} and returns the cache path that should host
     * {@code fileName}, ensuring the file is present and verified.
     */
    static Path ensureCached(byte[] bytes, String fileName) throws IOException {
        String hash = sha256Hex(bytes);
        String key = hash.substring(0, CACHE_KEY_LENGTH);
        Path cacheDir = cacheRoot().resolve(key);
        Files.createDirectories(cacheDir);
        Path target = cacheDir.resolve(fileName);

        if (Files.exists(target) && verifyChecksum(target, hash)) {
            return target;
        }

        Path tmp = cacheDir.resolve(fileName + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
            try {
                ch.force(true);
            } catch (IOException ignored) {
                // fsync best-effort
            }
        }

        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (FileAlreadyExistsException ignored) {
                // Another process won the race; fall through to verification below.
                Files.deleteIfExists(tmp);
            }
        } catch (FileAlreadyExistsException ignored) {
            // Another process wrote it between our existence check and our move.
            Files.deleteIfExists(tmp);
        }

        if (!verifyChecksum(target, hash)) {
            throw new IOException("Cached native library checksum mismatch at " + target);
        }
        return target;
    }

    static Path cacheRoot() {
        return cacheRoot(System.getProperty("os.name", ""), System.getenv("LOCALAPPDATA"),
                System.getProperty("user.home", "."));
    }

    static Path cacheRoot(String osName, String localAppData, String userHome) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String home = (userHome == null || userHome.isEmpty()) ? "." : userHome;
        if (os.contains("win")) {
            if (localAppData != null && !localAppData.isEmpty()) {
                return Path.of(localAppData, "typst-java");
            }
            Path fallback = Path.of(home, "AppData", "Local");
            if (Files.isDirectory(fallback)) {
                return fallback.resolve("typst-java");
            }
        }
        return Path.of(home, ".cache", "typst-java");
    }

    static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256(bytes));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new TypstNativeException("SHA-256 unavailable", e);
        }
    }

    private static boolean verifyChecksum(Path file, String expectedHex) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest()).equals(expectedHex);
        } catch (IOException | NoSuchAlgorithmException e) {
            return false;
        }
    }
}
