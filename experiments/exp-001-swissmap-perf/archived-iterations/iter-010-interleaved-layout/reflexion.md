# Reflexion: iter-010-interleaved-layout

## 무엇을 시도했는가

`keys[]` + `vals[]` 두 개의 별도 배열을 `Object[] entries`로 통합.
`entries[2*i] = key`, `entries[2*i+1] = val` — key와 val을 동일 캐시 라인에 배치.
이론적 효과: PutHit 시 key load 후 val load가 같은 64바이트 캐시 라인에 위치 → L3 miss 1회 절감.
총 메모리: entries[] = 7MB = keys[] + vals[] = 동일 (추가 GC 압력 없음).

## 결과

REVERT. PutMiss@784K 60.1ns → 104.6ns (+74% 퇴보), PutHit@784K 28.4ns → 31.3ns (+10.3%).
성공 기준 미달 (10%+ 개선 없음), iter-006 대비 전면 퇴보.

## 왜 실패했는가

### 가설 1: entries[] stride 증가가 HW prefetcher를 무력화

기존 keys[]: slot i에서 i+1로 이동 시 stride = 4 bytes (compressed oops).
interleaved entries[]: slot i의 key에서 다음 slot i+1의 key로 이동 시 stride = 8 bytes.
HW prefetcher는 2x stride 증가로 캐시 라인 예측이 어려워짐.
SWAR로 group 내 8슬롯을 순회할 때, keys[base], keys[base+1], ..., keys[base+7]이
기존에는 단일 캐시 라인(32 bytes)에 모여 있었지만,
interleaved에서는 entries[base*2], entries[base*2+2], ... → stride 8바이트로 캐시 라인 2개 필요.

**이것이 핵심 패배 원인**: key와 val을 co-locate해서 val access를 절약했지만,
group 내 key들의 밀도가 절반으로 떨어져 각 group scan당 캐시 라인 사용량이 2배 증가.

### 가설 2: PutMiss 퇴보는 empty slot 탐색 패턴 악화

PutMiss 경로는 빈 slot을 찾을 때까지 여러 group을 스캔한다.
interleaved layout에서 각 group의 key-access는 더 넓은 메모리 영역을 건드림.
PutMiss@784K 60→104ns: 기존 loop-specialization의 SWAR emptyBits 최적화가
interleaved layout에서 cache miss 증가로 상쇄됨.

### 가설 3: JIT bytecode budget (부수 요인)

`int ei = idx << 1` + `entries[ei]` + `entries[ei | 1]`는
기존 `keys[idx]` + `vals[idx]`와 비교해 bytecode가 비슷하거나 약간 많음.
iter-008에서 확인된 bytecode budget 민감도가 있었지만, 이번에는 주 원인이 아님.
(PutHit@12K는 동일 = JIT inline budget은 통과함)

## 핵심 교훈

1. **Key 배열의 밀도가 캐시 효율에 결정적이다.**
   - 기존 keys[]: group 8개 key = 32 bytes = 캐시 반 라인
   - interleaved: group 8개 key = 64 bytes = 캐시 1 라인 (key-value 혼합)
   - HW prefetcher는 순차 접근 패턴에 최적화되어 있음. stride 2x 증가는 치명적.

2. **"같은 메모리 총량"이 "같은 캐시 효율"을 의미하지 않는다.**
   - 총 7MB = 동일하지만, key scan 밀도가 절반 → effective working set 증가

3. **Val co-location의 이점은 실제로 작다.**
   - PutHit hot path에서 val load는 key equality 확인 이후 발생
   - key는 group SWAR 후 로드, val은 1개만 로드 → val cache miss는 이미 rare
   - val을 key 옆에 두는 것보다 key group을 compact하게 유지하는 것이 더 중요

4. **이 실험으로 keys[]/vals[]의 현재 레이아웃이 이미 최적에 가깝다는 것이 확인됨.**

## 다음 iteration 후보

현재 상황: iter-006이 성능 천장에 가깝다. 남은 여지:
- PutHit@784K: 28.4ns (L3 latency 한계 ~30ns에 근접)
- PutMiss@784K: 60.1ns (baseline -46.7% 달성)

### Candidate A: ctrl[] prefetch via JNI shim
- `__builtin_prefetch(&ctrl[next_group])` 호출하는 C shim
- JNI 오버헤드 검증 필요 (10ns 이하여야 의미 있음)
- 단, JNI 경계 비용이 prefetch 효과를 초과할 가능성 높음

### Candidate B: 다른 hash function 실험
- 현재 Murmur3 smear → 더 빠른 hash (FNV, xxHash)로 교체
- PutHit/PutMiss 모두 hash 계산 포함 → 5~10% 절감 가능성
- 위험: hash 품질 저하 시 probe length 증가

### Candidate C: 메모리 레이아웃 직접 최적화 — ctrl[] 압축
- 현재: ctrl[] = long[] (8 bytes/group), keys[] = Object[capacity]
- ctrl[]은 이미 compact. keys[]에서 각 slot = 4 bytes (compressed oop).
- 개선 여지 없음.

### Candidate D: tombstones==0 fast path에서 group-level empty 조기 종료
- 현재 hasEmpty()가 group마다 계산되지만, step==0 첫 group에서 eqMask 비어있으면
  즉시 empty check 없이 다음 group으로 가는 최적화
- 단, iter-003/006 이미 ILP 최적화 완료 → 추가 여지 불분명

### 최우선 추천: Candidate B (hash function 교체)
이론적으로 hash 속도가 put의 일부 시간을 차지하므로, 더 빠른 hash로 교체 시
PutHit@12K와 PutMiss@12K에서 효과 볼 수 있음. 784K는 L3 latency dominant.
단, Murmur3 smear의 품질이 이미 최적화되어 있으므로 대체 hash의 분포 품질 검증 필수.
