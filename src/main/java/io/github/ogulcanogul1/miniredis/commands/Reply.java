package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The value a command returns (K1). Every subtype is an immutable record, so replies can be
 * compared with {@code equals} in tests and passed out of a lock safely. Faz 2's RESP writer turns
 * a reply into bytes with a {@code switch} over these subtypes.
 */
public sealed interface Reply {

    /** {@code +OK}. */
    Reply OK = new SimpleString("OK");

    /** {@code +PONG}. */
    Reply PONG = new SimpleString("PONG");

    /** {@code $-1}: the nil bulk string. */
    Reply NIL = new NullBulk();

    /** {@code *-1}: the nil array (returned by aborted {@code EXEC} after {@code WATCH}). */
    Reply NULL_ARRAY = new ArrayReply(NullArraySentinel.INSTANCE);

    /**
     * A simple string: {@code +text}.
     *
     * @throws IllegalArgumentException if {@code text} contains CR or LF: in RESP they would end the
     *     line early and the client would read the rest as a new reply
     */
    record SimpleString(String text) implements Reply {
        public SimpleString {
            requireSingleLine(text);
        }
    }

    /**
     * An error: {@code -message}. The message starts with the error code ({@code ERR}, later also
     * {@code WRONGTYPE} or {@code OOM}).
     *
     * @throws IllegalArgumentException if {@code message} contains CR or LF
     */
    record ErrorReply(String message) implements Reply {
        public ErrorReply {
            requireSingleLine(message);
        }
    }

    /** An integer: {@code :value}. */
    record IntegerReply(long value) implements Reply {}

    /** A binary-safe bulk string: {@code $len value}. */
    record BulkString(Bytes value) implements Reply {
        public BulkString {
            Objects.requireNonNull(value, "value");
        }
    }

    /** The nil bulk string. Use {@link Reply#NIL}. */
    record NullBulk() implements Reply {}

    /** An array: {@code *count elements} (or {@link #NULL_ARRAY} for {@code *-1}). */
    record ArrayReply(List<Reply> elements) implements Reply {
        public ArrayReply {
            if (elements != NullArraySentinel.INSTANCE) {
                elements = List.copyOf(elements); // also rejects null and null elements
            }
        }
    }

    final class NullArraySentinel {
        static final List<Reply> INSTANCE = Collections.unmodifiableList(new ArrayList<>(0));

        private NullArraySentinel() {}
    }

    static Reply simple(String text) {
        return new SimpleString(text);
    }

    static Reply error(String message) {
        return new ErrorReply(message);
    }

    static Reply integer(long value) {
        return new IntegerReply(value);
    }

    static Reply bulk(Bytes value) {
        return new BulkString(value);
    }

    static Reply array(List<Reply> elements) {
        return new ArrayReply(elements);
    }

    private static void requireSingleLine(String s) {
        Objects.requireNonNull(s);
        if (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("must not contain CR or LF");
        }
    }
}
