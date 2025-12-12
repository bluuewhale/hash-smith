# Changelog
## 0.1.3
- Added SwissHashSet support via the `SwissSet` implementation (SIMD SwissTable-style hash set with tombstone reuse and null-element support).

## Unreleased
- Added `SwissMap#removeWithoutTombstone` for efficient deletions without leaving tombstones for benchmark tests
- SwissMap SIMD probe now loads control bytes once per group and reuses the vector for fingerprint/empty/tombstone masks to cut repeated loads.
- Added a SwissMap probe group-visit cap to prevent infinite probing when tombstones saturate the table.