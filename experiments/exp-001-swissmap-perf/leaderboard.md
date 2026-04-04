# 실험 리더보드: exp-001-swissmap-perf
마지막 업데이트: 2026-04-05 (iter-015 추가)

| 순위 | Iteration | 측정값 | delta | 전략 요약 | 상태 |
|------|-----------|--------|-------|-----------|------|
| 1 | iter-011-fibonacci-hash | PutHit@12K=5.95ns, PutHit@784K=26.4ns, PutMiss@12K=17.0ns, PutMiss@784K=71.2ns* | PutHit@12K -24.8%, PutHit@784K -7.9% | Fibonacci multiplicative hash (single IMULQ) replaces Murmur3 smear in SwissMap/ConcurrentSwissMap | Keep |
| 2 | iter-006-loop-specialization | PutHit@12K=6.0ns, PutHit@784K=28.4ns, PutMiss@12K=17.7ns, PutMiss@784K=60.1ns | PutHit@12K -23.6%, PutMiss@784K -46.7% | tombstones==0 loop specialization: 두 개의 전용 인라인 루프, fast path에서 DELETED_BROADCAST 완전 제거 | Keep |
| 3 | iter-003-ilp-hoisting | PutHit@12K=6.0ns, PutHit@784K=29.0ns, PutMiss@12K=17.5ns, PutMiss@784K=102.2ns* | PutHit@12K -24.1% | eqMask+hasEmpty 인접 배치로 OOO ILP 파이프라인 활용 | Keep |
| 4 | iter-002-fast-empty-detect | PutHit@12K=9.3ns, PutHit@784K=30.7ns, PutMiss@12K=18.3ns, PutMiss@784K=67.8ns | PutMiss@784K -40.0% | hasEmpty SWAR(no mul)+직접 offset; EMPTY_BROADCAST eqMask 제거 | Keep |
| 5 | iter-001-skip-tombstone-scan | PutHit@12K=8.8ns, PutHit@784K=30.0ns, PutMiss@12K=18.2ns, PutMiss@784K=78.7ns | PutMiss@784K -30.3% | tombstones==0일 때 DELETED_BROADCAST eqMask 스캔 건너뜀 | Keep |
| — | iter-015-restore-h1h2-split | PutHit@12K=9.859±21.3ns, PutHit@784K=32.762±44.9ns, PutMiss@12K=16.307±14.9ns | PutHit@12K +24.5% ❌ (216% noise), PutHit@784K +14.2% ❌ (137% noise) | SwissSimdMap tombstones==0 fast path; benchmark noise dominated — load factor no-op proven, SwissMap!=benchmark target | Revert |
| — | iter-014-fast-empty-tombstone0 | PutHit@12K=10.788±14.6ns, PutHit@784K=31.058±27.5ns, PutMiss@12K=19.671±9.1ns | PutHit@12K +81% ❌ (high noise), PutHit@784K +18% ❌, PutMiss@12K +16% ❌ | Replace hasEmpty(word) with word & BITMASK_MSB in tombstones==0 path; JIT already optimal on hasEmpty idiom | Revert |
| — | iter-013-bit-disjoint-h2 | PutHit@12K=7.042ns, PutHit@784K=33.587ns, PutMiss@12K=21.012ns | PutHit@784K +17.1% ❌, PutMiss@12K +13.9% ❌ vs baseline | Bit-disjoint h2: `h2=(hash>>>25)&0x7F`, `h1=hash&0x1FFFFFF`; h1 low bits have weak Fibonacci distribution, hurts group uniformity | Revert |
| — | iter-012-remeasure | PutHit@12K=6.98ns±13.6, PutHit@784K=31.0ns±28.6, PutMiss@12K=20.0ns±72, PutMiss@784K=90ns±341 | PutHit@784K +8.1% ❌ vs baseline | Upper-bit h2: `h2 = hash >>> 25` (top 7 bits); h1/h2 bit overlap caused false-positive surge | Revert |
| — | iter-005-coldpath-split | PutHit@12K=9.2ns, PutHit@784K=32.1ns, PutMiss@12K=18.7ns, PutMiss@784K=76.4ns | PutHit@12K +16.4% ❌ | miss path를 putMiss() 메서드로 분리; ILP 이득 파괴, call overhead 부작용 | Revert |
| — | baseline | PutHit@12K=7.9ns, PutHit@784K=28.7ns, PutMiss@12K=18.4ns, PutMiss@784K=112.9ns | — | — | 기준 |

*iter-011 PutMiss@784K ±52.768 ns — 고노이즈, 결과 불확실
****iter-012 모든 지표 고노이즈; PutHit@784K 방향성 기준으로 Revert 결정
**PutMiss@784K ±58.161 ns 오차 (고노이즈, iter-003 측정 시)
***iter-006 PutMiss@784K ±34.789 ns — 이전보다 노이즈 감소
