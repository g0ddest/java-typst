package name.velikodniy.vitaliy.typst.internal;

import name.velikodniy.vitaliy.typst.TypstNativeException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads the typst-java native library from the classpath or a development build directory.
 */
public final class NativeLibLoader {
    private static final AtomicReference<SymbolLookup> LOOKUP = new AtomicReference<>();

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
                String fileName = libraryFileName(platform);
                Path tempDir = Files.createTempDirectory("typst-java-");
                Path tempLib = tempDir.resolve(fileName);
                Files.copy(is, tempLib, StandardCopyOption.REPLACE_EXISTING);
                tempLib.toFile().deleteOnExit();
                tempDir.toFile().deleteOnExit();
                LOOKUP.set(SymbolLookup.libraryLookup(tempLib, Arena.ofAuto()));
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
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String osName;
        if (os.contains("linux")) {
            osName = "linux";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osName = "macos";
        } else if (os.contains("win")) {
            osName = "windows";
        } else {
            throw new TypstNativeException("Unsupported OS: " + os);
        }
        String archName;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            archName = "x86_64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
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
}
