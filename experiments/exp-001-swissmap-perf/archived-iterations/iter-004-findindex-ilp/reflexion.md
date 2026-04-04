# Reflexion: iter-004-findindex-ilp

## 무엇을 시도했는가
`findIndexHashed` 프로브 루프에 ILP hoisting 적용.
`hasEmpty(word)`를 `eqMask(word, h2Broadcast)` 바로 아래에 배치해
OOO CPU가 두 독립적 SWAR 연산을 병렬로 실행할 수 있도록 유도.
iter-003이 `putValHashed`에 적용한 것과 동일한 패턴.

## 결과
- PutHit@12K: **9.222 ns (+16.4% vs baseline 7.921)** ← REVERT 트리거
- PutHit@784K: 31.209 ns (+8.8%)
- PutMiss@12K: 16.768 ns (-9.1%)
- PutMiss@784K: 76.922 ns ±83.168 (108% 노이즈 — 신뢰 불가)

## 결정: REVERT
PutHit@12K가 baseline 대비 +16.4% 회귀. 트레이드오프 제약(>10% 회귀 → 즉시 리버트) 위반.
iter-003의 핵심 성과(PutHit@12K 6.011 ns, -24%)가 완전히 파괴됨.

## 왜 실패했는가

### 가설: JIT 레지스터 압박
`findIndexHashed`는 `putValHashed`보다 변수가 적은 타이트한 함수다:
- 로컬 변수: `h1`, `h2`, `h2Broadcast`, `ctrl`, `keys`, `mask`, `g`, `step`, `word`, `base`, `eqMask`
- `long emptyBits` 추가 시 → eqMask while-loop 걸쳐 live long 변수 하나 더 생김

x86-64에서 long은 64-bit 레지스터 필요. JIT가 레지스터를 spill하면 put-hit 경로에서
메모리 접근이 추가되어 오히려 더 느려진다.

### putValHashed와의 차이점
`putValHashed`는 더 많은 로컬 변수(`value`, `firstTombstone`, `h2` byte 등)를 이미 갖고 있어
JIT가 더 이른 시점에 레지스터 사용을 최적화했을 것. `findIndexHashed`는 변수가 적어
"타이트한" 레지스터 사용 패턴에 더 민감하다.

### 또 다른 가설: JIT 인라인 경계 변화
`findIndexHashed`가 `putValHashed`에서 인라인될 때, `emptyBits`의 추가가
JIT의 인라인 임계값(bytecode size threshold)을 초과시켜 인라인을 포기했을 수 있음.
이는 hit 경로에서 메서드 호출 오버헤드를 유발한다.

## 무엇을 배웠는가

1. **ILP hoisting은 함수의 레지스터 예산에 따라 효과가 다르다.**
   변수가 많은 함수(putValHashed)는 이미 JIT가 레지스터를 적극적으로 재사용하므로
   추가 변수가 상대적으로 덜 영향을 준다. 변수가 적은 tight loop(findIndexHashed)는
   추가 변수가 레지스터 spill을 유발할 수 있다.

2. **PutMiss@784K 노이즈는 여전히 심각하다.**
   이번에도 ±108% 오차. 784K 크기에서의 PutMiss는 현재 측정 방법으로 신뢰할 수 없다.
   더 많은 fork/iteration이 필요하거나, 측정 방법 자체를 재검토해야 한다.

3. **최적화의 이식성은 보장되지 않는다.**
   한 함수에서 효과적인 패턴이 다른 함수에서는 역효과를 낼 수 있다.
   JIT는 함수 전체를 하나의 단위로 컴파일하므로, 부분적인 패턴 이식은 위험하다.

## 다음에 시도할 방향

### Candidate A: putValHashed 내 miss path 분리
`putValHashed`의 hit path와 miss path를 JIT-친화적으로 분리.
현재 하나의 함수에서 `firstTombstone` 추적, `emptyBits` 처리, `insertAt` 호출이 혼재.
miss path를 별도 cold path로 분리하면 hit path의 코드 크기가 줄어 JIT 최적화 개선 가능.

### Candidate B: PutMiss@784K 재측정 (fork=5)
jmh.fork=5 또는 jmh.iterations=5로 PutMiss@784K 측정 신뢰도 향상.
iter-002의 67.8 ns vs 현재 ~76-102 ns 범위에서 실제 값 파악.

### Candidate C: 탐색 단계(probing) 카운터 최적화
`step++`가 루프마다 실행되는 branch/register 비용을 줄이기 위해,
triangular probing 대신 linear probing 또는 group 순서를 미리 계산하는 방식.

### 최우선 추천: Candidate A (putValHashed miss path cold-path 분리)
hit path를 더 타이트하게 만들면 PutHit@12K -24%를 유지하면서
miss path도 개선할 가능성이 있다.
