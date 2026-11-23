package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Per-connection authentication, protected-mode, transaction ({@code MULTI}/{@code EXEC}/
 * {@code WATCH}), and Pub/Sub subscription state.
 */
public final class SessionAuthState {

    private static final SessionAuthState UNRESTRICTED = new SessionAuthState(true, false);

    private boolean authenticated;
    private final boolean protectedModeBlocked;
    private final Consumer<Reply> asyncReplySink;

    private boolean inMulti;
    private boolean txDirty;
    private final List<List<Bytes>> txQueue = new ArrayList<>();
    private final Map<Bytes, Long> watchedKeys = new LinkedHashMap<>();
    private final Set<Bytes> subscriptions = new LinkedHashSet<>();

    public SessionAuthState(boolean authenticated, boolean protectedModeBlocked) {
        this(authenticated, protectedModeBlocked, reply -> {});
    }

    public SessionAuthState(boolean authenticated, boolean protectedModeBlocked, Consumer<Reply> asyncReplySink) {
        this.authenticated = authenticated;
        this.protectedModeBlocked = protectedModeBlocked;
        this.asyncReplySink = Objects.requireNonNull(asyncReplySink, "asyncReplySink");
    }

    /** Returns an unrestricted state for in-process unit tests without network auth. */
    public static SessionAuthState unrestricted() {
        return UNRESTRICTED;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    void markAuthenticated() {
        this.authenticated = true;
    }

    public boolean isProtectedModeBlocked() {
        return protectedModeBlocked;
    }

    void pushAsync(Reply reply) {
        asyncReplySink.accept(reply);
    }

    synchronized void addSubscription(Bytes channel) {
        subscriptions.add(channel);
    }

    synchronized void removeSubscription(Bytes channel) {
        subscriptions.remove(channel);
    }

    synchronized int subscriptionCount() {
        return subscriptions.size();
    }

    synchronized List<Bytes> drainSubscriptions() {
        List<Bytes> copy = List.copyOf(subscriptions);
        subscriptions.clear();
        return copy;
    }

    boolean isInMulti() {
        return inMulti;
    }

    void beginMulti() {
        this.inMulti = true;
        this.txDirty = false;
        this.txQueue.clear();
    }

    void markTxDirty() {
        this.txDirty = true;
    }

    boolean isTxDirty() {
        return txDirty;
    }

    void enqueue(List<Bytes> argv) {
        this.txQueue.add(List.copyOf(argv));
    }

    List<List<Bytes>> drainQueue() {
        List<List<Bytes>> copy = List.copyOf(txQueue);
        this.txQueue.clear();
        return copy;
    }

    void watch(Bytes key, long version) {
        watchedKeys.putIfAbsent(key, version);
    }

    Map<Bytes, Long> drainWatched() {
        Map<Bytes, Long> copy = Map.copyOf(watchedKeys);
        watchedKeys.clear();
        return copy;
    }

    void unwatch() {
        watchedKeys.clear();
    }

    void discardTransaction() {
        this.inMulti = false;
        this.txDirty = false;
        this.txQueue.clear();
        this.watchedKeys.clear();
    }
}
