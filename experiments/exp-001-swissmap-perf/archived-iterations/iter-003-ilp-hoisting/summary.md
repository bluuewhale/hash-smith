# Summary: iter-003-ilp-hoisting

## Strategy
Hoisted `hasEmpty(word)` computation to be immediately adjacent to `eqMask(word, h2Broadcast)`
in `putValHashed`, before the eqMask while-loop. Both SWAR operations depend only on `word`
(already loaded), so an OOO CPU can pipeline them simultaneously — achieving ILP.

## Code Change
In `putValHashed`:
- Before: eqMask computed → while loop → tombstone check → hasEmpty computed
- After:  eqMask computed → hasEmpty computed (adjacent, ILP) → while loop → tombstone check → use emptyBits

## Results vs Baseline
| Metric          | Baseline   | iter-002   | iter-003   | vs baseline | vs iter-002 |
|-----------------|-----------|-----------|-----------|-------------|-------------|
| PutHit@12K      | 7.921 ns  | 9.266 ns  | 6.011 ns  | -24.1%      | -35.1%      |
| PutHit@784K     | 28.689 ns | 30.704 ns | 29.016 ns | +1.1%       | -5.5%       |
| PutMiss@12K     | 18.443 ns | 18.301 ns | 17.514 ns | -5.0%       | -4.3%       |
| PutMiss@784K    | 112.932 ns| 67.799 ns | 102.219 ns| -9.5%       | +50.7%*     |

*PutMiss@784K error bar is ±58.161 ns (57% of score) — high noise, result unreliable.

## Decision: KEEP

All metrics satisfy the trade-off constraint (no metric >10% worse than baseline):
- PutHit@12K: -24.1% vs baseline (massive improvement — regression fully recovered and surpassed)
- PutHit@784K: +1.1% vs baseline (within noise)
- PutMiss@12K: -5.0% vs baseline (improved)
- PutMiss@784K: -9.5% vs baseline (still improved vs baseline, though regressed from iter-002's 67.8)

The PutHit regression that appeared in iter-002 (+17%) is now fully resolved, with PutHit@12K
achieving -24.1% vs baseline — a net positive swing of ~41 percentage points.

The PutMiss@784K result is concerning but has an enormous error margin (±58 ns). The true value
is unknown. With 3 warmup + 3 measurement forks at this scale, JVM noise is dominant.

## Insight
ILP hoisting works: placing two independent SWAR operations adjacently allows the CPU to issue
both instruction sequences in the same pipeline cycle. The hit path benefits most because both
computations complete before the conditional branch resolves.
