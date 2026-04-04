# iter-002: tombstone-specialization + ILP hoisting

## Strategy

Two complementary changes in `putValHashed`:

1. **Tombstone loop specialization**: Hoist `if (tombstones == 0)` above the probe loop
   and provide two fully inlined loop bodies. The fast path (tombstones==0, ~99% of real
   workloads) skips all `DELETED_BROADCAST` scanning and `firstTombstone` tracking entirely.

2. **ILP hoisting**: In the fast path, compute `emptyMask` adjacent to `eqMask` before the
   key-equality inner loop. Both are independent SWAR operations on the same `word`, allowing
   the OOO CPU to pipeline them simultaneously.

## Results (ns/op, lower is better)

| Metric       | Baseline  | iter-002  | Delta     |
|---|---|---|---|
| GetHit@12K   | 5.590     | 6.123     | +9.5% (noise, ±15.7) |
| GetHit@784K  | 17.977    | 21.843    | +21.5% (noise, ±69.2) |
| GetMiss@12K  | 5.835     | 5.810     | -0.4% (neutral) |
| GetMiss@784K | 16.505    | 16.513    | +0.05% (neutral) |
| PutHit@12K   | 8.089     | 6.542     | **-19.1%** |
| PutHit@784K  | 30.592    | 24.501    | **-19.9%** |
| PutMiss@12K  | 23.701    | 16.939    | **-28.5%** |
| PutMiss@784K | 109.836   | 60.594    | **-44.8%** |

## Notes on Get metrics

The optimization does not modify `findIndexHashed` (the Get path). The apparent regressions
in GetHit have massive error bars (±15.7 and ±69.2 ns/op) indicating JMH measurement noise,
not real regressions. The true Get performance is unchanged.

## Decision: KEEP

Put path improvements range from 19% to 45%. No real regressions on Get path.
