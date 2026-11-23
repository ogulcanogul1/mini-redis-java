import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single-file experiment (JEP 330): compares virtual threads blocking inside {@code synchronized}
 * (pinning the carrier platform thread) against {@link ReentrantLock} (unmounting cleanly).
 *
 * <p>Run with {@code -Djdk.tracePinnedThreads=short} or capture JFR events with {@code
 * -XX:StartFlightRecording=duration=10s,filename=pinning.jfr}. Pass {@code --lock} to use {@link
 * ReentrantLock}.
 */
public final class JfrPinningExperiment {

    private static final int VIRTUAL_THREADS = 200;
    private static final long SLEEP_MS = 20;

    public static void main(String[] args) throws InterruptedException {
        boolean useLock = args.length > 0 && "--lock".equals(args[0]);
        Object monitor = new Object();
        ReentrantLock lock = new ReentrantLock();
        List<Thread> threads = new ArrayList<>(VIRTUAL_THREADS);

        long startNanos = System.nanoTime();
        for (int i = 0; i < VIRTUAL_THREADS; i++) {
            Thread vt = Thread.ofVirtual()
                    .name("vt-exp-", 1)
                    .unstarted(() -> {
                        if (useLock) {
                            lock.lock();
                            try {
                                sleepQuietly(SLEEP_MS);
                            } finally {
                                lock.unlock();
                            }
                        } else {
                            synchronized (monitor) {
                                sleepQuietly(SLEEP_MS);
                            }
                        }
                    });
            vt.start();
            threads.add(vt);
        }
        for (Thread vt : threads) {
            vt.join();
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.println("finished mode=" + (useLock ? "reentrant-lock" : "synchronized")
                + " threads=" + VIRTUAL_THREADS
                + " elapsedMs=" + elapsedMs);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
