# Reflexion: iter-012-remeasure

## 무엇을 시도했는가

### Phase 1: iter-011 재측정
iter-011 상태(코드 변경 없음)에서 `./gradlew jmhSwissMap` 재실행.
결과: `swissSimdPutMiss@784K = 103.965 ns ± 528.115 ns` — 측정 불가 수준의 노이즈.
784K 환경 노이즈는 구조적 문제 (JIT warm-up, GC pressure, OS jitter at large working set).

### Phase 2: Upper-bit h2 최적화
Fibonacci 곱셈 해시에서 h2(핑거프린트) 에 상위 7비트(bits 31..25) 사용:
- `h2 = (byte)(hash >>> 25)` — 최고 avalanche 품질 비트
- `h1 = hash` — 전체 32비트 (call-site의 `h1 & groupMask`로 인덱싱)

## 결과

**REVERT.**

| Metric | Baseline | iter-011 | iter-012 | Δ vs baseline |
|--------|----------|----------|----------|---------------|
| PutHit@12K | 7.921 | 5.954 | 6.978 | -11.9% |
| PutHit@784K | 28.689 | 26.435 | 31.026 | **+8.1% ❌** |
| PutMiss@12K | 18.443 | 17.006 | 19.985 | **+8.4% ❌** |
| PutMiss@784K | 112.932 | 71.225 | 90.019 | -20.3% |

PutHit@784K, PutMiss@12K 둘 다 baseline 대비 악화. 결과 노이즈가 크지만 방향이 명확히 나쁨.

## 왜 실패했는가

### h1/h2 비트 겹침 문제 (핵심 원인)
- `h2 = hash >>> 25` → bits 31..25 가 h2
- `h1 = hash` → bits 31..0 전체가 h1 (group 선택 시 `h1 & (nGroups-1)` 적용)

문제: h1이 bits 31..25를 포함하기 때문에 h1 (group index) 와 h2 (fingerprint) 가 **같은 비트를 공유**한다.
같은 그룹에 배치된 키들이 높은 확률로 같은 h2 값을 가짐 → false-positive eqMask 증가 → 더 많은 key.equals() 호출 → PutMiss 악화.

SwissTable의 h1/h2 설계 원칙: **h1과 h2는 서로 다른 비트 영역을 사용해야 독립적 필터로 기능**한다.
기존 `h1 = hash >>> 7`, `h2 = hash & 0x7F`는 bits 31..7 / bits 6..0 으로 완전 분리된 설계.

### 교훈
- Fibonacci hash의 상위 비트 품질 이점은 h1/h2 독립성을 깨는 비용을 상쇄하지 못한다.
- "고품질 비트를 h2에" 는 맞는 직관이지만, h2 비트와 h1 비트가 겹치지 않아야 한다.
- 올바른 상위 비트 h2: `h2 = hash >>> 25`, `h1 = (hash << 7) >>> 7` — 이렇게 해야 bits 24..0 만 h1에 사용.
  (이는 다음 iteration 후보)

## 핵심 교훈

1. **SwissTable h1/h2 독립성은 필수 불변**: 같은 비트가 group 선택과 fingerprint 양쪽에 쓰이면 필터 효과가 파괴됨.
2. **784K PutMiss는 측정 환경 노이즈가 너무 높음**: 3-iteration JMH로는 신뢰 구간이 너무 넓다. 이 지표는 방향성만 참고.
3. **iter-011(Fibonacci hash)는 계속 현재 최선**: 모든 지표 baseline 이상, PutHit 경로 -24.8%/-7.9% 개선.

## 다음 iteration 후보

### Candidate A: 올바른 상위 비트 h2 재시도 (비트 독립성 보장)
```java
h2 = (byte)(hash >>> 25)          // bits 31..25
h1 = (hash << 7) >>> 7            // bits 24..0  (마스킹으로 h2 비트 제거)
// 또는: h1 = hash & 0x1FFFFFF
```
h1/h2 완전 독립 유지하면서 h2에 고품질 비트 사용. 이론적으로 타당하며 아직 시도 안 됨.

### Candidate B: putVal SWAR 루프 내 early-exit 최적화
tombstones==0 전용 루프에서 `hasEmpty` SWAR을 루프 조건으로 호이스팅 (group이 full이면 즉시 next group).

### Candidate C: insertAt inlining (iter-008 재시도)
`insertAt()` 을 putVal 인라인으로 합치면 JIT이 h2 저장 주변 코드를 더 잘 최적화할 수 있음.

**최우선 추천: Candidate A** (Fibonacci hash 상위 비트 활용, 단 올바른 비트 분리 방식으로).
