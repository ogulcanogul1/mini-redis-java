package io.github.ogulcanogul1.miniredis.persistence;

/** Unchecked exception thrown when encoding, decoding, or saving an RDB snapshot fails (K12). */
public final class RdbException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RdbException(String message) {
        super(message);
    }

    public RdbException(String message, Throwable cause) {
        super(message, cause);
    }
}
