# Reflexion: iter-007-findindex-ilp

## 무엇을 시도했는가

`findIndexHashed` probe loop에서 `eqMask(word, h2Broadcast)`와 `hasEmpty(word)`를
인접 배치하여 OOO CPU가 두 SWAR 연산을 파이프라인할 수 있게 ILP hoisting 적용.

iter-003/006이 `putValHashed`에서 성공한 패턴과 동일.

## 결과

PutHit@12K: 9.014 ns (baseline 7.921 ns) → **+13.8% 악화 ❌**
→ 기준치 +10% 초과 → REVERT

## 왜 실패했는가

### 가설 1: JIT 레지스터 압력 변화
`putValHashed` fast path (iter-003/006)는 이미 tombstone 관련 변수를 제거하여
레지스터 여유가 있었다. `findIndexHashed`는 이미 최소한의 변수만 사용하는 상태.
여기에 `long emptyBits`를 명시적 변수로 추가하면 liveness 집합이 변경되어
JIT이 기존보다 열악한 레지스터 배치를 선택할 수 있다.

### 가설 2: JIT의 기존 스케줄이 이미 최적
C2 JIT은 `if (hasEmpty(word) != 0) return -1;`를 조건 분기 패턴으로 인식하고
내부적으로 이미 eqMask와 겹쳐 실행할 수 있다.
명시적 변수화는 이 최적화를 방해한다.

### 가설 3: findIndexHashed의 루프 구조 차이
`putValHashed`는 루프 탈출 시 `insertAt`을 호출 (return null).
`findIndexHashed`는 `return -1` 또는 `return idx`.
JIT이 두 루프의 exit pattern을 다르게 처리하며, ILP hoisting의 효과가 다르게 나타난다.

## iter-003과의 핵심 차이

iter-003은 `putValHashed`에서:
- 기존에 `firstTombstone` 체크가 있어서 `emptyBits`가 "추가 비용" 없이 ILP 이득을 줬다
- 실제로는 tombstone path 제거(iter-006)에서 진정한 이득이 나왔다

findIndexHashed는:
- 이미 tombstone 관련 코드 없음
- 변수 추가 없이 instruction 재배치만으로 ILP를 얻으려 했으나,
  JIT이 이미 더 나은 방식으로 처리하고 있었을 가능성 高

## 교훈

1. **ILP hoisting은 "제거"와 결합될 때 효과적이다**: iter-003은 변수를 인접 배치,
   iter-006은 tombstone branch를 제거. 단순 재배치만으로는 효과 없거나 역효과.

2. **findIndexHashed는 이미 JIT 최적화 sweet spot에 있다**: 단순한 loop body는
   JIT이 이미 aggressively optimize한 상태. human intervention이 오히려 방해.

3. **다음 방향**: findIndexHashed 내부 조작 대신, 호출 횟수 자체를 줄이거나
   (cache, memoization) 완전히 다른 probe 전략(e.g., 2-level hash shortcut)을 고려.

## 다음 iteration 후보

### Candidate A: PutHit@784K 개선 — group prefetch via fake-read
현재 PutHit@784K가 28.3 ns으로 개선 여지 있음.
probe 루프에서 `ctrl[nextGroup]`를 미리 읽어 HW prefetcher 힌트 주기 시도.
단, JIT이 dead read를 제거할 위험 있음.

### Candidate B: insertAt 인라인 최적화
`insertAt` 메서드 호출이 hot path에서 발생. 메서드 call boundary 제거.
iter-005 실패 교훈: 분리는 실패, 인라인은 성공. insertAt을 putValHashed에 직접 인라인.

### Candidate C: h2 broadcast 비용 분석
`long h2Broadcast = toUnsignedByte(h2) * BITMASK_LSB` — 현재 putValHashed와 findIndexHashed
둘 다 사용. 이미 최소화되어 있으나, `toUnsignedByte` 변환 제거 가능 여부 확인.

### 최우선 추천: Candidate B (insertAt 인라인)
putValHashed fast path에서 `insertAt` 호출을 제거하여 call overhead 및 tombstone 체크 비용 제거.
tombstones==0 fast path에서는 tombstone 체크 자체가 불필요 — 인라인 시 완전 제거 가능.
