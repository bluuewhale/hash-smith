# Reflexion: iter-006-loop-specialization

## 무엇을 시도했는가

`putValHashed` 내 probe loop를 `tombstones == 0` 조건 기반으로 두 개의 전용 인라인 루프로 분리.
- **Fast path** (tombstones==0, 일반적인 경우): `DELETED_BROADCAST` 참조 없음, `firstTombstone` 변수 없음
- **Slow path** (tombstones>0): 기존 iter-003 로직 유지 (DELETED_BROADCAST 스캔 포함)
- 두 루프 모두 iter-003의 ILP hoisting (`eqM` + `emptyBits` 인접 배치) 유지
- 메서드 호출 경계 없음 (iter-005의 실패 요인 회피)

## 결과

| Metric          | Baseline   | iter-003   | iter-006   | vs baseline | vs iter-003 |
|-----------------|-----------|-----------|-----------|-------------|-------------|
| PutHit@12K      | 7.921 ns  | 6.011 ns  | 6.048 ns  | **-23.6% ✅** | +0.6% (노이즈) |
| PutHit@784K     | 28.689 ns | 29.016 ns | 28.365 ns | -1.1% ✅    | -2.2%       |
| PutMiss@12K     | 18.443 ns | 17.514 ns | 17.692 ns | -4.1% ✅    | +1.0% (노이즈) |
| PutMiss@784K    | 112.932 ns| 102.219 ns| 60.132 ns | **-46.7% ✅✅** | **-41.1% ✅✅** |

## 결정: **KEEP** ✅

PutHit@12K -23.6% (threshold: 10%) + PutMiss@784K -46.7% — 두 지표 모두 큰 개선.
baseline 대비 어떤 지표도 10% 이상 악화 없음.

## 왜 효과가 있었는가

### 가설 1: Fast path loop body 단순화
tombstones==0 경우 (99%+ 실제 워크로드):
- `DELETED_BROADCAST` 로드 명령 제거
- `firstTombstone` 초기화(-1), 조건 체크, 갱신 제거
- 루프 body instruction count 감소 → decode/dispatch 병목 완화

### 가설 2: 레지스터 압력 감소
`firstTombstone`이 live variable에서 제거되면서 JIT이 `eqM`, `emptyBits`, `base`, `word`, `g`를
더 효율적인 레지스터 배치로 컴파일 가능. iter-003의 ILP 이득 유지+강화.

### 가설 3: PutMiss@784K 대폭 개선 (iter-003 대비 -41%)
784K 케이스는 LLC 캐시 미스가 빈번 → probe loop이 더 많은 그룹을 순회.
명령어 수 감소가 각 순회마다 적용되어 누적 효과가 큼.
iter-003이 PutHit@12K에만 집중했던 것과 달리, iter-006은 cache-cold miss path도 개선.

### 왜 메서드 경계가 없는 loop duplication이 핵심인가
iter-005는 메서드 분리로 hit path를 단순화했지만 call convention overhead로 ILP를 잃었음.
iter-006은 동일한 개념을 메서드 경계 없이 구현 → ILP 보존 + instruction 감소 동시 달성.

## PutHit@12K가 iter-003 (6.011)과 거의 동일한 이유

Fast path에서 `firstTombstone` 변수와 `DELETED_BROADCAST` 브랜치가 제거되었지만,
iter-003 이후 이미 tombstones>0 가드(`firstTombstone < 0 && tombstones > 0`)가 있어서
12K case에서는 실제 비용이 이미 낮았음. 개선이 PutMiss@784K에 더 집중된 이유.

## 다음에 시도할 방향

### Candidate A: findIndexHashed에도 loop specialization 적용
get/containsKey 경로의 `findIndexHashed`에 유사한 최적화:
현재 size==0 early return은 있지만 loop body에 tombstone 관련 코드는 없음.
ILP hoisting이 get 경로에도 적용 가능한지 검토.

### Candidate B: putValHashedConcurrent에도 loop specialization 적용
concurrent path도 동일한 loop specialization 적용 가능.
하지만 concurrent 경로는 writer lock 하에 실행되므로 우선순위 낮음.

### Candidate C: PutHit@784K 추가 개선 시도
28.4 ns (baseline 28.7 ns) — 여전히 baseline 수준. cache-cold hit path 최적화.
group prefetching (next group prefetch in probe loop)이나
probe step 패턴 변경 고려.

### Candidate D: 현재 상태를 공고히 하고 다른 자료구조로 확장
iter-006은 이미 두 가지 주요 지표에서 큰 개선을 달성.
SwissSet, RobinHoodMap에도 유사한 최적화 적용 가능성 검토.

### 최우선 추천: Candidate A (findIndexHashed ILP hoisting)
get 경로는 put보다 더 자주 호출되는 경우가 많음.
현재 `findIndexHashed`의 `hasEmpty` 체크가 `eqMask` 이후 별도 계산됨.
ILP hoisting으로 PutHit에서 얻은 것과 유사한 이득 가능.
메서드 경계 없이 인라인 구현 필수.
