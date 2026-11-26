package lsmkv;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sorted in-memory write buffer. Safe for one writer plus many concurrent readers
 * (the engine must serialize writers). Inputs are copied on write; arrays returned
 * by get() must not be modified by the caller.
 */
public final class MemTable {
    public enum Kind { FOUND, DELETED, ABSENT }

    public record Lookup(Kind kind, byte[] value) {}

    private static final Lookup ABSENT = new Lookup(Kind.ABSENT, null);
    private static final Lookup DELETED = new Lookup(Kind.DELETED, null);
    private static final byte[] EMPTY = new byte[0];
    private static final int ENTRY_OVERHEAD = 16; // rough estimate: seq, type, object headers

    private final ConcurrentSkipListMap<InternalKey, byte[]> map =
            new ConcurrentSkipListMap<>(InternalKey.ORDER);
    private final AtomicLong approximateBytes = new AtomicLong();

    public void put(byte[] key, byte[] value, long seq) {
        Objects.requireNonNull(value, "value");
        add(new InternalKey(key.clone(), seq, ValueType.PUT), value.clone());
    }

    public void delete(byte[] key, long seq) {
        add(new InternalKey(key.clone(), seq, ValueType.DELETE), EMPTY);
    }

    private void add(InternalKey k, byte[] v) {
        if (map.putIfAbsent(k, v) != null) {
            throw new IllegalStateException("duplicate (key, seq): sequence numbers must be unique");
        }
        approximateBytes.addAndGet(k.userKey().length + v.length + ENTRY_OVERHEAD);
    }

    /** Newest version of key whose seq is <= snapshotSeq. */
    public Lookup get(byte[] key, long snapshotSeq) {
        InternalKey probe = new InternalKey(key, snapshotSeq, ValueType.PUT);
        Map.Entry<InternalKey, byte[]> e = map.ceilingEntry(probe);

        if (e == null) {
            return ABSENT;
        }
        if (Bytes.compare(e.getKey().userKey(), key) != 0) {
            return ABSENT;
        }
        if (e.getKey().type() == ValueType.DELETE) {
            return DELETED;
        }
        return new Lookup(Kind.FOUND, e.getValue());
    }

    public long approximateBytes() {
        return approximateBytes.get();
    }

    /** Weakly consistent: safe to use while writes continue, may or may not show them. */
    public KvIterator iterator() {
        return new Cursor();
    }

    private final class Cursor implements KvIterator {
        private Iterator<Map.Entry<InternalKey, byte[]>> source;
        private Map.Entry<InternalKey, byte[]> current;

        private void position(Iterator<Map.Entry<InternalKey, byte[]>> newSource) {
            source = newSource;
            current = source.hasNext() ? source.next() : null;
        }

        private Map.Entry<InternalKey, byte[]> requireValid() {
            if (current == null) {
                throw new IllegalStateException("iterator is not positioned on an entry");
            }
            return current;
        }

        @Override
        public boolean valid() {
            return current != null;
        }

        @Override
        public InternalKey key() {
            return requireValid().getKey();
        }

        @Override
        public byte[] value() {
            return requireValid().getValue();
        }

        @Override
        public void next() {
            requireValid();
            current = source.hasNext() ? source.next() : null;
        }

        @Override
        public void seekToFirst() {
            position(map.entrySet().iterator());
        }

        @Override
        public void seek(InternalKey target) {
            position(map.tailMap(target, true).entrySet().iterator());
        }

        @Override
        public void close() { }
    }
}