package lsmkv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32C;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Writes an immutable sorted table. Entries must arrive in strictly increasing InternalKey.ORDER.
 * Layout: [block][crc] ... [filter][crc] [index block][crc] [footer 40 bytes].
 * Call finish() once; the file is not valid until then. Not thread-safe.
 */
public final class SSTableWriter implements AutoCloseable {
    private static final int DEFAULT_BITS_PER_KEY = 10;

    private final FileChannel channel;
    private final int blockSize;
    private final int bitsPerKey;
    private final BlockBuilder index = new BlockBuilder();
    private BlockBuilder block = new BlockBuilder();
    private InternalKey lastKey;
    private byte[] lastUserKey;
    private long[] keyHashes = new long[1024];
    private int hashCount;
    private long offset;
    private boolean finished;

    public SSTableWriter(Path path, int blockSize) throws IOException {
        this(path, blockSize, DEFAULT_BITS_PER_KEY);
    }

    public SSTableWriter(Path path, int blockSize, int bitsPerKey) throws IOException {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be > 0");
        }
        if (bitsPerKey <= 0) {
            throw new IllegalArgumentException("bitsPerKey must be > 0");
        }
        this.channel = FileChannel.open(path, CREATE_NEW, WRITE);
        this.blockSize = blockSize;
        this.bitsPerKey = bitsPerKey;
    }

    public void add(InternalKey key, byte[] value) throws IOException {
        if (finished) {
            throw new IllegalStateException("already finished");
        }
        if (lastKey != null && InternalKey.ORDER.compare(lastKey, key) >= 0) {
            throw new IllegalArgumentException("keys must be added in strictly increasing order");
        }
        block.add(key, value);
        lastKey = key;

        // every entry's user key goes in the filter, tombstones included; versions of one key are adjacent
        if (lastUserKey == null || !Arrays.equals(lastUserKey, key.userKey())) {
            if (hashCount == keyHashes.length) {
                keyHashes = Arrays.copyOf(keyHashes, hashCount * 2);
            }
            keyHashes[hashCount++] = BloomFilter.hash(key.userKey());
            lastUserKey = key.userKey();
        }

        if (block.estimatedSize() >= blockSize) {
            flushBlock();
        }
    }

    public void finish() throws IOException {
        if (finished) {
            throw new IllegalStateException("already finished");
        }
        if (!block.isEmpty()) {
            flushBlock();
        }

        long filterOffset = 0;
        long filterLen = 0;
        if (hashCount > 0) {
            BloomFilter filter = new BloomFilter(hashCount, bitsPerKey);
            for (int i = 0; i < hashCount; i++) {
                filter.addHash(keyHashes[i]);
            }
            byte[] filterBytes = filter.toBytes();
            filterOffset = writeBlock(filterBytes);
            filterLen = filterBytes.length;
        }

        byte[] indexData = index.finish();
        long indexOffset = writeBlock(indexData);

        ByteBuffer footer = ByteBuffer.allocate(SSTableReader.FOOTER_SIZE);
        footer.putLong(indexOffset);
        footer.putLong(indexData.length);
        footer.putLong(filterOffset);
        footer.putLong(filterLen);
        footer.putLong(SSTableReader.MAGIC);
        footer.flip();
        writeFully(footer);

        channel.force(true);
        finished = true;
    }

    private void flushBlock() throws IOException {
        byte[] data = block.finish();
        long blockOffset = writeBlock(data);
        // index entry: last key of this block -> (offset, length)
        index.add(lastKey, ByteBuffer.allocate(12).putLong(blockOffset).putInt(data.length).array());
        block = new BlockBuilder();
    }

    /** Writes data followed by its CRC32C; returns the offset where data started. */
    private long writeBlock(byte[] data) throws IOException {
        long start = offset;
        ByteBuffer buf = ByteBuffer.allocate(data.length + 4);
        buf.put(data);
        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length);
        buf.putInt((int) crc.getValue());
        buf.flip();
        writeFully(buf);
        return start;
    }

    private void writeFully(ByteBuffer buf) throws IOException {
        int n = buf.remaining();
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        offset += n;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}