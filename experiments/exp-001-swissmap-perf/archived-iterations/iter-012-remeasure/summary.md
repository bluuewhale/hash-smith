# Summary: iter-012-remeasure

## Strategy
Two-phase iteration:
1. Re-measured iter-011 state (no code change) to verify PutMiss@784K noise.
2. Applied upper-bit h2 optimization: `h2 = hash >>> 25` (top 7 bits) instead of `h2 = hash & 0x7F` (bottom 7 bits), with `h1 = hash` (full value, let call-site mask do the work).

## Re-measurement (iter-011 state)
`swissSimdPutMiss@784K = 103.965 ns ± 528.115 ns` — extremely noisy, inconclusive.
Environment is consistently noisy at 784K; no regression confirmed or denied.

## Result (after upper-bit h2 change)
| Metric | Baseline | iter-011 | iter-012 | Δ vs baseline | Δ vs iter-011 |
|--------|----------|----------|----------|---------------|---------------|
| PutHit@12K | 7.921 | 5.954 | 6.978 ± 13.576 | -11.9% | +17.2% (noisy) |
| PutHit@784K | 28.689 | 26.435 | 31.026 ± 28.607 | **+8.1% ❌** | +17.4% (noisy) |
| PutMiss@12K | 18.443 | 17.006 | 19.985 ± 71.998 | +8.4% ❌ | +17.5% (noisy) |
| PutMiss@784K | 112.932 | 71.225 | 90.019 ± 340.969 | -20.3% | +26.4% (noisy) |

## Decision: REVERT
- PutHit@784K shows +8.1% regression vs baseline (approaching 10% threshold)
- PutMiss@12K shows +8.4% regression vs baseline
- No metric improved vs iter-011
- Reverted via `git revert HEAD`

## Why It Failed
Using top 7 bits of Fibonacci hash for h2 theoretically has better avalanche quality,
but the h1 path changed too: `h1 = hash` (no shift) means the group index bits now
OVERLAP with h2 bits (31..25 are in h1 AND in h2). This coupling between h1 and h2
increases collision probability — keys that map to the same group also share the same
h2 fingerprint more often, defeating the purpose of the fingerprint filter.

The original `h1 = hash >>> 7` and `h2 = hash & 0x7F` assigns NON-OVERLAPPING bit
regions — h2 uses bits 6..0 and h1 uses bits 31..7. They are independent.
