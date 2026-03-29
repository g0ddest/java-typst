package name.velikodniy.vitaliy.typst;

import java.util.List;

/**
 * Thrown when a Typst template compilation fails with one or more diagnostics.
 */
public class TypstCompilationException extends TypstException {

    /** The compilation diagnostics (errors and warnings). */
    private final List<TypstDiagnostic> diagnostics;

    /**
     * Create a new compilation exception.
     *
     * @param message     summary message
     * @param diagnostics the list of diagnostics from the compiler
     */
    public TypstCompilationException(String message, List<TypstDiagnostic> diagnostics) {
        super(message);
        this.diagnostics = List.copyOf(diagnostics);
    }

    /**
     * Return the diagnostics reported by the Typst compiler.
     *
     * @return unmodifiable list of diagnostics
     */
    public List<TypstDiagnostic> getDiagnostics() { return diagnostics; }
}
