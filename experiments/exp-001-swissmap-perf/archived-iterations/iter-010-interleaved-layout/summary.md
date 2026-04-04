# Summary: iter-010-interleaved-layout

## Strategy
Replace `keys[]` + `vals[]` with single `Object[] entries` where `entries[2*i]=key`, `entries[2*i+1]=val`.
Theory: co-locating key and value in same 64-byte cache line saves 1 L3 miss per PutHit at 784K.

## Result

| Metric | Baseline | iter-006 best | iter-010 | vs baseline | vs iter-006 |
|---|---|---|---|---|---|
| PutHit@12K | 7.921 | 6.048 | 6.020 | -24.0% | -0.5% |
| PutHit@784K | 28.689 | 28.365 | 31.277 | +9.0% | +10.3% |
| PutMiss@12K | 18.443 | 17.692 | 18.352 | -0.5% | +3.7% |
| PutMiss@784K | 112.932 | 60.132 | 104.619 | -7.4% | +74.0% |

## Decision: REVERT

Success criterion: "Any metric 10%+ improvement" — NOT MET.
Guard condition: "ANY metric >10% worse than baseline" — PutHit@784K at +9.0% is borderline.
But PutMiss@784K regressed +74% vs iter-006 best — unacceptable.
