package lsmkv;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashRecoveryTest {
    private static final long LIMIT = 1024;
    private static final int TRIGGER = 3;
    private static final int KEYS = 40;

    @TempDir
    Path dir;

    @AfterEach
    void clearHook() {
        CrashPoints.reset();
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(Optional<byte[]> o) {
        return o.map(x -> new String(x, StandardCharsets.UTF_8)).orElse(null);
    }

    static Stream<Arguments> cases() {
        List<String> points = List.of(
                "flush:sst-written", "flush:wal-created", "flush:committed", "flush:before-old-wal-delete",
                "manifest:tmp-written", "manifest:renamed",
                "compact:output-written", "compact:committed", "compact:before-retire");
        List<Arguments> out = new ArrayList<>();
        for (String p : points) {
            for (int n = 1; n <= 4; n++) {
                out.add(Arguments.of(p, n));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "crash at {0}, occurrence {1}")
    @MethodSource("cases")
    void recoversFromACrashAtEveryStep(String point, int occurrence) throws IOException {
        Path d = dir.resolve(point.replace(':', '-') + "-" + occurrence);
        Map<String, String> expected = new HashMap<>();
        Random rnd = new Random(point.hashCode() + occurrence);

        LsmEngine db = LsmEngine.open(d, false, LIMIT, TRIGGER);
        AtomicInteger hits = new AtomicInteger();
        CrashPoints.set(p -> {
            if (p.equals(point) && hits.incrementAndGet() == occurrence) {
                throw new CrashPoints.SimulatedCrash(p);
            }
        });

        boolean crashed = false;
        for (int i = 0; i < 3_000 && !crashed; i++) {
            String k = "k" + rnd.nextInt(KEYS);
            boolean delete = rnd.nextInt(4) == 0;
            try {
                if (delete) {
                    db.delete(b(k));
                } else {
                    db.put(b(k), b("v" + i));
                }
            } catch (CrashPoints.SimulatedCrash e) {
                crashed = true;   // the write was logged before the crash point, so it must survive
            }
            if (delete) {
                expected.remove(k);
            } else {
                expected.put(k, "v" + i);
            }
        }
        CrashPoints.reset();
        assertTrue(crashed, "crash point never reached: " + point + " #" + occurrence);
        try {
            db.close();   // only releases file handles; writes nothing
        } catch (IOException ignored) {
            // the "process" is dead anyway
        }

        for (int round = 0; round < 2; round++) {
            try (LsmEngine re = LsmEngine.open(d, false, LIMIT, TRIGGER)) {
                for (int n = 0; n < KEYS; n++) {
                    assertEquals(expected.get("k" + n), str(re.get(b("k" + n))),
                            "round " + round + ", key k" + n + " after crash at " + point + " #" + occurrence);
                }
                if (round == 0) {
                    assertNoOrphans(d);
                    re.put(b("after"), b("crash"));          // the recovered engine must accept writes
                } else {
                    assertEquals("crash", str(re.get(b("after"))));
                }
            }
        }
    }

    private static void assertNoOrphans(Path d) throws IOException {
        Manifest.State state = Manifest.read(d);
        try (Stream<Path> files = Files.list(d)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(state.tables().size(), names.stream().filter(n -> n.endsWith(".sst")).count(),
                    "orphan tables left behind: " + names);
            assertEquals(1, names.stream().filter(n -> n.endsWith(".wal")).count(),
                    "orphan logs left behind: " + names);
            assertFalse(names.contains("MANIFEST.tmp"), "temporary manifest left behind");
        }
    }
}