# Changelog
## Unreleased

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