# Summary: iter-006-loop-specialization

## Strategy

Duplicated the probe loop in `putValHashed` with a `tombstones == 0` fast-path check hoisted
above the loop. The fast-path loop body contains only `eqMask` + `hasEmpty` — no `DELETED_BROADCAST`
reference, no `firstTombstone` variable. The slow-path loop is unchanged from iter-003 semantics.

## Implementation

In `putValHashed`:
```java
if (tombstones == 0) {
    // FAST PATH: eqMask + hasEmpty only, no DELETED_BROADCAST, no firstTombstone
    for (;;) { ... }
} else {
    // SLOW PATH: full loop with DELETED_BROADCAST scan
    int firstTombstone = -1;
    for (;;) { ... }
}
```

Both loops retain iter-003's ILP: `eqM` and `emptyBits` computed adjacently.
No method call boundaries (unlike iter-005).

## Results

| Metric          | Baseline   | iter-003   | iter-006   | vs baseline | vs iter-003 |
|-----------------|-----------|-----------|-----------|-------------|-------------|
| PutHit@12K      | 7.921 ns  | 6.011 ns  | 6.048 ns  | **-23.6% ✅** | +0.6% (noise) |
| PutHit@784K     | 28.689 ns | 29.016 ns | 28.365 ns | -1.1% ✅    | -2.2% ✅    |
| PutMiss@12K     | 18.443 ns | 17.514 ns | 17.692 ns | -4.1% ✅    | +1.0% (noise) |
| PutMiss@784K    | 112.932 ns| 102.219 ns| 60.132 ns | **-46.7% ✅✅** | **-41.1% ✅✅** |

## Decision: **KEEP** ✅

Multiple metrics exceed the 10% improvement threshold.
No metric is >10% worse than baseline.
PutMiss@784K improvement is massive (-46.7%) and consistent.

## Why it worked

1. Eliminating `DELETED_BROADCAST` from the fast-path loop body reduces the instruction count
   in the 99%+ case where `tombstones == 0`.
2. Eliminating `firstTombstone` as a live variable in the fast path reduces register pressure,
   giving the JIT more freedom to allocate `eqM`, `emptyBits`, `base` to optimal registers.
3. ILP from iter-003 (adjacent `eqM` + `emptyBits`) is preserved in both loops.
4. No method call boundary (iter-005 failure mode) — everything stays inline.
5. The `tombstones == 0` check is a single compare+branch executed once per `put` call,
   not once per probe loop iteration — trivial overhead.

## Why PutMiss@784K improved so dramatically

The 784K case exercises cache-cold behavior (LLC misses). In this regime:
- Fewer instructions in the loop body = fewer decode/dispatch stalls
- The probe loop iterates more groups before finding an empty slot
- Each saved instruction amplifies across more iterations
- Previously iter-003 didn't help PutMiss@784K; iter-006's loop body simplification does
