package io.github.ogulcanogul1.miniredis.bench;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.HdrHistogram.Histogram;

/**
 * Fixed-rate open-loop RESP2 load generator using {@link Histogram} to avoid coordinated omission
 * (K13). Records {@code doneNanos - intendedStartNanos} in open-loop mode vs {@code doneNanos -
 * actualSendNanos} when {@code --closed-loop} is supplied.
 */
public final class OpenLoopLoadGenerator {

    private static final long MAX_TRACKABLE_NANOS = TimeUnit.HOURS.toNanos(1);
    private static final int SIGNIFICANT_DIGITS = 3;
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private OpenLoopLoadGenerator() {}

    public static void main(String[] args) throws Exception {
        Config cfg = Config.parse(args);
        byte[] payload = commandBytes(cfg.command());

        List<Histogram> histograms = new ArrayList<>(cfg.clients());
        for (int i = 0; i < cfg.clients(); i++) {
            histograms.add(new Histogram(1L, MAX_TRACKABLE_NANOS, SIGNIFICANT_DIGITS));
        }

        double perClientIntervalNanos = 1_000_000_000.0 * cfg.clients() / cfg.rate();
        long warmupNanos = TimeUnit.SECONDS.toNanos(cfg.warmupSeconds());
        long durationNanos = TimeUnit.SECONDS.toNanos(cfg.durationSeconds());
        CountDownLatch ready = new CountDownLatch(cfg.clients());
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long[] epochHolder = new long[1];

        List<Thread> workers = new ArrayList<>(cfg.clients());
        for (int c = 0; c < cfg.clients(); c++) {
            int clientIdx = c;
            Histogram localHist = histograms.get(clientIdx);
            Thread worker = Thread.ofPlatform().name("loadgen-" + clientIdx).start(() -> {
                try {
                    runWorker(
                            cfg,
                            clientIdx,
                            payload,
                            perClientIntervalNanos,
                            warmupNanos,
                            durationNanos,
                            ready,
                            startGate,
                            epochHolder,
                            localHist);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    ready.countDown();
                }
            });
            workers.add(worker);
        }

        ready.await();
        if (failure.get() != null) {
            throw new IllegalStateException("worker setup failed", failure.get());
        }
        long startEpoch = System.nanoTime();
        epochHolder[0] = startEpoch;
        startGate.countDown();

        for (Thread worker : workers) {
            worker.join();
        }
        if (failure.get() != null) {
            throw new IllegalStateException("load generation failed", failure.get());
        }

        Histogram total = new Histogram(1L, MAX_TRACKABLE_NANOS, SIGNIFICANT_DIGITS);
        for (Histogram h : histograms) {
            total.add(h);
        }
        long totalCount = total.getTotalCount();
        double achievedRps = cfg.durationSeconds() > 0 ? (double) totalCount / cfg.durationSeconds() : 0.0;

        System.out.printf(
                Locale.ROOT,
                "mode=%s command=%s clients=%d targetRps=%d warmupSec=%d durationSec=%d "
                        + "count=%d achievedRps=%.1f p50Us=%.1f p95Us=%.1f p99Us=%.1f p999Us=%.1f maxUs=%.1f%n",
                cfg.closedLoop() ? "closed-loop" : "open-loop",
                cfg.command(),
                cfg.clients(),
                cfg.rate(),
                cfg.warmupSeconds(),
                cfg.durationSeconds(),
                totalCount,
                achievedRps,
                total.getValueAtPercentile(50.0) / 1_000.0,
                total.getValueAtPercentile(95.0) / 1_000.0,
                total.getValueAtPercentile(99.0) / 1_000.0,
                total.getValueAtPercentile(99.9) / 1_000.0,
                total.getMaxValue() / 1_000.0);
    }

    private static void runWorker(
            Config cfg,
            int clientIdx,
            byte[] payload,
            double perClientIntervalNanos,
            long warmupNanos,
            long durationNanos,
            CountDownLatch ready,
            CountDownLatch startGate,
            long[] epochHolder,
            Histogram localHist)
            throws IOException, InterruptedException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(cfg.host(), cfg.port()), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            ready.countDown();
            startGate.await();

            long runStart = epochHolder[0];
            long measureStart = runStart + warmupNanos;
            long measureEnd = measureStart + durationNanos;
            long stagger = Math.round((perClientIntervalNanos * clientIdx) / cfg.clients());

