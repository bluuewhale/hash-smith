# Summary: iter-004-findindex-ilp

## Strategy
Applied ILP hoisting to `findIndexHashed` — same pattern as iter-003 applied to `putValHashed`.
Hoisted `hasEmpty(word)` computation adjacent to `eqMask(word, h2Broadcast)` so OOO CPU
can issue both independent SWAR operations in parallel.

## Results
| Metric | Baseline | iter-003 | iter-004 | delta vs baseline |
|--------|----------|----------|----------|-------------------|
| PutHit@12K | 7.921 | 6.011 | 9.222 | **+16.4% REGRESSION** |
| PutHit@784K | 28.689 | 29.016 | 31.209 | +8.8% |
| PutMiss@12K | 18.443 | 17.514 | 16.768 | -9.1% |
| PutMiss@784K | 112.932 | 102.219 | 76.922* | -31.9% |

*PutMiss@784K ±83.168 ns (108% noise — unreliable)

## Decision: REVERT

PutHit@12K regressed from baseline 7.921 to 9.222 (+16.4%), violating the trade-off constraint
(>10% degradation on any metric triggers revert). The protected gain from iter-003 (6.011 ns)
was completely destroyed.

## Why It Failed
ILP hoisting in `findIndexHashed` introduced an extra live `long emptyBits` variable across the
inner `eqMask` while-loop. This creates register pressure in the JIT-compiled code. The JIT
likely needs to spill/reload a register in the hot put-hit path because `putValHashed` calls
`findIndexHashed` indirectly via the miss-check on update paths — or more likely, the JIT's
inline and scheduling decisions changed when `findIndexHashed` bytecode changed, disrupting the
carefully-tuned compilation that iter-003 achieved in `putValHashed`.

Key difference from iter-003: `putValHashed` has a long probe loop with tombstone logic where
the extra variable has space in register budget. `findIndexHashed` is tighter — shorter loop,
fewer variables — and adding `long emptyBits` may push it over the register allocation threshold.

## Reverted
The commit was reverted. iter-003 state restored.
