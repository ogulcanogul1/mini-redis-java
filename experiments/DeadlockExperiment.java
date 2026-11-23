import java.util.concurrent.CountDownLatch;

/**
 * Single-file experiment (JEP 330): demonstrates a reproducible two-lock deadlock and how
 * lock ordering fixes it.
 *
 * <p>Run without arguments to trigger the deadlock and inspect it with {@code jcmd} +
 * {@code jcmd <pid> Thread.print}; pass {@code --fixed} to use consistent lock ordering.
 */
public final class DeadlockExperiment {

    public static void main(String[] args) throws InterruptedException {
        boolean fixed = args.length > 0 && "--fixed".equals(args[0]);
        Object lockA = new Object();
        Object lockB = new Object();
        CountDownLatch firstLocksAcquired = new CountDownLatch(2);

        Thread t1 = new Thread(
                () -> {
                    synchronized (lockA) {
                        firstLocksAcquired.countDown();
                        await(firstLocksAcquired);
                        synchronized (lockB) {
                            System.out.println("worker-1 acquired A -> B");
                        }
                    }
                },
                "exp-worker-1");

        Thread t2 = new Thread(
                () -> {
                    Object first = fixed ? lockA : lockB;
                    Object second = fixed ? lockB : lockA;
                    synchronized (first) {
                        if (!fixed) {
                            firstLocksAcquired.countDown();
                            await(firstLocksAcquired);
                        }
                        synchronized (second) {
                            System.out.println("worker-2 acquired " + (fixed ? "A -> B" : "B -> A"));
                        }
                    }
                },
                "exp-worker-2");

        t1.start();
        if (fixed) {
            firstLocksAcquired.countDown();
        }
        t2.start();
        t1.join();
        t2.join();
        System.out.println("finished cleanly (fixed=" + fixed + ")");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
