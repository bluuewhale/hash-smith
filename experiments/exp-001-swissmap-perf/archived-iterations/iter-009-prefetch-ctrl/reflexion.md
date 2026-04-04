# Reflexion: iter-009-prefetch-ctrl

## 무엇을 시도했는가

**1차 시도: Unsafe.prefetchRead 소프트웨어 프리페치**

iter-008 힌트에 따라 `sun.misc.Unsafe.prefetchRead(Object, long)` 인트린직으로
다음 probe group ctrl[] 주소를 미리 L1 캐시에 올리는 전략을 탐색했다.

구현 결과: JDK 21 (Temurin 21.0.9)에서 `sun.misc.Unsafe.prefetchRead`가 완전히 제거됨.
`jdk.internal.misc.Unsafe`도 prefetch 메서드 없음. **이 전략은 JDK 21에서 불가능하다.**

**2차 시도: h2 핑거프린트 XOR 폴딩**

- 변경: `h2 = hash & 0x7F` → `h2 = (hash ^ (hash >>> 25)) & 0x7F`
- 가설: 상위 비트 [25:31]을 XOR해 h2 엔트로피 증가 → false-positive eqMask 감소
- 결과: PutMiss@784K 60.1ns → 106.5ns (+77.1% 퇴보) ❌

## 결과

REVERT. PutMiss@784K +77% 퇴보 → 즉각 복원.

## 왜 실패했는가

### 가설 1: h2 XOR 폴딩이 Murmur3 혼합 품질을 저하시킴

Murmur3 smear는 이미 모든 32비트에 좋은 avalanche를 제공한다.
`(hash ^ (hash >>> 25)) & 0x7F`는 비트 [25:31]을 [0:6]과 XOR하는데,
String 키의 hashCode 패턴에서 고비트와 저비트 사이의 상관관계가 생길 수 있다.
이는 특정 h2 값이 과잉 표현되어 eqMask false-positive가 증가한다.

### 가설 2: h2 변경이 h1/h2 독립성을 파괴

SwissTable의 설계 원칙: h1(group 선택)과 h2(핑거프린트)는 독립적이어야 한다.
기존 `hash & 0x7F` (bit 0-6)와 `hash >>> 7` (bit 7+)는 완전히 독립.
XOR 폴딩으로 h2에 상위 비트를 포함시키면, h1과 h2 사이의 상관관계가 생겨
같은 group에 있는 키들이 더 높은 확률로 h2가 일치하게 된다 → probe chain 증가.

### 핵심 패턴 확인

**iter-008의 제약 (`putValHashed` 바이트코드 크기 고정)과 JDK 21의 prefetch API 부재가 조합되면,
PutHit@784K / PutMiss@784K를 `putValHashed` 내부 변경 없이 개선하는 것은 사실상 불가능하다.**

## 핵심 교훈

1. **JDK 21에는 Java 순수 코드로 PREFETCH 명령을 emit하는 방법이 없다.**
   - `sun.misc.Unsafe.prefetchRead` → JDK 21에서 제거됨
   - `jdk.internal.misc.Unsafe` → prefetch 메서드 없음
   - VarHandle, MemorySegment → prefetch API 없음

2. **h2는 Murmur3 smear 결과의 저비트를 그대로 사용하는 것이 최적이다.**
   - 고비트 XOR 추가 시 h1/h2 상관관계 발생 가능

3. **iter-006 상태는 현재 JDK/JIT 환경에서 해당 코드 구조의 성능 천장에 가깝다.**
   - PutHit@784K ≈ 28ns: L3 캐시 latency 한계 근처
   - PutMiss@784K ≈ 60ns: 이미 baseline 대비 -46.7%

## 다음 iteration 후보

### Candidate A: JNI 기반 prefetch shim
- C/JNI 레이어에서 `__builtin_prefetch` 또는 `_mm_prefetch` 호출
- 위험: JNI 오버헤드가 prefetch 효과를 무의미하게 만들 수 있음
- 선행 조건: JNI 호출 자체가 ~10ns 이하인지 검증 필요

### Candidate B: Interleaved key-value layout (Object[] entries)
- `keys[]` + `vals[]` → `Object[] entries` where `entries[2i]=key, entries[2i+1]=val`
- 효과: PutHit 시 key load와 val load가 동일 캐시 라인 → miss 1회 절감
- 위험: `putValHashed`의 `keys[idx]` → `entries[idx<<1]` 변환은 bytecode 1개 추가
  → iter-008 제약과 충돌. 단, ishl 1개 추가는 insertAt inline (5+개) 과 다를 수 있음.
- 예상 효과: PutHit@784K 28ns → 19ns (-33%)

### Candidate C: putValHashed 분기 구조 재검토
- tombstones==0 fast path에서 eqM 내부 while loop과 emptyBits 체크 순서 최적화
- 현재 코드가 이미 ILP-optimal인지 jitAsm으로 재검증

### 최우선 추천: Candidate B (interleaved layout)
이론적 근거가 가장 강하고 (-33% PutHit@784K), 구현 가능성 있음.
bytecode 크기 증가가 1개 ishl에 그친다면 JIT budget에서 iter-008과 다른 결과 나올 수 있음.
단, `putValHashed` + 전체 구조 변경으로 테스트 커버리지 확인 필수.
