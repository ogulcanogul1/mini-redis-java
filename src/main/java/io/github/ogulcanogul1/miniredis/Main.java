package io.github.ogulcanogul1.miniredis;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Entry point. Composition root: later phases wire the server here. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.out.println("mini-redis " + version());
    }

    /**
     * Returns the project version, read from {@code version.properties} which Maven fills in through
     * resource filtering. The version string is written only in {@code pom.xml}.
     *
     * @throws IllegalStateException if the resource or the key is missing
     */
    static String version() {
        try (InputStream in = Main.class.getResourceAsStream("/version.properties")) {
            if (in == null) {
                throw new IllegalStateException("version.properties not found on the classpath");
            }
            Properties props = new Properties();
            props.load(in);
            String v = props.getProperty("version");
            if (v == null || v.isBlank()) {
                throw new IllegalStateException("version.properties has no 'version' key");
            }
            return v;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
