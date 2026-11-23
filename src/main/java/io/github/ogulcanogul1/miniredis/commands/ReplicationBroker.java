package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks connected replica sessions and propagates {@code WRITE} commands from master to replicas
 * in RESP2 array format.
 */
final class ReplicationBroker {

    private final String replId = UUID.randomUUID().toString().replace("-", "") + "00000000";
    private final Set<SessionAuthState> replicas = ConcurrentHashMap.newKeySet();

    Reply handlePsync(SessionAuthState replica, CommandContext ctx) {
        byte[] rdb = ctx.snapshotTrigger().encodeSnapshot();
        replicas.add(replica);
        return Reply.array(List.of(Reply.simple("FULLRESYNC " + replId + " 0"), Reply.bulk(Bytes.of(rdb))));
    }

    void removeReplica(SessionAuthState replica) {
        replicas.remove(replica);
    }

    void propagate(List<Bytes> argv) {
        if (replicas.isEmpty()) {
            return;
        }
        List<Reply> bulkArgs = new ArrayList<>(argv.size());
        for (Bytes arg : argv) {
            bulkArgs.add(Reply.bulk(arg));
        }
        Reply frame = Reply.array(bulkArgs);
        for (SessionAuthState replica : replicas) {
            replica.pushAsync(frame);
        }
    }

    int replicaCount() {
        return replicas.size();
    }
}
