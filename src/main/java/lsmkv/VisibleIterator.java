package lsmkv;

/**
- Turns a merged stream of versions into the visible user-level view at a snapshot sequence:
- per user key, the newest version with seq <= snapshot, tombstoned keys hidden.
- Invariant: while valid, the source is positioned ON the emitted entry.
 */
final class VisibleIterator implements RangeIterator {
    private final KvIterator source;
    private final long snapshot;
    private final byte[] end;          // exclusive; null = unbounded
    private final Runnable onClose;
    private boolean closed;
    private boolean valid;
    private byte[] curKey;
    private byte[] curValue;

    VisibleIterator(KvIterator source, byte[] start, byte[] end, long snapshot, Runnable onClose) {
        this.source = source;
        this.snapshot = snapshot;
        this.end = end;
        this.onClose = onClose;
        try {
            if (start == null) {
                source.seekToFirst();
            } else {
                source.seek(new InternalKey(start, Long.MAX_VALUE, ValueType.PUT));
            }
            findNext();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private void findNext() {
        valid = false;
        while (source.valid()) {
            InternalKey k = source.key();
            byte[] userKey = k.userKey();
            if (end != null && Bytes.compare(userKey, end) >= 0) {
                return;                                   // past the range
            }
            if (k.seq() > snapshot) {
                source.next();
                continue;
            }
            if (k.type() == ValueType.DELETE) {
                skipKey(userKey);
                continue;
            }
            curKey = userKey;
            curValue = source.value();
            valid = true;
            return;
        }
    }

    private void skipKey(byte[] userKey) {
        while (source.valid() && Bytes.compare(source.key().userKey(), userKey) == 0) {
            source.next();
        }
    }

    private void requireValid() {
        if (!valid || closed) {
            throw new IllegalStateException("iterator is not positioned on an entry");
        }
    }

    @Override
    public boolean valid() {
        return valid && !closed;
    }

    @Override
    public byte[] key() {
        requireValid();
        return curKey.clone();
    }

    @Override
    public byte[] value() {
        requireValid();
        return curValue.clone();
    }

    @Override
    public void next() {
        requireValid();
        skipKey(curKey);      // older versions of the entry we just emitted
        findNext();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        valid = false;
        try {
            source.close();
        } finally {
            onClose.run();
        }
    }
}