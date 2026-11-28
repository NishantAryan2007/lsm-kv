package lsmkv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An open SSTable shared between readers and the engine. refs starts at 1 (owned by the live
 * set). The table is closed, and deleted if obsolete, when the last reference is released.
 */
final class Table {
    private final long number;
    private final SSTableReader reader;
    private final Path path;
    private final AtomicInteger refs = new AtomicInteger(1);
    private volatile boolean obsolete;

    Table(long number, SSTableReader reader, Path path) {
        this.number = number;
        this.reader = reader;
        this.path = path;
    }

    long number() {
        return number;
    }

    SSTableReader reader() {
        return reader;
    }

    /** Returns false if the table is already closed; the caller must then re-read the engine's view. */
    boolean tryAcquire() {
        while (true) {
            int n = refs.get();
            if (n <= 0) {
                return false;
            }
            if (refs.compareAndSet(n, n + 1)) {
                return true;
            }
        }
    }

    void release() {
        if (refs.decrementAndGet() == 0) {
            try {
                reader.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
            if (obsolete) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // cleaned up as an orphan on the next open
                }
            }
        }
    }

    /** Compaction replaced this table: delete it once the last reader leaves. */
    void retire() {
        obsolete = true;      // must be visible before the release that may reach zero
        release();
    }
}