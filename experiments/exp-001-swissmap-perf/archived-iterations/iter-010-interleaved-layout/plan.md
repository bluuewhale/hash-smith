# Plan: iter-010-interleaved-layout

## Hypothesis
Replace separate `keys[]` + `vals[]` arrays with a single `Object[] entries` where
`entries[2*i] = key` and `entries[2*i+1] = val`. Co-locating key and value on the same
cache line saves one L3 miss per PutHit at large table sizes (~784K entries).

## Step-Back → CoT → Self-Consistency → Pre-mortem

### Cache Analysis
- At 784K entries, capacity ≈ 896K slots, each Object ref = 4 bytes (compressed oops).
- keys[] = 3.5MB, vals[] = 3.5MB (separate contiguous blocks).
- On PutHit, access pattern: ctrl[g] → keys[idx] (L3 miss #1) → vals[idx] (L3 miss #2, different 3.5MB region).
- With interleaved: entries[2*idx] (miss #1) → entries[2*idx+1] is adjacent (same 64-byte cache line, FREE).
- Total memory: entries[] = 7MB = same as keys[] + vals[]. No extra GC pressure.

### Bytecode Impact (iter-008 lesson)
- iter-008: adding bytecodes to putValHashed body broke JIT inlining budget → REVERT.
- This change: `keys[idx]` → `entries[idx << 1]` adds 1 ishl, but replaces 2 array fields with 1.
- The local snapshot `Object[] keys = this.keys` and `Object[] vals = this.vals` → single `Object[] entries = this.entries`.
- Net bytecode: reduced by ~6 bytes (one fewer field load, one fewer ASTORE/ALOAD pair in prologue).
- insertAt: `keys[idx] = key; vals[idx] = val` → `entries[idx<<1] = key; entries[idx<<1|1] = val`.
  The idx<<1 can be computed once as `int ei = idx << 1` — no duplication.

### Pre-mortem: What Could Go Wrong
1. **JIT bytecode budget exceeded**: If the shift operations push putValHashed over the inline threshold.
   Mitigation: compute `int ei = idx << 1` once where idx is known; reuse.
2. **Cache pressure increases at small sizes (12K)**: At 12K, everything fits in L2/L3.
   Interleaving doubles stride for key access (entry i is at offset 2*i vs i). Array prefetcher
   may be less effective. Could regress PutHit@12K.
   Mitigation: Accept small regression at 12K if 784K improves significantly.
3. **Resize/rehash complexity**: rehash() iterates oldKeys[i]/oldVals[i] → must change to oldEntries[2*i]/oldEntries[2*i+1].
4. **Iterator and EntryRef**: keys[idx]/vals[idx] references → entries[idx<<1]/entries[idx<<1|1].
5. **containsValue**: iterates vals[i] → entries[2*i+1] for full slots.
6. **ConcurrentSwissMap compatibility**: putConcurrent/findIndexHashedConcurrent use same keys[]/vals[].

## Changes Required (all in SwissMap.java)

### Field change
```java
// Before
private Object[] keys;
private Object[] vals;

// After  
private Object[] entries; // entries[2*i]=key, entries[2*i+1]=val
```

### init()
```java
// Before
this.keys = new Object[capacity];
this.vals = new Object[capacity];

// After
this.entries = new Object[capacity << 1];  // capacity*2 slots
```

### rehash()
- `oldKeys`/`oldVals` → `oldEntries`
- Iteration: `castKey(oldEntries[2*i])` / `castValue(oldEntries[2*i+1])`

### putValHashed (CRITICAL - bytecode budget)
- Prologue: remove `Object[] keys = this.keys; Object[] vals = this.vals;`
  Replace with: `Object[] entries = this.entries;`
- Key access: `Object k = keys[idx];` → `int ei = idx << 1; Object k = entries[ei];`
- Val update: `vals[idx] = value;` → `entries[ei | 1] = value;`
- The `ei` variable can be hoisted outside the equality check.

### findIndexHashed
- Same pattern: `Object[] keys = this.keys` → `Object[] entries = this.entries`
- `Object k = keys[idx];` → `Object k = entries[idx << 1];`

### insertAt / setEntryAt
- `setEntryAt(idx, key, val)`: `entries[idx<<1] = key; entries[idx<<1|1] = val;`

### clear()
- `Arrays.fill(keys, null); Arrays.fill(vals, null);` → `Arrays.fill(entries, null);`

### Iterators (KeyIter, ValueIter, EntryIter, EntryRef)
- `keys[idx]` → `entries[idx<<1]`
- `vals[idx]` → `entries[idx<<1|1]`

### containsValue
- `vals[i]` → `entries[i<<1|1]`

### getConcurrent / putConcurrent / removeConcurrent paths
- Same entries[] access pattern

## Success Criteria
- PutHit@784K: target <25ns (currently 28.4ns, need -10% = <25.5ns)
- PutMiss@784K: maintain ≥54ns (no >10% regression from baseline 112.9ns)
- PutHit@12K: maintain ≥8.7ns (no >10% regression from baseline 7.9ns → limit is 8.7ns)
- All tests must pass: `./gradlew test apacheTest googleTest`
