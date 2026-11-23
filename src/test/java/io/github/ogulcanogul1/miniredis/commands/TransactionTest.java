package io.github.ogulcanogul1.miniredis.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.Keyspace;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import io.github.ogulcanogul1.miniredis.storage.LockType;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private static List<Bytes> cmd(String... parts) {
        return java.util.Arrays.stream(parts).map(Bytes::utf8).toList();
    }

    @Test
    void multiQueuesCommandsAndExecRunsInOrder() {
        Keyspace ks = Keyspaces.sharded(4, LockType.RWLOCK);
        Dispatcher dispatcher = new Dispatcher(CommandTable.standard(), new CommandContext(ks));
        SessionAuthState tx = dispatcher.newSessionState(true);

        assertEquals(Reply.OK, dispatcher.dispatch(cmd("MULTI"), tx));
        assertEquals(new Reply.SimpleString("QUEUED"), dispatcher.dispatch(cmd("SET", "a", "1"), tx));
        assertEquals(new Reply.SimpleString("QUEUED"), dispatcher.dispatch(cmd("INCR", "counter"), tx));
        assertEquals(new Reply.SimpleString("QUEUED"), dispatcher.dispatch(cmd("GET", "a"), tx));

        Reply exec = dispatcher.dispatch(cmd("EXEC"), tx);
        assertEquals(Reply.array(List.of(Reply.OK, Reply.integer(1), Reply.bulk(Bytes.utf8("1")))), exec);
    }

    @Test
    void discardAbortsQueueAndClearsWatch() {
        Keyspace ks = Keyspaces.sharded(4, LockType.RWLOCK);
        Dispatcher dispatcher = new Dispatcher(CommandTable.standard(), new CommandContext(ks));
        SessionAuthState tx = dispatcher.newSessionState(true);

        assertEquals(Reply.OK, dispatcher.dispatch(cmd("MULTI"), tx));
        assertEquals(new Reply.SimpleString("QUEUED"), dispatcher.dispatch(cmd("SET", "k", "v"), tx));
        assertEquals(Reply.OK, dispatcher.dispatch(cmd("DISCARD"), tx));
        assertTrue(ks.get(Bytes.utf8("k")).isEmpty());
        assertTrue(dispatcher.dispatch(cmd("EXEC"), tx) instanceof Reply.ErrorReply);
    }

    @Test
    void execAbortOnQueueTimeSyntaxError() {
        Keyspace ks = Keyspaces.sharded(4, LockType.RWLOCK);
        Dispatcher dispatcher = new Dispatcher(CommandTable.standard(), new CommandContext(ks));
        SessionAuthState tx = dispatcher.newSessionState(true);

        assertEquals(Reply.OK, dispatcher.dispatch(cmd("MULTI"), tx));
        assertEquals(new Reply.SimpleString("QUEUED"), dispatcher.dispatch(cmd("SET", "k", "v"), tx));
        assertTrue(dispatcher.dispatch(cmd("SET", "onlykey"), tx) instanceof Reply.ErrorReply);

        Reply exec = dispatcher.dispatch(cmd("EXEC"), tx);
        assertTrue(exec instanceof Reply.ErrorReply err && err.message().startsWith("EXECABORT "));
        assertTrue(ks.get(Bytes.utf8("k")).isEmpty());
    }

    @Test
    void watchDetectsConcurrentMutationAndAba() {
        Keyspace ks = Keyspaces.sharded(4, LockType.RWLOCK);
        Dispatcher dispatcher = new Dispatcher(CommandTable.standard(), new CommandContext(ks));
        SessionAuthState c1 = dispatcher.newSessionState(true);
        SessionAuthState c2 = dispatcher.newSessionState(true);

        dispatcher.dispatch(cmd("SET", "bal", "10"), c2);
        assertEquals(Reply.OK, dispatcher.dispatch(cmd("WATCH", "bal"), c1));

        // ABA: delete and recreate with the exact same value before EXEC
        dispatcher.dispatch(cmd("DEL", "bal"), c2);
        dispatcher.dispatch(cmd("SET", "bal", "10"), c2);

        assertEquals(Reply.OK, dispatcher.dispatch(cmd("MULTI"), c1));
        assertEquals(new Reply.SimpleString("QUEUED"), dispatcher.dispatch(cmd("SET", "bal", "99"), c1));
        assertEquals(Reply.NULL_ARRAY, dispatcher.dispatch(cmd("EXEC"), c1));
        assertEquals(Bytes.utf8("10"), ks.get(Bytes.utf8("bal")).orElseThrow());
    }
}
