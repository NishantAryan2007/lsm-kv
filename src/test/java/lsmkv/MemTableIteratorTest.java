package lsmkv;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import static lsmkv.ValueType.DELETE;
import static lsmkv.ValueType.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemTableIteratorTest {
    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static MemTable sample() {
        MemTable mt = new MemTable();
        mt.put(b("b"), b("1"), 1);
        mt.put(b("a"), b("2"), 2);
        mt.delete(b("a"), 3);
        mt.put(b("c"), b("3"), 4);
        return mt;
    }

    private static void assertAt(KvIterator it, String key, long seq, ValueType type) {
        assertTrue(it.valid());
        assertEquals(key, new String(it.key().userKey(), StandardCharsets.UTF_8));
        assertEquals(seq, it.key().seq());
        assertEquals(type, it.key().type());
    }

    @Test
    void startsUnpositioned_andEmptyTableStaysInvalid() {
        try (KvIterator it = new MemTable().iterator()) {
            assertFalse(it.valid());
            it.seekToFirst();
            assertFalse(it.valid());
            assertThrows(IllegalStateException.class, it::key);
        }
    }

    @Test
    void walksEntriesInInternalKeyOrder() {
        try (KvIterator it = sample().iterator()) {
            it.seekToFirst();
            assertAt(it, "a", 3, DELETE);
            it.next();
            assertAt(it, "a", 2, PUT);
            it.next();
            assertAt(it, "b", 1, PUT);
            it.next();
            assertAt(it, "c", 4, PUT);
            it.next();
            assertFalse(it.valid());
        }
    }

    @Test
    void seekLandsOnFirstEntryAtOrAfterTarget() {
        try (KvIterator it = sample().iterator()) {
            it.seek(new InternalKey(b("a"), 2, PUT));   // exact match
            assertAt(it, "a", 2, PUT);
            it.seek(new InternalKey(b("a"), 9, PUT));   // newer than anything stored for "a"
            assertAt(it, "a", 3, DELETE);
            it.seek(new InternalKey(b("bb"), 9, PUT));  // between "b" and "c"
            assertAt(it, "c", 4, PUT);
            it.seek(new InternalKey(b("d"), 9, PUT));   // past the end
            assertFalse(it.valid());
        }
    }

    @Test
    void randomTableComesOutStrictlySortedWithNothingLost() {
        Random rnd = new Random(11);
        MemTable mt = new MemTable();
        int count = 5_000;
        for (int seq = 1; seq <= count; seq++) {
            byte[] key = {(byte) rnd.nextInt(256)};
            if (rnd.nextInt(4) == 0) {
                mt.delete(key, seq);
            } else {
                mt.put(key, b("v" + seq), seq);
            }
        }
        try (KvIterator it = mt.iterator()) {
            it.seekToFirst();
            InternalKey prev = null;
            int seen = 0;
            while (it.valid()) {
                if (prev != null) {
                    assertTrue(InternalKey.ORDER.compare(prev, it.key()) < 0, "out of order at entry " + seen);
                }
                prev = it.key();
                seen++;
                it.next();
            }
            assertEquals(count, seen);
        }
    }
}
