# iter-002 요약: fast-empty-detect

## 전략
`hasEmpty(word)` SWAR 헬퍼를 도입:
- XOR EMPTY_BROADCAST → hasZero 공식 → 4 ops (no multiply)
- `putValHashed`: emptyBits에서 직접 trailing-zeros >>> 3으로 슬롯 위치 계산, 두 번째 `eqMask(EMPTY_BROADCAST)` 호출 완전 제거
- `findIndexHashed`: `if (hasEmpty(word) != 0) return -1` 단일 조건으로 단순화
- `putValHashedConcurrent`, `findIndexHashedConcurrent`에도 동일 적용
- iter-001의 tombstone guard(`tombstones > 0`) 유지

## 측정 결과 (ns/op)

| 지표 | baseline | iter-001 | iter-002 | delta vs baseline | delta vs iter-001 |
|------|----------|----------|----------|-------------------|-------------------|
| PutHit@12K | 7.921 | 8.777 | 9.266 | +1.345 (+17.0%) ↑ worse | +0.489 |
| PutHit@784K | 28.689 | 30.016 | 30.704 | +2.015 (+7.0%) ↑ worse | +0.688 |
| PutMiss@12K | 18.443 | 18.178 | 18.301 | -0.142 (-0.8%) | +0.123 |
| PutMiss@784K | 112.932 | 78.667 | **67.799** | **-45.133 (-40.0%)** | **-10.868 (-13.8%)** |

## 결정: Keep

PutMiss@784K 기준:
- baseline 대비 40.0% 개선 (112.9 → 67.8 ns/op)
- iter-001 대비 추가 13.8% 개선 (78.7 → 67.8 ns/op)

PutHit에 소폭 추가 회귀 있으나, miss 경로 개선 폭이 압도적으로 큼.

## 비고
- linter가 코드를 추가 개선: `Long.numberOfTrailingZeros(emptyBits) >>> 3`으로 slot offset 직접 추출, 두 번째 eqMask 호출 완전 삭제
- error bar 여전히 큼 (PutMiss@784K: ±85 ns/op) — 측정 노이즈 감안 필요
