package name.velikodniy.vitaliy.typst;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypstExceptionTest {

    private static byte[] serialize(Object o) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(o);
        }
        return bytes.toByteArray();
    }

    private static Object deserialize(byte[] data) throws Exception {
        try (var in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return in.readObject();
        }
    }

    @Test
    void exceptionsDeclareStableSerialVersionUid() {
        for (Class<?> c : List.of(
                TypstException.class,
                TypstEngineException.class,
                TypstCompilationException.class,
                TypstNativeException.class,
                TypstPackageNotFoundException.class)) {
            ObjectStreamClass osc = ObjectStreamClass.lookup(c);
            assertNotNull(osc, c.getName() + " must be serializable");
            assertEquals(1L, osc.getSerialVersionUID(),
                    c.getName() + " should declare a stable serialVersionUID");
        }
    }

    @Test
    void compilationExceptionRoundTripsWithDiagnostics() throws Exception {
        var diag = new TypstDiagnostic(
                TypstDiagnostic.Severity.ERROR, "boom", "main.typ", 3, 7, "try this");
        var original = new TypstCompilationException("compile failed", List.of(diag));

        var restored = (TypstCompilationException) deserialize(serialize(original));

        assertEquals("compile failed", restored.getMessage());
        assertEquals(List.of(diag), restored.getDiagnostics());
    }

    @Test
    void getDiagnosticsIsUnmodifiable() {
        var diag = new TypstDiagnostic(
                TypstDiagnostic.Severity.WARNING, "w", "f.typ", 1, 1, null);
        var ex = new TypstCompilationException("x", new ArrayList<>(List.of(diag)));

        assertThrows(UnsupportedOperationException.class,
                () -> ex.getDiagnostics().add(diag));
    }
}
