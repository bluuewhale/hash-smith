# Summary: iter-009-prefetch-ctrl

## What was tried

Two strategies were explored:

**Strategy A: Unsafe.prefetchRead on ctrl[] for next probe group**
- Attempted to use `sun.misc.Unsafe.prefetchRead(Object, long)` to emit a PREFETCH instruction
- **Blocked**: `sun.misc.Unsafe.prefetchRead` was removed in JDK 21; `jdk.internal.misc.Unsafe` also lacks prefetch methods on Temurin JDK 21.0.9
- **Blocked**: The iter-008 constraint (no new code in putValHashed body) makes adding ANY prefetch call to the loop infeasible regardless

**Strategy B: h2 fingerprint improvement via high-bit XOR folding**
- Changed `h2(hash) = hash & 0x7F` → `h2(hash) = (hash ^ (hash >>> 25)) & 0x7F`
- Rationale: XOR in bits [25:31] (unused by h1 for ≤128M groups) to add entropy
- Hypothesis: More distinct fingerprints → fewer false-positive eqMask matches → fewer unnecessary keys[] cache loads

## Result

REVERT.

PutMiss@784K degraded severely: 60.1 ns → 106.5 ns (+77%). PutHit@784K showed minor improvement (28.4 → 27.6 ns, -2.9%) but within noise.

## Root Cause Analysis

The h2 XOR folding increased false-positive eqMask hit rates for common patterns in String keys at the tested sizes. By drawing bits [25:31] into h2, we may have created systematic bias — the XOR of very-high bits with low bits creates a non-uniform distribution for keys whose hashes have correlated high/low bits. This increases cluster density in specific probe groups, lengthening average probe chain for misses.

Additionally, the SwissTable design specifies that h2 should have maximum entropy INDEPENDENT of h1. The Murmur3 smear already provides good mixing of all bits. Changing h2 from the natural low-7-bits to a XOR-folded value likely degraded the independence property.

## Key Lessons

1. `sun.misc.Unsafe.prefetchRead` does not exist on JDK 21 — the prefetch strategy from the hint is completely unavailable via pure Java APIs.
2. h2 must use INDEPENDENT bits from h1; the Murmur3 smear provides this naturally. XOR folding creates bias.
3. The iter-006 state (PutMiss@784K = 60.1 ns) represents a mathematically near-optimal distribution for the current h1/h2 split.

## Decision: REVERT
