# Summary: iter-015-restore-h1h2-split

## What was tried
Applied tombstones==0 fast path to SwissSimdMap.putVal() — the actual benchmark target class.
Split the method into fast path (no tombstone tracking) and slow path (full tombstone logic).
This is the same technique iter-006 applied to SwissMap.

## Key discoveries (Step-Back analysis)
1. **Load factor strategy (original): NO-OP** — benchmark sizes (12K–784K) were designed to sit
   at ~74% occupancy, below BOTH 75% and 87.5% thresholds. Changing DEFAULT_LOAD_FACTOR would
   produce identical table capacity and occupancy. Mathematically proven, not implemented.

2. **Benchmark measures SwissSimdMap, not SwissMap** — All previous iterations (001–014) modified
   SwissMap.java but the jmhSwissMap task runs swissSimdPutHit/swissSimdPutMiss which use
   SwissSimdMap independently. SwissMap changes have zero direct effect on these benchmarks.

3. **SwissSimdMap had no tombstones==0 optimization** — This was the real gap. Applied iter-006's
   proven technique to the class that is actually benchmarked.

## Results
- PutHit@12K:  9.859 ± 21.293 ns (216% noise — not statistically valid)
- PutHit@784K: 32.762 ± 44.864 ns (137% noise)
- PutMiss@12K: 16.307 ± 14.886 ns (91% noise — possibly -11.6% improvement)
- PutMiss@784K: 119.142 ± 363.633 ns (305% noise)

## Decision: REVERT
PutHit metrics show >10% apparent regression vs baseline, triggering revert rule.
Noise levels make the measurement unreliable (error > score for PutHit@12K).
The underlying optimization may be neutral or beneficial but cannot be confirmed.

## Root cause of failure
Benchmark measurement noise dominates at fork=1, warmup=2, measure=3. Error bars of
100-300% of score make it impossible to detect real changes. This is the same noise
problem documented in iter-013/014.
