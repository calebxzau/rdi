package calebxzau.rdi.mc.client.chunkcache;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/** A bounded, single-writer queue that keeps only the newest pending value for each key. */
public final class CoalescingWriteQueue<K, V> implements ChunkCacheWriterHandle {
    public interface Sink<K, V> extends AutoCloseable {
        void write(K key, V value) throws Exception;

        @Override
        void close() throws Exception;
    }

    public enum Submission {
        ACCEPTED,
        EVICTED,
        OVERSIZED,
        CLOSED
    }

    /** Evictions also includes queued values explicitly discarded for a session transition. */
    public record Stats(
            int pendingEntries,
            long pendingBytes,
            long activeBytes,
            long evictions,
            long oversized,
            long accepted,
            boolean accepting,
            int peakPendingEntries,
            long peakPendingBytes,
            long written,
            long writeNanos,
            long maxWriteNanos
    ) {
    }

    private record Pending<V>(V value, long bytes) {
    }

    private final Object lock = new Object();
    private final LinkedHashMap<K, Pending<V>> pending = new LinkedHashMap<>();
    private final Sink<K, V> sink;
    private final Consumer<Throwable> onFailure;
    private final ToLongFunction<V> weigher;
    private final int capacity;
    private final long maxPendingBytes;
    private final long maxValueBytes;
    private final Thread worker;

    private boolean accepting = true;
    private long pendingBytes;
    private long activeBytes;
    private long evictions;
    private long oversized;
    private long accepted;
    private int peakPendingEntries;
    private long peakPendingBytes;
    private long written;
    private long writeNanos;
    private long maxWriteNanos;

    public CoalescingWriteQueue(
            Sink<K, V> sink,
            Consumer<Throwable> onFailure,
            int capacity,
            long maxPendingBytes,
            long maxValueBytes,
            ToLongFunction<V> weigher
    ) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
        this.weigher = Objects.requireNonNull(weigher, "weigher");
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (maxPendingBytes <= 0) throw new IllegalArgumentException("maxPendingBytes must be positive");
        if (maxValueBytes <= 0) throw new IllegalArgumentException("maxValueBytes must be positive");
        this.capacity = capacity;
        this.maxPendingBytes = maxPendingBytes;
        this.maxValueBytes = maxValueBytes;
        this.worker = new Thread(this::runWorker, "rdi-chunk-cache-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** A cheap, non-mutating admission check. Pending entries may be evicted on submit. */
    public boolean canAccept(long valueBytes) {
        synchronized (lock) {
            return accepting && valueBytes > 0 && valueBytes <= maxValueBytes && valueBytes <= maxPendingBytes;
        }
    }

    /** Submits without waiting; oldest pending keys are evicted when limits require space. */
    public Submission submit(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        return submit(key, value, weigher.applyAsLong(value));
    }

    /** Submits with a previously validated weight when calculating it would repeat an expensive traversal. */
    public Submission submit(K key, V value, long valueBytes) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        synchronized (lock) {
            if (!accepting) return Submission.CLOSED;
            if (valueBytes <= 0 || valueBytes > maxValueBytes || valueBytes > maxPendingBytes) {
                oversized++;
                return Submission.OVERSIZED;
            }

            Pending<V> previous = pending.get(key);
            long bytesAfterReplacement = pendingBytes - (previous == null ? 0 : previous.bytes());
            int entriesAfterReplacement = pending.size() - (previous == null ? 0 : 1);
            boolean evicted = false;
            Iterator<Map.Entry<K, Pending<V>>> iterator = pending.entrySet().iterator();
            while (entriesAfterReplacement + 1 > capacity || bytesAfterReplacement > maxPendingBytes - valueBytes) {
                if (!iterator.hasNext()) {
                    // The value itself was validated against both limits, so this can only indicate
                    // an accounting invariant violation.
                    throw new IllegalStateException("Unable to make room for valid queued value");
                }
                Map.Entry<K, Pending<V>> oldest = iterator.next();
                if (Objects.equals(oldest.getKey(), key)) {
                    continue;
                }
                bytesAfterReplacement -= oldest.getValue().bytes();
                entriesAfterReplacement--;
                iterator.remove();
                evictions++;
                evicted = true;
            }

            pending.put(key, new Pending<>(value, valueBytes));
            pendingBytes = bytesAfterReplacement + valueBytes;
            accepted++;
            peakPendingEntries = Math.max(peakPendingEntries, pending.size());
            peakPendingBytes = Math.max(peakPendingBytes, pendingBytes);
            lock.notifyAll();
            return evicted ? Submission.EVICTED : Submission.ACCEPTED;
        }
    }

    /** Removes queued snapshots while allowing the active write to finish. */
    public void discardPending() {
        synchronized (lock) {
            evictions += pending.size();
            pending.clear();
            pendingBytes = 0;
            lock.notifyAll();
        }
    }

    public Stats stats() {
        synchronized (lock) {
            return new Stats(pending.size(), pendingBytes, activeBytes, evictions, oversized, accepted, accepting,
                    peakPendingEntries, peakPendingBytes, written, writeNanos, maxWriteNanos);
        }
    }

    /** Stops accepting work and lets the worker drain all accepted values before closing the sink. */
    public void closeAsync() {
        synchronized (lock) {
            accepting = false;
            lock.notifyAll();
        }
    }

    /** Waits until the worker has drained the queue and closed the sink. */
    public void awaitClosed() throws InterruptedException {
        worker.join();
    }

    /** Waits at most the given time for the worker to drain and close. */
    public boolean awaitClosed(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) throw new IllegalArgumentException("timeoutMillis must not be negative");
        if (timeoutMillis > 0) worker.join(timeoutMillis);
        return !worker.isAlive();
    }

    private void runWorker() {
        Throwable failure = null;
        try {
            while (true) {
                Map.Entry<K, Pending<V>> next;
                synchronized (lock) {
                    while (pending.isEmpty() && accepting) lock.wait();
                    if (pending.isEmpty()) break;
                    Iterator<Map.Entry<K, Pending<V>>> iterator = pending.entrySet().iterator();
                    next = iterator.next();
                    iterator.remove();
                    pendingBytes -= next.getValue().bytes();
                    activeBytes = next.getValue().bytes();
                }
                long writeStarted = System.nanoTime();
                boolean writeSucceeded = false;
                try {
                    sink.write(next.getKey(), next.getValue().value());
                    writeSucceeded = true;
                } finally {
                    long elapsed = Math.max(0L, System.nanoTime() - writeStarted);
                    synchronized (lock) {
                        activeBytes = 0;
                        writeNanos += elapsed;
                        maxWriteNanos = Math.max(maxWriteNanos, elapsed);
                        if (writeSucceeded) written++;
                    }
                }
            }
        } catch (Throwable writeFailure) {
            failure = writeFailure;
            synchronized (lock) {
                accepting = false;
                evictions += pending.size();
                pending.clear();
                pendingBytes = 0;
                lock.notifyAll();
            }
        }

        try {
            sink.close();
        } catch (Throwable closeFailure) {
            if (failure == null) failure = closeFailure;
            else if (closeFailure != failure) failure.addSuppressed(closeFailure);
        }

        if (failure != null) {
            synchronized (lock) {
                accepting = false;
                evictions += pending.size();
                pending.clear();
                pendingBytes = 0;
                lock.notifyAll();
            }
            try {
                onFailure.accept(failure);
            } catch (Throwable ignored) {
                // A reporting callback must not prevent worker termination.
            }
        }
    }
}
