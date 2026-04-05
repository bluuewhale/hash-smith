# iter-002 Reflexion: fast-empty-detect

## 무엇을 배웠는가

per-group SWAR 연산 비용 감소가 miss probe 경로에서 큰 효과를 냄.
- iter-001: tombstone guard로 DELETED_BROADCAST eqMask 제거 → +30%
- iter-002: hasEmpty로 EMPTY_BROADCAST eqMask 제거 (+ slot offset 직접 추출) → 추가 +14%

두 이터레이션 합산: baseline 112.9 → 67.8 ns/op, **40% 개선**.

## 왜 효과가 있었는가

784K 크기 테이블에서 miss probe는 여러 group을 순회한다.
각 group에서 기존에는:
1. `eqMask(word, h2Broadcast)` — h2 매칭 (5 ops + mul)
2. `eqMask(word, DELETED_BROADCAST)` — tombstone (5 ops + mul)  ← iter-001에서 제거
3. `eqMask(word, EMPTY_BROADCAST)` — empty 탐지 (5 ops + mul) ← iter-002에서 4 ops + 직접 offset으로 대체

non-terminal group(empty 없는 group)에서는 3번이 완전히 불필요하며,
terminal group에서는 multiply 없이 4 ops로 대체됨.
probe depth가 길수록 이 per-group 절약이 누적됨.

## PutHit 소폭 추가 회귀 원인

hasEmpty 계산(4 ops)이 hit 경로에서 항상 수행됨.
기존에는 hit 경로에서 emptyMask 계산이 거의 도달하지 않았으나,
이제는 eqMask hit 후에도 hasEmpty가 실행될 수 있음.
또한 JIT 코드 레이아웃 변화로 인한 분기 예측 패턴 변화 가능.

## 다음에 시도할 방향

### 현재 상태 분석
- PutMiss@784K: 67.8 ns (baseline 112.9 ns, -40%)
- PutHit@784K: 30.7 ns (baseline 28.7 ns, +7% 회귀)
- Hit 경로 회귀를 줄이는 것이 다음 과제

### Candidate A: eqMask와 hasEmpty 병렬화 (hoisting)
현재 루프에서 `eqMask(word, h2Broadcast)`를 먼저 계산하고, 이후 `hasEmpty(word)`를 계산.
hit이 없는 경우 둘 다 필요하므로, 두 값을 동시에 계산하도록 순서 조정:
```java
int eqM = eqMask(word, h2Broadcast);
long emptyBits = hasEmpty(word); // hoist next to eqM computation
if (eqM != 0) { /* check keys */ }
if (emptyBits != 0) { /* insert */ }
```
CPU 파이프라인에서 두 연산이 겹쳐 실행될 수 있음 (instruction-level parallelism).

### Candidate B: PutHit 경로 복구 — hasEmpty를 hit 이후로 지연
hit 경로에서는 eqMask 성공 직후 return되므로 hasEmpty는 도달하지 않아야 함.
현재 코드 구조를 확인하여, hasEmpty가 hit 경로에 영향을 주지 않는지 검증.
만약 JIT이 루프를 펼치며 불필요한 계산을 추가했다면, `@CompilerControl(DONT_INLINE)` 등으로 제어.

### Candidate C: h2 계산 개선 — h1/h2 분리 비용 측정
`h1 = hash >>> 7`, `h2 = hash & 0x7F` 두 연산은 저렴하지만,
`broadcast(h2)` 가 루프 외부에서 한 번 수행되므로 현재는 최적.
대신 `hashNonNull`의 비용이 상당할 수 있음 — JIT ASM 확인 권장.

### 최우선 추천: Candidate A (ILP hoisting)
두 독립적인 SWAR 계산(eqMask + hasEmpty)을 인접하게 배치하면
OOO(out-of-order) CPU에서 동시 실행 가능.
hit 경로 회귀도 감소 가능성 있음 (hasEmpty 결과를 hit 성공 시 throw away하므로 무해).
