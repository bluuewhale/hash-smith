# iter-002 요약: fused-empty-detect

## 전략
`putValHashed` / `putValHashedConcurrent` 에서 `hasEmpty(word) != 0` 체크 후 다시
`eqMask(word, EMPTY_BROADCAST)` 를 계산하던 이중 SWAR 계산을 제거.
`hasEmpty` 반환값에서 직접 slot offset을 추출: `Long.numberOfTrailingZeros(emptyBits) >>> 3`.

`findIndexHashed` / `findIndexHashedConcurrent` 에서도 `eqMask(word, EMPTY_BROADCAST)` 를
`hasEmpty(word) != 0` 단독 체크로 대체 (index 추출 불필요, miss 시 -1 반환만).

`hasEmpty` 계산 비용: XOR + subtract + AND + AND (곱셈 없음) vs
`eqMask` 계산 비용: XOR + shift + subtract + AND + 곱셈+shift (~6 ops, 곱셈 포함).

## 측정 결과 (ns/op) — baseline은 iter-001 이후 상태

| 지표 | iter-001 | iter-002 | delta | delta% |
|------|----------|----------|-------|--------|
| PutHit@12K | 8.777 | 9.266 | +0.489 | +5.6% ↑ worse |
| PutHit@784K | 30.016 | 30.704 | +0.688 | +2.3% ↑ worse |
| PutMiss@12K | 18.178 | 18.301 | +0.123 | +0.7% ↑ worse |
| PutMiss@784K | 78.667 | 67.799 | **-10.868** | **-13.8% ↓ better** |

## 원본 baseline 대비 누적 효과 (iter-001 + iter-002)

| 지표 | 원본 | 현재 | 누적 delta% |
|------|------|------|-------------|
| PutHit@12K | 7.921 | 9.266 | +17.0% worse |
| PutHit@784K | 28.689 | 30.704 | +7.0% worse |
| PutMiss@12K | 18.443 | 18.301 | -0.8% neutral |
| PutMiss@784K | 112.932 | 67.799 | **-40.0% better** |

## 결정: Keep

PutMiss@784K가 78.7 → 67.8 ns/op으로 **-13.8% 추가 개선** (5% 기준 충족).
원본 대비 누적 -40.0% 개선.

## 비고
- PutHit@12K의 누적 악화(+17.0%)가 지속되고 있음. 단, error bar가 ±1.4 ns/op 수준이고
  절대값 변화가 ~1.3 ns이므로 측정 noise 가능성 있음.
- PutMiss@784K error bar: ±85.6 ns/op — 재측정 시 변동 가능성 높음.
- 다음 iter에서 PutHit 경로를 집중 타겟으로 삼는 것을 고려.
