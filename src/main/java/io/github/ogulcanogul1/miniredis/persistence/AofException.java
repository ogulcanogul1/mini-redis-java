package io.github.ogulcanogul1.miniredis.persistence;

/** Unchecked exception thrown when reading, validating, or writing the AOF fails (K12). */
public final class AofException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AofException(String message) {
        super(message);
    }

    public AofException(String message, Throwable cause) {
        super(message, cause);
    }
}
