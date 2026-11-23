package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory Pub/Sub broker mapping channel names to subscribed client sessions.
 */
final class PubSubBroker {

    private static final Bytes SUBSCRIBE = Bytes.utf8("subscribe");
    private static final Bytes UNSUBSCRIBE = Bytes.utf8("unsubscribe");
    private static final Bytes MESSAGE = Bytes.utf8("message");

    private final ConcurrentHashMap<Bytes, Set<SessionAuthState>> channels = new ConcurrentHashMap<>();

    Reply subscribe(SessionAuthState sub, List<Bytes> channelNames) {
        List<Reply> frames = new ArrayList<>(channelNames.size());
        for (Bytes ch : channelNames) {
            channels.computeIfAbsent(ch, k -> ConcurrentHashMap.newKeySet()).add(sub);
            sub.addSubscription(ch);
            frames.add(Reply.array(
                    List.of(Reply.bulk(SUBSCRIBE), Reply.bulk(ch), Reply.integer(sub.subscriptionCount()))));
        }
        return emitFrames(sub, frames);
    }

    Reply unsubscribe(SessionAuthState sub, List<Bytes> channelNames) {
        List<Bytes> targets = channelNames.isEmpty() ? sub.drainSubscriptions() : channelNames;
        if (targets.isEmpty()) {
            return Reply.array(List.of(Reply.bulk(UNSUBSCRIBE), Reply.NIL, Reply.integer(0)));
        }
        List<Reply> frames = new ArrayList<>(targets.size());
        for (Bytes ch : targets) {
            Set<SessionAuthState> set = channels.get(ch);
            if (set != null) {
                set.remove(sub);
                if (set.isEmpty()) {
                    channels.remove(ch, set);
                }
            }
            sub.removeSubscription(ch);
            frames.add(Reply.array(
                    List.of(Reply.bulk(UNSUBSCRIBE), Reply.bulk(ch), Reply.integer(sub.subscriptionCount()))));
        }
        return emitFrames(sub, frames);
    }

    void unsubscribeAll(SessionAuthState sub) {
        for (Bytes ch : sub.drainSubscriptions()) {
            Set<SessionAuthState> set = channels.get(ch);
            if (set != null) {
                set.remove(sub);
                if (set.isEmpty()) {
                    channels.remove(ch, set);
                }
            }
        }
    }

    long publish(Bytes channel, Bytes message) {
        Set<SessionAuthState> subscribers = channels.get(channel);
        if (subscribers == null || subscribers.isEmpty()) {
            return 0L;
        }
        Reply frame = Reply.array(List.of(Reply.bulk(MESSAGE), Reply.bulk(channel), Reply.bulk(message)));
        long delivered = 0L;
        for (SessionAuthState sub : subscribers) {
            sub.pushAsync(frame);
            delivered++;
        }
        return delivered;
    }

    private static Reply emitFrames(SessionAuthState sub, List<Reply> frames) {
        for (int i = 0; i < frames.size() - 1; i++) {
            sub.pushAsync(frames.get(i));
        }
        return frames.getLast();
    }
}
