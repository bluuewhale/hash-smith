# Reflexion: iter-013-bit-disjoint-h2

## 무엇을 시도했는가
h1/h2 비트 독립성 보장을 위해 완전 비겹침 비트 분할:
- h2 = `(hash >>> 25) & 0x7F` — 상위 7비트 (bits 31..25)
- h1 = `hash & 0x01FFFFFF`   — 하위 25비트 (bits 24..0)

iter-012의 h1/h2 겹침 문제(h1=hash 전체 사용)를 정확히 수정한 버전.

## 결과
- PutHit@12K:   7.042 ns  (-11.1% vs baseline, +18.3% worse vs iter-011)
- PutHit@784K: 33.587 ns  (+17.1% vs baseline ❌)
- PutMiss@12K: 21.012 ns  (+13.9% vs baseline ❌)

REVERT — 두 개의 primary metric이 baseline 대비 10% 이상 악화.

## 왜 실패했는가

### Group selection이 약한 비트를 사용하게 됨 (핵심 원인)

현재(iter-011): `h1 = hash >>> 7` → group 선택 = hash의 bits [7+k-1 .. 7]
iter-013:       `h1 = hash & 0x01FFFFFF` → group 선택 = hash의 bits [k-1 .. 0]

Fibonacci 곱셈 해시에서 **하위 비트는 상위 비트보다 분포가 나쁘다**.
곱셈의 올림수(carry)는 하위에서 상위로 전파되므로, 상위 비트가 입력 비트 전체에 의존하는 반면
하위 비트는 입력의 하위 비트에만 주로 의존한다.

결과: h1의 하위 비트로 group을 선택하면 group 분포 불균형 → probe chain 증가 →
특히 큰 테이블(784K)에서 PutHit 및 PutMiss 성능 악화.

### 왜 PutHit@12K는 개선되었나 (-11.1%)
12K 테이블은 nGroups가 작아(~200개) group 분포 불균형의 영향이 적다.
h2 품질 개선(상위 비트 사용)이 일부 false-positive 감소로 이어져 작은 이득이 있었다.
하지만 784K에서는 group 분포 문제가 압도적.

## 핵심 교훈
**SwissTable에서 Fibonacci 해시 사용 시 최적 비트 분할은 현재 설계가 이미 최적:**
- `h1 = hash >>> 7` — 중간~상위 비트 사용 (group 선택에 강한 비트)
- `h2 = hash & 0x7F` — 하위 7비트 (fingerprint, false-positive rate ~0.78% 허용 가능)

h2에 상위 비트를 쓰면 fingerprint 품질은 좋아지지만 h1에 하위(약한) 비트를 강제 배치해야 해
더 큰 손해가 발생한다. 비트 독립성보다 group 선택 비트의 품질이 더 중요한 요소.

## h1/h2 비트 분할 방향 영구 폐쇄
- `h2 = hash >>> 25`, `h1 = hash` (iter-012): ❌ — h1/h2 겹침으로 false-positive 폭발
- `h2 = (hash >>> 25) & 0x7F`, `h1 = hash & 0x1FFFFFF` (iter-013): ❌ — h1 하위 비트 품질 저하
- 현재 `h1 = hash >>> 7`, `h2 = hash & 0x7F`: ✅ 최적

**결론: h1/h2 비트 분할 최적화 방향은 완전히 소진됨. 다음 iteration은 다른 방향을 시도해야 함.**

## 다음 iteration 후보

### Candidate A: putVal SWAR 루프 내 early-exit 최적화
tombstones==0 전용 루프에서 `hasEmpty` SWAR을 루프 조건으로 호이스팅.
현재: 그룹이 꽉 차도 eqMask 계산 후 다음 그룹 이동.
개선: `hasEmpty(word) == 0`이면 eqMask 계산 건너뛰고 즉시 next group.
예상 효과: PutMiss (insert path) 성능 개선.

### Candidate B: insertAt inlining
`insertAt()` 호출을 putValHashed 인라인으로 합침.
JIT이 h2 저장 주변 코드를 더 잘 최적화할 수 있음.
iter-008에서 blocked되었으나 코드 구조가 변경된 현재 재시도 가치 있음.

### Candidate C: `@ForceInline` / `@IntrinsicCandidate` 어노테이션 탐색
SWAR 헬퍼 메서드들이 실제로 인라인되는지 JIT 출력 확인.
