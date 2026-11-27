package lsmkv;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import static lsmkv.ValueType.DELETE;
import static lsmkv.ValueType.PUT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockTest {
    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static InternalKey k(String key, long seq, ValueType t) {
        return new InternalKey(b(key), seq, t);
    }

    private static BlockReader sample() {
        BlockBuilder bb = new BlockBuilder();
        bb.add(k("b", 5, PUT), b("v5"));
        bb.add(k("b", 2, DELETE), new byte[0]);
        bb.add(k("d", 1L << 55, PUT), b("big-seq"));
        return new BlockReader(bb.finish());
    }

    @Test
    void roundTripPreservesKeysValuesAndTypes() {
        BlockReader r = sample();
        assertEquals(3, r.count());
        assertEquals(5, r.keyAt(0).seq());
        assertEquals(PUT, r.keyAt(0).type());
        assertArrayEquals(b("v5"), r.valueAt(0));
        assertEquals(DELETE, r.keyAt(1).type());
        assertEquals(0, r.valueAt(1).length);
        assertEquals(1L << 55, r.keyAt(2).seq());
        assertArrayEquals(b("d"), r.keyAt(2).userKey());
    }

    @Test
    void seekFindsFirstEntryAtOrAfterTarget() {
        BlockReader r = sample();
        assertEquals(0, r.seek(k("a", 9, PUT)));    // before everything
        assertEquals(0, r.seek(k("b", 9, PUT)));    // newer than anything stored for "b"
        assertEquals(0, r.seek(k("b", 5, PUT)));    // exact match on the first entry
        assertEquals(1, r.seek(k("b", 4, PUT)));    // between the two "b" versions
        assertEquals(2, r.seek(k("c", 9, PUT)));    // between "b" and "d"
        assertEquals(3, r.seek(k("e", 9, PUT)));    // after everything
    }

    @Test
    void builderRejectsBadInput() {
        BlockBuilder bb = new BlockBuilder();
        bb.add(k("b", 5, PUT), b("x"));
        assertThrows(IllegalArgumentException.class, () -> bb.add(k("b", 5, PUT), b("x")));  // duplicate
        assertThrows(IllegalArgumentException.class, () -> bb.add(k("a", 1, PUT), b("x")));  // out of order
        assertThrows(IllegalArgumentException.class, () -> bb.add(k("z", 1L << 56, PUT), b("x"))); // seq too big
    }

    @Test
    void randomBlocksMatchLinearScan() {
        Random rnd = new Random(3);
        TreeMap<InternalKey, byte[]> sorted = new TreeMap<>(InternalKey.ORDER);
        for (int i = 0; i < 500; i++) {
            sorted.put(randomKey(rnd), b("v" + i));
        }
        BlockBuilder bb = new BlockBuilder();
        List<InternalKey> keys = new ArrayList<>();
        for (Map.Entry<InternalKey, byte[]> e : sorted.entrySet()) {
            bb.add(e.getKey(), e.getValue());
            keys.add(e.getKey());
        }
        BlockReader r = new BlockReader(bb.finish());
        assertEquals(keys.size(), r.count());
        for (int i = 0; i < keys.size(); i++) {
            assertEquals(0, InternalKey.ORDER.compare(keys.get(i), r.keyAt(i)));
        }
        for (int t = 0; t < 5_000; t++) {
            InternalKey target = randomKey(rnd);
            int expected = keys.size();
            for (int i = 0; i < keys.size(); i++) {
                if (InternalKey.ORDER.compare(keys.get(i), target) >= 0) {
                    expected = i;
                    break;
                }
            }
            assertEquals(expected, r.seek(target), "target seq=" + target.seq());
        }
    }

    private static InternalKey randomKey(Random rnd) {
        byte[] key = new byte[1 + rnd.nextInt(2)];
        rnd.nextBytes(key);
        return new InternalKey(key, rnd.nextInt(50), rnd.nextBoolean() ? PUT : DELETE);
    }
}