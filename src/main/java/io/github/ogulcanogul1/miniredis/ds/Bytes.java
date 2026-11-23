package io.github.ogulcanogul1.miniredis.ds;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable, binary-safe byte string. Used for keys and string values.
 *
 * <p>Every factory copies its input and {@link #toByteArray()} returns a copy, so no caller can
 * change a {@code Bytes} after it is created. {@link #equals}, {@link #hashCode} and {@link
 * #compareTo} look at the content. {@code compareTo} compares bytes as unsigned values, like {@code
 * memcmp} in Redis.
 */
public final class Bytes implements Comparable<Bytes> {

    /** The empty byte string. */
    public static final Bytes EMPTY = new Bytes(new byte[0]);

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] data;

    // Cached hash. 0 means "not computed yet". Not volatile: a benign race (explained in Faz 4).
    private int hash;

    private Bytes(byte[] data) {
        this.data = data;
    }

    /** Returns a {@code Bytes} holding a copy of {@code data}. */
    public static Bytes of(byte[] data) {
        Objects.requireNonNull(data, "data");
        return new Bytes(data.clone());
    }

    /** Encodes {@code s} as UTF-8. */
    public static Bytes utf8(String s) {
        Objects.requireNonNull(s, "s");
        return new Bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Encodes {@code s} as US-ASCII. Characters outside ASCII become {@code '?'}. */
    public static Bytes ascii(String s) {
        Objects.requireNonNull(s, "s");
        return new Bytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    public int length() {
        return data.length;
    }

    /**
     * Returns the byte at {@code index}.
     *
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link
     *     #length()}
     */
    public byte byteAt(int index) {
        Objects.checkIndex(index, data.length);
        return data[index];
    }

    /** Returns a copy of the content. Changing the returned array does not change this object. */
    public byte[] toByteArray() {
        return data.clone();
    }

    /** Decodes the content with {@code charset}. ISO-8859-1 maps every byte to one char, losslessly. */
    public String decode(Charset charset) {
        Objects.requireNonNull(charset, "charset");
        return new String(data, charset);
    }

    /** Returns a copy in which only the ASCII letters {@code A}-{@code Z} are lower-cased. */
    public Bytes toLowerAscii() {
        byte[] copy = data.clone();
        for (int i = 0; i < copy.length; i++) {
            byte b = copy[i];
            if (b >= 'A' && b <= 'Z') {
                copy[i] = (byte) (b + ('a' - 'A'));
            }
        }
        return new Bytes(copy);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Bytes other && Arrays.equals(data, other.data));
    }

    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0) {
            h = Arrays.hashCode(data);
            hash = h;
        }
        return h;
    }

    /** Compares the bytes as unsigned values, so {@code 0xFF} sorts after {@code 0x01}. */
    @Override
    public int compareTo(Bytes other) {
        return Arrays.compareUnsigned(data, other.data);
    }

    /**
     * Returns a readable form for debugging: printable ASCII as is, a backslash doubled, every other
     * byte as {@code \xNN} in lower-case hex.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(data.length);
        for (byte b : data) {
            if (b == '\\') {
                sb.append('\\').append('\\');
            } else if (b >= 0x20 && b <= 0x7E) {
                sb.append((char) b);
            } else {
                int u = Byte.toUnsignedInt(b);
                sb.append('\\').append('x').append(HEX[u >>> 4]).append(HEX[u & 0x0F]);
            }
        }
        return sb.toString();
    }
}
