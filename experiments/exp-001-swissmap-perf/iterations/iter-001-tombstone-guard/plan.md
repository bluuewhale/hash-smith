# iter-001-tombstone-guard: Plan

## Strategy

Guard the tombstone SWAR scan in `putValHashed` and `putValHashedConcurrent` with a `tombstones > 0` check, eliminating the `eqMask(word, DELETED_BROADCAST)` computation in the common case (no deletes).

## Step-Back Analysis

SwissMap uses SWAR (Software Auto-vectorization with Regular instructions) to scan 8 control bytes at a time. In `putValHashed`, for each probed group the loop computes:
1. `eqMask(word, h2Broadcast)` — match h2 fingerprint
2. `eqMask(word, DELETED_BROADCAST)` — find tombstones (for Robin Hood reuse)
3. `eqMask(word, EMPTY_BROADCAST)` — find empty slot for insertion

Each `eqMask` call is ~4 arithmetic instructions: XOR, shift, subtract, AND, plus the compress (multiply+shift). In benchmarks that do pure get/put without deletes (`swissPutHit`, `swissPutMiss`, `swissGetHit`, `swissGetMiss`), `tombstones` is always 0 — so step 2 is entirely wasted work per probe.

## Chain-of-Thought

- Benchmarks exercise maps populated via `put` with no `remove` calls → `tombstones == 0` always.
- The `if (firstTombstone < 0)` guard only skips subsequent groups after a tombstone is found, NOT the initial check per group.
- Adding `if (tombstones > 0)` around the entire tombstone-tracking block removes:
  - A SWAR computation per probe group (eqMask DELETED_BROADCAST)
  - A branch to check `delMask != 0`
  - The `firstTombstone` variable tracking overhead
- When tombstones > 0 (delete-heavy workloads), behavior is identical to baseline.
- The `tombstones` field is an `int` on the object — a single load/compare that the JIT can hoist out of the loop or CSE.

## Self-Consistency Check

- Functionally correct: when `tombstones == 0`, there are no DELETED bytes in ctrl, so `delMask` would always be 0 anyway. Skipping it is safe.
- No API change.
- Both `putValHashed` and `putValHashedConcurrent` have the same pattern → apply to both.

## Pre-Mortem

**Risk 1**: JIT already CSEs or eliminates the dead DELETED scan → no improvement, but also no regression. Safe to apply.
**Risk 2**: The `tombstones` load adds a memory access overhead → unlikely; it's a field on `this`, same cache line as `size`.
**Risk 3**: Some benchmark workload uses deletes → the guard is `tombstones > 0` so correctness is preserved.

## Expected Impact

- PutMiss (most probing): moderate gain — fewer SWAR ops per probe group
- PutHit (update existing): moderate gain — same loop, fewer SWAR ops
- GetHit/GetMiss: no direct impact (findIndex doesn't scan for tombstones)
- Overall: expect 3–10% improvement on put paths, minimal on get paths

## Change

In `putValHashed` and `putValHashedConcurrent`:
- Wrap `int firstTombstone = -1;` initialization and the `if (firstTombstone < 0) { delMask... }` block inside `if (tombstones > 0)` guard.
- The `target` selection at insert point also needs the guard.
