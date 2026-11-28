package lsmkv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import static lsmkv.MemTable.Kind.FOUND;
import static lsmkv.ValueType.PUT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SSTableTest {
    @TempDir
    Path dir;
    private int counter;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Path build(MemTable mem, int blockSize) throws IOException {
        Path p = dir.resolve("t" + (counter++) + ".sst");
        try (SSTableWriter w = new SSTableWriter(p, blockSize);
             KvIterator it = mem.iterator()) {
            it.seekToFirst();
            while (it.valid()) {
                w.add(it.key(), it.value());
                it.next();
            }
            w.finish();
        }
        return p;
    }

    private Path smallTable() throws IOException {
        MemTable mem = new MemTable();
        for (int i = 0; i < 5; i++) {
            mem.put(b("key" + i), b("value" + i), i + 1);
        }
        return build(mem, 16); // tiny blocks: roughly one entry each
    }

    private static MemTable randomMemTable(Random rnd, int ops) {
        MemTable mem = new MemTable();
        for (int seq = 1; seq <= ops; seq++) {
            byte[] key = {(byte) rnd.nextInt(256), (byte) rnd.nextInt(4)};
            if (rnd.nextInt(4) == 0) {
                mem.delete(key, seq);
            } else {
                mem.put(key, b("value-" + seq), seq);
            }
        }
        return mem;
    }

    @Test
    void randomTableAnswersLikeTheMemTable() throws IOException {
        Random rnd = new Random(21);
        int ops = 3_000;
        MemTable mem = randomMemTable(rnd, ops);
        try (SSTableReader t = SSTableReader.open(build(mem, 128))) {
            for (int q = 0; q < 5_000; q++) {
                byte[] key = {(byte) rnd.nextInt(256), (byte) rnd.nextInt(5)}; // second byte 4 was never written
                long snap = rnd.nextInt(ops + 2);
                MemTable.Lookup want = mem.get(key, snap);
                MemTable.Lookup got = t.get(key, snap);
                assertEquals(want.kind(), got.kind(), "key " + Arrays.toString(key) + " snap " + snap);
                if (want.kind() == FOUND) {
                    assertArrayEquals(want.value(), got.value());
                }
            }
        }
    }

    @Test
    void iteratorWalkAndSeekMatchTheMemTable() throws IOException {
        Random rnd = new Random(22);
        int ops = 3_000;
        MemTable mem = randomMemTable(rnd, ops);
        try (SSTableReader t = SSTableReader.open(build(mem, 128));
             KvIterator expected = mem.iterator();
             KvIterator actual = t.iterator()) {
            expected.seekToFirst();
            actual.seekToFirst();
            int n = 0;
            while (expected.valid()) {
                assertTrue(actual.valid(), "table ended early at entry " + n);
                assertEquals(0, InternalKey.ORDER.compare(expected.key(), actual.key()), "entry " + n);
                assertArrayEquals(expected.value(), actual.value());
                expected.next();
                actual.next();
                n++;
            }
            assertFalse(actual.valid());

            for (int q = 0; q < 2_000; q++) {
                InternalKey target = new InternalKey(
                        new byte[]{(byte) rnd.nextInt(256), (byte) rnd.nextInt(5)}, rnd.nextInt(ops + 2), PUT);
                expected.seek(target);
                actual.seek(target);
                assertEquals(expected.valid(), actual.valid());
                if (expected.valid()) {
                    assertEquals(0, InternalKey.ORDER.compare(expected.key(), actual.key()));
                }
            }
        }
    }

    @Test
    void emptyTableIsValid() throws IOException {
        try (SSTableReader t = SSTableReader.open(build(new MemTable(), 64));
             KvIterator it = t.iterator()) {
            assertEquals(MemTable.Kind.ABSENT, t.get(b("x"), 10).kind());
            it.seekToFirst();
            assertFalse(it.valid());
        }
    }

    @Test
    void writerRejectsOutOfOrderEntries() throws IOException {
        try (SSTableWriter w = new SSTableWriter(dir.resolve("x.sst"), 64)) {
            w.add(new InternalKey(b("b"), 1, PUT), b("x"));
            assertThrows(IllegalArgumentException.class, () -> w.add(new InternalKey(b("a"), 1, PUT), b("x")));
        }
    }

    @Test
    void corruptDataBlockIsDetectedOnRead() throws IOException {
        byte[] data = Files.readAllBytes(smallTable());
        data[3] ^= 1; // inside the first data block
        Path bad = dir.resolve("bad-data.sst");
        Files.write(bad, data);
        try (SSTableReader t = SSTableReader.open(bad)) {   // index and footer are intact
            assertThrows(IOException.class, () -> t.get(b("key0"), 100));
        }
    }

    @Test
    void corruptIndexIsDetectedOnOpen() throws IOException {
        byte[] data = Files.readAllBytes(smallTable());
        long indexOffset = ByteBuffer.wrap(data).getLong(data.length - SSTableReader.FOOTER_SIZE);
        data[(int) indexOffset + 1] ^= 1;
        Path bad = dir.resolve("bad-index.sst");
        Files.write(bad, data);
        assertThrows(IOException.class, () -> SSTableReader.open(bad));
    }

    @Test
    void corruptMagicIsRejected() throws IOException {
        byte[] data = Files.readAllBytes(smallTable());
        data[data.length - 1] ^= 1;
        Path bad = dir.resolve("bad-magic.sst");
        Files.write(bad, data);
        assertThrows(IOException.class, () -> SSTableReader.open(bad));
    }

    @Test
    void truncatedFilesAreRejected() throws IOException {
        byte[] data = Files.readAllBytes(smallTable());
        Path chopped = dir.resolve("chopped.sst");
        Files.write(chopped, Arrays.copyOf(data, data.length - 10));
        assertThrows(IOException.class, () -> SSTableReader.open(chopped));
        Path tiny = dir.resolve("tiny.sst");
        Files.write(tiny, Arrays.copyOf(data, 10));
        assertThrows(IOException.class, () -> SSTableReader.open(tiny));
    }
}