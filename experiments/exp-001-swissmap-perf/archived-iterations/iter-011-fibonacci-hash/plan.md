# Plan: iter-011-fibonacci-hash

## Objective
Replace the Murmur3 smear hash with a single-multiply Fibonacci (golden ratio) hash
to reduce per-operation hash computation cost on compute-bound small-table benchmarks.

## Current state
- iter-006-loop-specialization is leaderboard #1
- PutHit@12K: 6.048ns, PutMiss@12K: 17.692ns, PutMiss@784K: 60.132ns
- Hash function: `smear(hashCode)` = `C2 * rotateLeft(hashCode * C1, 15)` in Hashing.java

## Change
File: `src/main/java/io/github/bluuewhale/hashsmith/Hashing.java`

Replace `smear()` implementation with Fibonacci multiplicative hash:
```java
static int smear(int hashCode) {
    return (int) ((hashCode * 0x9e3779b97f4a7c15L) >>> 32);
}
```

This is the 64-bit golden ratio constant (Knuth / Fibonacci hashing).
Single IMULQ + SHR on x86-64 vs the current 3-operation chain.

## Constraints respected
- Does NOT touch putValHashed body
- Does NOT change findIndexHashed structure  
- Does NOT add methods or extract methods
- Is reversible (single method change)
- Keeps same API (smear() is package-private)

## Expected impact
- PutHit@12K: -5% to -15% (hash is ~1-2ns of 6ns total)
- PutMiss@12K: -5% to -15% (same hash computation on miss path)
- PutHit@784K: minimal (L3-latency dominated)
- PutMiss@784K: minimal (L3-latency dominated)

## Risk
- Distribution quality: 7-bit h2 fingerprint may have marginally different collision rate
- If probe chains increase, throughput may degrade
- Mitigation: benchmark validates actual end-to-end performance

## Rollback
Revert Hashing.java to original smear() implementation if any metric >10% worse than baseline.
