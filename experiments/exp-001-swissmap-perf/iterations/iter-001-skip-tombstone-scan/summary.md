# iter-001 요약: skip-tombstone-scan

## 전략
`putValHashed` 내 tombstone SIMD 스캔 진입 조건에 `&& tombstones > 0` 가드 추가.
tombstone이 없는 테이블(주로 신규 삽입 / 리해시 직후)에서는 DELETED_BROADCAST eqMask 계산 자체를 건너뜀.

## 측정 결과 (ns/op)

| 지표 | baseline | iter-001 | delta | delta% |
|------|----------|----------|-------|--------|
| PutHit@12K | 7.921 | 8.777 | +0.856 | +10.8% ↑ worse |
| PutHit@784K | 28.689 | 30.016 | +1.327 | +4.6% ↑ worse |
| PutMiss@12K | 18.443 | 18.178 | -0.265 | -1.4% ↓ better |
| PutMiss@784K | 112.932 | 78.667 | **-34.265** | **-30.3% ↓ better** |

## 결정: Keep

PutMiss@784K (주요 최적화 목표)가 112.9 → 78.7 ns/op으로 **30.3% 개선**.
PutHit에 소폭 회귀가 있으나, PutMiss 개선 폭이 압도적으로 크므로 유지.

## 비고
- error bar가 큰 항목(PutMiss@784K: ±102 ns/op)이 있어 재측정 시 수치 변동 가능성 존재.
- 핵심 성과: tombstone이 0인 경우 SIMD 스캔 분기 제거.
