package lsmkv;

import java.io.IOException;

/**
 * Turns a merged stream (newest version of each user key first) into a compacted table.
 * Keeps only the newest version of each key. A tombstone is dropped only if dropTombstones is
 * true, which the caller may set only when no older data for any key can exist below this output.
 */
public final class Compactor {
    private Compactor() {}

    /** Returns the number of entries written. The caller finishes and closes the writer. */
    public static long run(KvIterator in, SSTableWriter out, boolean dropTombstones) throws IOException {
        byte[] currentKey = null;
        long written = 0;
        in.seekToFirst();
        while (in.valid()) {
            InternalKey k = in.key();
            boolean newestVersion = currentKey == null || Bytes.compare(currentKey, k.userKey()) != 0;
            if (newestVersion) {
                currentKey = k.userKey();
                if (!(dropTombstones && k.type() == ValueType.DELETE)) {
                    out.add(k, in.value());
                    written++;
                }
            }
            in.next();   // older versions of the same key fall through and are skipped
        }
        return written;
    }
}