package io.github.ogulcanogul1.miniredis.ds;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Probabilistic skip list ordered by {@code (score, member)} ascending, matching Redis's sorted-set
 * ordering (PLAN §3 Faz 7). Not thread-safe; callers synchronize externally (K3).
 */
public final class SkipList {

    private static final int MAX_LEVEL = 32;
    private static final double P = 0.25;

    /** One member-score pair in the skip list. */
    public record Entry(Bytes member, double score) {
        public Entry {
            Objects.requireNonNull(member, "member");
        }
    }

    private static final class Node {
        final Bytes member;
        final double score;
        final Node[] forward;

        Node(Bytes member, double score, int level) {
            this.member = member;
            this.score = score;
            this.forward = new Node[level];
        }
    }

    private final Node head = new Node(null, Double.NEGATIVE_INFINITY, MAX_LEVEL);
    private int level = 1;
    private int size;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Inserts {@code (member, score)}; returns {@code true} if inserted, {@code false} if already present. */
    public boolean insert(Bytes member, double score) {
        Objects.requireNonNull(member, "member");
        Node[] update = new Node[MAX_LEVEL];
        Node cur = head;
        for (int i = level - 1; i >= 0; i--) {
            while (cur.forward[i] != null && compare(cur.forward[i], score, member) < 0) {
                cur = cur.forward[i];
            }
            update[i] = cur;
        }
        Node next = cur.forward[0];
        if (next != null && compare(next, score, member) == 0) {
            return false;
        }
        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; i++) {
                update[i] = head;
            }
            level = newLevel;
        }
        Node node = new Node(member, score, newLevel);
        for (int i = 0; i < newLevel; i++) {
            node.forward[i] = update[i].forward[i];
            update[i].forward[i] = node;
        }
        size++;
        return true;
    }

    /** Removes {@code (member, score)}; returns {@code true} if found and removed. */
    public boolean delete(Bytes member, double score) {
        Objects.requireNonNull(member, "member");
        Node[] update = new Node[MAX_LEVEL];
        Node cur = head;
        for (int i = level - 1; i >= 0; i--) {
            while (cur.forward[i] != null && compare(cur.forward[i], score, member) < 0) {
                cur = cur.forward[i];
            }
            update[i] = cur;
        }
        Node target = cur.forward[0];
        if (target == null || compare(target, score, member) != 0) {
            return false;
        }
        for (int i = 0; i < level; i++) {
            if (update[i].forward[i] != target) {
                break;
            }
            update[i].forward[i] = target.forward[i];
        }
        while (level > 1 && head.forward[level - 1] == null) {
            level--;
        }
        size--;
        return true;
    }

    /** Returns the 0-based rank of {@code (member, score)}, or {@code -1} if absent. */
    public long rank(Bytes member, double score) {
        Objects.requireNonNull(member, "member");
        long idx = 0;
        for (Node cur = head.forward[0]; cur != null; cur = cur.forward[0]) {
            int cmp = compare(cur, score, member);
            if (cmp == 0) {
                return idx;
            }
            if (cmp > 0) {
                return -1;
            }
            idx++;
        }
        return -1;
    }

    /** Returns entries in inclusive 0-based index range {@code [start, stop]}, supporting negative indices. */
    public List<Entry> rangeByIndex(long start, long stop) {
        if (size == 0) {
            return List.of();
        }
        long s = start < 0 ? size + start : start;
        long e = stop < 0 ? size + stop : stop;
        if (s < 0) {
            s = 0;
        }
        if (e >= size) {
            e = size - 1;
        }
        if (s > e) {
            return List.of();
        }
        List<Entry> result = new ArrayList<>((int) (e - s + 1));
        Node cur = head.forward[0];
        long idx = 0;
        while (cur != null && idx <= e) {
            if (idx >= s) {
                result.add(new Entry(cur.member, cur.score));
            }
            cur = cur.forward[0];
            idx++;
        }
        return List.copyOf(result);
    }

    /** Returns entries whose scores lie in inclusive {@code [min, max]}. */
    public List<Entry> rangeByScore(double min, double max) {
        if (size == 0 || min > max) {
            return List.of();
        }
        Node cur = head;
        for (int i = level - 1; i >= 0; i--) {
            while (cur.forward[i] != null && cur.forward[i].score < min) {
                cur = cur.forward[i];
            }
        }
        cur = cur.forward[0];
        List<Entry> result = new ArrayList<>();
        while (cur != null && cur.score <= max) {
            result.add(new Entry(cur.member, cur.score));
            cur = cur.forward[0];
        }
        return List.copyOf(result);
    }

    private static int compare(Node node, double score, Bytes member) {
        int c = Double.compare(node.score, score);
        return c != 0 ? c : node.member.compareTo(member);
    }

    private static int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && ThreadLocalRandom.current().nextDouble() < P) {
            lvl++;
        }
        return lvl;
    }
}
