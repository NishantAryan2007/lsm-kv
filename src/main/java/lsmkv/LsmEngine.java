package lsmkv;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * MemTable + WAL + SSTables + MANIFEST + compaction. One writer at a time (writeLock), lock-free
 * readers. Flush and compaction are synchronous (inside the write lock). After any I/O error on a
 * write the engine refuses further writes; reopen to recover. The engine owns its directory:
 * files that the MANIFEST does not reference are deleted on open.
 */
public final class LsmEngine implements AutoCloseable {
    private static final int BLOCK_SIZE = 4096;
    private static final long DEFAULT_MEMTABLE_LIMIT = 4L * 1024 * 1024;
    private static final int DEFAULT_COMPACTION_TRIGGER = 4;

    /** Immutable snapshot of where data lives. Tables are newest first. */
    private record View(MemTable mem, List<Table> tables) {}

    private final Path dir;
    private final boolean syncOnWrite;
    private final long memtableLimit;
    private final int compactionTrigger;
    private final Object writeLock = new Object();

    // guarded by writeLock
    private WalWriter wal;
    private long walNumber;
    private long nextFileNumber;
    private long lastAssignedSeq;
    private boolean failed;

    private volatile View view;
    private volatile long publishedSeq;   // readers snapshot this

    private LsmEngine(Path dir, boolean syncOnWrite, long memtableLimit, int compactionTrigger,
                      View view, WalWriter wal, Manifest.State state, long lastSeq) {
        this.dir = dir;
        this.syncOnWrite = syncOnWrite;
        this.memtableLimit = memtableLimit;
        this.compactionTrigger = compactionTrigger;
        this.view = view;
        this.wal = wal;
        this.walNumber = state.walNumber();
        this.nextFileNumber = state.nextFileNumber();
        this.lastAssignedSeq = lastSeq;
        this.publishedSeq = lastSeq;
    }

    public static LsmEngine open(Path dir, boolean syncOnWrite) throws IOException {
        return open(dir, syncOnWrite, DEFAULT_MEMTABLE_LIMIT, DEFAULT_COMPACTION_TRIGGER);
    }

    /** Compaction disabled; mainly for tests that count flushed tables. */
    public static LsmEngine open(Path dir, boolean syncOnWrite, long memtableLimit) throws IOException {
        return open(dir, syncOnWrite, memtableLimit, Integer.MAX_VALUE);
    }

    public static LsmEngine open(Path dir, boolean syncOnWrite, long memtableLimit, int compactionTrigger)
            throws IOException {
        if (compactionTrigger < 2) {
            throw new IllegalArgumentException("compactionTrigger must be >= 2");
        }
        Files.createDirectories(dir);
        Manifest.State state = Manifest.read(dir);
        if (state == null) {
            state = new Manifest.State(2, 1, 0, List.of());
            Manifest.write(dir, state);
        }
        deleteOrphans(dir, state);

        List<Table> tables = new ArrayList<>();
        try {
            for (long no : state.tables()) {
                Path p = sstPath(dir, no);
                tables.add(new Table(no, SSTableReader.open(p), p));
            }

            MemTable mem = new MemTable();
            long[] maxSeq = {state.lastSeq()};
            Path walFile = walPath(dir, state.walNumber());
            if (Files.exists(walFile)) {
                long valid = WalReader.replay(walFile, r -> {
                    if (r.type() == ValueType.PUT) {
                        mem.put(r.key(), r.value(), r.seq());
                    } else {
                        mem.delete(r.key(), r.seq());
                    }
                    maxSeq[0] = Math.max(maxSeq[0], r.seq());
                });
                if (valid < Files.size(walFile)) {
                    try (FileChannel ch = FileChannel.open(walFile, StandardOpenOption.WRITE)) {
                        ch.truncate(valid);
                        ch.force(true);
                    }
                }
            }
            WalWriter wal = new WalWriter(walFile, syncOnWrite);
            return new LsmEngine(dir, syncOnWrite, memtableLimit, compactionTrigger,
                    new View(mem, List.copyOf(tables)), wal, state, maxSeq[0]);
        } catch (IOException | RuntimeException e) {
            for (Table t : tables) {
                t.release();
            }
            throw e;
        }
    }

