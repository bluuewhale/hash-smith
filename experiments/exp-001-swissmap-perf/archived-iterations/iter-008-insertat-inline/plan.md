# Plan: iter-008-insertat-inline

## Hypothesis

Inlining `insertAt()` directly into the `tombstones==0` fast path of `putValHashed` eliminates:
1. The method call overhead (stack frame, register save/restore)
2. The dead `isDeleted(ctrlAt(ctrl, idx))` check — always false when `tombstones==0`
3. The `ctrlAt()` memory read that is never acted upon

## Step-Back Analysis

The `insertAt` method currently has:
```java
private V insertAt(int idx, K key, V value, byte h2) {
    if (isDeleted(ctrlAt(ctrl, idx))) tombstones--;  // dead on fast path
    setEntryAt(idx, key, value);
    setCtrlAt(ctrl, idx, h2);
    size++;
    return null;
}
```

On the `tombstones==0` fast path, this is called with a slot guaranteed to be EMPTY (not DELETED).
The `isDeleted` check is provably dead — but the JIT cannot know this without inlining.

## CoT Reasoning

**Why JIT may not inline this automatically:**
- `insertAt` is called from both the fast path (tombstones==0) and slow path (tombstones>0)
- The JIT sees two callers; it may choose to not inline if the call site is not "hot enough" relative to other callers
- Even if JIT does inline, it needs profile data to eliminate the tombstone branch; inlining manually makes it unconditional

**Why iter-005 (method extraction) failed but this should work:**
- iter-005 split putValHashed into two separate methods → JIT couldn't optimize across call boundaries
- This iter inlines code INTO putValHashed → reduces call boundaries, not increases them

**Why this is safer than findIndexHashed changes:**
- We're only touching the `tombstones==0` fast path exit point
- `findIndexHashed` is untouched (confirmed off-limits per prior lesson)
- Slow path retains the original `insertAt(target, ...)` call (tombstone slots ARE possible there)

## Changes

**In `putValHashed`, fast path only:**
Replace:
```java
return insertAt(idx, key, value, h2);
```
With:
```java
// Inlined insertAt: tombstones==0 guarantees slot is EMPTY, not DELETED.
// Dead isDeleted check eliminated; no method call boundary.
setEntryAt(idx, key, value);  // uses local `keys`/`vals` snapshots
setCtrlAt(ctrl, idx, h2);
size++;
return null;
```

Wait — `setEntryAt` uses `this.keys`/`this.vals` fields, not local snapshots. Need to verify.
Looking at the code: `setEntryAt(int idx, K key, V value)` sets `keys[idx]` and `vals[idx]`
where `keys` and `vals` are instance fields.

However, in `putValHashed`, we have local snapshots:
```java
Object[] keys = this.keys;
Object[] vals = this.vals;
```

For consistency with how the rest of the fast path uses these local snapshots,
we should inline the write to the local arrays directly:
```java
keys[idx] = key;
vals[idx] = value;
setCtrlAt(ctrl, idx, h2);
size++;
return null;
```

This uses the existing local `keys`/`vals` snapshots, which is exactly what `setEntryAt` does via fields.
Since non-concurrent path holds no lock but the local snapshots ARE the same reference as fields,
this is safe. (The snapshots are captured before the loop, and no rehash can occur mid-loop because
`maybeRehash()` is called before `putValHashed`.)

## Pre-mortem

**Risk 1: Correctness bug — do we write to the right arrays?**
→ The local `keys`/`vals` are captured at the start of `putValHashed` and ARE the current arrays
  (rehash happens in `maybeRehash()` before entry). Safe.

**Risk 2: Does inlining hurt slow-path JIT compilation?**
→ Slow path retains `insertAt` call — no change there. Fast path is a separate branch.

**Risk 3: Code size budget for JIT inlining of putValHashed itself?**
→ Adding ~4 lines to an already-large method. JIT has inline size limits.
  Mitigation: The eliminated method call + branch likely reduces bytecode net.

**Risk 4: No improvement (JIT was already inlining insertAt)**
→ If the JIT already inlined insertAt and eliminated the dead branch via PGO, we get nothing.
  But manual inlining at minimum locks in the dead-branch elimination regardless of JIT behavior.

## Success Criteria

- Any metric 10%+ improvement → Keep
- No metric >10% worse than baseline → (baseline = 7.921 / 28.689 / 18.443 / 112.932)
