# Summary: iter-011-fibonacci-hash

## Strategy
Replaced the Murmur3 smear hash (`C2 * rotateLeft(hashCode * C1, 15)`) with a Fibonacci
multiplicative hash (`(hashCode * 0x9e3779b97f4a7c15L) >>> 32`) in SwissMap and ConcurrentSwissMap.

Single 64-bit IMULQ replaces a 3-operation multiply chain. Hash function change is isolated to
SwissMap via `hashNonNull()` override — RobinHoodMap and SwissSimdMap retain the original smear.

## Result
| Metric | Baseline | iter-006 | iter-011 | Δ vs baseline | Δ vs iter-006 |
|--------|----------|----------|----------|--------------|--------------|
| PutHit@12K | 7.921 | 6.048 | 5.954 | -24.8% | -1.6% |
| PutHit@784K | 28.689 | 28.365 | 26.435 | -7.9% | -6.8% |
| PutMiss@12K | 18.443 | 17.692 | 17.006 | -7.8% | -3.9% |
| PutMiss@784K | 112.932 | 60.132 | 71.225 | -36.9% | +18.5%* |

*PutMiss@784K error: ±52.768 ns — result is very noisy, regression uncertain.

## Decision: KEEP
All metrics remain well below baseline. PutHit@784K shows -6.8% improvement vs iter-006.
PutMiss@784K apparent regression is within noise (±52.768 ns error = 74% of mean).

## Complications
- Initial approach of modifying `Hashing.smear()` directly broke RobinHoodMap Apache tests —
  a latent RobinHoodMap duplicate-key bug was exposed by the different hash distribution.
- Fix: override `hashNonNull()` in SwissMap only, and align ConcurrentSwissMap shards to use
  `Hashing.fibonacciHash()` for consistency.
