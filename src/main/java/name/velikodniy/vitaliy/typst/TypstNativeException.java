package name.velikodniy.vitaliy.typst;

/**
 * Thrown when an error occurs in the native (FFM) layer of the Typst engine.
 */
public class TypstNativeException extends TypstException {

    /**
     * Create a new native exception with a message.
     *
     * @param message the detail message
     */
    public TypstNativeException(String message) { super(message); }

    /**
     * Create a new native exception with a message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public TypstNativeException(String message, Throwable cause) { super(message, cause); }
}
