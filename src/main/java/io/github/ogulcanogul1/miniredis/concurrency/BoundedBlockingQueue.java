package io.github.ogulcanogul1.miniredis.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-capacity FIFO blocking queue implemented with a circular buffer, a single
 * {@link ReentrantLock}, and two {@link Condition}s ({@code notEmpty}, {@code notFull}).
 *
 * <p>Once {@link #close()} is called, producers can no longer add items, while consumers drain any
 * remaining items before {@link #take()} returns {@code null}.
 */
public final class BoundedBlockingQueue<E> {

    private final E[] items;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    private int head;
    private int tail;
    private int count;
    private boolean closed;

    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        // Generic array cannot be created directly because of type erasure; slot access stays within class.
        this.items = (E[]) new Object[capacity];
    }

    /**
     * Inserts {@code item}, waiting until space becomes available.
     *
     * @throws IllegalStateException if the queue has been closed
     */
    public void put(E item) throws InterruptedException {
        Objects.requireNonNull(item, "item");
        lock.lockInterruptibly();
        try {
            while (count == items.length && !closed) {
                notFull.await();
            }
            if (closed) {
                throw new IllegalStateException("queue is closed");
            }
            enqueue(item);
        } finally {
            lock.unlock();
        }
    }

    /** Inserts {@code item} immediately if capacity allows and the queue is open. */
    public boolean offer(E item) {
        Objects.requireNonNull(item, "item");
        lock.lock();
        try {
            if (closed || count == items.length) {
                return false;
            }
            enqueue(item);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head item, waiting if empty until an item arrives or the queue is
     * closed and drained (in which case {@code null} is returned).
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0 && !closed) {
                notEmpty.await();
            }
            if (count == 0) {
                return null;
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head item, waiting up to {@code timeout} before returning
     * {@code null}. Also returns {@code null} immediately when closed and empty.
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0 && !closed) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (count == 0) {
                return null;
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /** Closes the queue and wakes all waiting producers and consumers. */
    public void close() {
        lock.lock();
        try {
            closed = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Removes and returns all currently buffered items in FIFO order. */
    public List<E> drainAll() {
        lock.lock();
        try {
            if (count == 0) {
                return List.of();
            }
            List<E> drained = new ArrayList<>(count);
            while (count > 0) {
                E item = items[head];
                items[head] = null;
                head = (head + 1) % items.length;
                count--;
                drained.add(item);
            }
            tail = head;
            notFull.signalAll();
            return List.copyOf(drained);
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return items.length;
    }

    private void enqueue(E item) {
        items[tail] = item;
        tail = (tail + 1) % items.length;
        count++;
        notEmpty.signal();
    }

    private E dequeue() {
        E item = items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        count--;
        notFull.signal();
        return item;
    }
}
