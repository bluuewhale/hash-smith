# 실험 리더보드: exp-001-swissmap-perf
마지막 업데이트: 2026-04-04 (iter-005 추가)

| 순위 | Iteration | 측정값 | delta | 전략 요약 | 상태 |
|------|-----------|--------|-------|-----------|------|
| 1 | iter-003-ilp-hoisting | PutHit@12K=6.0ns, PutHit@784K=29.0ns, PutMiss@12K=17.5ns, PutMiss@784K=102.2ns* | PutHit@12K -24.1% | eqMask+hasEmpty 인접 배치로 OOO ILP 파이프라인 활용 | Keep |
| 2 | iter-002-fast-empty-detect | PutHit@12K=9.3ns, PutHit@784K=30.7ns, PutMiss@12K=18.3ns, PutMiss@784K=67.8ns | PutMiss@784K -40.0% | hasEmpty SWAR(no mul)+직접 offset; EMPTY_BROADCAST eqMask 제거 | Keep |
| 3 | iter-001-skip-tombstone-scan | PutHit@12K=8.8ns, PutHit@784K=30.0ns, PutMiss@12K=18.2ns, PutMiss@784K=78.7ns | PutMiss@784K -30.3% | tombstones==0일 때 DELETED_BROADCAST eqMask 스캔 건너뜀 | Keep |
| — | iter-005-coldpath-split | PutHit@12K=9.2ns, PutHit@784K=32.1ns, PutMiss@12K=18.7ns, PutMiss@784K=76.4ns | PutHit@12K +16.4% ❌ | miss path를 putMiss() 메서드로 분리; ILP 이득 파괴, call overhead 부작용 | Revert |
| — | baseline | PutHit@12K=7.9ns, PutHit@784K=28.7ns, PutMiss@12K=18.4ns, PutMiss@784K=112.9ns | — | — | 기준 |

*PutMiss@784K ±58.161 ns 오차 (고노이즈)
