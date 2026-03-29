package name.velikodniy.vitaliy.typst.internal;

import org.junit.jupiter.api.Test;

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
}
