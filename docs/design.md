## Decision 1: Entry identity
MemTable entries are keyed by `InternalKey(userKey, seq, type)` as a Java record, ordered by user key ascending (unsigned bytes), then seq descending, so the newest version is found first. Rejected: packing the triple into one `byte[]` (LevelDB style). Revisit only if benchmarks show object overhead matters.

## Decision 2: MemTable container
`ConcurrentSkipListMap<InternalKey, byte[]>` ordered by `InternalKey.ORDER`. Writers are serialized by the engine; the skip list gives lock-free concurrent readers. Rejected: `TreeMap` + RW lock (readers block while writing).

## Decision 3: Deletes
A delete is an entry of type DELETE with an empty value (the skip list rejects null values). `get()` returns FOUND / DELETED / ABSENT so the engine can tell "stop, deleted" from "keep searching older files".

## Invariant: sequence numbers are unique
`InternalKey.ORDER` returns 0 for equal `(key, seq)`, so the engine must never reuse a seq. MemTable throws on a duplicate. Inputs are copied on write; `get()` returns the stored array.

## Decision 4: One iterator interface
`KvIterator` (valid/key/value/next/seekToFirst/seek/close), LevelDB style, implemented by the MemTable, every SSTable, and later the merging iterator. Rejected: `java.util.Iterator` (no seek, no peek, no close; open file handles block deletes on Windows).

Readers snapshot the last *published* sequence number, not the last assigned one.

## Decision 5: WAL format and recovery
Record = `[crc32c:4][len:4][type:1][seq:8][keyLen:4][key][valLen:4][value]`, big-endian; crc covers everything after the first 8 bytes. Built in one `ByteBuffer` and written in one write loop. Replay stops at the first torn or corrupt record and reports the valid byte count; recovery truncates the file there before appending. Sync policy is a flag (per-write force vs manual sync); group commit is a planned optimisation. Caveat: some consumer SSDs acknowledge fsync early, so power-loss durability can't be fully proven on a laptop.

## Decision 6: Write path and recovery
put/delete under one write lock: assign seq, append to WAL, apply to MemTable, then publish the seq (volatile). Readers snapshot the published seq and take no lock. I/O error on the log puts the engine in a failed state (no more writes until reopen). Recovery replays the WAL into a fresh MemTable, then truncates the log at the last valid byte so new records can't land behind garbage. Known test gap: the WAL-before-MemTable order is not yet fault-injection tested. I also suggest adding one line: "If MemTable insertion fails after the WAL append, the engine fail-stops."

## Decision 7: SSTable block format
~4 KB data blocks. Entry = [keyLen][userKey][trailer = seq<<8|type][valLen][value]. Block = entries,offset array, entry count; binary search inside the block via the offsets. No prefix compression for now (measure first). The file index will hold the LAST key of each block, because versions of one user key can straddle blocks; lookup = first block whose last key >= probe.

## Decision 8: SSTable file layout
[data block][crc32c] ... [index block][crc32c][footer 40B = indexOffset, indexLen, filterOffset,filterLen, magic "LSMKV001"]. Index reuses the block format: key = last key of each data block,value = (offset, length). Every block is checksummed and verified on read. Footer is fixed-size at the end because the index location is only known after the data is written. Reads use positional FileChannel reads (thread-safe, no mmap, no Windows file locking issue). Writer opens with CREATE_NEW; temp-file + atomic rename is the flush step's job.

## Decision 9: Bloom filters
One filter per SSTable over distinct user keys, tombstones included (otherwise a skipped delete lets an older file resurrect the value). 10 bits/key, 7 probes (~1% false positives) from one MurmurHash64A hash via double hashing. The writer collects key hashes and builds the filter at finish(), sized exactly. Stored as a checksummed block between data and index; the footer holds its offset and length (length 0 = no filter). Verified by a test that counts block reads.

## Decision 10: Flush, MANIFEST and recovery
The MANIFEST (atomically replaced: tmp, fsync, rename, dir-fsync) is the single source of truth: live tables (newest first), current WAL number, nextFileNumber, lastSeq. Flush order: write and fsync the SSTable, create the new WAL, write the MANIFEST (commit point), swap the in-memory View, delete the old WAL. Anything the MANIFEST does not reference is an orphan and is deleted on open. lastSeq is persisted so sequence numbers never restart below flushed data. Readers use
one volatile immutable View (memtable + table list) and snapshot the published seq BEFORE the view. Flush is synchronous for now (writers stall); background flush with an immutable memtable and backpressure is planned. Directory fsync is skipped on Windows.

## Decision 11: Compaction plan
Leveled compaction, built in stages: (a) MergingIterator (heap k-way merge; no recency tie-break needed because InternalKey order is total), (b) dedupe + tombstone dropping, first by merging all tables into one (output is the bottom, so tombstones can be dropped safely), (c) engine integration with reader-safe retirement of old Views (needed on Windows: open files cannot be deleted), then real levels with per-file key ranges. Invariant: there are no user-visible snapshots, so compaction keeps only the newest version of each key.