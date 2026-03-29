package name.velikodniy.vitaliy.typst;

public abstract class TypstException extends RuntimeException {
    protected TypstException(String message) { super(message); }
    protected TypstException(String message, Throwable cause) { super(message, cause); }
}
