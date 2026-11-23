package io.github.ogulcanogul1.miniredis.server;

/**
 * Thrown when server configuration cannot be read, parsed, or validated (fail-fast at startup,
 * K12).
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
