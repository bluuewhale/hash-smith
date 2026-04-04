# 실험 리더보드: exp-001-swissmap-perf
마지막 업데이트: 2026-04-04

| 순위 | Iteration | 측정값 | delta | 전략 요약 | 상태 |
|------|-----------|--------|-------|-----------|------|
| 1 | iter-002-fast-empty-detect | PutHit@12K=9.3ns, PutHit@784K=30.7ns, PutMiss@12K=18.3ns, PutMiss@784K=67.8ns | PutMiss@784K -40.0% | hasEmpty SWAR(no mul)+직접 offset; EMPTY_BROADCAST eqMask 제거 | Keep |
| 2 | iter-001-skip-tombstone-scan | PutHit@12K=8.8ns, PutHit@784K=30.0ns, PutMiss@12K=18.2ns, PutMiss@784K=78.7ns | PutMiss@784K -30.3% | tombstones==0일 때 DELETED_BROADCAST eqMask 스캔 건너뜀 | Keep |
| — | baseline | PutHit@12K=7.9ns, PutHit@784K=28.7ns, PutMiss@12K=18.4ns, PutMiss@784K=112.9ns | — | — | 기준 |
