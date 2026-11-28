package lsmkv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LsmEngineTest {
    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(Optional<byte[]> o) {
        return o.map(x -> new String(x, StandardCharsets.UTF_8)).orElse(null);
    }

    @Test
    void putOverwriteDelete() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false)) {
            assertNull(str(db.get(b("k"))));
            db.put(b("k"), b("v1"));
            assertEquals("v1", str(db.get(b("k"))));
            db.put(b("k"), b("v2"));
            assertEquals("v2", str(db.get(b("k"))));
            db.delete(b("k"));
            assertNull(str(db.get(b("k"))));
        }
    }

    @Test
    void dataSurvivesRestart() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false)) {
            db.put(b("a"), b("1"));
            db.put(b("b"), b("2"));
            db.delete(b("a"));
        }
        try (LsmEngine db = LsmEngine.open(dir, false)) {
            assertNull(str(db.get(b("a"))));
            assertEquals("2", str(db.get(b("b"))));
        }
    }

    @Test
    void tornTailIsDroppedAndTruncated() throws IOException {
        try (LsmEngine db = LsmEngine.open(dir, false)) {
            db.put(b("k1"), b("v1"));
            db.put(b("k2"), b("v2"));
        }
        Path wal = dir.resolve("000001.wal");
        try (FileChannel ch = FileChannel.open(wal, StandardOpenOption.WRITE)) {
            ch.truncate(Files.size(wal) - 3); // simulate a crash mid-record
        }
        try (LsmEngine db = LsmEngine.open(dir, false)) {
            assertEquals("v1", str(db.get(b("k1"))));
            assertNull(str(db.get(b("k2"))));
            db.put(b("k3"), b("v3"));
        }
        try (LsmEngine db = LsmEngine.open(dir, false)) {
            assertEquals("v1", str(db.get(b("k1"))));
            assertNull(str(db.get(b("k2"))));
            assertEquals("v3", str(db.get(b("k3")))); // fails if the garbage tail was not truncated
        }
    }

    @Test
    void randomOperationsWithRestartsMatchModel() throws IOException {
        Random rnd = new Random(5);
        Map<String, String> model = new HashMap<>();
        LsmEngine db = LsmEngine.open(dir, false);
        try {
            for (int i = 0; i < 5_000; i++) {
                String key = "k" + rnd.nextInt(20);
                int op = rnd.nextInt(10);
                if (op < 4) {
                    String v = "v" + i;
                    db.put(b(key), b(v));
                    model.put(key, v);
                } else if (op < 6) {
                    db.delete(b(key));
                    model.remove(key);
                } else if (op == 6 && rnd.nextInt(30) == 0) {
                    db.close();                       // simulated restart
                    db = LsmEngine.open(dir, false);
                } else {
                    assertEquals(model.get(key), str(db.get(b(key))), "key " + key + " at op " + i);
                }
            }
            db.close();
            db = LsmEngine.open(dir, false);
            for (int k = 0; k < 20; k++) {
                assertEquals(model.get("k" + k), str(db.get(b("k" + k))), "final check k" + k);
            }
        } finally {
            db.close();
        }
    }
}