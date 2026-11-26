package lsmkv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.CRC32C;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Appends records to the log. Not thread-safe: the engine's single writer owns it.
 * Record layout: [crc32c:4][len:4][type:1][seq:8][keyLen:4][key][valLen:4][value], big-endian.
 * The crc covers everything after the first 8 bytes.
 */
public final class WalWriter implements AutoCloseable {
    private final FileChannel channel;
    private final boolean syncOnWrite;

    public WalWriter(Path path, boolean syncOnWrite) throws IOException {
        this.channel = FileChannel.open(path, CREATE, WRITE, APPEND);
        this.syncOnWrite = syncOnWrite;
    }

    public void append(long seq, ValueType type, byte[] key, byte[] value) throws IOException {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        int payloadLen = 1 + 8 + 4 + key.length + 4 + value.length;
        ByteBuffer buf = ByteBuffer.allocate(8 + payloadLen);
        buf.position(8); // leave room for crc + length, filled in below
        buf.put((byte) (type == ValueType.PUT ? 1 : 0));
        buf.putLong(seq);
        buf.putInt(key.length);
        buf.put(key);
        buf.putInt(value.length);
        buf.put(value);

        CRC32C crc = new CRC32C();
        crc.update(buf.array(), 8, payloadLen);
        buf.putInt(0, (int) crc.getValue());
        buf.putInt(4, payloadLen);

        buf.flip();
        while (buf.hasRemaining()) {   // a single write() may be partial
            channel.write(buf);
        }
        if (syncOnWrite) {
            channel.force(false);
        }
    }

    /** Flushes appended data to disk (use when syncOnWrite is false). */
    public void sync() throws IOException {
        channel.force(false);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}