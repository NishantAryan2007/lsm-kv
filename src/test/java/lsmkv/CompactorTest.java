package lsmkv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static lsmkv.MemTable.Kind.ABSENT;
import static lsmkv.MemTable.Kind.DELETED;
import static lsmkv.MemTable.Kind.FOUND;
import static lsmkv.ValueType.DELETE;
import static lsmkv.ValueType.PUT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactorTest {
    @TempDir
    Path dir;
    private int counter;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static MemTable[] newParts(int n) {
        MemTable[] parts = new MemTable[n];
        for (int i = 0; i < n; i++) {
            parts[i] = new MemTable();
        }
        return parts;
    }

    /** Each operation goes to one random part and to the reference table. */
    private static void fill(Random rnd, MemTable reference, MemTable[] parts, int ops) {
        for (int seq = 1; seq <= ops; seq++) {
            byte[] key = {(byte) rnd.nextInt(256), (byte) rnd.nextInt(3)};
            MemTable target = parts[rnd.nextInt(parts.length)];
            if (rnd.nextInt(3) == 0) {
                target.delete(key, seq);
                reference.delete(key, seq);
            } else {
                byte[] v = b("v" + seq);
                target.put(key, v, seq);
                reference.put(key, v, seq);
            }
        }
    }

    private Path compactToFile(MemTable[] parts, boolean dropTombstones) throws IOException {
        List<KvIterator> kids = new ArrayList<>();
        for (MemTable p : parts) {
            kids.add(p.iterator());
        }
        Path out = dir.resolve("out" + (counter++) + ".sst");
        try (KvIterator merged = new MergingIterator(kids);
             SSTableWriter w = new SSTableWriter(out, 256)) {
            Compactor.run(merged, w, dropTombstones);
            w.finish();
        }
        return out;
    }

    /** Returns {distinct user keys, keys whose newest version is a PUT}. */
    private static int[] countKeys(MemTable ref) {
        int distinct = 0;
        int live = 0;
        byte[] prev = null;
        try (KvIterator it = ref.iterator()) {
            it.seekToFirst();
            while (it.valid()) {
                byte[] k = it.key().userKey();
                if (prev == null || Bytes.compare(prev, k) != 0) {
                    distinct++;
                    if (it.key().type() == PUT) {
                        live++;
                    }
                    prev = k;
                }
                it.next();
            }
        }
        return new int[]{distinct, live};
    }

    @Test
    void keepsExactlyTheNewestVersionOfEveryKey() throws IOException {
        MemTable ref = new MemTable();
        MemTable[] parts = newParts(4);
        fill(new Random(41), ref, parts, 4_000);

        try (SSTableReader t = SSTableReader.open(compactToFile(parts, false));
             KvIterator it = t.iterator()) {
            it.seekToFirst();
            byte[] prev = null;
            int entries = 0;
            while (it.valid()) {
                InternalKey k = it.key();
                if (prev != null) {
                    assertTrue(Bytes.compare(prev, k.userKey()) < 0, "two versions of one key survived");
                }
                prev = k.userKey();
                MemTable.Lookup want = ref.get(k.userKey(), Long.MAX_VALUE);
                if (k.type() == DELETE) {
                    assertEquals(DELETED, want.kind());
                } else {
                    assertEquals(FOUND, want.kind());
                    assertArrayEquals(want.value(), it.value());
                }
                entries++;
                it.next();
            }
            assertEquals(countKeys(ref)[0], entries);
        }
    }

    @Test
    void bottomMostCompactionDropsTombstonesAndKeepsLiveKeys() throws IOException {
        MemTable ref = new MemTable();
        MemTable[] parts = newParts(4);
        fill(new Random(42), ref, parts, 4_000);

        try (SSTableReader t = SSTableReader.open(compactToFile(parts, true));
             KvIterator it = t.iterator()) {
            it.seekToFirst();
            int entries = 0;
            while (it.valid()) {
                assertEquals(PUT, it.key().type(), "a tombstone survived a bottom-most compaction");
                MemTable.Lookup want = ref.get(it.key().userKey(), Long.MAX_VALUE);
                assertEquals(FOUND, want.kind());
                assertArrayEquals(want.value(), it.value());
                entries++;
                it.next();
            }
            assertEquals(countKeys(ref)[1], entries);
        }
    }

    @Test
    void tombstoneIsKeptWhenOlderDataExistsBelow() throws IOException {
        MemTable older = new MemTable();
        older.put(b("k"), b("old"), 1);
        older.put(b("z"), b("z1"), 2);
        MemTable newer = new MemTable();
        newer.put(b("k"), b("x"), 7);
        newer.delete(b("k"), 9);

        try (SSTableReader below = SSTableReader.open(compactToFile(new MemTable[]{older}, false));
             SSTableReader top = SSTableReader.open(compactToFile(new MemTable[]{newer}, false))) {
            MemTable.Lookup r = top.get(b("k"), 100);          // newest table first
            if (r.kind() == ABSENT) {
                r = below.get(b("k"), 100);
            }
            assertEquals(DELETED, r.kind());

            try (KvIterator it = top.iterator()) {              // only the tombstone, not the shadowed put
                it.seekToFirst();
                assertEquals(9, it.key().seq());
                assertEquals(DELETE, it.key().type());
                it.next();
                assertFalse(it.valid());
            }
        }
    }

    @Test
    void droppingATombstoneTooEarlyResurrectsTheKey() throws IOException {
        // Documents the bug the rule prevents: same data as above, but the tombstone is wrongly dropped.
        MemTable older = new MemTable();
        older.put(b("k"), b("old"), 1);
        MemTable newer = new MemTable();
        newer.delete(b("k"), 9);

        try (SSTableReader below = SSTableReader.open(compactToFile(new MemTable[]{older}, false));
             SSTableReader top = SSTableReader.open(compactToFile(new MemTable[]{newer}, true))) {
            MemTable.Lookup r = top.get(b("k"), 100);
            if (r.kind() == ABSENT) {
                r = below.get(b("k"), 100);
            }
            assertEquals(FOUND, r.kind());                      // the deleted key came back from the dead
            assertArrayEquals(b("old"), r.value());
        }
    }

    @Test
    void emptyInputsProduceEmptyTables() throws IOException {
        try (SSTableReader t = SSTableReader.open(compactToFile(newParts(1), false));
             KvIterator it = t.iterator()) {
            it.seekToFirst();
            assertFalse(it.valid());
        }
        MemTable onlyDeletes = new MemTable();
        onlyDeletes.delete(b("a"), 1);
        onlyDeletes.delete(b("b"), 2);
        try (SSTableReader t = SSTableReader.open(compactToFile(new MemTable[]{onlyDeletes}, true));
             KvIterator it = t.iterator()) {
            it.seekToFirst();
            assertFalse(it.valid());
        }
    }
}