# 실험 계획서

## 실험 ID
exp-001-swissmap-perf

## 최적화 목표
SwissMap의 전반적 성능 지표 개선:
- **처리량 (ops/s)**: put/get/remove throughput 최대화
- **지연시간 (latency)**: 개별 연산의 평균/p99 latency 단축
- **메모리 사용량**: heap footprint 및 객체 오버헤드 감소
- **캐시 미스율**: L1/L2/L3 캐시 미스 감소를 통한 메모리 레이아웃 최적화

## 대상 파일 범위
`src/main/java/io/github/bluuewhale/hashsmith/SwissMap.java` (전체)

## 지표
- 이름: JMH throughput (ops/ms), average time (ms/op), memory footprint
- 방향: throughput — higher is better / latency — lower is better / memory — lower is better

## 성공 기준
- Throughput: 현재 baseline 대비 **20% 이상** 향상
- Average latency: **10% 이상** 단축
- 메모리 footprint: **10% 이상** 감소 또는 유지

## 트레이드오프 제약
- Get/Put 모두 중요한 지표임 — 어느 한 지표를 **10% 초과** 악화시키는 변경은, 다른 지표가 개선되더라도 **Revert**
- 예: PutMiss@784K -15% 개선이라도 PutHit@12K +12% 악화이면 → Revert

## Guard 명령
```
./gradlew test apacheTest googleTest
```

## Verify 명령
```
./gradlew jmhSwissMap
```

## 반복 횟수
50회

## 탐색 예정 전략
- (Phase 2 루프에서 채워짐)

## 탐색 가능한 최적화 방향 (초기 브레인스토밍)
1. **Control byte probe 최적화**: SWAR 기반 16바이트 동시 비교 (현재 구현 확인 필요)
2. **메모리 레이아웃 개선**: cache line 정렬, key/value 배열 분리 vs 인터리빙
3. **resize 정책 최적화**: load factor 조정, 증분 rehash
4. **해시 함수 교체**: Fibonacci hashing, AHash-style mixing
5. **JIT 친화적 코드 구조**: 불필요한 분기 제거, 루프 unrolling
6. **객체 할당 제거**: Iterator 재사용, Entry 객체 pooling
7. **Vector API (SIMD)**: jdk.incubator.vector를 통한 batch probe
8. **Robin Hood 변위 최적화**: 현재 SwissMap과 비교하여 probe 길이 단축

## 생성일
2026-04-04
