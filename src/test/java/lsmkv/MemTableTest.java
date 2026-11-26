package lsmkv;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import static lsmkv.MemTable.Kind.ABSENT;
import static lsmkv.MemTable.Kind.DELETED;
import static lsmkv.MemTable.Kind.FOUND;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemTableTest {
    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] x) {
        return new String(x, StandardCharsets.UTF_8);
    }

    @Test
    void threeVersionsOfOneKey() {
        MemTable mt = new MemTable();
        mt.put(b("k"), b("a"), 1);
        mt.put(b("k"), b("b"), 2);
        mt.delete(b("k"), 3);

        assertEquals(ABSENT, mt.get(b("k"), 0).kind());
        assertEquals("a", str(mt.get(b("k"), 1).value()));
        assertEquals("b", str(mt.get(b("k"), 2).value()));
        assertEquals(DELETED, mt.get(b("k"), 3).kind());
        assertEquals(DELETED, mt.get(b("k"), 5).kind());
    }

    @Test
    void doesNotLeakFromNeighbouringKeys() {
        MemTable mt = new MemTable();
        mt.put(b("c"), b("x"), 1);
        assertEquals(ABSENT, mt.get(b("b"), 5).kind()); // "c" sorts right after "b"
        assertEquals(ABSENT, mt.get(b("d"), 5).kind()); // nothing sorts after "d"
    }

    @Test
    void copiesInputsOnWrite() {
        MemTable mt = new MemTable();
        byte[] key = b("k");
        byte[] value = b("v");
        mt.put(key, value, 1);
        key[0] = 'z';
        value[0] = 'z';
        assertArrayEquals(b("v"), mt.get(b("k"), 1).value());
    }

    @Test
    void byteAccountingIsEstimatedFromKeyAndValueSizes() {
        MemTable mt = new MemTable();
        assertEquals(0, mt.approximateBytes());
        mt.put(b("k"), b("abc"), 1);      // 1 + 3 + 16
        assertEquals(20, mt.approximateBytes());
        mt.delete(b("k"), 2);             // 1 + 0 + 16
        assertEquals(37, mt.approximateBytes());
    }

    @Test
    void duplicateSequenceNumberIsRejected() {
        MemTable mt = new MemTable();
        mt.put(b("k"), b("a"), 1);
        assertThrows(IllegalStateException.class, () -> mt.put(b("k"), b("b"), 1));
    }

    @Test
    void randomOperationsMatchSimpleModel() {
        Random rnd = new Random(7);
        MemTable mt = new MemTable();
        // model: key -> (seq -> value), where a null value means "deleted at that seq"
        Map<String, TreeMap<Long, String>> model = new HashMap<>();
        long seq = 0;

        for (int i = 0; i < 20_000; i++) {
            byte[] key = {(byte) (rnd.nextInt(6) * 50)}; // 0,50,...,250: includes bytes above 127
            String hex = HexFormat.of().formatHex(key);
            int op = rnd.nextInt(10);

            if (op < 4) {
                seq++;
                String v = "v" + seq;
                mt.put(key, b(v), seq);
                model.computeIfAbsent(hex, x -> new TreeMap<>()).put(seq, v);
            } else if (op < 6) {
                seq++;
                mt.delete(key, seq);
                model.computeIfAbsent(hex, x -> new TreeMap<>()).put(seq, null);
            } else {
                long snap = rnd.nextInt((int) seq + 1);
                Map.Entry<Long, String> expected =
                        model.getOrDefault(hex, new TreeMap<>()).floorEntry(snap);
                MemTable.Lookup got = mt.get(key, snap);
                if (expected == null) {
                    assertEquals(ABSENT, got.kind());
                } else if (expected.getValue() == null) {
                    assertEquals(DELETED, got.kind());
                } else {
                    assertEquals(FOUND, got.kind());
                    assertEquals(expected.getValue(), str(got.value()));
                }
            }
        }
    }
}