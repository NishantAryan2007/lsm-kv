## Decision 1: Entry identity
MemTable entries are keyed by InternalKey(userKey, seq, type) as a Java record, ordered by
user key ascending (unsigned bytes), then seq descending, so the newest version is found first.
Rejected: packing the triple into one byte[] (LevelDB style). Revisit only if benchmarks show
object overhead matters.