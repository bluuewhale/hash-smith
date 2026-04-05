# Plan: iter-005-putval-lazy-emptymask

## What change will be applied and where

**Target**: `putValHashed()`, tombstones==0 fast path inner loop (lines ~424-449 in SwissMap.java).

**Current structure**:
```java
if (tombstones == 0) {
    for (;;) {
        long word = ctrl[g];
        int base = g << 3;
        int eqMask = eqMask(word, h2Broadcast);
        int emptyMask = eqMask(word, EMPTY_BROADCAST);  // always computed
        while (eqMask != 0) {
            // key-equality check (update path)
        }
        if (emptyMask != 0) {
            // insert path
        }
        g = (g + (++step)) & mask;
    }
}
```

**Proposed structure** (lazy emptyMask):
```java
if (tombstones == 0) {
    for (;;) {
        long word = ctrl[g];
        int base = g << 3;
        int eqMask = eqMask(word, h2Broadcast);
        if (eqMask != 0) {
            // key-equality check (update path) — may return early
            // only reach here if all candidates failed
        }
        // Only compute emptyMask when eqMask==0 or all candidates failed
        int emptyMask = eqMask(word, EMPTY_BROADCAST);
        if (emptyMask != 0) {
            // insert path
        }
        g = (g + (++step)) & mask;
    }
}
```

The key difference: on a PutHit (update existing key), `eqMask != 0` and we find the key in the inner loop, returning before ever computing `emptyMask`. This saves one SWAR multiply (with a multiply-based hash) on the hot PutHit path, exactly as iter-004 saved it on the GetHit path.

## Expected impact reasoning

- **PutHit**: Primary beneficiary. On every hit, we skip one `eqMask(word, EMPTY_BROADCAST)` SWAR call. For small maps (@12K), almost all PutHit probes resolve on the first group. The saved multiply frees the multiply port earlier. Expected improvement: 3-10%.
- **PutMiss**: No benefit — on a miss, `eqMask == 0` every group, so we always fall through to compute `emptyMask`. Behavior unchanged.
- **GetHit/GetMiss**: Not touched (findIndexHashed already optimized in iter-004).

## Pre-mortem: what could go wrong

1. **Branch misprediction cost exceeds multiply savings**: On a dense map near load factor, many groups may have a high rate of `eqMask != 0`. If the branch predictor has trouble with it, the branch could cost more than the multiply saved. Mitigation: the `eqMask != 0` branch for PutHit is highly biased toward true on the first probe group.

2. **JIT restructuring**: The JIT may already be doing this lazily via dead-code elimination if it can prove `emptyMask` is only used later. However, since `emptyMask` is always computed in the current code before the loop, the JIT cannot eliminate it without alias/control-flow analysis that crosses the `while` loop. Making it explicit should be safer.

3. **PutMiss regression**: If the restructured loop has different branch layout that confuses the branch predictor on the miss path, PutMiss could regress slightly. The `eqMask != 0` check becomes an extra branch on every group of a miss probe chain. Risk: low for short probe chains.

4. **Noise in PutMiss@784K**: This metric has extreme variance (±198 ns/op in iter-004). Any apparent regression there should be treated with skepticism unless it exceeds 2x the error interval.
