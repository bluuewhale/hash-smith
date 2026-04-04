# Summary: iter-004-swar-match-shortcut

## Strategy
SWAR match shortcut in `findIndexHashed`: compute `eqMask` first; only compute `emptyMask`
when `eqMask == 0`. Saves one SWAR multiply on the dominant first-probe hit path.

## Measured Results (ns/op, lower is better)

| Metric      | Baseline | iter-003 | iter-004 | vs Baseline | vs iter-003 |
|-------------|----------|----------|----------|-------------|-------------|
| GetHit@12K  | 5.59     | 4.95     | 4.402    | -21.2%      | -11.1%      |
| GetHit@784K | 17.98    | 15.76    | 13.056   | -27.4%      | -17.2%      |
| GetMiss@12K | 5.84     | 5.01     | 4.750    | -18.7%      | -5.2%       |
| GetMiss@784K| 16.51    | 14.28    | 13.058   | -20.9%      | -8.6%       |
| PutHit@12K  | 8.09     | 6.70     | 6.428    | -20.5%      | -4.1%       |
| PutHit@784K | 30.59    | 23.85    | 21.602   | -29.4%      | -9.4%       |
| PutMiss@12K | 23.70    | 16.97    | 17.601   | -25.7%      | +3.7%       |
| PutMiss@784K| 109.84   | 69.91    | 79.756   | -27.4%      | +14.1%*     |

*PutMiss@784K error is ±198.8 ns/op — result is noise-dominated; mean not reliable.

## Decision: KEEP

All metrics show improvement ≥10% vs baseline. GetHit and GetMiss are strong wins.
PutMiss@784K shows apparent regression vs iter-003 but error bar (±198.8) makes the mean
unreliable — both iter-003 (±99.7) and iter-004 (±198.8) have extreme variance on this metric.
No metric regressed >10% vs baseline (PutMiss@784K is still -27.4% better than baseline).

## Notes
- GetHit@12K: best result ever (-21.2% vs baseline, -11.1% vs iter-003)
- GetHit@784K: best result ever (-27.4% vs baseline, -17.2% vs iter-003)
- PutMiss@784K variance is a persistent benchmarking concern; needs more forks
