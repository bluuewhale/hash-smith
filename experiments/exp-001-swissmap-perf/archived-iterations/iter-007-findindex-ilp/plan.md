# Plan: iter-007-findindex-ilp

## Hypothesis

`findIndexHashed` computes `eqMask(word, h2Broadcast)` and `hasEmpty(word)` sequentially.
These two SWAR operations are INDEPENDENT — both read `word`, neither depends on the other.
By hoisting `hasEmpty(word)` to be adjacent to `eqMask(word, h2Broadcast)`, the OOO CPU
can issue both SWAR computations in a single pipeline cycle.

This is the same ILP hoisting that iter-003 applied to `putValHashed`, which achieved -24%
on PutHit@12K. `findIndexHashed` is the hot path for `get()` / `containsKey()` / `put()` hit
lookups, so any improvement here propagates directly to PutHit benchmarks.

## Why iter-004 failed but iter-007 should succeed

iter-004 failed because it added `emptyBits` as a live variable that was stored for use in
the NEXT loop iteration. That created register pressure across iterations → spills.

iter-007's `hasEmpty` result is consumed IMMEDIATELY in the same iteration (as an early-exit
condition). It is NOT carried across iterations. Therefore:
- No new cross-iteration live variable
- No register pressure increase across iteration boundaries
- Pure instruction-level parallelism within one iteration

## Change

In `findIndexHashed`, reorder the probe loop body:

BEFORE:
```java
long word = ctrl[g];
int base = g << 3;
int eqMask = eqMask(word, h2Broadcast);
while (eqMask != 0) { ... }
if (hasEmpty(word) != 0) return -1;
g = (g + (++step)) & mask;
```

AFTER:
```java
long word = ctrl[g];
int base = g << 3;
int eqM = eqMask(word, h2Broadcast);        // SWAR compare — issued cycle N
long emptyBits = hasEmpty(word);             // SWAR empty — issued cycle N (independent)
while (eqM != 0) { ... }
if (emptyBits != 0) return -1;
g = (g + (++step)) & mask;
```

## Expected Impact

- PutHit@12K: potential -5% to -15% (get path includes findIndexHashed on key presence check)
- PutHit@784K: potential improvement (cache-cold, more probe iterations = more ILP opportunities)
- PutMiss@12K / PutMiss@784K: may be unaffected (miss path exercises putValHashed more)

## Risk Assessment

LOW risk:
- Semantically identical — same operations, same order of side effects
- No new variables carried across iterations
- Worst case: JIT already reorders these; no measurable change
- Unlikely regression: we are not adding computation, only reordering
