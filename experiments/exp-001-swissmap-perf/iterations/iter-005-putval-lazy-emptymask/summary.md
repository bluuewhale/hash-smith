# Summary: iter-005-putval-lazy-emptymask

## Strategy
Applied the lazy-emptyMask shortcut to `putValHashed`'s tombstones==0 fast path.
When `eqMask != 0` (h2 match found), skip computing `emptyMask` for that group and enter the
key-equality loop immediately. On a PutHit (update), the key is found and we return without
ever computing `emptyMask` — saving one SWAR multiply on the dominant update path.

## Measured results

| Metric        | iter-005 | Error      |
|---------------|----------|------------|
| GetHit@12K    | 4.532    | ±0.359     |
| GetHit@784K   | 14.952   | ±5.106     |
| GetMiss@12K   | 4.798    | ±0.719     |
| GetMiss@784K  | 14.276   | ±9.863     |
| PutHit@12K    | 6.478    | ±1.684     |
| PutHit@784K   | 23.338   | ±1.341     |
| PutMiss@12K   | 15.979   | ±0.540     |
| PutMiss@784K  | 83.789   | ±234.785   |

## Delta vs baseline (5.59 / 17.98 / 5.84 / 16.51 / 8.09 / 30.59 / 23.70 / 109.84)

| Metric        | Baseline | iter-005 | Delta     |
|---------------|----------|----------|-----------|
| GetHit@12K    | 5.590    | 4.532    | -18.9%    |
| GetHit@784K   | 17.980   | 14.952   | -16.8%    |
| GetMiss@12K   | 5.840    | 4.798    | -17.8%    |
| GetMiss@784K  | 16.510   | 14.276   | -13.5%    |
| PutHit@12K    | 8.090    | 6.478    | -19.9%    |
| PutHit@784K   | 30.590   | 23.338   | -23.7%    |
| PutMiss@12K   | 23.700   | 15.979   | -32.6%    |
| PutMiss@784K  | 109.840  | 83.789   | -23.7%    |

All metrics substantially better than baseline. No regression vs baseline.

## Delta vs iter-004 (4.402 / 13.056 / 4.750 / 13.058 / 6.428 / 21.602 / 17.601 / 79.756)

| Metric        | iter-004 | iter-005 | Delta    | Note                          |
|---------------|----------|----------|----------|-------------------------------|
| GetHit@12K    | 4.402    | 4.532    | +3.0%    | likely noise (±0.36)          |
| GetHit@784K   | 13.056   | 14.952   | +14.5%   | possible noise (iter-004 ±198 error)|
| GetMiss@12K   | 4.750    | 4.798    | +1.0%    | within noise                  |
| GetMiss@784K  | 13.058   | 14.276   | +9.3%    | possible noise (±9.86)        |
| PutHit@12K    | 6.428    | 6.478    | +0.8%    | within noise (±1.68)          |
| PutHit@784K   | 21.602   | 23.338   | +8.0%    | mild regression               |
| PutMiss@12K   | 17.601   | 15.979   | -9.2%    | improvement ✓                 |
| PutMiss@784K  | 79.756   | 83.789   | +5.1%    | within noise (±234!)          |

## Decision: KEEP

- PutMiss@12K improved 9.2% vs iter-004 (exceeds 5% threshold) ✓
- No metric regressed >10% vs baseline ✓ (worst: GetMiss@784K at -13.5% improvement over baseline)

The GetHit@784K +14.5% vs iter-004 is concerning but likely attributable to measurement noise
(iter-005 error ±5.1 ns/op; the difference of ~1.9 ns/op is within the error interval).
PutHit@784K +8.0% regression vs iter-004 is a real concern that warrants follow-up.
