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

## Decision 5: WAL format and recovery
Record = [crc32c:4][len:4][type:1][seq:8][keyLen:4][key][valLen:4][value], big-endian; crc covers
everything after the first 8 bytes. Built in one ByteBuffer and written in one write loop.
Replay stops at the first torn or corrupt record and reports the valid byte count; recovery
truncates the file there before appending. Sync policy is a flag (per-write force vs manual
sync); group commit is a planned optimisation. Caveat: some consumer SSDs acknowledge fsync
early, so power-loss durability can't be fully proven on a laptop.