package lsmkv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LsmEngineFlushTest {
    private static final long LIMIT = 4096;

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

    private long count(String extension) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(extension)).count();
        }
    }

    private static void filler(LsmEngine db, String prefix, int n) throws IOException {
        for (int i = 0; i < n; i++) {
            db.put(b(prefix + i), b("x"));
        }
    }

    @Test
    void flushesAndStaysReadable() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT)) {
            for (int i = 0; i < 1000; i++) {
                db.put(b(key(i)), b("value-" + i));
            }
            assertTrue(count(".sst") >= 5, "expected several flushes");
            for (int i = 0; i < 1000; i++) {
                assertEquals("value-" + i, str(db.get(b(key(i)))));
            }
            assertNull(str(db.get(b("missing"))));
        }
    }

    @Test
    void survivesRestartAndSequenceNumbersKeepIncreasing() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT)) {
            for (int i = 0; i < 1000; i++) {
                db.put(b(key(i)), b("old-" + i));
            }
        }
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT)) {
            assertEquals("old-0", str(db.get(b(key(0)))));
            db.put(b(key(0)), b("new-0"));
            db.delete(b(key(1)));
            assertEquals("new-0", str(db.get(b(key(0)))));
            assertNull(str(db.get(b(key(1)))));
        }
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT)) {
            assertEquals("new-0", str(db.get(b(key(0)))));
            assertNull(str(db.get(b(key(1)))));
            assertEquals("old-2", str(db.get(b(key(2)))));
        }
    }

    @Test
    void deleteWorksAcrossFlushes() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT)) {
            db.put(b("victim"), b("alive"));
            filler(db, "fillA-", 300);                       // pushes "victim" into a table
            assertEquals("alive", str(db.get(b("victim"))));
            db.delete(b("victim"));
            assertNull(str(db.get(b("victim"))));            // tombstone in memtable shadows the table
            filler(db, "fillB-", 300);                       // pushes the tombstone into a newer table
            assertNull(str(db.get(b("victim"))));            // newer tombstone shadows older value
        }
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT)) {
            assertNull(str(db.get(b("victim"))));
        }
    }

    @Test
    void orphanFilesAreRemovedOnOpen() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT)) {
            for (int i = 0; i < 500; i++) {
                db.put(b(key(i)), b("value-" + i));
            }
        }
        Path realTable;
        try (Stream<Path> files = Files.list(dir)) {
            realTable = files.filter(p -> p.getFileName().toString().endsWith(".sst")).findFirst().orElseThrow();
        }
        byte[] real = Files.readAllBytes(realTable);
        Files.write(dir.resolve("000900.sst"), Arrays.copyOf(real, real.length / 2)); // half-written table
        Files.write(dir.resolve("000901.wal"), b("garbage"));
        Files.write(dir.resolve("MANIFEST.tmp"), b("garbage"));

        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT)) {
            assertFalse(Files.exists(dir.resolve("000900.sst")));
            assertFalse(Files.exists(dir.resolve("000901.wal")));
            assertFalse(Files.exists(dir.resolve("MANIFEST.tmp")));
            for (int i = 0; i < 500; i++) {
                assertEquals("value-" + i, str(db.get(b(key(i)))));
            }
        }
    }

    @Test
    void randomOperationsWithFlushesAndRestartsMatchModel() throws IOException {
        Random rnd = new Random(8);
        Map<String, String> model = new HashMap<>();
        LsmEngine db = LsmEngine.open(dir, false, LIMIT);
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
                    db = LsmEngine.open(dir, false, LIMIT);
                } else {
                    assertEquals(model.get(k), str(db.get(b(k))), "key " + k + " at op " + i);
                }
            }
            db.close();
            db = LsmEngine.open(dir, false, LIMIT);
            for (int n = 0; n < 60; n++) {
                assertEquals(model.get("k" + n), str(db.get(b("k" + n))), "final check k" + n);
            }
        } finally {
            db.close();
        }
    }

    @Test
    void corruptManifestRefusesToOpen() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT)) {
            db.put(b("k"), b("v"));
        }
        Path manifest = dir.resolve("MANIFEST");
        byte[] data = Files.readAllBytes(manifest);
        data[data.length - 1] ^= 1;
        Files.write(manifest, data);
        assertThrows(IOException.class, () -> LsmEngine.open(dir, false, LIMIT));
    }
}