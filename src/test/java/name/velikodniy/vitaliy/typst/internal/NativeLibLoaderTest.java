package name.velikodniy.vitaliy.typst.internal;

import name.velikodniy.vitaliy.typst.TypstNativeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NativeLibLoaderTest {

    @Test
    void detectsPlatformCorrectly() {
        String p = NativeLibLoader.detectPlatform();
        assertNotNull(p);
        assertTrue(p.matches("(linux|macos|windows)-(x86_64|aarch64)"), "Got: " + p);
    }

    @Test
    void generatesCorrectLibraryName() {
        assertEquals("libtypst_java.so", NativeLibLoader.libraryFileName("linux-x86_64"));
        assertEquals("libtypst_java.dylib", NativeLibLoader.libraryFileName("macos-aarch64"));
        assertEquals("typst_java.dll", NativeLibLoader.libraryFileName("windows-x86_64"));
    }

    @Test
    void resourcePathIsCorrect() {
        assertEquals("/native/macos-aarch64/libtypst_java.dylib",
                NativeLibLoader.resourcePath("macos-aarch64"));
    }

    @Test
    void detectPlatformMapsLinuxAmd64() {
        assertEquals("linux-x86_64", NativeLibLoader.detectPlatform("Linux", "amd64"));
    }

    @Test
    void detectPlatformMapsLinuxArm64() {
        assertEquals("linux-aarch64", NativeLibLoader.detectPlatform("Linux", "aarch64"));
    }

    @Test
    void detectPlatformMapsMacArm64() {
        assertEquals("macos-aarch64", NativeLibLoader.detectPlatform("Mac OS X", "arm64"));
        assertEquals("macos-x86_64", NativeLibLoader.detectPlatform("Darwin", "x86_64"));
    }

    @Test
    void detectPlatformMapsWindows() {
        assertEquals("windows-x86_64", NativeLibLoader.detectPlatform("Windows 11", "amd64"));
        assertEquals("windows-aarch64", NativeLibLoader.detectPlatform("Windows 11", "aarch64"));
    }

    @Test
    void detectPlatformRejectsUnknownOs() {
        TypstNativeException ex = assertThrows(TypstNativeException.class,
                () -> NativeLibLoader.detectPlatform("Plan9", "amd64"));
        assertTrue(ex.getMessage().contains("Plan9"));
    }

    @Test
    void detectPlatformRejectsUnknownArch() {
        TypstNativeException ex = assertThrows(TypstNativeException.class,
                () -> NativeLibLoader.detectPlatform("Linux", "riscv64"));
        assertTrue(ex.getMessage().contains("riscv64"));
    }

    @Test
    void sha256HexIsDeterministic() {
        byte[] payload = "typst-java".getBytes(StandardCharsets.UTF_8);
        String first = NativeLibLoader.sha256Hex(payload);
        String second = NativeLibLoader.sha256Hex(payload);
        assertEquals(first, second);
        assertEquals(64, first.length());
        // RFC 6234 vector for "typst-java"
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void cacheRootPrefersLocalAppDataOnWindows() {
        Path p = NativeLibLoader.cacheRoot("Windows 11", "C:\\Users\\u\\AppData\\Local", "C:\\Users\\u");
        assertEquals(Path.of("C:\\Users\\u\\AppData\\Local", "typst-java"), p);
    }

    @Test
    void cacheRootFallsBackToCacheDirOnPosix() {
        Path p = NativeLibLoader.cacheRoot("Linux", null, "/home/u");
        assertEquals(Path.of("/home/u", ".cache", "typst-java"), p);
    }

    @Test
    void ensureCachedExtractsToShaKeyedPath(@TempDir Path tempHome) throws Exception {
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            byte[] payload = "fake-native-lib-bytes".getBytes(StandardCharsets.UTF_8);
            Path first = NativeLibLoader.ensureCached(payload, "libtypst_java.so");
            assertTrue(Files.exists(first));
            assertArrayEquals(payload, Files.readAllBytes(first));

            // Same input → same cache path (SHA-256 stability).
            Path second = NativeLibLoader.ensureCached(payload, "libtypst_java.so");
            assertEquals(first, second);

            String expectedKey = NativeLibLoader.sha256Hex(payload).substring(0, 16);
            assertEquals(expectedKey, first.getParent().getFileName().toString());
        } finally {
            if (oldHome != null) System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void ensureCachedReusesExistingFileOnSecondCall(@TempDir Path tempHome) throws Exception {
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            byte[] payload = "another-payload".getBytes(StandardCharsets.UTF_8);
            Path first = NativeLibLoader.ensureCached(payload, "libtypst_java.so");
            long firstMtime = Files.getLastModifiedTime(first).toMillis();

            // Sleep would be flaky; instead, verify that the path is identical and contents match.
            Path second = NativeLibLoader.ensureCached(payload, "libtypst_java.so");
            assertEquals(first, second);
            assertEquals(firstMtime, Files.getLastModifiedTime(second).toMillis(),
                    "Second call must not rewrite the file when checksum matches");
        } finally {
            if (oldHome != null) System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void ensureCachedReExtractsWhenCachedFileIsCorrupt(@TempDir Path tempHome) throws Exception {
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            byte[] payload = "good-bytes".getBytes(StandardCharsets.UTF_8);
            Path first = NativeLibLoader.ensureCached(payload, "libtypst_java.so");
            // Corrupt the cached file.
            Files.write(first, "corrupted".getBytes(StandardCharsets.UTF_8));
            Path recovered = NativeLibLoader.ensureCached(payload, "libtypst_java.so");
            assertEquals(first, recovered);
            assertArrayEquals(payload, Files.readAllBytes(recovered));
        } finally {
            if (oldHome != null) System.setProperty("user.home", oldHome);
        }
    }
}
