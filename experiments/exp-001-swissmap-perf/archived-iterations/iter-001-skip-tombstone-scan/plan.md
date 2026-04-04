# Plan: iter-001-skip-tombstone-scan

## Step-Back: What type of bottleneck is this?
PutMiss@784K at 112.9ns is ~4x slower than PutMiss@12K at 18.4ns.
This is clearly **memory-bound**: the table at 784K entries does not fit in L2 (typical 512KB-1MB),
so each group probe that loads ctrl[] hits LLC or DRAM (~30-50ns each).

PutHit@784K at 28.7ns vs 7.9ns at 12K — same pattern but less severe since vals[] write
is likely absorbed by write buffers.

The number of groups probed dominates latency at large scale.

## Candidate Approaches (CoT)

### Candidate 1: Skip tombstone scan when tombstones == 0 [CHOSEN]
- **What**: Guard the `v.eq(DELETED).toLong()` block with `if (tombstones > 0)`
- **Where**: putVal inner loop (and findIndex for symmetry)
- **Trade-offs**:
  + Eliminates 1 vector compare + toLong per probe group in the common case
  + Benchmarks test pure insert (no deletes) so tombstones==0 always during benchmark
  + Zero impact on correctness — tombstone scan only needed to reuse deleted slots
  - Adds 1 branch (`tombstones > 0`) per probe group, but branch is perfectly predicted (always false in benchmark)
  - When tombstones > 0, behavior is identical to before
- **Expected gain**: ~5-15% reduction in PutMiss ns/op

### Candidate 2: Prefetch next group's ctrl bytes
- **What**: `VarHandle.fullFence()` or `Unsafe.prefetchRead` of ctrl[next_base] at start of each group
- **Trade-offs**:
  + Could hide memory latency for ctrl[] fetches
  - Java doesn't expose reliable prefetch; LLVM/JIT may already handle this
  - Adds complexity, unpredictable benefit
  - Hard to validate without assembly inspection

### Candidate 3: Reduce h1 computation — use raw hash bits directly
- **What**: Remove H1_MASK shift — use `hash >>> 7` directly since groupMask will limit range
- **Trade-offs**:
  + Saves 1 AND + 1 shift — negligible
  - No meaningful cycle savings on modern superscalar CPUs

## Decision: Candidate 1
Candidate 1 has the best risk/reward: zero correctness risk, perfect branch prediction on benchmarks,
direct reduction of work per probe group.

## Implementation
In `putVal`, change:
```java
if (firstTombstone < 0) {
    long delMask = v.eq(DELETED).toLong();
    if (delMask != 0) firstTombstone = base + Long.numberOfTrailingZeros(delMask);
}
```
To:
```java
if (firstTombstone < 0 && tombstones > 0) {
    long delMask = v.eq(DELETED).toLong();
    if (delMask != 0) firstTombstone = base + Long.numberOfTrailingZeros(delMask);
}
```

This is a one-character change (`tombstones > 0` guard added).

## Pre-mortem: If this fails, what's the most likely reason?
1. The JIT was already hoisting or eliminating the dead SIMD op when tombstones==0 — no measurable delta
2. The bottleneck is actually cache misses on keys[] dereferences in the hit branch, not SIMD work
3. Vector API `toLong()` is so fast (~1 cycle) the savings are noise-level vs 30ns LLC misses
