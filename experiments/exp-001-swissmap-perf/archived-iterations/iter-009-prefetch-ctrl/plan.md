# Plan: iter-009-prefetch-ctrl

## Goal
Improve PutHit@784K (currently 28.4 ns) and PutMiss@784K (currently 60.1 ns)
by hiding memory latency via software prefetch of the next probe group's ctrl word.

## Strategy: Software Prefetch via sun.misc.Unsafe.prefetchRead

### Why prefetch?
- At 784K entries (capacity ~896K, groups ~112K), ctrl[] = 112K * 8 bytes = ~900KB
  → Does NOT fit in L2 cache (typically 256KB-512KB per core)
- keys[] = 784K * 8 bytes = ~6.3MB, vals[] = same → both spill to L3/DRAM
- Each probe group access causes an LLC miss; prefetching the NEXT group address
  ahead of the current group processing hides this latency

### Mechanism
`sun.misc.Unsafe.prefetchRead(Object, long)` is a HotSpot intrinsic that emits
a native PREFETCHT0 instruction on x86. It takes an object reference and a byte
offset within that object (accounting for array header).

### Placement (STEP 3 reads SwissMap.java)
**Data layout:**
- `ctrl[]` is a `long[]` — group index `g` maps to `ctrl[g]`
- Array element offset: `Unsafe.arrayBaseOffset(long[].class)` + `g * 8`
- Next group for prefetch: `(g + (step+1)) & mask` (one step ahead)

**Critical constraint from iter-008:** NO new code in `putValHashed` body.

However, re-analysis shows iter-008 failed due to ~5 added instructions (isDeleted
check + branch + decrement in insertAt inline). A prefetch intrinsic is a SINGLE
native hint with no branches. The bytecode cost is one `invokevirtual` instruction
(3 bytes) vs iter-008's ~15+ bytes of new bytecode.

**Calculated risk:** Try adding ONE Unsafe.prefetchRead call per loop iteration in
the fast path loop (tombstones==0 branch). If JIT degrades, revert.

### Implementation
```java
// At top of putValHashed, acquire Unsafe + array base offset as static fields:
private static final sun.misc.Unsafe UNSAFE = getUnsafe();
private static final long CTRL_ARRAY_BASE = UNSAFE.arrayBaseOffset(long[].class);
private static final int CTRL_ARRAY_SCALE = UNSAFE.arrayIndexScale(long[].class);

// In the fast path loop, after updating g:
// Prefetch ctrl word for the next probe group (one triangular step ahead)
int nextG = (g + step + 1) & mask;
UNSAFE.prefetchRead(ctrl, CTRL_ARRAY_BASE + (long) nextG * CTRL_ARRAY_SCALE);
```

Wait — this adds TWO operations per loop iteration (nextG calculation + prefetch),
which IS adding code to the method body. But these are:
1. One int arithmetic (AND operation, 1 bytecode instruction)
2. One intrinsic call (no branches, 1 machine instruction)

Alternative: compute prefetch address lazily using step already incremented:
After `g = (g + (++step)) & mask;`, immediately:
`UNSAFE.prefetchRead(ctrl, CTRL_ARRAY_BASE + ((long)(g + step) & mask) * CTRL_ARRAY_SCALE);`

This reuses already-computed `g` and `step`, minimizing extra instructions.

### Verification: jitAsm check
Before benchmarking, run `./gradlew jitAsm` and verify `prefetchr` or `prefetcht0`
appears in the compiled output for `putValHashed`. If no PREFETCH instruction found,
the JIT is treating it as a no-op and we should revert.

### Success criteria
- PutHit@784K: improve by ≥10% (to ≤25.5 ns)
- OR PutMiss@784K: improve by ≥10% (to ≤54.1 ns)
- MUST NOT regress any metric by >10% vs baseline (7.921/28.689/18.443/112.932)

### Risk assessment
- HIGH: JIT may still not emit PREFETCH (treat as dead hint) → no-op, revert
- MEDIUM: Adding bytecodes to putValHashed may degrade fast-path JIT quality
- LOW: Prefetch thrash if stride is irregular (triangular probing is irregular)

### Rollback plan
If any metric regresses >10%: `git revert` the change and document in reflexion.md
