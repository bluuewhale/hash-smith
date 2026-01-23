# Changelog
## Unreleased

## 0.1.8
### Added
- Added `ConcurrentSwissMap`: a sharded, thread-safe wrapper around `SwissMap`. (#10)
- Added Guava testlib + Apache Commons Collections test suites; expanded `ConcurrentSwissMap` `ConcurrentMap` API and fixed deterministic random-cycle initialization in collection classes. (#11, thanks @ben-manes)
### Fixed
- Fixed `./gradlew jmh` failing on macOS/Linux due to missing `--add-modules jdk.incubator.vector` for the JMH bytecode generator task. (#13)
### Changed
- `SwissMap` and `SwissSimdMap` probing changed from linear probing to triangular/quadratic probing (group-step sequence `+1, +2, +3, ...`) to reduce primary clustering. (#9)
- `SwissMap`: removed the probe-cycle guard and the unused `numGroups` / `visitedGroups` counters (keep only `groupMask`).
- `ConcurrentSwissMap` sharding now ignores the lower 7 bits reserved for `SwissMap`'s H2 (control-byte tag) and shards by the remaining high bits (H1).
- `ConcurrentSwissMap` now reuses the precomputed `Hashing.smearedHash(key)` when calling into per-shard `SwissMap` operations to avoid hashing the same key twice on hot paths (get/containsKey/put/remove).

## 0.1.7
### Fixed
- eqMask zero-byte detection bug caused by cross-byte borrow (Mycroft haszero mask) (#8, thanks to @aqrit)

### Changed
- optimize `putAll` by pre-sizing capacity for batch insertion to reduce resizing/rehashing (#3, thanks @NBHZW).
- refactor `put` into `putVal` for code reuse/readability (#3, thanks @NBHZW).
- Cache group-count derived values to avoid repeated recomputation in hot probe loops, (`numGroups` and `mask`)
- SwissMap SWAR probe now returns packed eq masks and uses trailing-zero indexing (removing spaced-mask helper).

## 0.1.6
- Added `SwissSwarMap`: SWAR-based SwissTable variant (8-slot groups, packed control bytes) plus JMH benchmarks alongside SwissMap.
- Renamed SIMD map to `SwissSimdMap` and SWAR map to `SwissMap`; `SwissMap` is now the SWAR default and Vector-API SIMD lives in `SwissSimdMap`.
- Updated benchmarks/tests/docs and README guidance (Vector API still incubating; SWAR chosen by default based on profiling).

## 0.1.5
- Fixed `SwissMap` and `SwissSet` to only grow (2x resize) when rehashing due to exceeding `maxLoad`; tombstone-cleanup rehash now keeps the same capacity to prevent unbounded growth under heavy delete workloads.
- Fixed `SwissMap#retainAll` / `removeAll` (via JDK `AbstractCollection` implementations) potentially throwing `NullPointerException` due to iterator invalidation when a tombstone-cleanup rehash occurred during iteration.

## 0.1.4
- Added `SwissMap#removeWithoutTombstone` for efficient deletions without leaving tombstones for benchmark tests
- SwissMap SIMD probe now loads control bytes once per group and reuses the vector for fingerprint/empty/tombstone masks to cut repeated loads.
- Added a SwissMap probe group-visit cap to prevent infinite probing when tombstones saturate the table.

## 0.1.3
- Added SwissHashSet support via the `SwissSet` implementation (SIMD SwissTable-style hash set with tombstone reuse and null-element support).