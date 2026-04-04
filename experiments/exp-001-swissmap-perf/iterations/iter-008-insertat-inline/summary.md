# Summary: iter-008-insertat-inline

## What was tried

Inlined `insertAt()` body directly into the `tombstones==0` fast path of `putValHashed`.
The goal was to eliminate:
1. Method call overhead (stack frame, register save/restore)
2. Dead `isDeleted(ctrlAt(ctrl, idx))` check — always false when `tombstones==0`
3. Redundant `ctrlAt()` memory read on the fast path

## Result

| Metric | Baseline | iter-006 | iter-008 | vs Baseline | vs iter-006 |
|---|---|---|---|---|---|
| PutHit@12K | 7.921 | 6.048 | 5.947 | -24.9% | -1.7% |
| PutHit@784K | 28.689 | 28.365 | 28.403 | -1.0% | +0.1% |
| PutMiss@12K | 18.443 | 17.692 | 18.161 | -1.5% | +2.7% |
| PutMiss@784K | 112.932 | 60.132 | 117.143 | +3.7% | **+94.8%** |

## Decision: REVERT

PutHit@12K marginally improved (-1.7% vs iter-006), but PutMiss@784K catastrophically
regressed from 60.132 → 117.143 ns, completely undoing iter-006's biggest gain.

## Root Cause Hypothesis

The inlining increased bytecode size of `putValHashed`. JIT C2 compiler has an inline
budget for hot methods. When `putValHashed` grows larger, the JIT may:
1. Stop inlining `putValHashed` at its call sites, or
2. Spend less optimization effort on the slow path (PutMiss@784K hits the slow path
   when resizing/rehashing; the 784K size triggers frequent rehash cycles)

The most likely cause: the inlined fast path changed register allocation pressure for
the slow-path branch in the same compiled method, degrading the slow path's PutMiss
performance at the 784K working set size.
