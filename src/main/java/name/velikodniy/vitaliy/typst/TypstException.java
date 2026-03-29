package name.velikodniy.vitaliy.typst;

/**
 * Base exception for all Typst-related errors.
 */
public abstract class TypstException extends RuntimeException {

    /**
     * Create a new exception with a message.
     *
     * @param message the detail message
     */
    protected TypstException(String message) { super(message); }

    /**
     * Create a new exception with a message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    protected TypstException(String message, Throwable cause) { super(message, cause); }
}
