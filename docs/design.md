## Decision 1: Entry identity
MemTable entries are keyed by InternalKey(userKey, seq, type) as a Java record, ordered by
user key ascending (unsigned bytes), then seq descending, so the newest version is found first.
Rejected: packing the triple into one byte[] (LevelDB style). Revisit only if benchmarks show
object overhead matters.

## Decision 2: MemTable container
ConcurrentSkipListMap<InternalKey, byte[]> ordered by InternalKey.ORDER. Writers are serialized
by the engine; the skip list gives lock-free concurrent readers. Rejected: TreeMap + RW lock
(readers block while writing).

## Decision 3: Deletes
A delete is an entry of type DELETE with an empty value (the skip list rejects null values).
get() returns FOUND / DELETED / ABSENT so the engine can tell "stop, deleted" from "keep
searching older files".

## Invariant: sequence numbers are unique
InternalKey.ORDER returns 0 for equal (key, seq), so the engine must never reuse a seq.
MemTable throws on a duplicate. Inputs are copied on write; get() returns the stored array.

## Decision 4: One iterator interface
KvIterator (valid/key/value/next/seekToFirst/seek/close), LevelDB style, implemented by the
MemTable, every SSTable, and later the merging iterator. Rejected: java.util.Iterator (no seek,
no peek, no close; open file handles block deletes on Windows).
Readers snapshot the last *published* sequence number, not the last assigned one.