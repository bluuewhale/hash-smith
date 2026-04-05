# iter-001-tombstone-guard: Summary

## Strategy
Guard `eqMask(word, DELETED_BROADCAST)` in `putValHashed` / `putValHashedConcurrent`
with `boolean hasTombstones = tombstones > 0` hoisted before the probe loop.
Skips one SWAR op per group when no tombstones exist (common case in benchmarks).

## Results

| Metric        | Baseline (ns/op) | iter-001 (ns/op) | Delta     |
|---------------|-----------------|------------------|-----------|
| GetHit@12K    | 5.590           | 5.601            | +0.2%     |
| GetHit@784K   | 17.977          | 15.987           | -11.1% ✓  |
| GetMiss@12K   | 5.835           | 5.948            | +1.9%     |
| GetMiss@784K  | 16.505          | 16.393           | -0.7%     |
| PutHit@12K    | 8.089           | 8.409            | +4.0%     |
| PutHit@784K   | 30.592          | 35.630           | +16.5% ✗  |
| PutMiss@12K   | 23.701          | 23.917           | +0.9%     |
| PutMiss@784K  | 109.836         | 96.153           | -12.5% ✓  |

## Decision: REVERT

PutHit@784K regressed by +16.5% (exceeds the 10% regression threshold).
GetHit@784K improved -11.1% and PutMiss@784K improved -12.5%, but the
PutHit@784K regression is a hard blocker per the success criteria.

## Notes
- The tombstone guard does not directly affect `findIndex` (get path), yet
  GetHit@784K improved. This is likely JMH noise or JIT profile shift.
- PutHit@784K regression is surprising since the change reduces work. Possible
  causes: JIT deoptimization triggered by the new boolean variable, branch
  prediction disruption at large sizes, or inter-run variance masking a deeper
  issue.
- Next iteration should investigate whether the regression is noise (re-run
  baseline) or real (try ILP hoisting / different guard placement).