    public void put(byte[] key, byte[] value) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        synchronized (writeLock) {
            checkNotFailed();
            MemTable mem = view.mem();
            long seq = ++lastAssignedSeq;
            appendOrFail(seq, ValueType.PUT, key, value);
            try {
                mem.put(key, value, seq);
            } catch (RuntimeException | Error e) {
                failed = true;   // log and memory now disagree: stop and recover from the log
                throw e;
            }
            publishedSeq = seq;
            afterWrite();
        }
    }

    public void delete(byte[] key) throws IOException {
        Objects.requireNonNull(key, "key");
        synchronized (writeLock) {
            checkNotFailed();
            MemTable mem = view.mem();
            long seq = ++lastAssignedSeq;
            appendOrFail(seq, ValueType.DELETE, key, new byte[0]);
            try {
                mem.delete(key, seq);
            } catch (RuntimeException | Error e) {
                failed = true;
                throw e;
            }
            publishedSeq = seq;
            afterWrite();
        }
    }

    /**
     * Empty if the key is absent or deleted. Newest data wins: memtable first, then tables newest
     * first. If a table was retired by a compaction while we were looking, start over on the new view.
     */
    public Optional<byte[]> get(byte[] key) throws IOException {
        Objects.requireNonNull(key, "key");
        while (true) {
            long snapshot = publishedSeq;   // read BEFORE the view: the view then holds everything <= snapshot
            View v = view;
            MemTable.Lookup result = v.mem().get(key, snapshot);
            boolean stale = false;
            if (result.kind() == MemTable.Kind.ABSENT) {
                for (Table t : v.tables()) {
                    if (!t.tryAcquire()) {
                        stale = true;
                        break;
                    }
                    try {
                        result = t.reader().get(key, snapshot);
                    } finally {
                        t.release();
                    }
                    if (result.kind() != MemTable.Kind.ABSENT) {
                        break;   // FOUND or DELETED both end the search
                    }
                }
            }
            if (stale) {
                continue;
            }
            return result.kind() == MemTable.Kind.FOUND
                    ? Optional.of(result.value().clone())
                    : Optional.empty();
        }
    }

    private void afterWrite() throws IOException {
        try {
            if (view.mem().approximateBytes() >= memtableLimit) {
                flush();
                if (view.tables().size() >= compactionTrigger) {
                    compactAll();
                }
            }
        } catch (IOException | RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    /** Caller holds writeLock. Order matters: file, then MANIFEST (commit point), then cleanup. */
    private void flush() throws IOException {
        View current = view;

        // 1. write the SSTable; it is invisible to recovery until the MANIFEST names it
        long tableNo = nextFileNumber++;
        Path sst = sstPath(dir, tableNo);
        try (SSTableWriter w = new SSTableWriter(sst, BLOCK_SIZE);
             KvIterator it = current.mem().iterator()) {
            it.seekToFirst();
            while (it.valid()) {
                w.add(it.key(), it.value());
                it.next();
            }
            w.finish();   // fsyncs the file
        }
        Manifest.fsyncDirectory(dir);

        // 2. a fresh WAL for the writes that follow
        long newWalNo = nextFileNumber++;
        WalWriter newWal = new WalWriter(walPath(dir, newWalNo), syncOnWrite);

        // 3. commit: the new MANIFEST makes the table live and retires the old WAL
        List<Table> newTables = new ArrayList<>(current.tables().size() + 1);
        newTables.add(new Table(tableNo, SSTableReader.open(sst), sst));
        newTables.addAll(current.tables());
        Manifest.write(dir, new Manifest.State(nextFileNumber, newWalNo, lastAssignedSeq, numbersOf(newTables)));
        // ---- commit point: from here on, recovery starts from the new state ----

        // 4. swap in the new state, then delete the old WAL (it is just an orphan if this fails)
        WalWriter oldWal = wal;
        long oldWalNo = walNumber;
        wal = newWal;
        walNumber = newWalNo;
        view = new View(new MemTable(), List.copyOf(newTables));
        oldWal.close();
        try {
            Files.deleteIfExists(walPath(dir, oldWalNo));
        } catch (IOException ignored) {
            // cleaned up as an orphan on the next open
        }
    }

    /**
     * Caller holds writeLock. Merges every table into one. The output is the bottom of the tree
     * (all tables are inputs and the memtable is newer), so tombstones can be dropped.
     */
    private void compactAll() throws IOException {
        View current = view;
        List<Table> inputs = current.tables();

        long outNo = nextFileNumber++;
        Path outPath = sstPath(dir, outNo);
        List<KvIterator> sources = new ArrayList<>();
        for (Table t : inputs) {
            sources.add(t.reader().iterator());
        }
        long written;
        try (KvIterator merged = new MergingIterator(sources);
             SSTableWriter w = new SSTableWriter(outPath, BLOCK_SIZE)) {
            written = Compactor.run(merged, w, true);
            w.finish();
        }
        Manifest.fsyncDirectory(dir);

        List<Table> newTables;
        if (written == 0) {
            Files.deleteIfExists(outPath);   // everything was deleted: no table at all
            newTables = List.of();
        } else {
            newTables = List.of(new Table(outNo, SSTableReader.open(outPath), outPath));
        }
        Manifest.write(dir, new Manifest.State(nextFileNumber, walNumber, lastAssignedSeq, numbersOf(newTables)));
        // ---- commit point ----

        view = new View(current.mem(), newTables);   // same memtable: writes continue uninterrupted
        for (Table t : inputs) {
            t.retire();   // deleted when the last reader that may still be using it leaves
        }
    }

    private static List<Long> numbersOf(List<Table> tables) {
        List<Long> numbers = new ArrayList<>(tables.size());
        for (Table t : tables) {
            numbers.add(t.number());
        }
        return numbers;
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
            IOException first = null;
            try {
                wal.close();
            } catch (IOException e) {
                first = e;
            }
            for (Table t : view.tables()) {
                t.release();   // closes now, or when the last in-flight reader leaves
            }
            if (first != null) {
                throw first;
            }
        }
    }

    private static void deleteOrphans(Path dir, Manifest.State state) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path p : files) {
                String name = p.getFileName().toString();
                boolean orphan;
                if (name.equals(Manifest.TMP)) {
                    orphan = true;
                } else if (name.endsWith(".sst")) {
                    orphan = !state.tables().contains(parseNumber(name));
                } else if (name.endsWith(".wal")) {
                    orphan = parseNumber(name) != state.walNumber();
                } else {
                    continue;
                }
                if (orphan) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static long parseNumber(String fileName) {
        try {
            return Long.parseLong(fileName.substring(0, fileName.indexOf('.')));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Path sstPath(Path dir, long number) {
        return dir.resolve(String.format("%06d.sst", number));
    }

    private static Path walPath(Path dir, long number) {
        return dir.resolve(String.format("%06d.wal", number));
    }
}