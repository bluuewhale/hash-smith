# Plan: iter-015-restore-h1h2-split

## Strategy: tombstones==0 fast path in SwissSimdMap.putVal()

### Step-Back Analysis: Load Factor Evaluation (Original Strategy)

The proposed load factor change (0.875 → 0.75) was analyzed mathematically and found to be a
**no-op** for the benchmarks:

- Benchmark sizes: 12K, 48K, 196K, 784K elements
- The benchmark comment itself says "load factor equals to 74.x% (right before resizing)"
- At 74% occupancy, the table is BELOW both the 75% and 87.5% thresholds
- Both load factors produce IDENTICAL final table capacity and occupancy for all benchmark sizes

Simulation confirms: N=12000 ends at cap=16384 (73.2%) under LF=0.875 AND LF=0.75.
Therefore, load factor change would show zero measurable difference.

**Load factor strategy: ABANDONED. No implementation change.**

### Discovery: Benchmark vs Implementation Mismatch

Investigation revealed a critical architectural fact:
- The jmhSwissMap benchmark measures **SwissSimdMap** (swissSimdPutHit, swissSimdPutMiss)
- SwissMap @Benchmark methods (swissPutHit, swissPutMiss) are commented out
- SwissSimdMap has its **own independent** putVal() using SIMD ByteVector
- SwissSimdMap does NOT delegate to SwissMap for hot-path operations

This means all previous SwissMap.java optimizations (iter-001 through iter-014) do NOT
directly affect the benchmark results. Benchmark improvements/regressions are noise.

### Pivoted Strategy: tombstones==0 Fast Path in SwissSimdMap

**Rationale:**
- iter-006 applied tombstones==0 fast path to SwissMap and was KEPT (-23.6% PutHit@12K)
- The same optimization has NOT been applied to SwissSimdMap
- SwissSimdMap.putVal() currently always tracks firstTombstone even when tombstones==0
- PutHitState has tombstones==0 (no removes in setup)
- The tombstone tracking path includes: conditional check + `v.eq(DELETED).toLong()` SIMD op

**Current SwissSimdMap.putVal() slow path overhead (tombstones==0 case):**
```java
if (firstTombstone < 0 && tombstones > 0) {          // branch (predicted=false but still costs)
    long delMask = v.eq(DELETED).toLong();             // SIMD op (unnecessary when tombstones==0)
    if (delMask != 0) firstTombstone = ...;
}
int target = (firstTombstone >= 0) ? firstTombstone : idx;  // ternary (firstTombstone always -1)
return insertAt(target, key, value, h2);
```

**Proposed fast path:**
```java
if (tombstones == 0) {
    // FAST PATH: tombstone tracking entirely absent
    for (;;) {
        ...
        if (emptyMask != 0) {
            int idx = base + Long.numberOfTrailingZeros(emptyMask);
            return insertAt(idx, key, value, h2);
        }
        ...
    }
} else {
    // SLOW PATH: full tombstone tracking (existing code)
    ...
}
```

**Expected improvement:**
- Eliminates: `firstTombstone` local variable (frees register)
- Eliminates: `if (firstTombstone < 0 && tombstones > 0)` conditional check per iteration
- Eliminates: `v.eq(DELETED).toLong()` SIMD operation (not needed for tombstones==0)
- Eliminates: `int target = (firstTombstone >= 0) ? firstTombstone : idx` ternary
- Shorter loop body → better JIT inlining, more register pressure relief
- Fewer live variables in the loop → OOO CPU can pipeline better

**Pre-mortem risk analysis:**
- Risk: bytecode size increase → JIT threshold exceeded (seen in iter-014)
  - Mitigation: Unlike iter-014's complex predicate, this split is a simple if/else
  - SwissMap iter-006 used the same pattern and succeeded
- Risk: JIT already optimizes the `tombstones > 0` branch (predicted false for PutHit)
  - This is possible. But the SIMD op + ternary still have real cost even if branch-predicted
- Risk: SIMD overhead is already minimal compared to memory access time
  - At 784K, memory latency dominates. Less benefit at large sizes.
  - At 12K (L1/L2 cache hot), SIMD loop overhead matters more.

**Memory behavior note:**
This is purely an algorithmic change — no change to memory layout.
tombstones==0 is always true for PutHit benchmark (clean map).
tombstones is 1 for PutMiss (1 remove per invocation), so PutMiss still hits slow path.

**API compatibility:** Zero API changes. Internal implementation only.

### Changes Required
1. `SwissSimdMap.java` (primary target — what the benchmark measures):
   - Split putVal() into tombstones==0 fast path and tombstones>0 slow path
   - Keep identical semantics, only eliminate dead code in the fast path

2. `SwissMap.java` (optional, for consistency — already has this optimization via iter-006):
   - No changes needed; iter-006 already applied this optimization

### Success Criteria
- PutHit@12K: target ≥ 5.954 ns (match iter-011 claimed best) or better
- PutHit@784K: target ≥ 26.435 ns or better
- PutMiss@12K: no regression vs baseline (18.443 ns)
- No regression >10% on any primary metric vs baseline
