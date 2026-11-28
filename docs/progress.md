Step 3: project skeleton created.
Step 4: Bytes.compare done, 8 tests passing.
Step 5: ValueType + InternalKey + ordering, 13 tests passing.
Step 6: MemTable + randomized model test, 19 tests passing.
Step 7: KvIterator + MemTable iterator, 23 tests passing.
Step 8: WAL writer/reader + cut-at-every-byte test, 26 tests passing.
Step 9: LsmEngine v0 (WAL + MemTable), 30 tests passing.
Step 10a: BlockBuilder + BlockReader, 34 tests passing.
Step 10b: SSTableWriter + SSTableReader, 42 tests passing.
Step 10c: BloomFilter in SSTable, 49 tests passing.
Step 11: flush + MANIFEST + orphan cleanup, 59 tests passing.
Step 12a: MergingIterator, 63 tests passing.
Step 12b: Compactor (newest version + tombstone rule), 68 tests passing.
Step 12c: compaction in the engine, per-table refcounts, 73 tests passing.
Step 13: range scan with snapshot isolation, 78 tests passing.
Step 14: CI (Linux + Windows) and crash fault injection, ~114 tests passing.
Step 15a: DbBench load generator, baseline recorded in docs/benchmarks.md.