            if (cfg.closedLoop()) {
                long interval = Math.max(1L, Math.round(perClientIntervalNanos));
                long nextTarget = runStart + stagger;
                while (true) {
                    long now = System.nanoTime();
                    if (now >= measureEnd) {
                        break;
                    }
                    if (now < nextTarget) {
                        parkUntil(nextTarget);
                    }
                    long actualSend = System.nanoTime();
                    if (actualSend >= measureEnd) {
                        break;
                    }
                    sendAndDrainReply(out, in, payload);
                    long done = System.nanoTime();
                    if (actualSend >= measureStart && done <= measureEnd) {
                        localHist.recordValue(clampLatency(done - actualSend));
                    }
                    // Closed-loop resets the schedule after each completed response, omitting stalled arrivals.
                    nextTarget = done + interval;
                }
            } else {
                long measureIdx = 0;
                long warmupBase = runStart + stagger;
                for (long i = 0; ; i++) {
                    long intended = warmupBase + Math.round(i * perClientIntervalNanos);
                    if (intended >= measureStart) {
                        break;
                    }
                    long now = System.nanoTime();
                    if (now < intended) {
                        parkUntil(intended);
                    }
                    sendAndDrainReply(out, in, payload);
                }
                long measureBase = measureStart + stagger;
                while (true) {
                    long intended = measureBase + Math.round(measureIdx * perClientIntervalNanos);
                    if (intended >= measureEnd) {
                        break;
                    }
                    long now = System.nanoTime();
                    if (now < intended) {
                        parkUntil(intended);
                    }
                    sendAndDrainReply(out, in, payload);
                    long done = System.nanoTime();
                    localHist.recordValue(clampLatency(done - intended));
                    measureIdx++;
                }
            }
        }
    }

    private static void parkUntil(long targetNanos) {
        long remaining = targetNanos - System.nanoTime();
        while (remaining > 0) {
            if (remaining > 2_000_000L) {
                long sleepMs = (remaining - 1_000_000L) / 1_000_000L;
                try {
                    Thread.sleep(Math.max(1L, sleepMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                Thread.onSpinWait();
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            remaining = targetNanos - System.nanoTime();
        }
    }

    private static long clampLatency(long nanos) {
        if (nanos < 1L) {
            return 1L;
        }
        return Math.min(nanos, MAX_TRACKABLE_NANOS);
    }

    private static void sendAndDrainReply(OutputStream out, InputStream in, byte[] payload) throws IOException {
        out.write(payload);
        out.flush();
        readRespValue(in);
    }

    private static void readRespValue(InputStream in) throws IOException {
        int prefix = in.read();
        if (prefix < 0) {
            throw new EOFException("unexpected EOF reading RESP prefix");
        }
        switch (prefix) {
            case '+', '-', ':' -> readLine(in);
            case '$' -> {
                int len = Integer.parseInt(readLine(in));
                if (len >= 0) {
                    skipFully(in, len + 2L);
                }
            }
            case '*' -> {
                int count = Integer.parseInt(readLine(in));
                for (int i = 0; i < count; i++) {
                    readRespValue(in);
                }
            }
            default -> throw new IOException("unexpected RESP prefix byte: " + prefix);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(32);
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("unexpected EOF reading RESP line");
            }
            if (b == '\r') {
                int lf = in.read();
                if (lf != '\n') {
                    throw new IOException("expected LF after CR, got " + lf);
                }
                return sb.toString();
            }
            sb.append((char) b);
        }
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("unexpected EOF reading bulk payload");
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static byte[] commandBytes(String command) {
        return switch (command.toLowerCase(Locale.ROOT)) {
            case "ping" -> "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII);
            case "incr" -> "*2\r\n$4\r\nINCR\r\n$11\r\nloadgen:seq\r\n".getBytes(StandardCharsets.US_ASCII);
            case "set" -> "*3\r\n$3\r\nSET\r\n$11\r\nloadgen:key\r\n$3\r\nval\r\n".getBytes(StandardCharsets.US_ASCII);
            case "get" -> "*2\r\n$3\r\nGET\r\n$11\r\nloadgen:key\r\n".getBytes(StandardCharsets.US_ASCII);
            default -> throw new IllegalArgumentException("unsupported --command: " + command);
        };
    }

    private record Config(
            String host,
            int port,
            int clients,
            int rate,
            int warmupSeconds,
            int durationSeconds,
            String command,
            boolean closedLoop) {

        static Config parse(String[] args) {
            String host = "127.0.0.1";
            int port = 6380;
            int clients = 16;
            int rate = 10_000;
            int warmupSeconds = 2;
            int durationSeconds = 10;
            String command = "ping";
            boolean closedLoop = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--closed-loop".equals(arg)) {
                    closedLoop = true;
                    continue;
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + arg);
                }
                String val = args[++i];
                switch (arg) {
                    case "--host" -> host = val;
                    case "--port" -> port = Integer.parseInt(val);
                    case "--clients" -> clients = Integer.parseInt(val);
                    case "--rate" -> rate = Integer.parseInt(val);
                    case "--warmup-seconds" -> warmupSeconds = Integer.parseInt(val);
                    case "--duration-seconds" -> durationSeconds = Integer.parseInt(val);
                    case "--command" -> command = val.toLowerCase(Locale.ROOT);
                    default -> throw new IllegalArgumentException("unknown argument: " + arg);
                }
            }
            if (clients < 1 || rate < 1 || warmupSeconds < 0 || durationSeconds < 1) {
                throw new IllegalArgumentException("invalid positive bounds in arguments");
            }
            return new Config(host, port, clients, rate, warmupSeconds, durationSeconds, command, closedLoop);
        }
    }
}
