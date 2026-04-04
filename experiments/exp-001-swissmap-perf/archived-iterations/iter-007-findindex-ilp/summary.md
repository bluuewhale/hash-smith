# Summary: iter-007-findindex-ilp

## Strategy
Apply ILP hoisting to `findIndexHashed`: compute `eqMask` and `hasEmpty` adjacently at loop top
so OOO CPU can pipeline both SWAR operations within the same loop iteration.

## Result: REVERT ❌

| Metric         | Baseline    | iter-006    | iter-007    | vs baseline    |
|----------------|-------------|-------------|-------------|----------------|
| PutHit@12K     | 7.921 ns    | 6.048 ns    | 9.014 ns    | +13.8% ❌       |
| PutHit@784K    | 28.689 ns   | 28.365 ns   | 28.162 ns   | -1.8% ✅        |
| PutMiss@12K    | 18.443 ns   | 17.692 ns   | 17.465 ns   | -5.3% ✅        |
| PutMiss@784K   | 112.932 ns  | 60.132 ns   | 123.172 ns  | +9.1% ⚠️        |

PutHit@12K regressed +13.8% vs baseline (exceeds +10% threshold). REVERT.

## Why it regressed

The hypothesis that ILP hoisting is "safe" in findIndexHashed was wrong.
Unlike putValHashed (iter-003, iter-006), findIndexHashed has a different register pressure profile:

1. **No tombstone flag**: putValHashed's fast path already eliminated `firstTombstone`.
   findIndexHashed's baseline loop is already lean — adding `emptyBits` as an explicit
   variable may have increased register pressure versus the JIT's existing instruction schedule.

2. **JIT already handles sequential SWAR well**: The C2 JIT likely already schedules
   `eqMask` computation and `hasEmpty` check optimally in the existing code (the `if`
   branch at end of loop body). Forcing an explicit `long emptyBits` variable may have
   prevented JIT from using a conditional branch optimization it had previously applied.

3. **Different loop structure**: findIndexHashed exits via `return -1` (not via a slot
   insertion). The JIT may handle this exit differently; introducing `emptyBits` as an
   explicit variable changes liveness which could affect code generation.

## Code state
Reverted to iter-006 state.
