package lsmkv;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/**
 * v0: MemTable + WAL only. One writer at a time (writeLock), lock-free readers.
 * After any I/O error on the log the engine refuses further writes; reopen to recover.
 */
public final class LsmEngine implements AutoCloseable {
    private final MemTable mem;
    private final WalWriter wal;
    private final Object writeLock = new Object();
    private long lastAssignedSeq;          // guarded by writeLock
    private boolean failed;                // guarded by writeLock
    private volatile long publishedSeq;    // readers snapshot this

    private LsmEngine(MemTable mem, WalWriter wal, long lastSeq) {
        this.mem = mem;
        this.wal = wal;
        this.lastAssignedSeq = lastSeq;
        this.publishedSeq = lastSeq;
    }

    public static LsmEngine open(Path dir, boolean syncOnWrite) throws IOException {
        Files.createDirectories(dir);
        Path walPath = dir.resolve("wal.log");
        MemTable mem = new MemTable();
        long[] maxSeq = {0};

        if (Files.exists(walPath)) {
            long valid = WalReader.replay(walPath, r -> {
                if (r.type() == ValueType.PUT) {
                    mem.put(r.key(), r.value(), r.seq());
                } else {
                    mem.delete(r.key(), r.seq());
                }
                maxSeq[0] = Math.max(maxSeq[0], r.seq());
            });
            if (valid < Files.size(walPath)) {
                try (FileChannel ch = FileChannel.open(walPath, StandardOpenOption.WRITE)) {
                    ch.truncate(valid);
                    ch.force(true);
                }
            }
        }
        return new LsmEngine(mem, new WalWriter(walPath, syncOnWrite), maxSeq[0]);
    }

    public void put(byte[] key, byte[] value) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        synchronized (writeLock) {
            checkNotFailed();
            long seq = ++lastAssignedSeq;
            appendOrFail(seq, ValueType.PUT, key, value);
            try {
                mem.put(key, value, seq);
            } catch (RuntimeException | Error e) {
                failed = true;   // log and memory disagree: stop, and recover from the log
                throw e;
            }
            publishedSeq = seq;
        }
    }

    public void delete(byte[] key) throws IOException {
        Objects.requireNonNull(key, "key");
        synchronized (writeLock) {
            checkNotFailed();
            long seq = ++lastAssignedSeq;
            appendOrFail(seq, ValueType.DELETE, key, new byte[0]);
            try {
                mem.delete(key, seq);
            } catch (RuntimeException | Error e) {
                failed = true;   // log and memory disagree: stop, and recover from the log
                throw e;
            }
            publishedSeq = seq;
        }
    }

    /** Empty if the key is absent or deleted. (Later: ABSENT will continue to the disk files.) */
    public Optional<byte[]> get(byte[] key) {
        Objects.requireNonNull(key, "key");
        long snapshot = publishedSeq;
        MemTable.Lookup found = mem.get(key, snapshot);
        return found.kind() == MemTable.Kind.FOUND
                ? Optional.of(found.value().clone())
                : Optional.empty();
    }

    private void appendOrFail(long seq, ValueType type, byte[] key, byte[] value) throws IOException {
        try {
            wal.append(seq, type, key, value);
        } catch (IOException | RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    private void checkNotFailed() {
        if (failed) {
            throw new IllegalStateException("engine failed after an I/O error; reopen to recover");
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (writeLock) {
            wal.close();
        }
    }
}