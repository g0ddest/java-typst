package name.velikodniy.vitaliy.typst;

/**
 * Thrown by {@link TypstPackageResolver} when a requested package cannot be found.
 */
public class TypstPackageNotFoundException extends TypstEngineException {

    public TypstPackageNotFoundException(String namespace, String name, String version) {
        super("Package not found: @%s/%s:%s".formatted(namespace, name, version));
    }

    public TypstPackageNotFoundException(String message) {
        super(message);
    }
}
