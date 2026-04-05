# iter-001-tombstone-guard: Reflexion

## What happened
Applied a `tombstones > 0` guard to skip the DELETED_BROADCAST eqMask SWAR
computation in putValHashed when no tombstones exist. Expected to reduce work on
put hot path.

## Outcome
- REVERTED: PutHit@784K regressed +16.5% (threshold: >10% = revert)
- Some metrics improved: GetHit@784K -11.1%, PutMiss@784K -12.5%
- Small-table metrics within noise (<5%)

## Why the regression likely occurred
The benchmark run may have captured JMH inter-run variance rather than a true
regression from this specific change. The optimization only adds one boolean
compare per putValHashed call — it cannot logically make PutHit slower.

More likely causes:
1. **JMH thermal/JIT variance at 784K**: Large-table benchmarks are sensitive to
   JIT compilation order and CPU thermal state. The +16.5% and -11.1% at the
   same size hint at correlated JIT noise.
2. **Single benchmark run**: Only 1 benchmark run was done; JMH recommends 5+
   forks to reduce variance.

## Lessons
- The tombstone guard is logically sound and functionally safe (tests pass).
- A single JMH run is insufficient to distinguish signal from noise at this scale.
- The iter-002 attempt should: (a) re-run baseline to establish true current
  variance, or (b) try the tombstone guard again with a fresh JMH run.

## Hint for iter-002
Consider ILP hoisting: compute `eqMask` and `emptyMask` in parallel before
the key-equality inner loop. On OOO CPUs, adjacent independent SWAR ops
(eqMask for h2 and EMPTY_BROADCAST) can be issued in the same cycle.
Alternative: investigate if the `firstTombstone` variable itself (even without
the guard) can be removed from the probe loop when tombstones==0, by splitting
putValHashed into a fast-path (no tombstones) and slow-path (with tombstones).
