# Reflexion: iter-015-restore-h1h2-split

## 무엇을 시도했는가
1. **Load factor tuning (0.875 → 0.75) 수학적 분석**: 원래 전략
2. **SwissSimdMap.putVal() tombstones==0 fast path**: 실제 구현한 전략

## 결과
| 지표 | 기준선 | iter-015 결과 | 변화 |
|---|---|---|---|
| PutHit@12K | 7.921 ns | 9.859 ± 21.293 ns | +24.5% ❌ (216% 노이즈) |
| PutHit@784K | 28.689 ns | 32.762 ± 44.864 ns | +14.2% ❌ (137% 노이즈) |
| PutMiss@12K | 18.443 ns | 16.307 ± 14.886 ns | -11.6% ✅ (91% 노이즈) |
| PutMiss@784K | 112.932 ns | 119.142 ± 363.633 ns | +5.5% ~ (완전 노이즈) |

**결정: REVERT** (>10% 기준선 대비 악화 규칙 적용)

## 왜 실패했는가

### 근본 발견 1: Load factor 변경은 no-op
- Benchmark 크기 (12K/48K/196K/784K)는 정확히 74%의 점유율로 설계됨
- 기준선 LF=0.875일 때: maxLoad@cap=16384 = 14336 → 12000 < 14336 (resize 없음)
- 새 LF=0.75일 때: maxLoad@cap=16384 = 12288 → 12000 < 12288 (resize 없음)
- 두 경우 모두 최종 capacity = 16384, 점유율 = 73.2% (동일)
- **수학적으로 zero effect. 구현 불필요.**

### 근본 발견 2: Benchmark가 SwissSimdMap을 측정함
- `jmhSwissMap`은 `swissSimdPutHit`와 `swissSimdPutMiss`만 활성화 (@Benchmark)
- `swissPutHit`, `swissPutMiss`는 모두 `//` 주석 처리
- SwissSimdMap은 SwissMap과 독립적인 own putVal() 사용 (SIMD ByteVector)
- **SwissMap.java의 변경이 benchmark에 직접 영향 없음**
- iter-001~014의 개선/회귀는 SwissSimdMap 측정값의 노이즈였을 가능성 높음

### 근본 발견 3: 극심한 측정 노이즈
- fork=1, warmup=2, measure=3 설정으로 신뢰성 없는 결과 생성
- PutHit@12K: ±21.293 = 216% of score → 측정값 무의미
- 기준선 PutHit@12K: ±0.649 = 8% noise (깨끗)와 극명한 대조
- 동일 코드에서도 5.954ns (iter-011 결과)에서 9.859ns로 65% 변동

### 왜 SwissSimdMap 최적화도 확인 불가?
- 코드 변경은 이론적으로 타당 (tombstones==0에서 SIMD op 1개 제거)
- PutMiss@12K의 -11.6% 개선이 실제 효과를 시사할 수 있으나 91% 노이즈로 불확실
- 노이즈 문제가 해결되지 않는 한 어떤 변경도 확인 불가능

## 핵심 교훈

1. **Load factor 분석을 먼저 해야 함**: 수학적 분석으로 no-op임을 사전에 확인 가능했음 (실제로 확인함)

2. **Benchmark 대상 확인 필수**: 최적화 전 `@Benchmark` 어노테이션 활성화 확인 필요
   - 현재: SwissSimdMap만 측정
   - SwissMap 최적화를 측정하려면 MapBenchmark.java에서 `swissPutHit`/`swissPutMiss` 활성화 필요

3. **노이즈가 모든 것을 가림**: 현재 JMH 설정 (fork=1, wi=2, i=3)은 일관된 결과 불가
   - 기준선 측정 당시: ±0.649 (8%)
   - 최근 측정: ±21.293 (216%)
   - 머신 상태, 백그라운드 프로세스, JVM warmup 편차가 원인

## 다음 iteration 후보

### Candidate A: 벤치마크 노이즈 근본 해결 (최우선)
MapBenchmark.java에서 SwissMap @Benchmark 활성화:
- `@Benchmark` 주석 해제: `swissPutHit`, `swissPutMiss`
- `swissSimdPutHit`, `swissSimdPutMiss` 비활성화 (또는 유지)
- 이렇게 하면 SwissMap.java 변경이 실제로 측정됨
- JMH 설정도 fork=2, warmup=5로 높여 노이즈 감소 검토

### Candidate B: SwissSimdMap tombstones==0 fast path (재측정)
iter-015의 변경은 이론적으로 타당. 노이즈가 해결되면 재시도.
PutMiss@12K에서 -11.6% 시사값이 있음.

### Candidate C: SwissMap @Benchmark 활성화 후 이전 최적화 재검증
SwissMap이 실제로 최적화된 상태 (iter-006, iter-011 등)인지 SwissMap 벤치마크로 확인.
