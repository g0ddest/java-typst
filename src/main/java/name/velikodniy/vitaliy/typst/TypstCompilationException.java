package name.velikodniy.vitaliy.typst;

import java.util.List;

public class TypstCompilationException extends TypstException {
    private final List<TypstDiagnostic> diagnostics;
    public TypstCompilationException(String message, List<TypstDiagnostic> diagnostics) {
        super(message);
        this.diagnostics = List.copyOf(diagnostics);
    }
    public List<TypstDiagnostic> getDiagnostics() { return diagnostics; }
}
