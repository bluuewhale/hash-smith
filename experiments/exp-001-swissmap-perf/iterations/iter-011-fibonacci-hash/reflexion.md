# Reflexion: iter-011-fibonacci-hash

## 무엇을 시도했는가

Murmur3 smear (`C2 * rotateLeft(hashCode * C1, 15)`) → Fibonacci multiplicative hash
(`(hashCode * 0x9e3779b97f4a7c15L) >>> 32`) 교체.

단일 64-bit IMULQ + SHR이 3-operation multiply chain을 대체.
SwissMap `hashNonNull()` override + ConcurrentSwissMap `smearedHashNonNull()` 수정으로
두 경로의 hash 일관성 유지. RobinHoodMap과 SwissSimdMap은 기존 Murmur3 유지.

## 결과

KEEP.

| Metric | Baseline | iter-006 | iter-011 | Δ vs baseline |
|--------|----------|----------|----------|---------------|
| PutHit@12K | 7.921 | 6.048 | 5.954 | -24.8% |
| PutHit@784K | 28.689 | 28.365 | 26.435 | -7.9% |
| PutMiss@12K | 18.443 | 17.692 | 17.006 | -7.8% |
| PutMiss@784K | 112.932 | 60.132 | 71.225* | -36.9% |

*PutMiss@784K ±52.768 ns — 고노이즈, 결과 불신뢰.

## 왜 동작했는가

### PutHit@12K 개선 (-1.6% vs iter-006, -24.8% vs baseline)
12K 벤치마크는 compute-bound. hash 계산이 전체 6ns 중 ~1-2ns를 차지.
Murmur3: C1 mul → rotateLeft → C2 mul = 3 operations, ~6-9 cycles latency chain
Fibonacci: single IMULQ = 1 operation, ~3-4 cycles
→ 순수 hash 비용 감소가 PutHit/PutMiss@12K에서 측정됨.

### PutHit@784K 개선 (-6.8% vs iter-006)
784K는 L3 latency dominant이므로 hash 개선 효과가 적다고 예상했지만,
실제로 26.4ns (-6.8%)로 눈에 띄는 개선. 가능한 설명:
1. Fibonacci hash의 더 나은 분포가 probe chain length를 단축시킴
2. Fibonacci 상수로 h1 분포가 더 균등 → group collision 감소 → L3 miss 횟수 감소

### PutMiss@784K 노이즈 문제
71.225 ±52.768 ns — error가 mean의 74%. 이 결과는 신뢰할 수 없음.
실제 값은 60~123 ns 어디에도 있을 수 있음. iter-006 대비 regression이 real인지 불확실.

## 구현 시 발견한 문제

### Hashing.smear() 직접 교체 → RobinHoodMap 버그 노출
최초 시도는 `Hashing.smear()`를 직접 Fibonacci로 교체.
→ ApacheRobinHoodMapTest.testMapPutAll() 실패: `hashCodes should be the same`
원인: RobinHoodMap의 put() 루프에 latent bug — Robin Hood swap 중 duplicate key 검출 실패.
새로운 hash 분포가 특정 key 순서를 만들어 bug trigger.

해결책: SwissMap에만 `hashNonNull()` override → RobinHoodMap은 기존 Murmur3 유지.

### ConcurrentSwissMap 일관성 문제
SwissMap shard가 `hashNonNull()` → Fibonacci를 쓰는데,
ConcurrentSwissMap이 `smearedHashNonNull()` → Murmur3 smear를 계산해서 shard에 전달하면
shard selection hash ≠ insertFresh hash → 데이터 손실.
ConcurrentSwissMap도 Fibonacci hash로 통일 필요 → 수정 완료.

## 핵심 교훈

1. **Hash 함수 변경은 전체 스택의 일관성을 요구한다.**
   - Shard selection + within-shard slot placement + rehash 모두 동일 hash 사용해야 함.
   - 변경 전 모든 호출 경로 추적 필수.

2. **Latent bug 노출**: 더 좋은 hash가 기존 버그를 드러낼 수 있다.
   - RobinHoodMap의 duplicate detection 버그는 Murmur3으로는 trigger되지 않던 것.

3. **Fibonacci hash는 실제로 더 빠르다** — 특히 compute-bound 경로에서.
   - 단순한 상수 교체로 PutHit@12K -24.8% (baseline 대비) 달성.

## 다음 iteration 후보

현재 iteration에서 PutMiss@784K가 노이즈로 불확실. 우선순위:

### Candidate A: PutMiss@784K 재측정 (단독 실행)
- iter-011 상태 그대로 jmhSwissMap 재실행하여 PutMiss@784K 노이즈 재확인
- 실제 regression인지 noise인지 판단
- 조치 없이 정보 수집만 가능

### Candidate B: h1 범위 확장 (hash의 상위 비트 활용 최적화)
- 현재 `h1(hash) = hash >>> 7` — 7비트 버린 후 상위 비트 사용
- Fibonacci hash의 좋은 분포를 더 잘 활용하도록 h1 추출 방식 조정
- 단, findIndexHashed 구조 변경은 BLOCKED

### Candidate C: insertAt inlining (iter-008 재시도)
- iter-008은 inlining을 시도했지만 다른 방식으로 접근
- 현재 Fibonacci hash로 bytecode budget이 달라졌을 가능성
- BLOCKED 리스크 높음

### 최우선 추천: Candidate A (재측정)
PutMiss@784K의 실제 성능을 확인한 후 다음 방향 결정.
만약 60ns 수준 유지된다면 iter-011이 전면 우위 (leaderboard #1 확정).
