# Summary: iter-005-coldpath-split

## Strategy

Separate the miss-path logic (tombstone scan + `insertAt`) from `putValHashed` into a cold
private method `putMiss()`. The intent was to reduce the number of live variables in the hot
probe loop, allowing the JIT better register allocation and a tighter instruction footprint
for the hit path.

## Code Change

- Removed `firstTombstone` variable and `DELETED_BROADCAST` scan from the main probe loop.
- When empty slot is found (`emptyBits != 0`), delegate to new `putMiss()` method.
- `putMiss()` re-probes from the start to find the first tombstone if `tombstones > 0`.

## Results vs Baseline

| Metric          | Baseline   | iter-003   | iter-005   | vs baseline | vs iter-003 |
|-----------------|-----------|-----------|-----------|-------------|-------------|
| PutHit@12K      | 7.921 ns  | 6.011 ns  | 9.223 ns  | **+16.4% ❌** | +53.4%     |
| PutHit@784K     | 28.689 ns | 29.016 ns | 32.070 ns | **+11.8% ❌** | +10.5%     |
| PutMiss@12K     | 18.443 ns | 17.514 ns | 18.657 ns | +1.2%       | +6.5%       |
| PutMiss@784K    | 112.932 ns| 102.219 ns| 76.356 ns | -32.4% ✅   | -25.3%      |

## Decision: REVERT

Two metrics exceeded the 10% regression threshold vs baseline:
- PutHit@12K: +16.4% (threshold: >10% = revert)
- PutHit@784K: +11.8% (threshold: >10% = revert)

The cold-path split backfired on the hit path. While PutMiss@784K showed -32.4% improvement,
the hit regressions are unacceptable.

## Root Cause Analysis

The strategy assumed the JIT would compile the smaller `putValHashed` hot loop more efficiently.
However, the opposite happened:

1. **iter-003's ILP gain was destroyed**: iter-003's -24% PutHit gain came from hoisting
   `eqMask + hasEmpty` adjacent to exploit OOO CPU pipelining. That gain likely also relied on
   the JIT inlining the entire `putValHashed` body into a tight unit. The `putMiss` call creates
   a new method boundary — even if the JIT inlines `putMiss`, the presence of the call site
   changes the compilation context.

2. **Method call overhead**: Even when JIT inlines `putMiss`, the call-site setup (passing 8
   arguments: key, value, h2, h1, mask, emptyIdx, probeStep, emptyGroup) adds register move
   overhead that negates the register-pressure savings.

3. **Re-probe cost on miss**: `putMiss` re-probes from scratch to find tombstones, doubling
   work for the miss path. Despite this, PutMiss@784K improved — suggesting the hit-path
   register pressure savings were real for the miss benchmark, but the call overhead hit
   PutHit hard.
