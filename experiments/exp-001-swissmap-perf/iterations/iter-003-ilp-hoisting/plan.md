# Plan: iter-003-ilp-hoisting

## Step-Back

The PutHit regression (+17%) from iter-002 is the main blocker. iter-002 restructured the loop to
compute hasEmpty via the multiply-free SWAR path (hasEmpty) after the tombstone check. The PutMiss
gain was preserved because hasEmpty is cheaper and avoids the old EMPTY_BROADCAST eqMask call.

However, PutHit degraded. The root cause identified in iter-002 reflexion: hasEmpty is now always
computed in every loop iteration, adding pressure to the hit path. Even though hasEmpty's result is
discarded on a hit (return happens inside the eqMask while loop), the JIT may reorder or the
processor may not speculate optimally.

## Step-Back Insight

Two independent SWAR computations exist per loop iteration:
  1. `eqMask(word, h2Broadcast)` — finds key matches
  2. `hasEmpty(word)` — finds empty slots for insertion

These are data-independent: both take `word` as input but produce independent outputs.
A modern OOO CPU can pipeline these 2-4 instruction sequences simultaneously.

The current layout (iter-002):
  eqMask computed → check eqMask while loop → tombstone check → hasEmpty computed

Problem: `hasEmpty` is far from `eqMask` in instruction stream. The CPU cannot start hasEmpty
until eqMask's branch resolves. This serializes independent work.

## CoT Strategy: ILP Hoisting

Hoist `hasEmpty(word)` to be computed immediately after `eqMask(word, h2Broadcast)`, before
entering the eqMask while-loop. Both computations only depend on `word` (loaded once), so the
CPU can issue both instruction sequences simultaneously:

```
long word = ctrl[g];
int eqM = eqMask(word, h2Broadcast);      // issue 1
long emptyBits = hasEmpty(word);           // issue 2 — OOO can run in parallel with eqM
while (eqM != 0) { ... key check ... }    // uses eqM result
if (firstTombstone < 0 && tombstones > 0) { ... delMask check ... }
if (emptyBits != 0) { ... insert ... }    // uses emptyBits result — already computed
```

Effect on hit path: emptyBits is computed but immediately discarded when the key is found.
The extra latency for hasEmpty (4 ops: XOR, SUB, AND, AND) is absorbed by OOO parallelism
with eqMask (5 ops: XOR, SHR, OR, SUB, AND, MUL, SHR).

Effect on miss path: emptyBits is already computed when needed — no sequential dependency.

## Self-Consistency Check

- Does hoisting hasEmpty help the hit path? Yes: the 4-op hasEmpty runs in parallel with the
  5-op eqMask chain; on a hit, both complete before the branch resolves, with no extra latency
  added to the critical path.
- Does it preserve PutMiss gains? Yes: hasEmpty result is still available when needed for
  insertion; the multiply-free path from iter-002 is unchanged.
- Does it change correctness? No: emptyBits is only used for insertion, computed from same word.

## Pre-mortem

Risk 1: JIT may not recognize the ILP opportunity and serialize anyway → result: no change.
Risk 2: Register pressure increases slightly (one more live variable) → result: minor overhead.
Risk 3: Tombstone check ordering matters — firstTombstone must still be found before insertion
  point selection → unchanged by this refactor.

## Implementation

Modify `putValHashed` only. Move `hasEmpty(word)` computation from line 453 to immediately
after `eqMask` computation on line 436.

Expected outcome: PutHit@12K recovers toward baseline (ideally <8.7 ns, i.e., within 10%),
PutMiss@784K remains near 67.8 ns.
