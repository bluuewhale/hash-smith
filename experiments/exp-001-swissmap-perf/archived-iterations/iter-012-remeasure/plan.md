# iter-012-remeasure: Plan

## Re-measurement Decision

Re-ran `./gradlew jmhSwissMap` with NO code changes (iter-011 state).

Result: `swissSimdPutMiss@784K = 103.965 ns ± 528.115 ns` — extremely noisy, result unusable.

The 784K PutMiss benchmark has been consistently noisy across all iterations (iter-003: ±58ns,
iter-006: ±34ns, iter-011: ±52ns). The re-measurement does NOT confirm a regression from
iter-011 — the noise band (~528 ns) far exceeds any plausible signal difference.

**Decision: iter-011 state is AMBIGUOUS at 784K. No clear regression confirmed. Proceed to optimize.**

## Optimization Strategy: h1/h2 Bit Reassignment (Upper-bit h2)

### Motivation

Current `fibonacciHash` returns a 32-bit value: `(int)((hashCode * FIBONACCI_LONG) >>> 32)`

Current split:
- `h2 = hash & 0x7F`  → bits 6..0  (low 7 bits — weakest avalanche in the 32-bit output)
- `h1 = hash >>> 7`   → bits 31..7 (upper 25 bits — good quality)

The 64-bit Fibonacci multiply produces a 64-bit product where:
- Bits 63..32 = `fibonacciHash` result (currently used)
- Bits 31..0  = discarded lower half (less useful, but bit 63 > bit 32 in avalanche quality)

Within the 32-bit result from `>>> 32`, bit 31 (MSB) has the best avalanche properties
(carries from the full 64-bit multiply chain), while bit 0 (LSB) has the worst.

**Proposed change**: Swap h2 to use the TOP 7 bits of the hash (bits 31..25) — highest quality
bits with best distribution — and h1 uses bits 24..0 for group selection.

```java
// Before
h1 = hash >>> 7           // bits 31..7  (25 bits for group)
h2 = hash & 0x7F          // bits  6..0  ( 7 bits for fingerprint)

// After
h1 = hash & 0x1FFFFFF     // bits 24..0  (25 bits for group — mask instead of shift)
h2 = hash >>> 25          // bits 31..25 ( 7 bits for fingerprint — top bits)
```

### Expected Impact

- h2 fingerprint quality improves → fewer false-positive eqMask hits in SWAR scan
- PutMiss paths (must scan full groups) should benefit most from fewer false matches
- h1 group distribution: bits 24..0 of Fibonacci output are still well-distributed
  (Fibonacci multiplicative hashing distributes all 32 output bits, not just the top)
- No allocation, no branch change — same instruction count, just different shift/mask constants

### Risk

- h1 now uses a mask (`& 0x1FFFFFF`) instead of shift (`>>> 7`): 
  `g = h1 & mask` where mask = `nGroups - 1`. This remains a single AND.
  The computation `hash & 0x1FFFFFF & (nGroups-1)` = `hash & (nGroups-1)` when nGroups ≤ 2^25,
  which is always true in practice. So the mask in h1() can be eliminated entirely:
  `h1 = hash` (let the caller's `h1 & groupMask` do the work).
  
  But h2 must stay independent of h1, so h2 = top 7 bits regardless.

### Final Implementation

```java
private int h1(int hash) {
    return hash;  // full 32-bit value; group selection by (h1 & groupMask)
}

private byte h2(int hash) {
    return (byte) (hash >>> 25);  // top 7 bits — highest avalanche quality
}
```

This also saves the `>>> 7` shift instruction in the h1 path.
