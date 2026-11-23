package io.github.ogulcanogul1.miniredis.protocol;

import io.github.ogulcanogul1.miniredis.commands.Reply;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Turns a {@link Reply} into RESP2 bytes (K1: the only place that knows the wire format of a reply). */
public final class RespWriter {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] NULL_BULK = {'$', '-', '1', '\r', '\n'};
    private static final byte[] NULL_ARRAY = {'*', '-', '1', '\r', '\n'};

    private RespWriter() {}

    /** Returns the RESP2 encoding of {@code reply}. */
    public static byte[] encode(Reply reply) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTo(reply, out);
        return out.toByteArray();
    }

    /** Appends the RESP2 encoding of {@code reply} to {@code out}. */
    static void writeTo(Reply reply, ByteArrayOutputStream out) {
        Objects.requireNonNull(reply, "reply");
        // No catch-all branch: Reply is sealed, so the compiler checks that every subtype is handled.
        switch (reply) {
            case Reply.SimpleString s -> line(out, '+', s.text());
            case Reply.ErrorReply e -> line(out, '-', e.message());
            case Reply.IntegerReply i -> line(out, ':', Long.toString(i.value()));
            case Reply.BulkString b -> {
                byte[] data = b.value().toByteArray();
                line(out, '$', Integer.toString(data.length));
                out.writeBytes(data);
                out.writeBytes(CRLF);
            }
            case Reply.NullBulk n -> out.writeBytes(NULL_BULK);
            case Reply.ArrayReply a -> {
                if (a == Reply.NULL_ARRAY) {
                    out.writeBytes(NULL_ARRAY);
                    return;
                }
                line(out, '*', Integer.toString(a.elements().size()));
                for (Reply element : a.elements()) {
                    writeTo(element, out);
                }
            }
        }
    }

    // Simple and error texts are encoded with ISO-8859-1: commands.Errors decodes client bytes with
    // ISO-8859-1, so every byte the client sent comes back unchanged. Reply guarantees no CR or LF.
    private static void line(ByteArrayOutputStream out, char type, String text) {
        out.write(type);
        out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(CRLF);
    }
}
