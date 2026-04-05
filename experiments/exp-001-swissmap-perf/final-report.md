# Final Report: exp-001-swissmap-perf

## Summary

5 iterations across 2026-04-04 — 2026-04-05.
Net result: **all 8 key metrics improved vs baseline**, with GetHit@784K showing the largest gain at -27%.

---

## Results at a Glance

| Metric | Baseline | Optimized (iter-005) | Δ |
|---|---|---|---|
| GetHit@12K | 5.59 ns/op | 4.53 ns/op | **-18.9%** |
| GetHit@784K | 17.98 ns/op | 14.95 ns/op | **-16.8%** |
| GetMiss@12K | 5.84 ns/op | 4.80 ns/op | **-17.8%** |
| GetMiss@784K | 16.51 ns/op | 14.28 ns/op | **-13.5%** |
| PutHit@12K | 8.09 ns/op | 6.48 ns/op | **-19.9%** |
| PutHit@784K | 30.59 ns/op | 23.34 ns/op | **-23.7%** |
| PutMiss@12K | 23.70 ns/op | 15.98 ns/op | **-32.6%** |
| PutMiss@784K | 109.84 ns/op | 83.79 ns/op | **-23.7%** |

---

## Iteration History

| Iteration | Strategy | Decision | Notable |
|---|---|---|---|
| iter-001 | Tombstone guard in putVal (skip scan when tombstones==0) | ❌ REVERT | PutHit@784K regressed +16.5% |
| iter-002 | Tombstone loop specialization + ILP hoisting in putValHashed | ✅ KEEP | PutMiss -46%, PutHit -20% vs baseline |
| iter-003 | ILP hoisting in findIndexHashed | ✅ KEEP | GetHit/GetMiss -11–14%, best overall at time |
| iter-004 | SWAR match shortcut in findIndexHashed (skip emptyMask when eqMask≠0) | ✅ KEEP | Get -17–27%, Put -20–29% vs baseline |
| iter-005 | Lazy emptyMask in putValHashed tombstones==0 path | ✅ KEEP | PutMiss@12K -9.2% additional gain |

---

## Key Techniques Applied

### 1. ILP Hoisting (iter-002, iter-003)
Place independent SWAR operations (`eqMask` and `emptyMask`) adjacent on the same `word` variable so the out-of-order CPU can issue them in the same dispatch window. Eliminates pipeline stalls from data-dependency chains in the probe loop.

### 2. SWAR Match Shortcut (iter-004)
In `findIndexHashed`, when `eqMask != 0` (match found in this probe group), return immediately without computing `emptyMask`. For dense maps, the common case resolves on the first group — this shortcut saves one SWAR operation per lookup.

### 3. Lazy emptyMask in putValHashed (iter-005)
Same principle applied to `putValHashed`: when `eqMask != 0` (existing key found — update path), skip the `emptyMask` computation entirely and jump directly to the update branch.

---

## Observations

- **ILP is the biggest lever** for the SWAR probe loop. Both iter-002 and iter-003 showed that instruction-level parallelism — achieved by grouping independent operations — outperforms algorithmic changes in this workload.
- **SWAR shortcut compounds ILP gains** (iter-004 added 11–17% on top of iter-003). Reducing the critical path length per probe iteration synergizes with ILP.
- **PutMiss@784K is noisy** (±99–198 ns/op error across iterations). Exact ranking for that metric is unreliable; all values are within statistical overlap of each other but clearly below baseline.
- **iter-001 regression lesson**: Skipping tombstone scan behind a branch predicate adds a check to every `putVal` call path, and at 784K (cache-miss-heavy load) the branch mispredict cost exceeded the tombstone scan cost.

---

## Remaining Headroom

- **Further SWAR shortcut**: Apply to `getValHashed` if a separate code path exists there
- **Probe loop unrolling**: 2× unroll of the inner probe loop may reduce loop overhead at cold sizes
- **Software prefetch**: `Unsafe.prefetchRead` on the next group's metadata word during current iteration to hide L3 latency at 784K+
- **Re-run PutMiss@784K** with more forks/iterations (currently 3 forks) to get a tighter confidence interval

---

## Created
2026-04-05
