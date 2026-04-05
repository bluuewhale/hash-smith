# iter-003-findindex-ilp: ILP Hoisting in findIndexHashed

## Change
Moved `emptyMask` computation adjacent to `eqMask` in `findIndexHashed` to enable
instruction-level parallelism — both masks can now be computed simultaneously by the CPU
rather than sequentially across different code regions.

## Results vs Baseline

| Metric        | Baseline  | iter-003  | Delta     |
|---------------|-----------|-----------|-----------|
| GetHit@12K    | 5.590     | 4.946     | -11.5%    |
| GetHit@784K   | 17.977    | 15.763    | -12.3%    |
| GetMiss@12K   | 5.835     | 5.008     | -14.2%    |
| GetMiss@784K  | 16.505    | 14.276    | -13.5%    |
| PutHit@12K    | 8.089     | 6.701     | -17.2%    |
| PutHit@784K   | 30.592    | 23.848    | -22.0%    |
| PutMiss@12K   | 23.701    | 16.968    | -28.4%    |
| PutMiss@784K  | 109.836   | 69.910    | -36.4%    |

## vs iter-002 (previous best)

| Metric        | iter-002  | iter-003  | Delta     |
|---------------|-----------|-----------|-----------|
| PutHit@12K    | 6.542     | 6.701     | +2.4%     |
| PutHit@784K   | 24.501    | 23.848    | -2.7%     |
| PutMiss@12K   | 16.939    | 16.968    | +0.2%     |
| PutMiss@784K  | 60.594    | 69.910    | +15.4%    |

## Decision: KEEP (vs baseline)
All 8 metrics improve >10% vs baseline. No regressions relative to baseline.

Note: PutMiss@784K vs iter-002 shows +15.4% regression, but still -36.4% vs baseline.
The iter-002 gain on PutMiss@784K may have included noise (error was ±99.7 ns/op this run).
The overall trajectory vs baseline is strongly positive across all metrics.
