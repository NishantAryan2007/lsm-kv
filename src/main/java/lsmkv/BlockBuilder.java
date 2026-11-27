package lsmkv;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds one data block. Entries must be added in strictly increasing InternalKey.ORDER.
 * Layout: entries..., then int offset per entry, then int entry count (big-endian).
 * Entry: [keyLen:4][userKey][trailer:8 = seq<<8 | type][valLen:4][value]
 */
public final class BlockBuilder {
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final List<Integer> offsets = new ArrayList<>();
    private InternalKey last;

    public void add(InternalKey key, byte[] value) {
        if (last != null && InternalKey.ORDER.compare(last, key) >= 0) {
            throw new IllegalArgumentException("keys must be added in strictly increasing order");
        }
        if (key.seq() >>> 56 != 0) {
            throw new IllegalArgumentException("seq does not fit in 56 bits");
        }
        ByteBuffer e = ByteBuffer.allocate(4 + key.userKey().length + 8 + 4 + value.length);
        e.putInt(key.userKey().length);
        e.put(key.userKey());
        e.putLong((key.seq() << 8) | (key.type() == ValueType.PUT ? 1 : 0));
        e.putInt(value.length);
        e.put(value);

        offsets.add(body.size());
        body.writeBytes(e.array());
        last = key;
    }

    public boolean isEmpty() {
        return offsets.isEmpty();
    }

    public int estimatedSize() {
        return body.size() + 4 * offsets.size() + 4;
    }

    public byte[] finish() {
        ByteBuffer out = ByteBuffer.allocate(estimatedSize());
        out.put(body.toByteArray());
        for (int off : offsets) {
            out.putInt(off);
        }
        out.putInt(offsets.size());
        return out.array();
    }
}