package lsmkv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

public final class WalReader {
    private WalReader() {}

    private static final int HEADER = 8;
    private static final int MIN_PAYLOAD = 1 + 8 + 4 + 4; // type, seq, keyLen, valLen

    /**
     * Replays records in order until the end of the file or the first torn/corrupt record.
     * Returns the number of valid bytes, so the caller can truncate anything after them.
     * (The log is bounded by the MemTable size, so reading it whole is fine.)
     */
    public static long replay(Path path, Consumer<WalRecord> sink) throws IOException {
        byte[] data = Files.readAllBytes(path);
        ByteBuffer buf = ByteBuffer.wrap(data);
        int pos = 0;

        while (data.length - pos >= HEADER) {
            int storedCrc = buf.getInt(pos);
            int len = buf.getInt(pos + 4);

            if (len < MIN_PAYLOAD || len > data.length - pos - HEADER) {
                break;
            }

            CRC32C crc = new CRC32C();
            crc.update(data, pos + HEADER, len);

            if ((int) crc.getValue() != storedCrc) {
                break;
            }

            int p = pos + HEADER;
            byte t = data[p];
            p += 1;
            long seq = buf.getLong(p);
            p += 8;
            int keyLen = buf.getInt(p);
            p += 4;
            byte[] key = Arrays.copyOfRange(data, p, p + keyLen);
            p += keyLen;
            int valLen = buf.getInt(p);
            p += 4;
            byte[] value = Arrays.copyOfRange(data, p, p + valLen);

            sink.accept(new WalRecord(seq, t == 1 ? ValueType.PUT : ValueType.DELETE, key, value));
            pos += HEADER + len;
        }
        return pos;
    }
}