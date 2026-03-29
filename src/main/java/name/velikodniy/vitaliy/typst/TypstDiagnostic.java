package name.velikodniy.vitaliy.typst;

public record TypstDiagnostic(
    Severity severity, String message, String file, int line, int column, String hint
) {
    public enum Severity { ERROR, WARNING }
}
