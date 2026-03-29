package name.velikodniy.vitaliy.typst;

/**
 * Thrown when the Typst engine encounters a non-compilation error (e.g. lifecycle or font loading failure).
 */
public class TypstEngineException extends TypstException {

    /**
     * Create a new engine exception with a message.
     *
     * @param message the detail message
     */
    public TypstEngineException(String message) { super(message); }

    /**
     * Create a new engine exception with a message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public TypstEngineException(String message, Throwable cause) { super(message, cause); }
}
