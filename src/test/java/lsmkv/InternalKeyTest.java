package lsmkv;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static lsmkv.ValueType.DELETE;
import static lsmkv.ValueType.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalKeyTest {
    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static InternalKey k(String key, long seq, ValueType type) {
        return new InternalKey(b(key), seq, type);
    }

    @Test
    void sameKey_newerSeqSortsFirst() {
        assertTrue(InternalKey.ORDER.compare(k("k", 2, PUT), k("k", 1, PUT)) < 0);
    }

    @Test
    void differentKeys_orderedByKeyNotSeq() {
        assertTrue(InternalKey.ORDER.compare(k("a", 1, PUT), k("b", 99, PUT)) < 0);
    }

    @Test
    void sortingTheThreeEntries() {
        List<InternalKey> list = new ArrayList<>(List.of(
                k("k", 1, PUT), k("k", 3, DELETE), k("k", 2, PUT)));
        list.sort(InternalKey.ORDER);
        assertEquals(3, list.get(0).seq());
        assertEquals(2, list.get(1).seq());
        assertEquals(1, list.get(2).seq());
    }

    @Test
    void userKeysAreComparedAsUnsigned() {
        InternalKey low = new InternalKey(new byte[]{1}, 1, PUT);
        InternalKey high = new InternalKey(new byte[]{(byte) 0xFF}, 1, PUT);
        assertTrue(InternalKey.ORDER.compare(low, high) < 0);
    }

    @Test
    void rejectsNegativeSeq() {
        assertThrows(IllegalArgumentException.class, () -> k("k", -1, PUT));
    }
}