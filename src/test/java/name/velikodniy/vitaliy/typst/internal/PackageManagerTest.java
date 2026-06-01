package name.velikodniy.vitaliy.typst.internal;

import name.velikodniy.vitaliy.typst.TypstEngineException;
import name.velikodniy.vitaliy.typst.TypstPackageResolver;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PackageManagerTest {

    /** Resolver that returns a fixed, pre-built archive regardless of coordinates. */
    private static TypstPackageResolver fixedResolver(byte[] archive) {
        return (namespace, name, version) -> archive;
    }

    private static byte[] tarGz(Entry... entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var gz = new GzipCompressorOutputStream(bytes);
             var tar = new TarArchiveOutputStream(gz)) {
            for (Entry e : entries) {
                TarArchiveEntry te;
                if (e.linkName != null) {
                    te = new TarArchiveEntry(e.name, TarArchiveEntry.LF_SYMLINK);
                    te.setLinkName(e.linkName);
                    tar.putArchiveEntry(te);
                    tar.closeArchiveEntry();
                } else {
                    te = new TarArchiveEntry(e.name);
                    te.setSize(e.content.length);
                    tar.putArchiveEntry(te);
                    tar.write(e.content);
                    tar.closeArchiveEntry();
                }
            }
        }
        return bytes.toByteArray();
    }

    private record Entry(String name, byte[] content, String linkName) {
        static Entry file(String name, byte[] content) { return new Entry(name, content, null); }
        static Entry symlink(String name, String target) { return new Entry(name, null, target); }
    }

    @Test
    void rejectsSymlinkEntries() throws IOException {
        byte[] archive = tarGz(Entry.symlink("escape", "/etc/passwd"));
        var pm = new PackageManager(fixedResolver(archive));

        assertThrows(TypstEngineException.class,
                () -> pm.resolveToPath("symtest", "pkg", "1.0.0"));
    }

    @Test
    void rejectsArchiveExceedingMaxUnpackSize() throws IOException {
        byte[] archive = tarGz(Entry.file("big.bin", new byte[4096]));
        // 1 KiB cap, archive expands to 4 KiB.
        var pm = new PackageManager(fixedResolver(archive), 1024);

        assertThrows(TypstEngineException.class,
                () -> pm.resolveToPath("sizetest", "pkg", "1.0.0"));
    }
}
