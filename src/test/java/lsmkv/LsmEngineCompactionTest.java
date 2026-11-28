package lsmkv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LsmEngineCompactionTest {
    private static final long LIMIT = 2048;
    private static final int TRIGGER = 3;

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(Optional<byte[]> o) {
        return o.map(x -> new String(x, StandardCharsets.UTF_8)).orElse(null);
    }

    private static String key(int i) {
        return String.format("key-%05d", i);
    }

    private List<Path> sstFiles() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sst")).toList();
        }
    }

    private long sstBytes() throws IOException {
        long total = 0;
        for (Path p : sstFiles()) {
            total += Files.size(p);
        }
        return total;
    }

    @Test
    void compactionKeepsTheTableCountBounded() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT, TRIGGER)) {
            for (int i = 0; i < 2_000; i++) {
                db.put(b(key(i)), b("value-" + i));
                assertTrue(sstFiles().size() <= TRIGGER, "too many tables after write " + i);
            }
            for (int i = 0; i < 2_000; i++) {
                assertEquals("value-" + i, str(db.get(b(key(i)))));
            }
        }
    }

    @Test
    void compactionReclaimsSpaceFromOverwrittenKeys() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT, TRIGGER)) {
            for (int i = 0; i < 5_000; i++) {
                db.put(b("hot-" + (i % 20)), b("version-" + i));
            }
            long bytes = sstBytes();
            assertTrue(bytes < 20_000, "table bytes after compaction: " + bytes); // ~200 KB without compaction
            for (int k = 0; k < 20; k++) {
                assertEquals("version-" + (4_980 + k), str(db.get(b("hot-" + k))));
            }
        }
    }

    @Test
    void deletedKeysStayDeletedThroughCompactionAndRestart() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT, TRIGGER)) {
            for (int i = 0; i < 300; i++) {
                db.put(b(key(i)), b("value-" + i));
            }
            for (int i = 0; i < 300; i += 2) {
                db.delete(b(key(i)));
            }
            for (int i = 0; i < 400; i++) {
                db.put(b("filler-" + i), b("x"));   // drives more flushes and compactions
            }
            for (int i = 0; i < 300; i++) {
                assertEquals(i % 2 == 0 ? null : "value-" + i, str(db.get(b(key(i)))), "key " + i);
            }
        }
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT, TRIGGER)) {
            for (int i = 0; i < 300; i++) {
                assertEquals(i % 2 == 0 ? null : "value-" + i, str(db.get(b(key(i)))), "after restart, key " + i);
            }
        }
    }

    @Test
    void randomOperationsWithCompactionAndRestartsMatchModel() throws IOException {
        Random rnd = new Random(9);
        Map<String, String> model = new HashMap<>();
        LsmEngine db = LsmEngine.open(dir, false, LIMIT, TRIGGER);
        try {
            for (int i = 0; i < 4_000; i++) {
                String k = "k" + rnd.nextInt(60);
                int op = rnd.nextInt(10);
                if (op < 4) {
                    String v = "v" + i;
                    db.put(b(k), b(v));
                    model.put(k, v);
                } else if (op < 6) {
                    db.delete(b(k));
                    model.remove(k);
                } else if (op == 6 && rnd.nextInt(40) == 0) {
                    db.close();                       // simulated restart
                    db = LsmEngine.open(dir, false, LIMIT, TRIGGER);
                } else {
                    assertEquals(model.get(k), str(db.get(b(k))), "key " + k + " at op " + i);
                }
            }
            db.close();
            db = LsmEngine.open(dir, false, LIMIT, TRIGGER);
            for (int n = 0; n < 60; n++) {
                assertEquals(model.get("k" + n), str(db.get(b("k" + n))), "final check k" + n);
            }
        } finally {
            db.close();
        }
    }

    @Test
    void readersSurviveFlushesAndCompactionsHappeningUnderneath() throws Exception {
        int total = 3_000;
        AtomicInteger lastWritten = new AtomicInteger(-1);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT, TRIGGER)) {
            Thread[] readers = new Thread[3];
            for (int t = 0; t < readers.length; t++) {
                int seed = t;
                readers[t] = new Thread(() -> {
                    Random rnd = new Random(seed);
                    try {
                        while (!done.get()) {
                            int hi = lastWritten.get();
                            if (hi < 0) {
                                continue;
                            }
                            int i = rnd.nextInt(hi + 1);
                            String got = str(db.get(b(key(i))));
                            if (!("value-" + i).equals(got)) {
                                throw new AssertionError("key " + i + " returned " + got);
                            }
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                });
                readers[t].start();
            }
            for (int i = 0; i < total && failure.get() == null; i++) {
                db.put(b(key(i)), b("value-" + i));
                lastWritten.set(i);            // only advertised after put returned
            }
            done.set(true);
            for (Thread th : readers) {
                th.join();
            }
        }
        if (failure.get() != null) {
            fail("a reader failed while compaction ran underneath it", failure.get());
        }
    }
}