# Reflexion: iter-005-coldpath-split

## 무엇을 시도했는가

`putValHashed` 내 miss path (tombstone 스캔 + `insertAt` 호출)를 별도 `putMiss()` 메서드로 분리.
hot probe loop의 live variable 수를 줄여(firstTombstone 제거, DELETED_BROADCAST 스캔 제거)
JIT이 hit path를 더 작은 instruction footprint로 컴파일하도록 유도.

## 결과

| Metric          | Baseline   | iter-003   | iter-005   | vs baseline |
|-----------------|-----------|-----------|-----------|-------------|
| PutHit@12K      | 7.921 ns  | 6.011 ns  | 9.223 ns  | **+16.4% ❌** |
| PutHit@784K     | 28.689 ns | 29.016 ns | 32.070 ns | **+11.8% ❌** |
| PutMiss@12K     | 18.443 ns | 17.514 ns | 18.657 ns | +1.2%       |
| PutMiss@784K    | 112.932 ns| 102.219 ns| 76.356 ns | -32.4% ✅   |

## 결정: REVERT

PutHit@12K +16.4%, PutHit@784K +11.8% — 두 지표 모두 기준 대비 >10% 악화. 즉시 리버트.

## 왜 실패했는가

### 가설 1: iter-003 ILP 이득이 파괴됨

iter-003의 -24% PutHit 이득은 `eqMask + hasEmpty`를 인접 배치해 OOO CPU가 두 SWAR 연산을
병렬 파이프라인으로 처리하게 한 것에서 왔다.
`putMiss()` 분리 이후, JIT은 `putValHashed`를 더 작은 루프로 컴파일하지만,
**메서드 호출 경계가 생기면서** 루프 바깥으로 이어지는 컴파일 맥락이 바뀐다.

JIT이 `putMiss`를 인라인해도, 8개 인자를 넘기는 call site 셋업(레지스터 이동)이
`eqMask + hasEmpty` ILP에 쓰이던 레지스터 슬롯을 점유한다.

### 가설 2: 8개 인자 call convention 오버헤드

Java JIT의 calling convention은 인자를 레지스터에 배치한다.
`putMiss(key, value, h2, h1, mask, emptyIdx, probeStep, emptyGroup)` — 8개 인자 중
일부는 스택에 spill될 수 있다. 이 자체가 PutHit@12K에서 +53% 회귀를 유발할 정도의 비용.

### 무엇을 배웠는가

1. **iter-003의 ILP 이득은 method boundary에 매우 민감하다.**
   putValHashed 전체가 하나의 컴파일 단위로 처리될 때만 OOO 파이프라인 이득이 유지된다.

2. **live variable 수 감소 ≠ register pressure 감소.**
   호출 경계를 만들면 call convention이 새 register pressure를 생성한다.
   "hot loop를 분리하면 레지스터가 줄어든다"는 직관은 inlining 여부와 call convention에 따라
   반대 효과를 낼 수 있다.

3. **PutMiss@784K는 -32.4% 개선.** 이것은 tombstone re-probe를 putMiss로 분리하는 방향이
   miss 전용 측정에는 도움이 됨을 시사. hit path를 건드리지 않는 방식이 필요.

## 다음에 시도할 방향

### Candidate A: tombstone 조건부 분기 제거 (tombstones==0 fastpath inline)

현재 probe loop 내에 `if (firstTombstone < 0 && tombstones > 0)` 브랜치가 있다.
`tombstones == 0`인 경우가 일반적이므로, 이 브랜치를 loop 밖의 `if/else`로 끌어올려
tombstones==0 경로에서는 DELETED_BROADCAST 스캔 자체를 loop body에서 제거할 수 있다.

iter-001이 이미 유사한 아이디어를 사용했지만 (tombstones>0 guard), 그것은 메서드 레벨이었고
이번엔 loop 내 브랜치를 완전히 제거하는 loop specialization.

```java
private V putValHashed(K key, V value, int smearedHash) {
    // ... setup ...
    if (tombstones == 0) {
        return putValHashedNoTombstone(key, value, h2, h2Broadcast, h1, mask, ctrl, keys, vals);
    }
    return putValHashedWithTombstone(key, value, h2, h2Broadcast, h1, mask, ctrl, keys, vals);
}
```

단, iter-005에서 배운 교훈: **call convention 오버헤드를 최소화해야 한다.**
인자를 최소화하거나 (예: `this` 필드를 직접 참조하는 private 메서드로 분리),
또는 `@ForceInline` 어노테이션 등을 활용.

### Candidate B: probe loop의 step 카운터 eliminatin

triangular probing의 `step++`은 매 iteration마다 레지스터를 소모.
group 순서를 precomputed index array로 대체하면 step 변수를 제거할 수 있다.
단, 배열 접근이 추가되므로 cache miss 비용과 상충.

### Candidate C: PutMiss@784K 재현성 확인 (fork=5 실행)

현재 PutMiss@784K의 error bar가 크다(±88 ns). fork=5로 실행해 실제 값을 확인.

### 최우선 추천: Candidate A (loop specialization with minimal call overhead)

tombstones==0 fastpath를 위해 두 개의 전용 루프를 만들되,
**인자 개수를 최소화하거나 인스턴스 필드를 직접 참조**하는 방식으로 call overhead를 줄인다.
가장 큰 위험은 다시 call convention 오버헤드이므로, 신중하게 구현해야 한다.
