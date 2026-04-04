# iter-004-findindex-ilp: Plan

## Step-Back Analysis
The core insight from iter-003: placing two independent SWAR operations adjacently in source
code helps OOO CPU pipelines issue them in parallel (ILP). iter-003 applied this to
`putValHashed` — placing `eqMask` and `hasEmpty` adjacent — which yielded -24% on PutHit@12K.

`findIndexHashed` still serializes these ops:
```java
int eqMask = eqMask(word, h2Broadcast);  // eqMask
while (eqMask != 0) { ... }              // serialized dependency chain
if (hasEmpty(word) != 0) return -1;     // hasEmpty runs AFTER eqMask loop
```

The `hasEmpty(word)` call is data-independent from `eqMask(word, h2Broadcast)`.
Both read `word` and write independent results. The CPU can compute both in parallel
if they appear before any dependent branch.

## Chain-of-Thought

**Why does adjacency matter?**
Modern CPUs have multiple ALU execution units. When two independent SIMD-like ops
(bit manipulation chains on `word`) are adjacent in the instruction stream, the
out-of-order scheduler issues them to different execution units simultaneously.

Serializing them via a while-loop (that creates a dependency chain via eqMask &= eqMask-1)
forces hasEmpty to wait until all eqMask iterations complete.

**Current findIndexHashed structure:**
```
loop:
  word = ctrl[g]                         ← single load
  eqMask = eqMask(word, h2Broadcast)    ← ALU op A (reads word)
  while (eqMask != 0) { ... eqMask &= eqMask - 1 }  ← serialized
  if (hasEmpty(word) != 0) return -1    ← ALU op B (reads word) — DELAYED
  advance probe
```

**Proposed structure:**
```
loop:
  word = ctrl[g]
  int eqM = eqMask(word, h2Broadcast)  ← ALU op A
  long emptyBits = hasEmpty(word)      ← ALU op B — adjacent, OOO-parallel
  while (eqM != 0) { ... eqM &= eqM - 1 }
  if (emptyBits != 0) return -1       ← use hoisted result
  advance probe
```

**Why this should help get/miss paths:**
- GetMiss benchmark = PutMiss benchmark (lookup that terminates at empty slot)
- PutMiss@784K and PutMiss@12K both call putValHashed which already has the hoisting
- The `get()` path calls `findIndexHashed` which does NOT have hoisting
- Benchmarks labeled PutMiss measure putValHashed, not findIndexHashed directly
- However, `containsKey`, `remove`, and `get` all call findIndexHashed

## Self-Consistency Check
- Does this break any semantics? No — we compute hasEmpty eagerly but only use it later.
  The `word` variable is already loaded, so no extra memory access.
- Does this add any allocations or boxing? No — all primitives.
- Does it change the logical flow? No — `return -1` still triggers on the same condition.
- Risk of regression? Low. This is a pure ILP scheduling hint with no semantic change.
  The only risk is JIT register pressure from one extra live variable.

## Pre-mortem
**What could go wrong:**
1. Register pressure: `emptyBits` (long) is live across the eqMask while-loop.
   On x86-64 with 16 registers, this is unlikely to spill, but possible.
2. JIT may not honor the adjacency hint if it reorders for other reasons.
3. The get path may not be the bottleneck in PutMiss benchmarks (putValHashed is called).

**Mitigation:**
- Accept the change if benchmarks are neutral — this is a correctness-neutral improvement
  that at minimum makes findIndexHashed consistent with putValHashed.
- Keep/Revert decision uses the standard trade-off constraint.

## Change Plan
File: `src/main/java/io/github/bluuewhale/hashsmith/SwissMap.java`
Method: `findIndexHashed`

Before (lines 554-569):
```java
for (;;) {
    long word = ctrl[g];
    int base = g << 3;
    int eqMask = eqMask(word, h2Broadcast);
    while (eqMask != 0) {
        int idx = base + Integer.numberOfTrailingZeros(eqMask);
        Object k = keys[idx];
        if (k == key || k.equals(key)) {
            return idx;
        }
        eqMask &= eqMask - 1;
    }
    if (hasEmpty(word) != 0) return -1;
    g = (g + (++step)) & mask;
}
```

After:
```java
for (;;) {
    long word = ctrl[g];
    int base = g << 3;
    // Hoist both independent SWAR ops adjacently so OOO CPU can pipeline them.
    int eqM = eqMask(word, h2Broadcast);
    long emptyBits = hasEmpty(word); // independent of eqM; CPU issues in parallel
    while (eqM != 0) {
        int idx = base + Integer.numberOfTrailingZeros(eqM);
        Object k = keys[idx];
        if (k == key || k.equals(key)) {
            return idx;
        }
        eqM &= eqM - 1;
    }
    if (emptyBits != 0) return -1;
    g = (g + (++step)) & mask;
}
```

No changes to `findIndexHashedConcurrent` — it uses acquire-loads with different memory
ordering semantics and is a separate optimization target.
