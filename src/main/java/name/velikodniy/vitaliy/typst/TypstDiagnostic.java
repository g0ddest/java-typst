package name.velikodniy.vitaliy.typst;

/**
 * A single diagnostic (error or warning) produced by the Typst compiler.
 *
 * @param severity the severity level of this diagnostic
 * @param message  the human-readable diagnostic message
 * @param file     the source file where the diagnostic originated
 * @param line     the line number in the source file
 * @param column   the column number in the source file
 * @param hint     an optional hint for resolving the issue
 */
public record TypstDiagnostic(
    Severity severity, String message, String file, int line, int column, String hint
) {
    /**
     * Severity level of a Typst diagnostic.
     */
    public enum Severity {
        /** An error that prevents successful compilation. */
        ERROR,
        /** A warning that does not prevent compilation. */
        WARNING
    }
}
