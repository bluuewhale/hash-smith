# Plan: iter-014 — fast-empty-tombstone0

## Strategy
Replace `hasEmpty(word)` with `word & BITMASK_MSB` in the tombstones==0 fast path of `putValHashed`.

## Correctness Proof
In `tombstones==0` branch:
- Invariant: `tombstones == 0` → no DELETED (0xFE) bytes exist in ctrl array
- FULL slots: h2 in [0, 127] → byte values 0x00–0x7F → bit7 = 0
- EMPTY slots: 0x80 → bit7 = 1
- DELETED slots: 0xFE → bit7 = 1 (NOT present)

Therefore in tombstones==0: `word & BITMASK_MSB` has a 1-bit exactly at each byte position
where the byte is EMPTY (0x80). This is identical to `hasEmpty(word)` output in this context.

The `Long.numberOfTrailingZeros(emptyBits) >>> 3` slot extraction remains valid because:
- hasEmpty() sets bit7 of matching bytes → LSB of result = position of first matching byte's bit7
- `word & BITMASK_MSB` also sets bit7 of matching bytes → same LSB property

## Change
File: `src/main/java/io/github/bluuewhale/hashsmith/SwissMap.java`

In `putValHashed`, tombstones==0 fast path only:
```java
// BEFORE:
long emptyBits = hasEmpty(word); // independent of eqM; CPU issues in parallel

// AFTER:
long emptyBits = word & BITMASK_MSB; // tombstones==0: only EMPTY(0x80) has bit7=1; 1 op vs 3
```

Scope: 1 line change. No structural changes. Slow path (tombstones>0) unchanged.

## Expected Impact
- Reduces per-iteration ALU ops: 3 ops → 1 op for empty detection
- Particularly benefits PutMiss path (must scan multiple groups before finding empty)
- PutHit benefits slightly (empty check per probed group)
- JIT bytecode size of putValHashed decreases slightly → may help JIT optimization

## Risk Assessment
- Low: mathematical invariant is proven above
- DELETED bytes cannot exist when tombstones==0 — this is maintained by the `tombstones` counter
- No structural changes — only a SWAR expression replacement
- Covered by existing test suite (Apache + Google compatibility tests)

## Pre-mortem
What could go wrong?
1. JIT already simplifies hasEmpty to equivalent — change is neutral. Accept: still not a regression.
2. The 1-op expression doesn't express the same semantic clearly — mitigated by comment.
3. tombstones counter could be wrong — but this is tested by extensive test suite.
