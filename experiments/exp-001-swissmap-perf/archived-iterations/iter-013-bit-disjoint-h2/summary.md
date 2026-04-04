# Summary: iter-013-bit-disjoint-h2

## Strategy
Bit-disjoint h1/h2 split using non-overlapping bit ranges:
- h2 = `(hash >>> 25) & 0x7F` — bits 31..25 (upper 7 bits, high avalanche quality)
- h1 = `hash & 0x01FFFFFF` — bits 24..0 (lower 25 bits, group index)

This corrects iter-012's h1/h2 overlap flaw. The bit ranges are fully non-overlapping.

## Primary Metrics (lower is better)

| Metric | Baseline | iter-011 (best) | iter-013 | vs Baseline | vs iter-011 |
|--------|----------|-----------------|----------|-------------|-------------|
| PutHit@12K | 7.921 | 5.954 | 7.042 | -11.1% ✓ | +18.3% worse |
| PutHit@784K | 28.689 | 26.435 | 33.587 | **+17.1% ❌** | +27.1% worse |
| PutMiss@12K | 18.443 | 17.006 | 21.012 | **+13.9% ❌** | +23.6% worse |

*PutMiss@784K: 91.350 ± 476.776 ns — unreliable, excluded from decision*

## Decision: REVERT

Two primary metrics regress >10% vs baseline: PutHit@784K (+17.1%) and PutMiss@12K (+13.9%).

## Root Cause Analysis

The bit-disjoint split fixes the false-positive fingerprint issue (h1/h2 overlap),
but introduces a worse problem: **group selection now uses low bits of the hash**.

Current (iter-011): `h1 = hash >>> 7`, so group index = bits [7 + k - 1 .. 7] of hash.
iter-013:           `h1 = hash & 0x1FFFFFF`, so group index = bits [k - 1 .. 0] of hash.

For Fibonacci multiplicative hash (`hash = key.hashCode() * 0x9e3779b9`), the low bits
of the product have weaker distribution than the upper bits (modular arithmetic property).
By using the lowest bits for group selection, we get worse group uniformity — more
collisions, longer probe chains, degraded performance especially at large table sizes
(PutHit@784K +27% worse than iter-011).

## Key Insight
The current design `h1 = hash >>> 7` is superior: it uses **middle bits** of the Fibonacci
hash, which have better distribution than either extreme (lowest bits: weak; highest bits: good
for fingerprint but wasted on group index). The h2 = low 7 bits tradeoff is acceptable because:
1. h2 just needs enough diversity to reduce false positives (7 bits = 128 buckets, ~0.78% false rate)
2. h1 uses the stronger bits for group uniformity, which dominates at large table sizes

## Lesson
For SwissTable with Fibonacci mixing, the optimal bit split is NOT highest bits for h2.
The current `h1 = hash >>> 7`, `h2 = hash & 0x7F` design is well-tuned.
Changing either direction (upper bits for h2, or bit-disjoint via low bits for h1) hurts performance.
