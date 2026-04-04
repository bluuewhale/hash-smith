# Plan: iter-006-loop-specialization

## Strategy

Loop specialization: hoist the `tombstones == 0` check ABOVE the probe loop in `putValHashed`
and compile two separate inline probe loops — a no-tombstone fast path and a tombstone slow path.

## Current state of putValHashed (iter-003)

The probe loop currently contains:
```java
int eqM = eqMask(word, h2Broadcast);        // ILP: computed in parallel with...
long emptyBits = hasEmpty(word);             // ...this (iter-003 hoisting)
while (eqM != 0) { ... }
if (firstTombstone < 0 && tombstones > 0) { // iter-001 guard: skip when no tombstones
    int delMask = eqMask(word, DELETED_BROADCAST);
    ...
}
if (emptyBits != 0) { ... }
```

The `DELETED_BROADCAST` branch is guarded but still present in the loop body.
Even with the guard, the JIT must keep `firstTombstone` as a live variable and
emit the conditional branch instruction, creating extra register pressure.

## Proposed transformation

```java
private V putValHashed(K key, V value, int smearedHash) {
    // ... setup: h1, h2, h2Broadcast, ctrl, keys, vals, mask, g, step ...

    if (tombstones == 0) {
        // FAST PATH: no tombstone scan needed
        // Loop body: only eqMask + hasEmpty — DELETED_BROADCAST never referenced
        // firstTombstone variable eliminated entirely from this path
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
                int idx = base + (Long.numberOfTrailingZeros(emptyBits) >>> 3);
                return insertAt(idx, key, value, h2);
            }
            g = (g + (++step)) & mask;
        }
    } else {
        // SLOW PATH: tombstone scan needed
        // Identical to current iter-003 loop (with DELETED_BROADCAST scan)
        int firstTombstone = -1;
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
            if (firstTombstone < 0) {
                int delMask = eqMask(word, DELETED_BROADCAST);
                if (delMask != 0) firstTombstone = base + Integer.numberOfTrailingZeros(delMask);
            }
            if (emptyBits != 0) {
                int idx = base + (Long.numberOfTrailingZeros(emptyBits) >>> 3);
                int target = (firstTombstone >= 0) ? firstTombstone : idx;
                return insertAt(target, key, value, h2);
            }
            g = (g + (++step)) & mask;
        }
    }
}
```

## Why this should work

1. **Eliminates DELETED_BROADCAST from fast path entirely** — no conditional branch, no dead variable
2. **firstTombstone variable removed from fast path** — fewer live variables → fewer registers → JIT can
   allocate `eqM`, `emptyBits`, `base` to better registers
3. **Keeps ILP gain from iter-003** — `eqM` and `emptyBits` remain adjacent in both loops,
   preserving OOO pipeline opportunity
4. **No method call boundary** — both loops are inline within `putValHashed`, so no register
   convention moves (the failure mode of iter-005)
5. **tombstones==0 is 99%+ of real workloads** — hot path optimization has maximum impact

## Risks

- Code duplication (both loops are similar) — acceptable for performance-critical code
- Slow path is unchanged in semantics, just restructured
- JIT may not produce a tighter fast path despite our expectations (empirical test needed)

## Files to modify

- `src/main/java/io/github/bluuewhale/hashsmith/SwissMap.java` — `putValHashed` method only
