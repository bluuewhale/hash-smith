# Reflexion: iter-008-insertat-inline

## 무엇을 시도했는가

`tombstones==0` fast path에서 `insertAt()` 호출을 인라인으로 대체.
목표: method call overhead + dead `isDeleted` branch 제거.

## 결과

REVERT. PutMiss@784K: 60.132 → 117.143 ns (+94.8% regression vs iter-006).
PutHit@12K는 약간 개선 (-1.7%), 나머지는 noise 범위.

## 왜 실패했는가

### 가설 1: JIT C2 inline budget 초과

`putValHashed`의 bytecode 크기가 커지면서 JIT C2가 이 메서드를 호출 사이트에서
인라인하는 것을 포기했을 가능성. 784K size의 PutMiss는 large working set으로
cache miss가 많고, JIT 최적화 품질에 더 민감하다.

### 가설 2: 느린 경로의 register pressure 변화

Fast path와 slow path는 같은 JIT compilation unit 내에 있다.
Fast path에 4개의 인라인 명령어를 추가하면, 컴파일러의 register allocator가
slow path 코드에도 영향을 준다. 특히 784K에서는 loop iteration이 많아
register spill 비용이 누적된다.

### 가설 3: 패턴 — 큰 메서드는 건드리지 말라

iter-005 교훈과 반대되는 새 교훈:
- iter-005: 메서드 분리 → 실패 (call boundary 증가)
- iter-008: 코드 인라인 → 실패 (메서드 크기 증가)

`putValHashed`는 이미 JIT가 최적화한 크기에 있다.
이 메서드의 크기를 어느 방향으로도 바꾸는 것이 위험하다.

## 핵심 교훈

**"putValHashed의 크기를 늘리는 변경은 slow path (PutMiss@784K)를 망친다."**

- Fast path 전용 인라인이라도 같은 컴파일 단위 내 slow path에 영향을 준다
- JIT은 메서드 전체를 하나의 compilation unit으로 본다
- Fast path와 slow path의 register/instruction scheduling은 연결되어 있다

## 다음 iteration 후보

### Candidate A: PutHit@784K 개선 — group prefetch via fake-read (iter-007 reflexion)
next probe group 주소를 미리 L1에 올리기 위한 소프트웨어 prefetch.
PutHit@784K는 28.4 ns 수준 — 여기서 개선 여지 있음.

### Candidate B: PutMiss@784K slow path — tombstones>0 경로 최적화
slow path의 `insertAt` 호출 자체는 PutMiss@784K에서 거의 불리지 않는다
(tombstones==0이 대부분). 784K PutMiss의 진짜 병목은 probe chain 길이.
h1 분산을 개선하거나, group probe 횟수를 줄이는 방향.

### Candidate C: `size++` hoisting + ILP (putValHashed 내부)
fast path에서 `size++`를 이른 위치로 이동하여 OOO CPU가 파이프라인을 채우도록.
주의: 메서드 크기는 변경하지 않아야 한다.

### 최우선 추천: Candidate A (group prefetch)

putValHashed 메서드 크기를 건드리지 않고,
런타임 힌트(prefetch intrinsic)만 추가하여 memory latency를 은닉.
Java의 `Unsafe.prefetchRead` 또는 VarHandle 기반 prefetch 탐색 필요.
단, Java에서 prefetch intrinsic이 실제로 어셈블리 PREFETCH 명령으로 나오는지
jitAsm으로 확인이 필수.
