package lsmkv;

/**
 * A cursor over entries sorted by InternalKey.ORDER. Starts unpositioned (valid() == false)
 * until seekToFirst() or seek() is called. key()/value()/next() require valid().
 * Returned arrays must not be modified by the caller.
 */
public interface KvIterator extends AutoCloseable {
    boolean valid();
    InternalKey key();
    byte[] value();
    void next();
    void seekToFirst();
    /** Positions on the first entry that is >= target in InternalKey.ORDER. */
    void seek(InternalKey target);
    @Override
    void close();
}