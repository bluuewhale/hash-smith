package io.github.bluuewhale.hashsmith;

import java.util.HashMap;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

@Fork(
    value=1,
    jvmArgsAppend = {
        "--add-modules=jdk.incubator.vector",
        "--enable-preview",
//        "-Xms1g",
//        "-Xmx1g",
//        "-XX:+AlwaysPreTouch",
//        "-XX:+UnlockDiagnosticVMOptions",
//        "-XX:+DebugNonSafepoints",
//        "-XX:StartFlightRecording=name=JMHProfile,filename=jmh-profile.jfr,settings=profile",
//        "-XX:FlightRecorderOptions=stackdepth=256"
    }
)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MapBenchmark {

	private static String randomUuidString(Random rnd) {
		return new UUID(rnd.nextLong(), rnd.nextLong()).toString();
	}

	/**
	 * Generates UUID-string keys and miss-keys such that:
	 * - keys are unique
	 * - misses are unique
	 * - misses never overlap with keys
	 */
	private static void generateKeysAndMisses(Random rnd, String[] keys, String[] misses) {
		if (keys.length != misses.length) throw new IllegalArgumentException("keys and misses must have same length");
		int size = keys.length;
		var set = new java.util.HashSet<String>(size * 2);
		for (int i = 0; i < size; i++) {
			String k;
			do { k = randomUuidString(rnd); } while (!set.add(k));
			keys[i] = k;
		}
		for (int i = 0; i < size; i++) {
			String miss;
			do { miss = randomUuidString(rnd); } while (set.contains(miss));
			misses[i] = miss;
			set.add(miss); // ensures misses are unique as well
		}
	}

	@State(Scope.Benchmark)
	public static class ReadState {
        @Param({ "12000", "48000", "196000", "784000" }) // load factor equals to 74.x% (right before resizing)
		int size;

		SwissSimdMap<String, Object> swissSimd;
		SwissMap<String, Object> swiss;
		Object2ObjectOpenHashMap<String, Object> fastutil;
		UnifiedMap<String, Object> unified;
		HashMap<String, Object> jdk;
		String[] keys;
		String[] misses;
		Random rnd;
		int nextKeyIndex;
		int nextMissIndex;
		Set<String> keySet;

		@Setup(Level.Trial)
		public void setup() {
			rnd = new Random(123);
			keys = new String[size];
			misses = new String[size];
			keySet = new java.util.HashSet<>(size * 2);
			for (int i = 0; i < size; i++) {
				var k = randomUuidString(rnd);
				keys[i] = k;
				keySet.add(k);
			}
			for (int i = 0; i < size; i++) {
				String miss;
				do { miss = randomUuidString(rnd); } while (keySet.contains(miss));
				misses[i] = miss;
			}
			nextKeyIndex = 0;
			nextMissIndex = 0;
			swiss = new SwissMap<>();
			swissSimd = new SwissSimdMap<>();
			fastutil = new Object2ObjectOpenHashMap<>();
			unified = new UnifiedMap<>();
			jdk = new HashMap<>();
			for (int i = 0; i < size; i++) {
				swiss.put(keys[i], "dummy");
				swissSimd.put(keys[i], "dummy");
				fastutil.put(keys[i], "dummy");
				unified.put(keys[i], "dummy");
				jdk.put(keys[i], "dummy");
			}
		}

		String nextHitKey() {
			var k = keys[nextKeyIndex];
			nextKeyIndex = (nextKeyIndex + 1) % keys.length;
			return k;
		}
		String nextMissingKey() {
			var k = misses[nextMissIndex];
			nextMissIndex = (nextMissIndex + 1) % misses.length;
			return k;
		}
	}

	@State(Scope.Thread)
	public static class PutHitState {
        @Param({ "12000", "48000", "196000", "784000" }) // load factor equals to 74.x% (right before resizing)
		int size;

		int idx;
		String[] keys;
		String[] misses;
		Random rnd;
		SwissSimdMap<String, Object> swissSimd;
		SwissMap<String, Object> swiss;
		Object2ObjectOpenHashMap<String, Object> fastutil;
		UnifiedMap<String, Object> unified;
		HashMap<String, Object> jdk;

		@Setup(Level.Trial)
		public void initKeys() {
			rnd = new Random(456);
			keys = new String[size];
			misses = new String[size];
			generateKeysAndMisses(rnd, keys, misses);
		}

		@Setup(Level.Iteration)
		public void resetMaps() {
			idx = 0;
			swiss = new SwissMap<>();
			swissSimd = new SwissSimdMap<>();
			fastutil = new Object2ObjectOpenHashMap<>();
			unified = new UnifiedMap<>();
			jdk = new HashMap<>();
			for (int i = 0; i < size; i++) {
				swiss.put(keys[i], "dummy");
				swissSimd.put(keys[i], "dummy");
				fastutil.put(keys[i], "dummy");
				unified.put(keys[i], "dummy");
				jdk.put(keys[i], "dummy");
			}
			idx = 0;
		}

		String nextHitKey() {
			var k = keys[idx];
			idx = (idx + 1) % keys.length;
			return k;
		}
		String nextValue() { return "dummy"; }
	}

	/**
	 * Keeps entry count constant at n by doing the compensating remove
	 * in {@code @Setup(Level.Invocation)} (excluded from measurement).
	 *
	 * Benchmark methods should only call {@code put(nextMissKey(), value)} so that the measured region is insertion only.
	 */
	@State(Scope.Thread)
	public static class PutMissState {
		@Param({ "12000", "48000", "196000", "784000" }) // load factor equals to 74.x% (right before resizing)
		int size;

		int idx;
		String[] keys;   // keys currently present in the maps
		String[] misses; // keys currently absent from the maps
		Random rnd;

		String nextKey;

		SwissSimdMap<String, Object> swissSimd;
		SwissMap<String, Object> swiss;
		Object2ObjectOpenHashMap<String, Object> fastutil;
		UnifiedMap<String, Object> unified;
		HashMap<String, Object> jdk;

		@Setup(Level.Trial)
		public void initKeys() {
			rnd = new Random(456);
			keys = new String[size];
			misses = new String[size];
			generateKeysAndMisses(rnd, keys, misses);
		}

		@Setup(Level.Iteration)
		public void resetMaps() {
			idx = 0;
			swissSimd = new SwissSimdMap<>();
			swiss = new SwissMap<>();
			fastutil = new Object2ObjectOpenHashMap<>();
			unified = new UnifiedMap<>();
			jdk = new HashMap<>();
			for (int i = 0; i < size; i++) {
				swissSimd.put(keys[i], "dummy");
				swiss.put(keys[i], "dummy");
				fastutil.put(keys[i], "dummy");
				unified.put(keys[i], "dummy");
				jdk.put(keys[i], "dummy");
			}
		}

		/**
		 * Prepares a steady-size insertion for the next benchmark invocation:
		 * - remove one existing key so size becomes n-1
		 * - choose one missing key for insertion
		 * - swap keys/misses so hit/miss invariants remain true for subsequent invocations
		 */
		@Setup(Level.Invocation)
		public void beforeInvocation() {
			String evictKey = keys[idx];
			// Note: SwissMap/SwissSimdMap now use quadratic probing; "tombstone-free delete via backshift"
			// is no longer compatible. Benchmarks use regular tombstone deletes; maps will periodically
			// self-rehash when tombstones dominate (see maybeRehash()).
			swissSimd.remove(evictKey);
			swiss.remove(evictKey);
			fastutil.remove(evictKey);
			unified.remove(evictKey);
			jdk.remove(evictKey);

			nextKey = misses[idx];

			// swap so that nextKey becomes a "present" key and evict becomes an "absent" key
			keys[idx] = nextKey;
			misses[idx] = evictKey;

			idx = (idx + 1) % keys.length;
		}

		String nextMissKey() { return nextKey; }
		String nextValue() { return "dummy"; }
	}

	// ------- get hit/miss -------
//	@Benchmark
	public void swissSimdGetHit(ReadState s, Blackhole bh) {
		bh.consume(s.swissSimd.get(s.nextHitKey()));
	}

//	@Benchmark
	public void swissGetHit(ReadState s, Blackhole bh) {
		bh.consume(s.swiss.get(s.nextHitKey()));
	}

//	@Benchmark
	public void fastutilGetHit(ReadState s, Blackhole bh) {
        bh.consume(s.fastutil.get(s.nextHitKey()));
	}

//	@Benchmark
	public void unifiedGetHit(ReadState s, Blackhole bh) {
        bh.consume(s.unified.get(s.nextHitKey()));
	}

//	@Benchmark
	public void jdkGetHit(ReadState s, Blackhole bh) {
        bh.consume(s.jdk.get(s.nextHitKey()));
	}

//	@Benchmark
	public void swissSimdGetMiss(ReadState s, Blackhole bh) {
		bh.consume(s.swissSimd.get(s.nextMissingKey()));
	}

//	@Benchmark
	public void swissGetMiss(ReadState s, Blackhole bh) {
		bh.consume(s.swiss.get(s.nextMissingKey()));
	}

//	@Benchmark
	public void fastutilGetMiss(ReadState s, Blackhole bh) {
        bh.consume(s.fastutil.get(s.nextMissingKey()));
	}

//	 @Benchmark
	public void unifiedGetMiss(ReadState s, Blackhole bh) {
        bh.consume(s.unified.get(s.nextMissingKey()));
	}

//	@Benchmark
	public void jdkGetMiss(ReadState s, Blackhole bh) {
        bh.consume(s.jdk.get(s.nextMissingKey()));
	}

	// ------- mutating: put hit/miss -------
	@Benchmark
	public void swissSimdPutHit(PutHitState s, Blackhole bh) {
        bh.consume(s.swissSimd.put(s.nextHitKey(), s.nextValue()));
	}

//    @Benchmark
	public void swissPutHit(PutHitState s, Blackhole bh) {
        bh.consume(s.swiss.put(s.nextHitKey(), s.nextValue()));
	}

//    @Benchmark
	public void fastutilPutHit(PutHitState s, Blackhole bh) {
        bh.consume(s.fastutil.put(s.nextHitKey(), s.nextValue()));
	}

//	@Benchmark
	public void unifiedPutHit(PutHitState s, Blackhole bh) {
        bh.consume(s.unified.put(s.nextHitKey(), s.nextValue()));
	}

//	@Benchmark
	public void jdkPutHit(PutHitState s, Blackhole bh) {
        bh.consume(s.jdk.put(s.nextHitKey(), s.nextValue()));
	}

	@Benchmark
	public void swissSimdPutMiss(PutMissState s, Blackhole bh) {
        bh.consume(s.swissSimd.put(s.nextMissKey(), s.nextValue()));
	}

//    @Benchmark
	public void swissPutMiss(PutMissState s, Blackhole bh) {
        bh.consume(s.swiss.put(s.nextMissKey(), s.nextValue()));
	}

//    @Benchmark
	public void fastutilPutMiss(PutMissState s, Blackhole bh) {
        bh.consume(s.fastutil.put(s.nextMissKey(), s.nextValue()));
	}

//    @Benchmark
	public void unifiedPutMiss(PutMissState s, Blackhole bh) {
        bh.consume(s.unified.put(s.nextMissKey(), s.nextValue()));
	}

//	@Benchmark
	public void jdkPutMiss(PutMissState s, Blackhole bh) {
        bh.consume(s.jdk.put(s.nextMissKey(), s.nextValue()));
	}

    //	@Benchmark
    public void fastutilIterate(ReadState s, Blackhole bh) {
        for (var e : s.fastutil.object2ObjectEntrySet()) {
            bh.consume(e.getKey());
            bh.consume(e.getValue());
        }
    }

    // @Benchmark
    public void unifiedIterate(ReadState s, Blackhole bh) {
        for (var e : s.unified.entrySet()) {
            bh.consume(e.getKey());
            bh.consume(e.getValue());
        }
    }

    //	@Benchmark
    public void jdkIterate(ReadState s, Blackhole bh) {
        for (var e : s.jdk.entrySet()) {
            bh.consume(e.getKey());
            bh.consume(e.getValue());
        }
    }
}

