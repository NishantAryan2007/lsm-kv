package lsmkv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static lsmkv.ValueType.DELETE;
import static lsmkv.ValueType.PUT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WalTest {
    @TempDir
    Path dir;

    private record Op(long seq, ValueType type, String key, String value) {}

    private static final List<Op> OPS = List.of(
            new Op(1, PUT, "alpha", "one"),
            new Op(2, PUT, "beta", "two-two"),
            new Op(3, DELETE, "alpha", ""),
            new Op(4, PUT, "", "empty-key"),
            new Op(5, PUT, "gamma", ""));

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static long recordSize(Op op) {
        return 8 + 1 + 8 + 4 + b(op.key()).length + 4 + b(op.value()).length;
    }

    private Path writeAll() throws IOException {
        Path p = dir.resolve("test.wal");
        try (WalWriter w = new WalWriter(p, false)) {
            for (Op op : OPS) {
                w.append(op.seq(), op.type(), b(op.key()), b(op.value()));
            }
        }
        return p;
    }

    @Test
    void roundTrip() throws IOException {
        Path p = writeAll();
        List<WalRecord> got = new ArrayList<>();
        long valid = WalReader.replay(p, got::add);

        assertEquals(OPS.size(), got.size());
        for (int i = 0; i < OPS.size(); i++) {
            Op op = OPS.get(i);
            assertEquals(op.seq(), got.get(i).seq());
            assertEquals(op.type(), got.get(i).type());
            assertArrayEquals(b(op.key()), got.get(i).key());
            assertArrayEquals(b(op.value()), got.get(i).value());
        }
        assertEquals(Files.size(p), valid);
    }

    @Test
    void cutAtEveryByteKeepsExactlyTheCompleteRecords() throws IOException {
        byte[] full = Files.readAllBytes(writeAll());
        Path cutFile = dir.resolve("cut.wal");

        for (int cut = 0; cut <= full.length; cut++) {
            Files.write(cutFile, Arrays.copyOf(full, cut));
            List<WalRecord> got = new ArrayList<>();
            long valid = WalReader.replay(cutFile, got::add);

            int expectedCount = 0;
            long expectedValid = 0;
            for (Op op : OPS) {
                long end = expectedValid + recordSize(op);
                if (end > cut) {
                    break;
                }
                expectedCount++;
                expectedValid = end;
            }
            assertEquals(expectedCount, got.size(), "records, cut=" + cut);
            assertEquals(expectedValid, valid, "valid bytes, cut=" + cut);
        }
    }

    @Test
    void flippedBitStopsReplayAtThatRecord() throws IOException {
        byte[] data = Files.readAllBytes(writeAll());
        int secondRecordStart = (int) recordSize(OPS.get(0));
        data[secondRecordStart + 8 + 3] ^= 0x01; // one bit inside the second record's payload

        Path p = dir.resolve("flipped.wal");
        Files.write(p, data);
        List<WalRecord> got = new ArrayList<>();
        long valid = WalReader.replay(p, got::add);

        assertEquals(1, got.size());
        assertEquals(secondRecordStart, valid);
    }
}
