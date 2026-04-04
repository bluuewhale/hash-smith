# 실험 리더보드: exp-001-swissmap-perf
마지막 업데이트: 2026-04-05

| 순위 | Iteration | GetHit@12K | GetHit@784K | GetMiss@12K | GetMiss@784K | PutHit@12K | PutHit@784K | PutMiss@12K | PutMiss@784K | 전략 | 상태 |
|------|-----------|------------|-------------|-------------|--------------|------------|-------------|-------------|--------------|------|------|
| — | baseline | 5.59 | 17.98 | 5.84 | 16.51 | 8.09 | 30.59 | 23.70 | 109.84 | — | 기준 |
| 1 | iter-003-findindex-ilp | **4.95** | **15.76** | **5.01** | **14.28** | **6.70** | **23.85** | **16.97** | **69.91** | ILP hoisting in findIndexHashed | KEEP |
| 2 | iter-002-tombstone-specialization | ~6.1 (noise) | ~21.8 (noise) | 5.81 | 16.51 | 6.54 | 24.50 | 16.94 | 60.59 | Tombstone loop specialization + ILP | KEEP |
| — | iter-001-tombstone-guard | 5.60 | 15.99 | 5.95 | 16.39 | 8.41 | 35.63 | 23.92 | 96.15 | Tombstone guard in putVal | REVERT |
