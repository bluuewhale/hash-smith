# iter-002 Plan: Fast Empty Detection (hasEmpty SWAR)

## Strategy

Replace the two-SWAR-call pattern per group with a single cheap empty check.

### Current pattern (per group iteration):
1. `eqMask(word, h2Broadcast)` — full SWAR compare for h2 hit
2. (if tombstones>0) `eqMask(word, DELETED_BROADCAST)` — tombstone check
3. `eqMask(word, EMPTY_BROADCAST)` — full SWAR compare to detect any empty slot

Step 3 costs the same as step 1: 5 arithmetic ops + 1 multiply.

### Proposed optimization:
Replace step 3 with a **single-pass hasEmpty check**:

```java
// Standard SWAR "has any byte == 0x80?" trick adapted for EMPTY (0x80):
// Empty bytes have MSB set; full h2 bytes (0..127) have MSB clear.
// So: (word & BITMASK_MSB) isolates empty/deleted/sentinel MSBs.
// But DELETED is 0xFE which also has MSB set. We need to distinguish.
//
// Better: use the standard "hasValue(v)" with v=0x80:
// hasEmpty(word) = (word - BITMASK_LSB) & ~word & BITMASK_MSB
// This is 3 ops instead of the 5+mul in eqMask.
```

The `hasEmpty` check tells us "at least one empty byte exists" without finding which slot.
We still call `eqMask(word, EMPTY_BROADCAST)` only when `hasEmpty` returns true (to find the slot).

For **miss-heavy workloads at 784K**, most groups will have zero empty slots during probe traversal.
Skipping the full eqMask on non-empty groups saves ~4 ops per non-terminal probe group.

### Implementation

```java
// In putValHashed hot loop, replace:
//   int emptyMask = eqMask(word, EMPTY_BROADCAST);
//   if (emptyMask != 0) { ... }
// With:
//   if ((word & BITMASK_MSB) != 0) {
//       int emptyMask = eqMask(word, EMPTY_BROADCAST);
//       if (emptyMask != 0) { ... }
//   }
```

Wait — DELETED (0xFE) also has MSB set, so `(word & BITMASK_MSB) != 0` is true whenever ANY
empty OR deleted byte is present. This means we cannot skip eqMask(EMPTY) if deleted bytes exist.

**Refined approach**: use the true hasEmpty formula:
```java
// hasEmpty: true iff word contains at least one 0x80 byte
// Formula: (word - BITMASK_LSB) & ~word & BITMASK_MSB
// 0x80 - 0x01 = 0x7F, ~0x80 = 0x7F, 0x7F & 0x7F = 0x7F, & MSB = 0
// Wait, that detects zero bytes. For 0x80:
// x = 0x80: (0x80 - 0x01) = 0x7F, ~0x80 & 0xFF = 0x7F, 0x7F & BITMASK_MSB = 0
// Hmm, standard hasZero detects 0x00. Ours is EMPTY=0x80.
//
// XOR with EMPTY_BROADCAST first to convert empty->zero:
// hasEmpty(word) = hasZero(word ^ EMPTY_BROADCAST)
// hasZero(v) = (v - BITMASK_LSB) & ~v & BITMASK_MSB
```

Full formula:
```java
long xored = word ^ EMPTY_BROADCAST;  // EMPTY bytes become 0x00
boolean hasEmpty = (((xored - BITMASK_LSB) & ~xored & BITMASK_MSB)) != 0;
```
This is 4 ops (xor, sub, not, and) vs full eqMask (5 ops + multiply + shift) — saving the multiply.

We apply hasEmpty first; if true, fall through to full `eqMask(word, EMPTY_BROADCAST)` to locate the slot.
If false, skip entirely. For miss probes traversing groups with no empty slots, the multiply is avoided each group.

## Step-Back Analysis

The probe loop bottleneck for PutMiss@784K is: large number of groups visited per miss.
Each group currently runs 2+ expensive SWAR ops. Reducing cost per non-terminal group is high value.

## CoT Reasoning

- iter-001 showed tombstone guard helped 30% — this confirms branch elimination is the win.
- A similar guard around emptyMask computation (but cheaper: saves a multiply) should help miss path.
- PutHit paths are unaffected: on hits, the emptyMask branch is rarely reached.

## Pre-mortem

Risk: JIT may already optimize the multiply away, making hasEmpty check add overhead rather than save.
Mitigation: benchmark will reveal; revert if regression.

## Files to Modify

- `src/main/java/io/github/bluuewhale/hashsmith/SwissMap.java`
  - `putValHashed`: add hasEmpty guard before `eqMask(word, EMPTY_BROADCAST)`
  - `findIndexHashed`: same guard before `eqMask(word, EMPTY_BROADCAST)`
