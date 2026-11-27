package lsmkv;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Read-only view of a block produced by BlockBuilder. */
public final class BlockReader {
    private final byte[] data;
    private final ByteBuffer buf;
    private final int count;
    private final int offsetsStart;

    public BlockReader(byte[] block) {
        if (block.length < 4) {
            throw new IllegalArgumentException("corrupt block: too short");
        }
        this.data = block;
        this.buf = ByteBuffer.wrap(block);
        this.count = buf.getInt(block.length - 4);
        this.offsetsStart = block.length - 4 - 4 * count;
        if (count < 0 || offsetsStart < 0) {
            throw new IllegalArgumentException("corrupt block: bad entry count");
        }
    }

    public int count() {
        return count;
    }

    public InternalKey keyAt(int i) {
        int off = buf.getInt(offsetsStart + 4 * i);
        int keyLen = buf.getInt(off);
        byte[] userKey = Arrays.copyOfRange(data, off + 4, off + 4 + keyLen);
        long trailer = buf.getLong(off + 4 + keyLen);
        ValueType type = (trailer & 0xFF) == 1 ? ValueType.PUT : ValueType.DELETE;
        return new InternalKey(userKey, trailer >>> 8, type);
    }

    public byte[] valueAt(int i) {
        int off = buf.getInt(offsetsStart + 4 * i);
        int keyLen = buf.getInt(off);
        int valPos = off + 4 + keyLen + 8;
        int valLen = buf.getInt(valPos);
        return Arrays.copyOfRange(data, valPos + 4, valPos + 4 + valLen);
    }

    /**
     * Index of the first entry whose key is >= target in InternalKey.ORDER, or count()
     * if every entry is smaller.
     */
    public int seek(InternalKey target) {
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (InternalKey.ORDER.compare(keyAt(mid), target) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}