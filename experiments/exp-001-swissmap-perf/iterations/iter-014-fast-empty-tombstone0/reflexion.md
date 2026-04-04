# Reflexion: iter-014-fast-empty-tombstone0

## 무엇을 시도했는가
`putValHashed` tombstones==0 fast path에서 `hasEmpty(word)` (XOR + subtract + AND, 3-op SWAR)를
`word & BITMASK_MSB` (1-op AND)으로 교체.

**수학적 근거**: tombstones==0 시 DELETED(0xFE, bit7=1)가 존재하지 않으므로
FULL(0x00-0x7F, bit7=0)과 EMPTY(0x80, bit7=1)만 존재 → 단순 MSB AND로 충분.

## 결과
| 지표 | iter-011 기준 | iter-014 결과 | 변화 |
|---|---|---|---|
| PutHit@12K | 5.954 ns | 10.788 ± 14.627 ns | +81% ❌ |
| PutHit@784K | 26.435 ns | 31.058 ± 27.539 ns | +18% ❌ |
| PutMiss@12K | 17.006 ns | 19.671 ± 9.101 ns | +16% ❌ |

**결정: REVERT**

## 왜 실패했는가

### 노이즈 문제
PutHit@12K의 오차가 ±14.627 (측정값 10.788의 135%)으로 비정상적으로 크다.
실제 성능 변화는 중립일 가능성이 높지만, 프로토콜상 >10% 악화 시 revert 필수.

### JIT 이미 최적화
`hasEmpty()`의 `(x - BITMASK_LSB) & ~x & BITMASK_MSB` 체인은
JDK 21 JIT에서 hasZero 패턴으로 인식되어 최적 어셈블리를 생성할 가능성이 높다.
단순 AND로 교체해도 얻는 이득이 없거나 코드 레이아웃이 변경되어 손해.

### Bytecode budget 영향
iter-008에서 확인: `putValHashed`의 bytecode size가 JIT 최적 임계치에 있음.
코드 라인을 제거해도 다른 변화가 JIT inlining 결정에 영향을 줄 수 있음.

### OOO ILP 간섭 가능성
3-op 체인이 eqMask 체인과 함께 OOO CPU에서 병렬 실행될 때
단일 AND보다 파이프라인을 더 효율적으로 활용할 수 있음.

## 핵심 교훈
- `hasEmpty()` SWAR 표현 단순화 방향 폐쇄 — JIT이 이미 처리함
- 수학적으로 옳은 단순화가 반드시 성능 개선으로 이어지지 않음
- 벤치마크 노이즈가 크면 2-3회 재측정 필요 (현재 프로토콜은 단일 run)

## 다음 iteration 후보

### Candidate A: insertAt 인라이닝 재시도 (Candidate B from iter-013)
iter-008에서 실패했으나, iter-006/iter-011 이후 코드 구조 변경으로 재시도 가능.
tombstones==0 fast path에서 insertAt() 호출을 인라인으로 전개하면
JIT이 h2 저장 주변 최적화를 더 잘 수행할 수 있음.
예상 위험: bytecode size 증가 → JIT threshold 초과 가능.

### Candidate B: `@ForceInline` 어노테이션 탐색
JDK 내부 전용이지만, 대안으로 `putValHashed`를 분리하여 JIT이 더 강하게 최적화하도록 유도.
또는 `eqMask()`/`hasEmpty()`를 static final private으로 명시하여 인라이닝 강제.

### Candidate C: 벤치마크 노이즈 근본 원인 조사
오차 ±14.6 ns는 비정상적. JMH fork/warmup 설정 재검토 또는 
`@Fork(3)`, `@Warmup(iterations=5)` 증가로 안정적 측정 환경 확보.

### Candidate D: load factor 조정 (0.875 → 0.75)
더 낮은 load factor로 평균 probe chain 길이 감소.
메모리 사용 증가 대가로 PutMiss/PutHit 모두 개선 가능.
API는 `new SwissMap(n, 0.75)` 지원하므로 호환성 문제 없음.
단: 기본값 변경이므로 기존 동작 변경 위험.
