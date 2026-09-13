package calebxzau.rdi.mc.server.region;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.RecyclingBufferPool;
import com.github.luben.zstd.ZstdInputStream;
import net.minecraft.util.FastBufferedInputStream;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** The private ID8 Anvil codec used by the 1.21.1 server. */
public final class RegionZstdCodec {
    public static final int COMPRESSION_ID = 8;
    public static final int COMPRESSION_LEVEL = 7;
    private static final int STREAM_BUFFER_SIZE = 64 * 1024;
    private static final byte[] EMPTY_BUFFER = new byte[0];
    private static final System.Logger LOGGER = System.getLogger(RegionZstdCodec.class.getName());

    private static final Object POOL_LOCK = new Object();
    private static final int POOL_SIZE = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors()));
    private static final ArrayDeque<CompressionState> AVAILABLE = new ArrayDeque<>(POOL_SIZE);
    private static final List<CompressionState> ALL_STATES = new ArrayList<>(POOL_SIZE);
    private static boolean poolClosed;
    private static volatile RegionFileVersion version;

    static {
        for (int i = 0; i < POOL_SIZE; i++) {
            CompressionState state = new CompressionState();
            AVAILABLE.addLast(state);
            ALL_STATES.add(state);
        }
    }

    private RegionZstdCodec() {
    }

    public static synchronized RegionFileVersion register() {
        if (version != null) {
            return version;
        }
        RegionFileVersion existing = RegionFileVersion.fromId(COMPRESSION_ID);
        if (existing != null) {
            throw new IllegalStateException("Region compression ID " + COMPRESSION_ID + " is already registered");
        }
        version = RegionFileVersion.register(new RegionFileVersion(
            COMPRESSION_ID,
            null,
            RegionZstdCodec::wrapInput,
            RegionZstdCodec::wrapOutput
        ));
        return version;
    }

    public static InputStream wrapInput(InputStream input) throws IOException {
        ZstdInputStream zstd = new ZstdInputStream(input, RecyclingBufferPool.INSTANCE);
        return new ValidatingInputStream(new FastBufferedInputStream(zstd));
    }

    public static OutputStream wrapOutput(OutputStream output) {
        return new StreamingOutput(output);
    }

    /**
     * Identifies the ID8 output created by RegionFile and adds an abort hook
     * without adding another byte buffer in the vanilla write path.
     */
    public static DataOutputStream wrapDataOutput(OutputStream wrapped, DataOutputStream output) {
        if (wrapped instanceof StreamingOutput streamingOutput) {
            return new AbortableDataOutputStream(output, streamingOutput);
        }
        return output;
    }

    /** Aborts an ID8 write after NBT serialization failed. */
    public static void abort(DataOutput output) {
        if (output instanceof AbortableDataOutputStream abortable) {
            abortable.abort();
        }
    }

    public static void closeAll() {
        Throwable failure = null;
        synchronized (POOL_LOCK) {
            if (poolClosed) {
                return;
            }
            poolClosed = true;
            for (CompressionState state : ALL_STATES) {
                state.closeWhenReturned = true;
            }
            try {
                for (CompressionState state : AVAILABLE) {
                    try {
                        state.close();
                    } catch (Throwable closeFailure) {
                        failure = combine(failure, closeFailure);
                    }
                }
                AVAILABLE.clear();
            } finally {
                POOL_LOCK.notifyAll();
            }
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Failed to close Zstd region compression pool", failure);
        }
    }

    private static CompressionState borrow() throws IOException {
        synchronized (POOL_LOCK) {
            while (AVAILABLE.isEmpty() && !poolClosed) {
                try {
                    POOL_LOCK.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for a Zstd region compression context", e);
                }
            }
            if (poolClosed) {
                throw new IOException("Zstd region compression context pool is closed");
            }
            return AVAILABLE.removeFirst();
        }
    }

    private static void release(CompressionState state) {
        synchronized (POOL_LOCK) {
            if (poolClosed || state.closeWhenReturned) {
                state.close();
            } else {
                AVAILABLE.addLast(state);
                POOL_LOCK.notifyAll();
            }
        }
    }

    private static final class CompressionState {
        private ZstdCompressCtx context = new ZstdCompressCtx();
        private ByteBuffer input = ByteBuffer.allocateDirect(STREAM_BUFFER_SIZE);
        private ByteBuffer output = ByteBuffer.allocateDirect(STREAM_BUFFER_SIZE);
        private byte[] transfer = new byte[STREAM_BUFFER_SIZE];
        private boolean closeWhenReturned;
        private boolean closed;
        private boolean frameStarted;

        private void beginFrame() throws IOException {
            if (closed) {
                throw new IOException("Zstd region compression context is closed");
            }
            if (input == null || output == null || transfer.length == 0) {
                input = ByteBuffer.allocateDirect(STREAM_BUFFER_SIZE);
                output = ByteBuffer.allocateDirect(STREAM_BUFFER_SIZE);
                transfer = new byte[STREAM_BUFFER_SIZE];
            }
            try {
                if (context == null) {
                    context = new ZstdCompressCtx();
                }
                context.reset();
                context.setLevel(COMPRESSION_LEVEL).setChecksum(true);
                input.clear();
                output.clear();
                frameStarted = false;
            } catch (Throwable failure) {
                suppress(discardContext(), failure);
                throwAsIOException("Failed to initialize Zstd region compression", failure);
            }
        }

        private void compress(OutputStream target, EndDirective directive) throws IOException {
            input.flip();
            try {
                while (true) {
                    output.clear();
                    int sourcePosition = input.position();
                    boolean complete;
                    try {
                        if (!frameStarted && directive == EndDirective.END) {
                            context.setPledgedSrcSize(input.remaining());
                        }
                        complete = context.compressDirectByteBufferStream(output, input, directive);
                        frameStarted = true;
                    } catch (Throwable failure) {
                        suppress(discardContext(), failure);
                        throwAsIOException("Zstd region compression failed", failure);
                        return;
                    }
                    int produced = output.position();
                    output.flip();
                    drainOutput(target);
                    if (directive == EndDirective.CONTINUE && !input.hasRemaining()) {
                        return;
                    }
                    if (complete) {
                        if (input.hasRemaining()) {
                            throw new IOException("Zstd completed without consuming all region input");
                        }
                        return;
                    }
                    if (sourcePosition == input.position() && produced == 0) {
                        throw new IOException("Zstd region compression made no progress");
                    }
                }
            } finally {
                input.clear();
            }
        }

        private void drainOutput(OutputStream target) throws IOException {
            while (output.hasRemaining()) {
                int count = Math.min(output.remaining(), transfer.length);
                output.get(transfer, 0, count);
                target.write(transfer, 0, count);
            }
        }

        private Throwable discardContext() {
            ZstdCompressCtx previous = context;
            context = null;
            if (previous != null) {
                try {
                    previous.close();
                } catch (Throwable failure) {
                    return failure;
                }
            }
            return null;
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            Throwable failure = discardContext();
            input = null;
            output = null;
            transfer = EMPTY_BUFFER;
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw new IllegalStateException("Failed to close Zstd region compression context", failure);
            }
        }
    }

    private static final class StreamingOutput extends OutputStream {
        private final OutputStream target;
        private CompressionState state;
        private boolean closed;
        private boolean aborted;

        private StreamingOutput(OutputStream target) {
            this.target = target;
        }

        @Override
        public synchronized void write(int value) throws IOException {
            ensureOpen();
            try {
                ensureStarted();
                state.input.put((byte) value);
                if (!state.input.hasRemaining()) {
                    state.compress(target, EndDirective.CONTINUE);
                }
            } catch (Throwable failure) {
                fail(failure);
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new AssertionError(failure);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null) {
                throw new NullPointerException("bytes");
            }
            if (offset < 0 || length < 0 || length > bytes.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            ensureOpen();
            if (length == 0) {
                return;
            }
            try {
                ensureStarted();
                int position = offset;
                int remaining = length;
                while (remaining > 0) {
                    int count = Math.min(state.input.remaining(), remaining);
                    state.input.put(bytes, position, count);
                    position += count;
                    remaining -= count;
                    if (!state.input.hasRemaining()) {
                        state.compress(target, EndDirective.CONTINUE);
                    }
                }
            } catch (Throwable failure) {
                fail(failure);
                throwAsIOException("Failed to write Zstd region output", failure);
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            if (aborted) {
                return;
            }
            try {
                ensureStarted();
                state.compress(target, EndDirective.END);
                releaseState();
                target.close();
            } catch (Throwable failure) {
                fail(failure);
                throwAsIOException("Failed to close Zstd region output", failure);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            if (closed) {
                return;
            }
            ensureOpen();
            try {
                if (state != null) {
                    state.compress(target, EndDirective.FLUSH);
                }
                target.flush();
            } catch (Throwable failure) {
                fail(failure);
                throwAsIOException("Failed to flush Zstd region output", failure);
            }
        }

        private void ensureStarted() throws IOException {
            if (state != null) {
                return;
            }
            state = borrow();
            try {
                state.beginFrame();
            } catch (Throwable failure) {
                Throwable cleanupFailure = state.discardContext();
                suppress(cleanupFailure, failure);
                releaseStateSuppressing(failure);
                throwAsIOException("Failed to start Zstd region frame", failure);
            }
        }

        private synchronized void abort() {
            if (closed) {
                return;
            }
            aborted = true;
            closed = true;
            releaseStateSuppressing(null);
        }

        private void fail(Throwable failure) {
            aborted = true;
            closed = true;
            releaseStateSuppressing(failure);
        }

        private void releaseState() {
            CompressionState released = state;
            state = null;
            if (released != null) {
                release(released);
            }
        }

        private void releaseStateSuppressing(Throwable primary) {
            try {
                releaseState();
            } catch (Throwable cleanupFailure) {
                if (primary == null) {
                    LOGGER.log(System.Logger.Level.ERROR, "Failed to release Zstd region compression context", cleanupFailure);
                } else {
                    suppress(cleanupFailure, primary);
                }
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Stream is closed");
            }
        }
    }

    private static void suppress(Throwable cleanup, Throwable primary) {
        if (cleanup != null && primary != null && cleanup != primary) {
            primary.addSuppressed(cleanup);
        }
    }

    private static Throwable combine(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
        return primary;
    }

    private static void throwAsIOException(String message, Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IOException(message, failure);
    }

    private static final class AbortableDataOutputStream extends DataOutputStream {
        private final StreamingOutput output;
        private boolean closed;

        private AbortableDataOutputStream(DataOutputStream delegate, StreamingOutput output) {
            super(delegate);
            this.output = output;
        }

        private void abort() {
            output.abort();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            // The delegate is a plain DataOutputStream with no pending user-space bytes;
            // close the codec directly to avoid an implicit FLUSH followed by END.
            output.close();
        }
    }

    /** Drains the decoded stream at close so Zstd validates the frame trailer/checksum. */
    private static final class ValidatingInputStream extends InputStream {
        private final InputStream delegate;
        private boolean closed;

        private ValidatingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return delegate.read(bytes, offset, length);
        }

        @Override
        public long skip(long count) throws IOException {
            return delegate.skip(count);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            Throwable failure = null;
            try {
                int first = delegate.read();
                if (first != -1) {
                    byte[] drain = new byte[8192];
                    do {
                        // Drain all decoded bytes so Zstd checks the frame trailer.
                    } while (delegate.read(drain, 0, drain.length) != -1);
                }
            } catch (Throwable e) {
                failure = e;
            }
            try {
                delegate.close();
            } catch (Throwable e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                switch (failure) {
                    case IOException exception -> throw exception;
                    case RuntimeException exception -> throw exception;
                    case Error error -> throw error;
                    default -> {
                    }
                }
                throw new IOException("Unexpected failure while validating Zstd frame", failure);
            }
        }
    }
}
