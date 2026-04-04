# Plan: iter-004-swar-match-shortcut

## Strategy Selection Reasoning

Three candidates from iter-003 reflexion:
1. **SWAR match shortcut** — skip emptyMask computation when eqMask != 0 on first probe
2. **Probe loop unrolling** — 2x unroll to reduce loop overhead
3. **Prefetch hints** — Unsafe.prefetchRead on next group metadata

### Why SWAR match shortcut wins

The SWAR match shortcut targets the single most common case: **a map hit on the first probe group**.
In a map filled to 87.5% load, the key being looked up is almost always in the first group probed.
When `eqMask != 0`, the current code still computes `emptyMask` (already hoisted in iter-003) even though
that value is unused on the hit path — it's only needed if `eqMask == 0` to decide whether to terminate
or continue probing.

**Current iter-003 flow (every probe):**
1. Load `word` from ctrl
2. Compute `eqMask = eqMask(word, h2Broadcast)` — needed
3. Compute `emptyMask = eqMask(word, EMPTY_BROADCAST)` — needed only if eqMask==0
4. If eqMask != 0: key equality loop (hit found → return idx)
5. If emptyMask != 0: return -1 (miss)
6. Else: advance to next group

**Proposed iter-004 flow:**
1. Load `word` from ctrl
2. Compute `eqMask = eqMask(word, h2Broadcast)`
3. **If eqMask != 0**: inner loop, return on hit — SKIP emptyMask entirely for this group
4. **Else**: compute `emptyMask = eqMask(word, EMPTY_BROADCAST)`
5. If emptyMask != 0: return -1
6. Else: advance to next group

This trades ILP (both ops in parallel) for branch prediction savings on the hot hit path.
The ILP benefit from iter-003 is preserved on miss paths and subsequent probe groups.
On the first-probe hit path (dominant case), we save one full SWAR multiplication.

### Risk assessment
- **Code complexity**: Minimal — one extra `if/else` branch replacing the always-computed emptyMask
- **Correctness**: Same semantics — emptyMask is only evaluated when eqMask == 0
- **Regression risk**: Low — miss path is unchanged; the only change is lazy evaluation of emptyMask
- **JIT interaction**: The branch `eqMask != 0` is highly predictable (biased toward true on hit-heavy benchmarks) — JIT should generate well-predicted branch code

### Expected gain
GetHit benchmarks should benefit most (every lookup hits on first probe for dense maps).
GetMiss may see slight improvement or neutral (miss path still computes emptyMask but with less ILP pressure).
PutHit similarly benefits (findIndexHashed is called first in putValHashed).
PutMiss unlikely to change much (must probe to empty slot regardless).

## Code Change

In `findIndexHashed`, restructure the probe body:

```java
// Before (iter-003)
int eqMask = eqMask(word, h2Broadcast);
int emptyMask = eqMask(word, EMPTY_BROADCAST);
while (eqMask != 0) { ... }
if (emptyMask != 0) return -1;

// After (iter-004)
int eqMask = eqMask(word, h2Broadcast);
if (eqMask != 0) {
    // hot hit path — skip emptyMask entirely
    do { ... } while (eqMask != 0);
} else {
    // cold path — compute emptyMask only when needed
    if (eqMask(word, EMPTY_BROADCAST) != 0) return -1;
}
```
