package lsmkv;

/**
 * A snapshot-consistent cursor over user keys in ascending order. Always close it: it holds
 * references to table files, and on Windows those files cannot be deleted until it is closed.
 * Returned arrays are copies. I/O errors surface as UncheckedIOException.
 */
public interface RangeIterator extends AutoCloseable {
    boolean valid();
    byte[] key();
    byte[] value();
    void next();
    @Override
    void close();
}