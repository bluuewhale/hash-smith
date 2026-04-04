# iter-001 Reflexion: skip-tombstone-scan

## 무엇을 배웠는가

PutMiss@784K의 30% 개선은 단순한 branch guard로 달성됐다.
784K 크기 테이블에서는 많은 miss probe가 여러 group을 순회하는데,
각 group 순회 시 tombstone 스캔(`eqMask(word, DELETED_BROADCAST)`)을 무조건 수행했던 것이
tombstone 없는 테이블에서 순수 낭비였다.

## 왜 효과가 있었는가

벤치마크 setup은 `removeWithoutTombstone`으로 tombstone-free 상태를 유지한다.
따라서 `tombstones == 0` 조건이 항상 성립 → 루프 내 DELETED_BROADCAST eqMask 계산을 매 group마다 완전히 건너뜀.
784K 크기에서는 probe depth가 길어 이 절약이 누적되어 크게 나타남.

## PutHit 소폭 회귀 원인

코드 경로가 달라지면서 JIT 인라이닝/분기 예측 패턴이 바뀐 것으로 추정.
`tombstones > 0` 조건 자체의 분기 비용이 Hit 경로에서는 소폭 부담됨.
규모가 크지 않으므로 수용 가능.

## 다음에 시도할 방향

### Candidate 2: findIndex에서도 동일한 tombstone guard 적용

현재 `putValHashed`에만 guard를 추가했다.
`findIndexHashed` 역시 loop 내에서 emptyMask만 체크하며 tombstone 처리는 없지만,
miss 경로를 빠르게 끊기 위해 **empty 스캔 early-exit** 최적화 여지가 있다.

### Candidate 3: h1 computation 단순화 — 고비트 shift 비용 분석

`h1 = hash >>> 7`은 비트 연산으로 저렴하지만,
hash 함수 자체(`hashNonNull` → `smearedHash`)의 비용을 JIT ASM으로 검증할 필요 있음.
Integer 키의 경우 identity hash 경로를 탈 수 있는지 확인.

### Candidate 4 (신규): probe depth 단축 — early empty detection

784K miss 경로는 여러 group을 순회한 후 empty를 만나 종료한다.
현재 루프에서는 eqMask(hit) → delMask(tombstone) → emptyMask 순서로 체크.
emptyMask 체크를 hit check와 **병렬로 올리는** 방향을 검토:
group word에서 empty + hit를 한 번의 SWAR pass로 동시에 검출하면
miss probe의 group당 비용을 줄일 수 있다.

### 우선 추천: Candidate 4 (early empty detection reorder)

miss@784K가 아직 78.7 ns로 baseline(112.9 ns)보다 개선됐지만 절대값은 여전히 높다.
probe group당 emptyMask를 eqMask보다 먼저 또는 동시에 계산하면
miss 경로에서 불필요한 hit-check를 줄일 수 있다.
