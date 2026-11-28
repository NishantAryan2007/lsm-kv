package lsmkv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.CRC32C;

/** Read-only, thread-safe view of a table written by SSTableWriter. */
public final class SSTableReader implements AutoCloseable {
    static final long MAGIC = 0x4C534D4B56303031L; // "LSMKV001"
    static final int FOOTER_SIZE = 40;

    private static final MemTable.Lookup ABSENT = new MemTable.Lookup(MemTable.Kind.ABSENT, null);
    private static final MemTable.Lookup DELETED = new MemTable.Lookup(MemTable.Kind.DELETED, null);

    private final FileChannel channel;
    private final long dataEnd;        // where data blocks stop (= index offset)
    private final BlockReader index;

    private SSTableReader(FileChannel channel, long dataEnd, BlockReader index) {
        this.channel = channel;
        this.dataEnd = dataEnd;
        this.index = index;
    }

    public static SSTableReader open(Path path) throws IOException {
        FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long size = ch.size();
            if (size < FOOTER_SIZE) {
                throw new IOException("not an sstable: file too short");
            }
            ByteBuffer footer = readFully(ch, size - FOOTER_SIZE, FOOTER_SIZE);
            long indexOffset = footer.getLong(0);
            long indexLen = footer.getLong(8);
            if (footer.getLong(32) != MAGIC) {
                throw new IOException("not an sstable: bad magic");
            }
            checkRange(indexOffset, indexLen, size - FOOTER_SIZE);
            BlockReader idx = readBlock(ch, indexOffset, (int) indexLen);
            return new SSTableReader(ch, indexOffset, idx);
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    /** Newest version of userKey with seq <= snapshotSeq, in this table only. */
    public MemTable.Lookup get(byte[] userKey, long snapshotSeq) throws IOException {
        InternalKey probe = new InternalKey(userKey, snapshotSeq, ValueType.PUT);
        int bi = index.seek(probe);                 // first block whose LAST key >= probe
        if (bi == index.count()) {
            return ABSENT;
        }
        BlockReader blk = loadDataBlock(bi);
        int i = blk.seek(probe);
        if (i == blk.count()) {
            return ABSENT;
        }
        InternalKey found = blk.keyAt(i);
        if (Bytes.compare(found.userKey(), userKey) != 0) {
            return ABSENT;
        }
        if (found.type() == ValueType.DELETE) {
            return DELETED;
        }
        return new MemTable.Lookup(MemTable.Kind.FOUND, blk.valueAt(i));
    }

    /** Valid only while this reader is open. I/O errors surface as UncheckedIOException. */
    public KvIterator iterator() {
        return new Cursor();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private BlockReader loadDataBlock(int blockIdx) throws IOException {
        ByteBuffer h = ByteBuffer.wrap(index.valueAt(blockIdx));
        long off = h.getLong(0);
        int len = h.getInt(8);
        checkRange(off, len, dataEnd);
        return readBlock(channel, off, len);
    }

    private static void checkRange(long offset, long len, long limit) throws IOException {
        if (offset < 0 || offset > limit || len < 4 || len > limit || offset + len + 4 > limit) {
            throw new IOException("corrupt block handle: offset=" + offset + " len=" + len);
        }
    }

    private static ByteBuffer readFully(FileChannel ch, long pos, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(len);
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos + buf.position());
            if (n < 0) {
                throw new IOException("unexpected end of file");
            }
        }
        return buf;
    }

    private static BlockReader readBlock(FileChannel ch, long offset, int len) throws IOException {
        ByteBuffer buf = readFully(ch, offset, len + 4);
        byte[] raw = buf.array();

        CRC32C crc = new CRC32C();
        crc.update(raw, 0, len);
        if ((int) crc.getValue() != buf.getInt(len)) {
            throw new IOException("block checksum mismatch at offset " + offset);
        }

        return new BlockReader(Arrays.copyOf(raw, len));
    }

    private final class Cursor implements KvIterator {
        private BlockReader block;   // null means not positioned / past the end
        private int blockIdx;
        private int pos;

        private void enter(int bi) {
            try {
                if (bi < index.count()) {
                    block = loadDataBlock(bi);
                    blockIdx = bi;
                    pos = 0;
                } else {
                    block = null;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void requireValid() {
            if (block == null) {
                throw new IllegalStateException("iterator is not positioned on an entry");
            }
        }

        @Override
        public boolean valid() {
            return block != null;
        }

        @Override
        public InternalKey key() {
            requireValid();
            return block.keyAt(pos);
        }

        @Override
        public byte[] value() {
            requireValid();
            return block.valueAt(pos);
        }

        @Override
        public void next() {
            requireValid();
            if (++pos >= block.count()) {
                enter(blockIdx + 1);
            }
        }

        @Override
        public void seekToFirst() {
            enter(0);
        }

        @Override
        public void seek(InternalKey target) {
            enter(index.seek(target));
            if (block != null) {
                pos = block.seek(target);
                if (pos >= block.count()) {
                    enter(blockIdx + 1);
                }
            }
        }

        @Override
        public void close() { }
    }
}