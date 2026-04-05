# Plan: iter-002-fused-empty-detect

## Step-Back: What type of bottleneck is this?

코드 현황 분석:
- `hasEmpty(word)` 메서드가 이미 존재 (line 416-419)
- 하지만 `putValHashed`에서 `hasEmpty(word) != 0` 체크 후 다시 `eqMask(word, EMPTY_BROADCAST)` 호출 → 이중 계산
- `findIndexHashed`는 `hasEmpty` 전혀 미적용, 매번 `eqMask(word, EMPTY_BROADCAST)` 호출

각 SWAR `eqMask` 계산 비용:
- XOR, shift, subtract, AND, 곱셈+shift: ~5-6 독립 ops, 하지만 곱셈이 가장 비쌈

`hasEmpty(word)` 계산:
- XOR, subtract, AND, AND: ~4 ops, 곱셈 없음 → eqMask보다 저렴

`hasEmpty` 반환값에서 직접 slot index 추출:
- `Long.numberOfTrailingZeros(hasEmpty_result) >>> 3` = 첫 번째 EMPTY 슬롯 인덱스 (0..7)
- 수학적으로 eqMask와 동일한 결과 (검증 완료)

## Candidate Approaches (CoT)

### Candidate 1: hasEmpty에서 직접 index 추출 — eqMask 이중 계산 제거 [CHOSEN]

**putValHashed 변경:**
```java
// 기존 (이중 계산):
if (hasEmpty(word) != 0) {
    int emptyMask = eqMask(word, EMPTY_BROADCAST);  // 다시 SWAR 계산
    if (emptyMask != 0) {
        int idx = base + Integer.numberOfTrailingZeros(emptyMask);
        ...
    }
}

// 변경 (직접 추출):
long emptyBits = hasEmpty(word);
if (emptyBits != 0) {
    int slotOffset = Long.numberOfTrailingZeros(emptyBits) >>> 3;
    int idx = base + slotOffset;
    int target = (firstTombstone >= 0) ? firstTombstone : idx;
    return insertAt(target, key, value, h2);
}
```

**findIndexHashed 변경:**
```java
// 기존:
int emptyMask = eqMask(word, EMPTY_BROADCAST);
if (emptyMask != 0) return -1;

// 변경:
if (hasEmpty(word) != 0) return -1;
```

**findIndexHashed에서 index 불필요 이유**: miss 시 index 자체가 필요 없고 `-1` 반환만 하면 됨.
따라서 `hasEmpty(word) != 0` 체크만으로 충분 (index 추출 불필요).

**putValHashedConcurrent도 동일하게 적용.**

### Candidate 2: BITMASK_MSB fast path (original plan)

`word & BITMASK_MSB` 체크로 "any non-full" 탐지. 하지만 DELETED(0xFE) 도 MSB=1이므로
"EMPTY only" 탐지가 아님. 별도 EMPTY 구분 필요 → hasEmpty보다 단순하지 않음.

## Decision: Candidate 1

- 제거되는 계산: `eqMask(word, EMPTY_BROADCAST)` (곱셈 포함 ~6 ops) → 0 (이미 hasEmpty가 계산함)
- findIndexHashed: 동일한 제거
- 변경 범위 최소 (putValHashed, findIndexHashed, putValHashedConcurrent, findIndexHashedConcurrent)
- 정확성 위험 없음 (수학적 등가 검증 완료)

**Expected gain**:
- PutHit (findIndex hot path): 매 probe group에서 eqMask(EMPTY) 절약 → 소폭 개선 가능
- PutMiss (putValHashed): probe chain 길수록 누적 절약 → 중간 이상

## Pre-mortem: If this fails, what's the most likely reason?

1. **JIT이 이미 최적화**: JIT이 `eqMask(word, EMPTY_BROADCAST)`를 이미 inline+optimize하여
   실제 cycle 차이가 noise 수준.
2. **hasEmpty의 추가 분기**: `if (emptyBits != 0)` 분기가 기존 `if (emptyMask != 0)`와 동일하므로
   분기 overhead 차이 없음.
3. **측정 noise**: 12K 벤치는 L1 캐시 내이므로 단일 곱셈 절약이 측정 임계 이하일 수 있음.
