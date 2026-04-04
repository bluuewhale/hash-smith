# Plan: iter-005-coldpath-split

## Context

iter-003 achieved PutHit@12K -24% by hoisting independent SWAR ops (eqMask + hasEmpty) adjacently
in the probe loop, enabling OOO CPU pipeline parallelism.

iter-004 tried the same ILP hoisting in `findIndexHashed` and regressed PutHit +16%.
Hypothesis: register pressure in `findIndexHashed` (it returns int and is called from more paths)
caused the JIT to spill registers in the wider context, negating the ILP gain.

## Strategy: Cold-Path Separation in putValHashed

### Problem

In the current `putValHashed` probe loop, all of the following are in the hot loop body:
1. `eqMask(word, h2Broadcast)` — hit detection (HOT)
2. `hasEmpty(word)` — empty slot detection (HOT for miss path)
3. `firstTombstone` tracking with `eqMask(word, DELETED_BROADCAST)` — only relevant when tombstones > 0
4. `insertAt(target, key, value, h2)` — the miss path insertion call
5. The tombstone/empty slot `target` selection branch

The JIT must keep all these variables live simultaneously in the probe loop, increasing register
pressure and potentially preventing ideal register allocation for the hit path.

### Optimization

Extract the miss-path insertion logic from the probe loop into a separate private method
`putMiss(key, value, h2, h1, mask, ctrl, keys, vals)`. The hot probe loop in `putValHashed`
becomes purely a hit-check loop that only handles the key-found case. When it determines
a miss is needed (emptyBits != 0 after exhausting eqMask hits), it delegates to the cold method.

This gives the JIT a smaller, tighter hot loop with fewer live variables:
- Hot loop needs only: `word`, `base`, `eqM`, `emptyBits`, `g`, `step`, `h2Broadcast`
- Cold path (tombstone tracking + insert) is in a separate compilation unit

### Code Change Sketch

```java
private V putValHashed(K key, V value, int smearedHash) {
    int h1 = h1(smearedHash);
    byte h2 = h2(smearedHash);
    long h2Broadcast = broadcast(h2);
    long[] ctrl = this.ctrl;
    Object[] keys = this.keys;
    Object[] vals = this.vals;
    int mask = ctrl.length - 1;
    int g = h1 & mask;
    int step = 0;
    for (;;) {
        long word = ctrl[g];
        int base = g << 3;
        int eqM = eqMask(word, h2Broadcast);
        long emptyBits = hasEmpty(word);
        while (eqM != 0) {
            int idx = base + Integer.numberOfTrailingZeros(eqM);
            Object k = keys[idx];
            if (k == key || k.equals(key)) {
                V old = castValue(vals[idx]);
                vals[idx] = value;
                return old;
            }
            eqM &= eqM - 1;
        }
        if (emptyBits != 0) {
            int emptyIdx = base + (Long.numberOfTrailingZeros(emptyBits) >>> 3);
            return putMiss(key, value, h2, h1, mask, emptyIdx, step, g);
        }
        g = (g + (++step)) & mask;
    }
}

// Cold path: only reached on miss; separate method keeps hot loop compact.
private V putMiss(K key, V value, byte h2, int h1, int mask, int emptyIdx, int probeStep, int emptyGroup) {
    // Re-probe from start to find firstTombstone if needed
    long[] ctrl = this.ctrl;
    int target = emptyIdx;
    if (tombstones > 0) {
        int g = h1 & mask;
        int step = 0;
        for (;;) {
            long word = ctrl[g];
            int base = g << 3;
            int delMask = eqMask(word, DELETED_BROADCAST);
            if (delMask != 0) {
                target = base + Integer.numberOfTrailingZeros(delMask);
                break;
            }
            if (g == emptyGroup && step == probeStep) break; // reached empty group, no tombstone
            g = (g + (++step)) & mask;
        }
    }
    return insertAt(target, key, value, h2);
}
```

### Expected Effect

- The hot probe loop in `putValHashed` is reduced to ~7 variables (no `firstTombstone` int,
  no `DELETED_BROADCAST`, no `tombstones > 0` branch in hot path).
- JIT can allocate registers more freely for the frequently-executed hit loop.
- The separate `putMiss` cold method is inlined separately or not at all — either way, it
  does not pollute the hot-path register file.
- PutHit performance should be maintained or improved (less register pressure = better JIT codegen).
- PutMiss may be slightly slower due to re-probe overhead, but miss path is already cold relative
  to hit path in a map used primarily for lookup.

### Risk

- Re-probing in putMiss adds extra work on miss. If PutMiss@12K or PutMiss@784K regresses >10%
  vs baseline, we revert.
- If PutHit does not improve significantly (stays same as iter-003), this is still a valid Keep
  as long as no regression occurs.

## Success Criteria

- Keep: any metric 10%+ better than baseline, and NO metric >10% worse than baseline.
- Revert: any metric >10% worse than baseline.
