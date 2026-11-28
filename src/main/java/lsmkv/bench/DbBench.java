package lsmkv.bench;

import lsmkv.LsmEngine;
import lsmkv.RangeIterator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

/**
 * db_bench-style load generator for the engine.
 * Run: mvn -q -DskipTests package, then: java -cp target/classes lsmkv.bench.DbBench [numKeys]
 */
public final class DbBench {
    private static final int VALUE_SIZE = 100;
    private static final long MEMTABLE_BYTES = 4L * 1024 * 1024;
    private static final int COMPACTION_TRIGGER = 4;
    private static final int SYNC_WRITES = 2_000;

    private DbBench() {}

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 300_000;
        Path root = Files.createTempDirectory("lsm-bench");
        try {
            System.out.println("java " + System.getProperty("java.version") + ", "
                    + System.getProperty("os.name") + ", "
                    + Runtime.getRuntime().availableProcessors() + " cores, keys=" + n
                    + ", value=" + VALUE_SIZE + " B, memtable=" + (MEMTABLE_BYTES >> 20)
                    + " MiB, compaction trigger=" + COMPACTION_TRIGGER);
            warmUp(root.resolve("warmup"));
            System.out.println();
            System.out.println("| workload | ops | ops/s | p50 us | p99 us | p99.9 us | max us |");
            System.out.println("|---|---|---|---|---|---|---|");
            runMain(root.resolve("main"), n);
            runSync(root.resolve("sync"));
        } finally {
            deleteRecursively(root);
        }
    }

    private static void warmUp(Path dir) throws IOException {
        Random rnd = new Random(7);
        byte[] value = new byte[VALUE_SIZE];
        rnd.nextBytes(value);
        byte[][] keys = shuffledKeys(30_000, "w", rnd);
        try (LsmEngine db = LsmEngine.open(dir, false, MEMTABLE_BYTES, COMPACTION_TRIGGER)) {
            for (byte[] k : keys) {
                db.put(k, value);
            }
            for (byte[] k : keys) {
                db.get(k);
            }
        }
    }

    private static void runMain(Path dir, int n) throws IOException {
        Random rnd = new Random(1);
        byte[] value = new byte[VALUE_SIZE];
        rnd.nextBytes(value);
        byte[][] keys = shuffledKeys(n, "k", rnd);
        byte[][] missing = shuffledKeys(n, "x", rnd);

        try (LsmEngine db = LsmEngine.open(dir, false, MEMTABLE_BYTES, COMPACTION_TRIGGER)) {
            long[] lat = new long[n];
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                db.put(keys[i], value);
                lat[i] = System.nanoTime() - t0;
            }
            report("fillrandom, sync=false", n, System.nanoTime() - start, lat);

            int reads = Math.min(n, 200_000);

            lat = new long[reads];
            start = System.nanoTime();
            for (int i = 0; i < reads; i++) {
                byte[] k = keys[rnd.nextInt(n)];
                long t0 = System.nanoTime();
                boolean found = db.get(k).isPresent();
                lat[i] = System.nanoTime() - t0;
                if (!found) {
                    throw new IllegalStateException("a key written earlier was not found");
                }
            }
            report("readrandom (existing keys)", reads, System.nanoTime() - start, lat);

            lat = new long[reads];
            start = System.nanoTime();
            for (int i = 0; i < reads; i++) {
                byte[] k = missing[rnd.nextInt(n)];
                long t0 = System.nanoTime();
                boolean found = db.get(k).isPresent();
                lat[i] = System.nanoTime() - t0;
                if (found) {
                    throw new IllegalStateException("a key that was never written was found");
                }
            }
            report("readmissing (absent keys)", reads, System.nanoTime() - start, lat);

            start = System.nanoTime();
            long count = 0;
            try (RangeIterator it = db.scan(null, null)) {
                while (it.valid()) {
                    it.key();
                    it.value();
                    count++;
                    it.next();
                }
            }
            long elapsed = System.nanoTime() - start;
            if (count != n) {
                throw new IllegalStateException("scan returned " + count + " keys, expected " + n);
            }
            System.out.printf(Locale.ROOT, "| scan (all keys) | %d | %,.0f keys/s | - | - | - | - |%n",
                    count, count / (elapsed / 1e9));

            long logical = (long) n * (keys[0].length + VALUE_SIZE);
            long onDisk = directoryBytes(dir);
            System.out.printf(Locale.ROOT,
                    "%ndisk after the run: %d table files, %.1f MiB on disk for %.1f MiB of keys+values (%.2fx)%n",
                    countFiles(dir, ".sst"), onDisk / 1048576.0, logical / 1048576.0, (double) onDisk / logical);
        }
    }

    private static void runSync(Path dir) throws IOException {
        Random rnd = new Random(2);
        byte[] value = new byte[VALUE_SIZE];
        rnd.nextBytes(value);
        byte[][] keys = shuffledKeys(SYNC_WRITES, "s", rnd);
        try (LsmEngine db = LsmEngine.open(dir, true, MEMTABLE_BYTES, COMPACTION_TRIGGER)) {
            long[] lat = new long[SYNC_WRITES];
            long start = System.nanoTime();
            for (int i = 0; i < SYNC_WRITES; i++) {
                long t0 = System.nanoTime();
                db.put(keys[i], value);
                lat[i] = System.nanoTime() - t0;
            }
            report("fillrandom, sync=true (fsync per write)", SYNC_WRITES, System.nanoTime() - start, lat);
        }
    }

    private static byte[][] shuffledKeys(int n, String prefix, Random rnd) {
        byte[][] keys = new byte[n][];
        for (int i = 0; i < n; i++) {
            keys[i] = (prefix + String.format("%015d", i)).getBytes(StandardCharsets.UTF_8);
        }
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            byte[] tmp = keys[i];
            keys[i] = keys[j];
            keys[j] = tmp;
        }
        return keys;
    }

    private static void report(String name, int ops, long elapsedNanos, long[] latencies) {
        long[] sorted = latencies.clone();
        Arrays.sort(sorted);
        System.out.printf(Locale.ROOT, "| %s | %d | %,.0f | %d | %d | %d | %d |%n",
                name, ops, ops / (elapsedNanos / 1e9),
                percentile(sorted, 50), percentile(sorted, 99), percentile(sorted, 99.9),
                sorted[sorted.length - 1] / 1000);
    }

    /** Percentile in microseconds from sorted nanosecond latencies. */
    private static long percentile(long[] sorted, double p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(idx, sorted.length - 1));
        return sorted[idx] / 1000;
    }

    private static long directoryBytes(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            long total = 0;
            for (Path p : files.toList()) {
                total += Files.size(p);
            }
            return total;
        }
    }

    private static long countFiles(Path dir, String extension) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(extension)).count();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort cleanup of a temp directory
                }
            });
        }
    }
}