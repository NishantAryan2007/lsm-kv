package lsmkv;

import java.util.List;
import java.util.PriorityQueue;

/**
 * Merges sorted child iterators into one sorted stream (k-way merge with a min-heap).
 * Keys must be globally unique across children (guaranteed by unique sequence numbers).
 * Owns its children: close() closes them.
 */
public final class MergingIterator implements KvIterator {
    private final List<KvIterator> children;
    private final PriorityQueue<KvIterator> heap;

    public MergingIterator(List<KvIterator> children) {
        this.children = List.copyOf(children);
        this.heap = new PriorityQueue<>(
                Math.max(1, children.size()),
                (a, b) -> InternalKey.ORDER.compare(a.key(), b.key()));
    }

    private void rebuild() {
        heap.clear();
        for (KvIterator c : children) {
            if (c.valid()) {
                heap.add(c);
            }
        }
    }

    private void requireValid() {
        if (heap.isEmpty()) {
            throw new IllegalStateException("iterator is not positioned on an entry");
        }
    }

    @Override
    public boolean valid() {
        return !heap.isEmpty();
    }

    @Override
    public InternalKey key() {
        requireValid();
        return heap.peek().key();
    }

    @Override
    public byte[] value() {
        requireValid();
        return heap.peek().value();
    }

    @Override
    public void next() {
        requireValid();
        KvIterator top = heap.poll();
        top.next();
        if (top.valid()) {
            heap.add(top);
        }
    }

    @Override
    public void seekToFirst() {
        for (KvIterator c : children) {
            c.seekToFirst();
        }
        rebuild();
    }

    @Override
    public void seek(InternalKey target) {
        for (KvIterator c : children) {
            c.seek(target);
        }
        rebuild();
    }

    @Override
    public void close() {
        for (KvIterator c : children) {
            c.close();
        }
    }
}