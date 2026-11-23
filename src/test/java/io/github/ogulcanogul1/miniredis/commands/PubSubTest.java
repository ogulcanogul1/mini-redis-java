package io.github.ogulcanogul1.miniredis.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PubSubTest {

    private static List<Bytes> cmd(String... parts) {
        return java.util.Arrays.stream(parts).map(Bytes::utf8).toList();
    }

    @Test
    void subscribePublishUnsubscribeAndCleanupWork() {
        Dispatcher dispatcher = new Dispatcher(CommandTable.standard(), new CommandContext(Keyspaces.inMemory()));
        List<Reply> pushed1 = new ArrayList<>();
        SessionAuthState sub1 = dispatcher.newSessionState(true, pushed1::add);
        SessionAuthState pub = dispatcher.newSessionState(true);

        Reply subReply = dispatcher.dispatch(cmd("SUBSCRIBE", "c1", "c2"), sub1);
        assertEquals(1, pushed1.size());
        assertEquals(
                Reply.array(
                        List.of(Reply.bulk(Bytes.utf8("subscribe")), Reply.bulk(Bytes.utf8("c1")), Reply.integer(1))),
                pushed1.getFirst());
        assertEquals(
                Reply.array(
                        List.of(Reply.bulk(Bytes.utf8("subscribe")), Reply.bulk(Bytes.utf8("c2")), Reply.integer(2))),
                subReply);

        assertTrue(dispatcher.dispatch(cmd("GET", "k"), sub1) instanceof Reply.ErrorReply);

        pushed1.clear();
        assertEquals(Reply.integer(1), dispatcher.dispatch(cmd("PUBLISH", "c1", "hi"), pub));
        assertEquals(1, pushed1.size());
        assertEquals(
                Reply.array(List.of(
                        Reply.bulk(Bytes.utf8("message")), Reply.bulk(Bytes.utf8("c1")), Reply.bulk(Bytes.utf8("hi")))),
                pushed1.getFirst());

        Reply unsub = dispatcher.dispatch(cmd("UNSUBSCRIBE", "c1"), sub1);
        assertEquals(
                Reply.array(
                        List.of(Reply.bulk(Bytes.utf8("unsubscribe")), Reply.bulk(Bytes.utf8("c1")), Reply.integer(1))),
                unsub);
        assertEquals(Reply.integer(0), dispatcher.dispatch(cmd("PUBLISH", "c1", "bye"), pub));

        dispatcher.closeSession(sub1);
        assertEquals(Reply.integer(0), dispatcher.dispatch(cmd("PUBLISH", "c2", "bye"), pub));
    }
}
