# Reflexion: iter-003-ilp-hoisting

## 무엇을 배웠는가

ILP hoisting이 PutHit 경로에 극적인 효과를 가져왔다.
`eqMask`와 `hasEmpty`를 인접하게 배치하는 단순한 순서 변경으로
PutHit@12K가 baseline 대비 -24.1% 개선 (iter-002의 +17% 회귀를 완전히 역전).

## 왜 효과가 있었는가

`word`는 루프 시작 시 한 번 로드된다. `eqMask(word, h2Broadcast)`와 `hasEmpty(word)`는
서로 독립적인 연산 — 공유 입력(`word`)만 있고 출력은 독립.

인접하게 배치하면 OOO CPU가 두 명령 시퀀스를 동시에 issue할 수 있음:
- eqMask: XOR, SHR, OR, SUB, AND, MUL, SHR (7 ops)
- hasEmpty: XOR, SUB, AND, AND (4 ops)

두 체인이 병렬 실행되면 hasEmpty의 4 ops는 eqMask의 7 ops 지연 안에 완료됨.
Hit 경로에서는 emptyBits가 사용되지 않지만, 계산 비용이 eqMask와 겹쳐 사라짐.

## PutMiss@784K 회귀 분석

iter-002에서 67.8 ns였던 PutMiss@784K가 102.2 ns로 상승 (±58.161 ns 오차).
그러나 오차 범위가 score의 57%에 달해 측정 신뢰도가 낮음.

가능한 원인:
1. JVM 벤치마크 노이즈 — 3회 측정으로는 784K 크기에서 신뢰도 부족
2. 코드 레이아웃 변화 — hasEmpty hoisting이 JIT 컴파일 경계를 이동시켜 miss path 영향
3. iter-002의 67.8 ns 자체가 비정상적으로 낮은 측정값이었을 가능성

baseline 대비 -9.5% (102.2 vs 112.9)이므로 trade-off 제약은 통과.

## 현재 상태 분석

- PutHit@12K: 6.011 ns (baseline 7.921, -24.1%) ← 목표 달성 및 초과
- PutHit@784K: 29.016 ns (baseline 28.689, +1.1%) ← 거의 flat
- PutMiss@12K: 17.514 ns (baseline 18.443, -5.0%) ← 소폭 개선
- PutMiss@784K: 102.219 ns (baseline 112.932, -9.5%) ← 개선이나 iter-002보다 나쁨 (고노이즈)

## 다음에 시도할 방향

### Candidate A: PutMiss@784K 재측정 (iter-004)
PutMiss@784K의 노이즈가 크므로, fork=5 또는 더 많은 iteration으로 정확한 측정 필요.
현재 iter-003이 iter-002보다 실제로 나쁜지 확인이 우선.

### Candidate B: findIndexHashed에도 ILP hoisting 적용
get() 경로의 `findIndexHashed`에도 동일한 패턴 적용 가능:
`hasEmpty(word)` 를 `eqMask` 바로 다음에 hoisting.
현재는 `if (hasEmpty(word) != 0) return -1;`로 별도 계산됨.

### Candidate C: h2Broadcast를 루프 밖으로 이미 있음 — broadcast() 비용 점검
`broadcast(h2)`는 루프 밖에서 계산됨. 현재 올바름.
하지만 `h2` 계산 자체(`hash & H2_MASK`)와 `h1` 계산을 분리하는 비용 확인.

### 최우선 추천: Candidate B (findIndexHashed ILP hoisting)
get/containsKey 경로도 최적화하면 전반적인 성능이 균형 잡힐 것.
PutHit 경로는 먼저 findIndex를 거치지 않고 직접 putValHashed를 호출하므로,
findIndexHashed의 ILP 개선은 put-hit 이외 케이스(get, containsKey)에 영향을 줌.
