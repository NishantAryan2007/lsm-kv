# Benchmarks

Machine: AMD Ryzen 5 8645HS (12 logical processors), 16 GB RAM, Windows 11,
disk: WD PC SN5000S 512 GB NVMe SSD, JDK 25.0.2 (code compiled with --release 21),
power: plugged in.

Method: `lsmkv.bench.DbBench`, 300,000 keys of 16 bytes with 100-byte values in random order,
4 MiB memtable, compaction when 4 tables exist (it merges all tables into one). JIT warm-up on a
separate engine first, keys pre-generated outside the timed region. Three runs; the table shows
the median of each number. The dataset (about 40 MiB on disk) fits in the OS page cache, so reads
are warm-cache reads, not disk seeks.

## Results (median of 3 runs, 2026-11-29)

| workload | ops | ops/s | p50 us | p99 us | p99.9 us | max us |
|---|---|---|---|---|---|---|
| fillrandom, sync=false | 300000 | 215,555 | 2 | 8 | 31 | 187,452 |
| readrandom (existing keys) | 200000 | 143,405 | 6 | 16 | 42 | 1,337 |
| readmissing (absent keys) | 200000 | 3,285,901 | 0 | 0 | 2 | 54 |
| scan (all keys) | 300000 | 2,436,119 keys/s | - | - | - | - |
| fillrandom, sync=true (fsync per write) | 2000 | 4,275 | 216 | 380 | 1,226 | 9,186 |

Range of ops/s across the three runs: fill 213,652 to 216,888; read existing 125,075 to 151,846;
read absent 2,185,766 to 3,299,176; scan 1,381,033 to 2,962,234; fsync per write 3,596 to 4,317.

Disk after each run: 3 table files, 39.8 MiB on disk for 33.2 MiB of keys+values (1.20x).

## Notes
- Writing with an fsync per write is about 50x slower than without (4,275 vs 215,555 ops/s). It is
  the price of "an acknowledged write is on stable storage". Some drives acknowledge a flush from a
  volatile cache, so this does not prove power-loss durability.
- The max write latency (about 190 ms) is a flush or compaction running inside the write lock.
  Background flush and compaction are planned.
- Absent-key reads are fast because of the per-table Bloom filters. A filters-off comparison is pending.
- "0 us" means under one microsecond (integer truncation).
- Unexplained: the fsync-per-write max was about 1.1 ms in one run and about 9.2 ms in two.
- Single machine, single process, no comparison against other engines: treat these as this engine
  measured against itself, not as a claim about RocksDB or LevelDB.