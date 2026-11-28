package lsmkv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LsmEngineScanTest {
    private static final long LIMIT = 2048;
    private static final int TRIGGER = 3;

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] x) {
        return new String(x, StandardCharsets.UTF_8);
    }

    private static String key(int i) {
        return String.format("key-%05d", i);
    }

    private static List<String> drain(RangeIterator it) {
        List<String> out = new ArrayList<>();
        while (it.valid()) {
            out.add(str(it.key()) + "=" + str(it.value()));
            it.next();
        }
        return out;
    }

    private static List<String> expected(TreeMap<String, String> model, String start, String end) {
        return model.entrySet().stream()
                .filter(e -> (start == null || e.getKey().compareTo(start) >= 0)
                        && (end == null || e.getKey().compareTo(end) < 0))
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList();
    }

    private List<String> sstNames() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".sst")).toList();
        }
    }

    @Test
    void scanHidesTombstonesAndOlderVersions() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false)) {
            db.put(b("a"), b("1"));
            db.put(b("b"), b("1"));
            db.put(b("b"), b("2"));
            db.put(b("c"), b("1"));
            db.delete(b("c"));
            db.put(b("d"), b("1"));
            try (RangeIterator it = db.scan(null, null)) {
                assertEquals(List.of("a=1", "b=2", "d=1"), drain(it));
            }
        }
    }

    @Test
    void boundsAreStartInclusiveEndExclusive() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false)) {
            try (RangeIterator it = db.scan(null, null)) {
                assertFalse(it.valid());                       // empty database
            }
            for (String k : List.of("a", "b", "c", "d")) {
                db.put(b(k), b(k.toUpperCase()));
            }
            try (RangeIterator it = db.scan(b("b"), b("d"))) {
                assertEquals(List.of("b=B", "c=C"), drain(it));
            }
            try (RangeIterator it = db.scan(b("b"), b("b"))) {
                assertTrue(drain(it).isEmpty());
            }
            try (RangeIterator it = db.scan(b("d"), b("a"))) {
                assertTrue(drain(it).isEmpty());               // start after end
            }
            try (RangeIterator it = db.scan(b("bb"), b("c"))) {
                assertTrue(drain(it).isEmpty());               // between two keys
            }
            try (RangeIterator it = db.scan(null, b("b"))) {
                assertEquals(List.of("a=A"), drain(it));
            }
            try (RangeIterator it = db.scan(b("c"), null)) {
                assertEquals(List.of("c=C", "d=D"), drain(it));
            }
        }
    }

    @Test
    void scanMatchesModelAcrossFlushesAndCompactions() throws IOException {
        Random rnd = new Random(10);
        TreeMap<String, String> model = new TreeMap<>();
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT, TRIGGER)) {
            for (int i = 0; i < 4_000; i++) {
                String k = String.format("k%02d", rnd.nextInt(60));
                int op = rnd.nextInt(10);
                if (op < 5) {
                    String v = "v" + i;
                    db.put(b(k), b(v));
                    model.put(k, v);
                } else if (op < 7) {
                    db.delete(b(k));
                    model.remove(k);
                } else if (op == 7 && rnd.nextInt(10) == 0) {
                    String start = rnd.nextInt(5) == 0 ? null : String.format("k%02d", rnd.nextInt(65));
                    String end = rnd.nextInt(5) == 0 ? null : String.format("k%02d", rnd.nextInt(65));
                    try (RangeIterator it = db.scan(b(start), b(end))) {
                        assertEquals(expected(model, start, end), drain(it),
                                "scan [" + start + ", " + end + ") at op " + i);
                    }
                }
            }
            try (RangeIterator it = db.scan(null, null)) {
                assertEquals(expected(model, null, null), drain(it));
            }
        }
    }

    @Test
    void scanIsASnapshotEvenWhileFlushesAndCompactionsRunUnderneath() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT, TRIGGER)) {
            TreeMap<String, String> atScan = new TreeMap<>();
            for (int i = 0; i < 100; i++) {
                db.put(b(key(i)), b("old-" + i));
            }
            for (int i = 20; i < 80; i++) {
                atScan.put(key(i), "old-" + i);
            }

            try (RangeIterator it = db.scan(b(key(20)), b(key(80)))) {
                for (int i = 20; i < 80; i++) {
                    db.put(b(key(i)), b("new-" + i));
                }
                for (int i = 20; i < 80; i += 3) {
                    db.delete(b(key(i)));
                }
                db.put(b(key(50) + "x"), b("added"));          // a new key inside the range
                for (int i = 0; i < 500; i++) {
                    db.put(b("filler-" + i), b("x"));          // drives flushes and compactions
                }
                assertEquals(expected(atScan, null, null), drain(it), "scan must not see later writes");
            }

            TreeMap<String, String> now = new TreeMap<>();
            for (int i = 20; i < 80; i++) {
                if ((i - 20) % 3 != 0) {
                    now.put(key(i), "new-" + i);
                }
            }
            now.put(key(50) + "x", "added");
            try (RangeIterator it = db.scan(b(key(20)), b(key(80)))) {
                assertEquals(expected(now, null, null), drain(it));
            }
        }
    }

    @Test
    void openScansKeepTablesAliveAndClosingReleasesThem() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false, LIMIT, TRIGGER)) {
            for (int i = 0; i < 300; i++) {
                db.put(b(key(i)), b("value-" + i));
            }
            List<String> before = sstNames();
            assertFalse(before.isEmpty());

            RangeIterator it = db.scan(null, null);
            for (int i = 0; i < 600; i++) {
                db.put(b("filler-" + i), b("x"));              // compactions retire the tables the scan holds
            }
            for (String name : before) {
                assertTrue(Files.exists(dir.resolve(name)), name + " was deleted while a scan was using it");
            }
            assertTrue(it.valid());
            it.close();
            for (String name : before) {
                assertFalse(Files.exists(dir.resolve(name)), name + " was not deleted after the scan closed");
            }
        }
    }
}