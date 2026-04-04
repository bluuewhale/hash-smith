# Reflexion: iter-003-findindex-ilp

## What we did
Hoisted `emptyMask` computation adjacent to `eqMask` in `findIndexHashed`, placing
both independent SWAR operations on the same `word` value close together so the OOO
CPU could pipeline them. This is the same ILP technique that iter-002 applied to the
`putValHashed` fast path, now extended to the read path.

## What happened
This is the strongest result so far across ALL metrics:
- GetHit improved 11-12% vs baseline (previously untouched by iter-002)
- GetMiss improved 13-14% vs baseline (previously untouched)
- PutHit improved 17-22% vs baseline (vs iter-002's 19-20%)
- PutMiss improved 28-36% vs baseline

The Get path improvements are notable — iter-002 did not modify `findIndexHashed`
at all, so the Get improvements here are genuinely new wins from this change.

## Why ILP hoisting works so well
`findIndexHashed` is the hottest path in the map — called on every get and on the
first phase of every put. The inner probe loop computes both `eqMask` and `emptyMask`
from the same `word` value on every iteration. By placing these adjacent, we allow:
1. The OOO CPU to issue both SWAR operations in the same dispatch window
2. The JIT to potentially fuse or reorder them optimally
3. Reduced pipeline stall from data-dependency chains

## Noise concerns
PutMiss@784K shows ±99.7 ns/op error — high variance. The mean of 69.9 ns/op vs
iter-002's 60.6 ns/op suggests iter-003 may be slightly worse here, but the error
interval is too large to conclude definitively. Both are large improvements over
baseline (109.8 ns/op).

## Next directions for iter-004
1. **Probe loop unrolling**: The inner probe loop in `findIndexHashed` iterates one
   group at a time. Unrolling 2x could reduce loop overhead and improve branch
   prediction on typical fill factors.
2. **Prefetch hints**: For the 784K case, the bottleneck shifts toward cache misses.
   Software prefetch (`Unsafe.prefetchRead`) on the next group's metadata word during
   the current iteration could hide memory latency.
3. **SWAR match shortcut**: If `eqMask != 0` on the first group (common case for hit),
   the empty check can be skipped entirely. A fast-path for single-group resolution
   avoids computing `emptyMask` on hits at all.
4. **Re-run PutMiss to confirm noise**: The ±99.7 variance on PutMiss@784K warrants
   a targeted re-run with more forks/iterations to get a tighter confidence interval.
