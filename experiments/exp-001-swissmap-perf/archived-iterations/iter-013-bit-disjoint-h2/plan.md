# Plan: iter-013-bit-disjoint-h2

## Hypothesis
The current h1/h2 split (`h1 = hash >>> 7`, `h2 = hash & 0x7F`) uses bits 6..0 for the fingerprint.
After Fibonacci multiplicative mixing, the upper bits should have better avalanche quality than the lower bits.
iter-012 tried `h2 = hash >>> 25` (upper 7 bits) but kept `h1 = hash` (full 32 bits), causing h1/h2 bit overlap
and false-positive fingerprint matches for keys in the same group.

This iteration corrects that by using **fully non-overlapping bit ranges**:
- h2 = bits 31..25 (7 bits): `(byte)((hash >>> 25) & 0x7F)` — clamped to stay below EMPTY(0x80)
- h1 = bits 24..0 (25 bits): `hash & 0x1FFFFFF`

## Expected benefit
Fibonacci hash's upper bits have stronger avalanche than lower bits. Better h2 distribution
→ fewer false-positive eqMask hits → fewer key.equals() calls → faster PutMiss path.

## Safety analysis
- h2 range: 0x00..0x7F (7 bits, MSB always 0) → safe, never collides with EMPTY(0x80) or DELETED(0xFE)
- h1 range: 0x0..0x1FFFFFF (25 bits) — sufficient for any practical table size
  (largest group count = capacity/8; at 784K entries, capacity ~1M, nGroups ~131K → needs ~17 bits)
- Bit independence: bits 31..25 (h2) and bits 24..0 (h1) have zero overlap

## Changes
File: `src/main/java/io/github/bluuewhale/hashsmith/SwissMap.java`
1. Change `H2_MASK` constant from `0x0000007F` to something descriptive (or inline)
2. `h1(int hash)`: `return hash & 0x1FFFFFF;`  (was: `hash >>> 7`)
3. `h2(int hash)`: `return (byte)((hash >>> 25) & 0x7F);`  (was: `(byte)(hash & H2_MASK)`)

## Risk
- If upper bits 31..25 after Fibonacci mixing don't distribute as well as lower bits → worse h2 spread
- The Fibonacci constant is 0x9e3779b9 (32-bit); multiplication mixes bits significantly but lower bits
  of the product depend on ALL input bits, while upper bits depend on fewer input bits.
  Actually for multiplicative hashing, upper bits typically have better distribution (less modular bias).
- Pre-mortem: nGroups is always a power of 2, so `h1 & (nGroups-1)` only uses low bits of h1.
  With h1 = bits 24..0, group selection uses the lowest log2(nGroups) bits of the 25-bit range,
  which are bits 0..log2(nGroups)-1 of the original hash (unchanged from current design effectively,
  since current h1 = hash>>>7 and group selection uses h1&mask = bits (7+log2(nGroups)-1)..7 of hash).
  This is a key difference — the GROUP SELECTION bits change from hash[7+k-1..7] to hash[k-1..0].
  Lower bits of Fibonacci hash have weaker distribution. This could hurt group uniformity.

## Decision gate
Use PutHit@12K, PutHit@784K, PutMiss@12K as primary signals.
Keep if any primary metric improves ≥10% AND none regress >10% vs baseline.
