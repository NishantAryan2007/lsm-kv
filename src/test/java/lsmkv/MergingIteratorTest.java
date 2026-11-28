package lsmkv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static lsmkv.ValueType.PUT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergingIteratorTest {
    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Each operation goes to one random part and to the reference table. */
    private static void fill(Random rnd, MemTable reference, MemTable[] parts, int ops) {
        for (int seq = 1; seq <= ops; seq++) {
            byte[] key = {(byte) rnd.nextInt(256), (byte) rnd.nextInt(3)};
            MemTable target = parts[rnd.nextInt(parts.length)];
            if (rnd.nextInt(4) == 0) {
                target.delete(key, seq);
                reference.delete(key, seq);
            } else {
                byte[] v = b("v" + seq);
                target.put(key, v, seq);
                reference.put(key, v, seq);
            }
        }
    }

    private static MemTable[] newParts(int n) {
        MemTable[] parts = new MemTable[n];
        for (int i = 0; i < n; i++) {
            parts[i] = new MemTable();
        }
        return parts;
    }

    private static void assertSame(KvIterator expected, KvIterator actual) {
        int n = 0;
        while (expected.valid()) {
            assertTrue(actual.valid(), "merged ended early at entry " + n);
            assertEquals(0, InternalKey.ORDER.compare(expected.key(), actual.key()), "entry " + n);
            assertArrayEquals(expected.value(), actual.value());
            expected.next();
            actual.next();
            n++;
        }
        assertFalse(actual.valid(), "merged has extra entries");
    }

    @Test
    void mergeOfManySourcesEqualsTheReference() {
        MemTable reference = new MemTable();
        MemTable[] parts = newParts(5);
        fill(new Random(31), reference, parts, 4_000);
        List<KvIterator> kids = new ArrayList<>();
        for (MemTable p : parts) {
            kids.add(p.iterator());
        }
        try (KvIterator merged = new MergingIterator(kids);
             KvIterator expected = reference.iterator()) {
            merged.seekToFirst();
            expected.seekToFirst();
            assertSame(expected, merged);
        }
    }

    @Test
    void seekMatchesTheReference() {
        Random rnd = new Random(32);
        MemTable reference = new MemTable();
        MemTable[] parts = newParts(4);
        fill(rnd, reference, parts, 4_000);
        List<KvIterator> kids = new ArrayList<>();
        for (MemTable p : parts) {
            kids.add(p.iterator());
        }
        try (KvIterator merged = new MergingIterator(kids);
             KvIterator expected = reference.iterator()) {
            for (int q = 0; q < 200; q++) {
                InternalKey target = new InternalKey(
                        new byte[]{(byte) rnd.nextInt(256), (byte) rnd.nextInt(4)}, rnd.nextInt(4_002), PUT);
                merged.seek(target);
                expected.seek(target);
                assertEquals(expected.valid(), merged.valid());
                assertSame(expected, merged);   // the whole remainder must match too
            }
        }
    }

    @Test
    void emptyAndNearlyEmptySources() {
        try (KvIterator none = new MergingIterator(List.of())) {
            none.seekToFirst();
            assertFalse(none.valid());
            assertThrows(IllegalStateException.class, none::key);
        }
        MemTable empty = new MemTable();
        MemTable one = new MemTable();
        one.put(b("a"), b("1"), 1);
        try (KvIterator m = new MergingIterator(List.of(empty.iterator(), one.iterator()))) {
            m.seekToFirst();
            assertTrue(m.valid());
            m.next();
            assertFalse(m.valid());
        }
    }

    @Test
    void mergesAnSSTableWithAMemTable() throws IOException {
        MemTable reference = new MemTable();
        MemTable[] parts = newParts(2);
        fill(new Random(33), reference, parts, 3_000);

        Path file = dir.resolve("part.sst");
        try (SSTableWriter w = new SSTableWriter(file, 256);
             KvIterator it = parts[0].iterator()) {
            it.seekToFirst();
            while (it.valid()) {
                w.add(it.key(), it.value());
                it.next();
            }
            w.finish();
        }
        try (SSTableReader table = SSTableReader.open(file);
             KvIterator merged = new MergingIterator(List.of(table.iterator(), parts[1].iterator()));
             KvIterator expected = reference.iterator()) {
            merged.seekToFirst();
            expected.seekToFirst();
            assertSame(expected, merged);
        }
    }
}