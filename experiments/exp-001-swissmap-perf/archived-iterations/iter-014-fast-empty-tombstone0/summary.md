# Summary: iter-014 — fast-empty-tombstone0

## What was attempted
In the `tombstones==0` fast path of `putValHashed`, replaced `hasEmpty(word)` (3-op SWAR chain:
XOR + subtract + AND) with `word & BITMASK_MSB` (1-op AND). In the tombstones==0 branch,
DELETED bytes (0xFE) cannot exist by invariant, so only FULL (bit7=0) and EMPTY (0x80, bit7=1)
bytes are present. Therefore `word & BITMASK_MSB` is mathematically equivalent to `hasEmpty(word)`.

## Result
- PutHit@12K:  10.788 ± 14.627 ns/op  (baseline: 5.954)  → WORSE
- PutHit@784K: 31.058 ± 27.539 ns/op  (baseline: 26.435) → WORSE
- PutMiss@12K: 19.671 ± 9.101  ns/op  (baseline: 17.006) → WORSE

## Decision: REVERT ❌

## Why it failed (hypothesis)
1. **JIT already optimizes hasEmpty**: The JIT may recognize the hasZero idiom and generate
   equally efficient code, making our simpler expression gain nothing.
2. **Bytecode budget**: The iter-008 lesson showed putValHashed is at JIT bytecode budget.
   Even removing ops can sometimes shift code layout in ways that hurt inlining decisions.
3. **OOO ILP disrupted**: The 3-op chain `(x - BITMASK_LSB) & ~x & BITMASK_MSB` may be
   scheduled better by OOO CPU than a single `&` when paired with the adjacent eqMask chain.
4. **Noise caveat**: Large error bars (±14.6 on 10.8 for PutHit@12K) suggest heavy system noise.
   The actual change may be neutral, but the protocol requires revert on >10% regression.

## Key lesson
The SWAR `hasEmpty()` 3-op chain is not a candidate for simplification even in the restricted
tombstones==0 context. The JIT likely handles it optimally already, or the code layout dependency
matters. Do not revisit this direction.
