package lsmkv;

import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;

/**
 * An entry's identity in the MemTable: which key, which version, put or delete.
 * Ownership rule: the caller must not modify userKey after passing it in.
 */
public record InternalKey(byte[] userKey, long seq, ValueType type) {

    public InternalKey {
        Objects.requireNonNull(userKey, "userKey");
        Objects.requireNonNull(type, "type");
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be >= 0");
        }
    }

    /** User key ascending, then sequence number DESCENDING (newest first). */
    public static final Comparator<InternalKey> ORDER = (a, b) -> {
        int c = Bytes.compare(a.userKey(), b.userKey());
        if (c != 0) {
            return c;
        }
        return Long.compare(b.seq(), a.seq());
    };

    @Override
    public String toString() {
        return "InternalKey[" + HexFormat.of().formatHex(userKey) + ", seq=" + seq + ", " + type + "]";
    }
}