# Reflexion: iter-005-putval-lazy-emptymask

## What we tried
Applied the lazy-emptyMask shortcut to `putValHashed`'s tombstones==0 fast path, mirroring
what iter-004 did for `findIndexHashed`. When `eqMask != 0` (h2 match exists), we enter the
key-equality loop immediately without computing `emptyMask`. On a PutHit, this means the
`emptyMask` SWAR multiply is never executed for the probe group where the key is found.

The tombstones==0 branch was also restructured from a `while` loop to a `do-while` on the
eq candidates (matching the iter-004 style in `findIndexHashed`), and the `emptyMask` check
is duplicated in both the `eqMask != 0` (fell-through all candidates) and `eqMask == 0`
(no h2 match) branches.

## What happened

| Metric        | iter-004 | iter-005 | Delta vs iter-004 |
|---------------|----------|----------|-------------------|
| GetHit@12K    | 4.402    | 4.532    | +3.0%             |
| GetHit@784K   | 13.056   | 14.952   | +14.5%*           |
| GetMiss@12K   | 4.750    | 4.798    | +1.0%             |
| GetMiss@784K  | 13.058   | 14.276   | +9.3%*            |
| PutHit@12K    | 6.428    | 6.478    | +0.8%             |
| PutHit@784K   | 21.602   | 23.338   | +8.0%             |
| PutMiss@12K   | 17.601   | 15.979   | -9.2%             |
| PutMiss@784K  | 79.756   | 83.789   | +5.1%*            |

*GetHit@784K ±5.1 ns/op, GetMiss@784K ±9.9 ns/op, PutMiss@784K ±234.8 ns/op — high noise.

All metrics remain substantially better than baseline (best: PutMiss@12K -32.6%).

## Why it partially worked / partially didn't

**PutMiss@12K improved -9.2%**: Unexpected win. The restructured loop in the tombstones==0
branch (now `if (eqMask != 0) { do-while } else { emptyMask check }` instead of
`while (eqMask != 0) { ... } if (emptyMask != 0) { ... }`) may have produced better branch
layout for the JIT. For PutMiss, `eqMask` is typically 0 on first probe (no h2 match),
so the `else` branch dominates. The new structure makes that path more predictable.

**PutHit@12K/784K neutral to slightly worse**: The hypothesis was that PutHit would benefit
most from skipping `emptyMask`. Instead, PutHit@12K is within noise (+0.8% ±1.68) and
PutHit@784K shows a mild regression (+8.0% ±1.34 — this one is statistically meaningful
given the tight error bar). The saving of one multiply may be offset by the additional branch
(`if (eqMask != 0)`) that must be predicted correctly, plus the code path through the
`if` branch is now slightly longer (do-while + inner emptyMask check vs the old flat layout).

**GetHit/GetMiss regressions**: These are surprising since `findIndexHashed` was not touched.
GetHit@784K +14.5% (±5.1) and GetMiss@784K +9.3% (±9.9) overlap with error intervals and
are likely measurement noise rather than true regressions. GetHit@12K +3.0% (±0.36) is
slightly outside its error interval — possible micro-regression from JIT code cache pressure
(the new `putValHashed` is slightly larger due to the duplicated `emptyMask` check path).

**Key lesson**: Duplicating the `emptyMask` check in both branches increased code size, which
may have caused I-cache pressure on the large map benchmarks. The iter-004 approach in
`findIndexHashed` avoided this by having a single `emptyMask` call after the do-while loop
and in the `else` branch — same duplication, but `findIndexHashed` is a simpler function.
The `putValHashed` function is larger and already strains inlining budgets.

## Noise assessment
PutMiss@784K (±234 ns/op) is statistically uninterpretable. GetHit/GetMiss@784K have
large enough error intervals that the apparent regressions are inconclusive. The statistically
meaningful numbers are PutHit@784K (+8.0% ±1.3) and PutMiss@12K (-9.2% ±0.5).

## Decision rationale
KEEP: PutMiss@12K improved 9.2% vs iter-004 (exceeds 5% threshold); all metrics still
substantially better than baseline (worst: -13.5% on GetMiss@784K). The PutHit@784K
regression is real but mild and may be addressed by iter-006.

## Next directions for iter-006

1. **Merge the duplicated emptyMask check to reduce code size**: Instead of duplicating
   `emptyMask` in both `if` and `else` branches, compute it once after the `if/else` block.
   This eliminates the code bloat while preserving the lazy-eqMask optimization for the
   return-early PutHit case. Structure would be:
   ```
   if (eqMask != 0) { do-while key check; return on match }
   // fall-through: either eqMask==0 or all candidates failed
   int emptyMask = eqMask(word, EMPTY_BROADCAST);
   if (emptyMask != 0) { insert; return }
   ```
   This removes the `else` branch entirely and reduces code size back toward iter-004 levels.

2. **Probe loop unrolling in findIndexHashed (2x)**: The GetMiss/GetHit regression vs iter-004
   at 784K suggests the miss path may be paying for multi-group probe chains. Unrolling 2x
   probe groups per iteration reduces loop overhead and may stabilize the 784K metrics.

3. **Apply lazy-emptyMask to putValHashedConcurrent**: Same pattern, lower priority since
   the concurrent path is not the primary benchmark target.

4. **Re-measure iter-004 vs iter-005 with more forks**: Run `-f 5 -i 10` on both to get
   tighter confidence intervals and settle whether the GetHit@784K regression is real.
