package io.github.bluuewhale.hashsmith;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.eclipse.collections.impl.set.mutable.UnifiedSet;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@Fork(2)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SetBenchmark {

	private static void generateKeysAndMisses(Random rnd, String[] keys, String[] misses) {
		if (keys.length != misses.length) throw new IllegalArgumentException("keys and misses must have same length");
		int size = keys.length;
		Set<String> set = new HashSet<>(size * 2);
		for (int i = 0; i < size; i++) {
			String k;
			do { k = nextUuidString(rnd); } while (!set.add(k));
			keys[i] = k;
		}
		for (int i = 0; i < size; i++) {
			String miss;
			do { miss = nextUuidString(rnd); } while (set.contains(miss));
			misses[i] = miss;
			set.add(miss); // keep misses unique as well
		}
	}

	@State(Scope.Benchmark)
	public static class ReadState {
		@Param({ "12000", "48000", "196000", "784000" }) // align with MapBenchmark load factors
		int size;

		SwissSet<String> swiss;
		HashSet<String> jdk;
		ObjectOpenHashSet<String> fastutil;
		UnifiedSet<String> unified;
		String[] keys;
		String[] misses;
		int nextKeyIndex;
		int nextMissIndex;

		@Setup(Level.Trial)
		public void setup() {
			Random rnd = new Random(123);
			keys = new String[size];
			misses = new String[size];
			Set<String> keySet = new HashSet<>(size * 2);
			for (int i = 0; i < size; i++) {
				String k = nextUuidString(rnd);
				keys[i] = k;
				keySet.add(k);
			}
			for (int i = 0; i < size; i++) {
				String miss;
				do { miss = nextUuidString(rnd); } while (keySet.contains(miss));
				misses[i] = miss;
			}
			nextKeyIndex = 0;
			nextMissIndex = 0;

			swiss = new SwissSet<>();
			jdk = new HashSet<>();
			fastutil = new ObjectOpenHashSet<>();
			unified = new UnifiedSet<>();
			for (String k : keys) {
				swiss.add(k);
				jdk.add(k);
				fastutil.add(k);
				unified.add(k);
			}
		}

		String nextHitKey() {
			String k = keys[nextKeyIndex];
			nextKeyIndex = (nextKeyIndex + 1) % keys.length;
			return k;
		}

		String nextMissKey() {
			String k = misses[nextMissIndex];
			nextMissIndex = (nextMissIndex + 1) % misses.length;
			return k;
		}
	}

	@State(Scope.Thread)
	public static class PutHitState {
		@Param({ "12000", "48000", "196000", "784000" }) // align with MapBenchmark load factors
		int size;

		String[] keys;
		String[] misses;
		int hitIndex;
		int missIndex;
		SwissSet<String> swiss;
		HashSet<String> jdk;
		ObjectOpenHashSet<String> fastutil;
		UnifiedSet<String> unified;
		Random rnd;

		@Setup(Level.Trial)
		public void initKeys() {
			rnd = new Random(456);
			keys = new String[size];
			misses = new String[size];
			generateKeysAndMisses(rnd, keys, misses);
		}

		@Setup(Level.Iteration)
		public void resetSets() {
			swiss = new SwissSet<>();
			jdk = new HashSet<>();
			fastutil = new ObjectOpenHashSet<>();
			unified = new UnifiedSet<>();
			for (String k : keys) {
				swiss.add(k);
				jdk.add(k);
				fastutil.add(k);
				unified.add(k);
			}
			hitIndex = 0;
			missIndex = 0;
		}

		String nextHitKey() {
			String k = keys[hitIndex];
			hitIndex = (hitIndex + 1) % keys.length;
			return k;
		}

		String nextMissKey() {
			String k = misses[missIndex];
			missIndex = (missIndex + 1) % misses.length;
			return k;
		}
	}

	/**
	 * Steady-state add-miss: remove one existing element before each invocation so that
	 * the set size stays constant at n while the measured region only performs an "add miss".
	 */
	@State(Scope.Thread)
	public static class PutMissState {
		@Param({ "12000", "48000", "196000", "784000" }) // align with MapBenchmark load factors
		int size;

		String[] keys;   // present
		String[] misses; // absent
		int existingIndex;
		int missingIndex;
		String preparedMissKey;
		Random rnd;

		SwissSet<String> swiss;
		HashSet<String> jdk;
		ObjectOpenHashSet<String> fastutil;
		UnifiedSet<String> unified;

		@Setup(Level.Trial)
		public void initKeys() {
			rnd = new Random(789);
			keys = new String[size];
			misses = new String[size];
			generateKeysAndMisses(rnd, keys, misses);
		}

		@Setup(Level.Iteration)
		public void resetSets() {
			swiss = new SwissSet<>();
			jdk = new HashSet<>();
			fastutil = new ObjectOpenHashSet<>();
			unified = new UnifiedSet<>();
			for (String k : keys) {
				swiss.add(k);
				jdk.add(k);
				fastutil.add(k);
				unified.add(k);
			}
			existingIndex = 0;
			missingIndex = 0;
		}

		@Setup(Level.Invocation)
		public void beforeInvocation() {
			int ei = existingIndex;
			int mi = missingIndex;

			String evict = keys[ei];
			swiss.remove(evict);
			jdk.remove(evict);
			fastutil.remove(evict);
			unified.remove(evict);

			preparedMissKey = misses[mi];

			// swap so preparedMissKey becomes "present" and evict becomes "absent"
			keys[ei] = preparedMissKey;
			misses[mi] = evict;

			existingIndex = (ei + 1) % keys.length;
			missingIndex = (mi + 1) % misses.length;
		}

		String nextMissKey() { return preparedMissKey; }
	}

	// contains hit/miss
//	@Benchmark
	public void swissContainsHit(ReadState s, Blackhole bh) { boolean res = s.swiss.contains(s.nextHitKey()); bh.consume(res); }

//	@Benchmark
	public void jdkContainsHit(ReadState s, Blackhole bh) { boolean res = s.jdk.contains(s.nextHitKey()); bh.consume(res); }

//	@Benchmark
	public void fastutilContainsHit(ReadState s, Blackhole bh) { boolean res = s.fastutil.contains(s.nextHitKey()); bh.consume(res); }

//	@Benchmark
	public void unifiedContainsHit(ReadState s, Blackhole bh) { boolean res = s.unified.contains(s.nextHitKey()); bh.consume(res); }

//	@Benchmark
	public void swissContainsMiss(ReadState s, Blackhole bh) { boolean res = s.swiss.contains(s.nextMissKey()); bh.consume(res); }

//	@Benchmark
	public void jdkContainsMiss(ReadState s, Blackhole bh) { boolean res = s.jdk.contains(s.nextMissKey()); bh.consume(res); }

//	@Benchmark
	public void fastutilContainsMiss(ReadState s, Blackhole bh) { boolean res = s.fastutil.contains(s.nextMissKey()); bh.consume(res); }

//	@Benchmark
	public void unifiedContainsMiss(ReadState s, Blackhole bh) { boolean res = s.unified.contains(s.nextMissKey()); bh.consume(res); }

	// add hit/miss
//	@Benchmark
	public void swissAddHit(PutHitState s, Blackhole bh) { boolean res = s.swiss.add(s.nextHitKey()); bh.consume(res); }

//	@Benchmark
	public void jdkAddHit(PutHitState s, Blackhole bh) { boolean res = s.jdk.add(s.nextHitKey()); bh.consume(res); }

//	@Benchmark
	public void fastutilAddHit(PutHitState s, Blackhole bh) { boolean res = s.fastutil.add(s.nextHitKey()); bh.consume(res); }

//	@Benchmark
	public void unifiedAddHit(PutHitState s, Blackhole bh) { boolean res = s.unified.add(s.nextHitKey()); bh.consume(res); }

//	@Benchmark
	public void swissAddMiss(PutHitState s, Blackhole bh) { boolean res = s.swiss.add(s.nextMissKey()); bh.consume(res); }

//	@Benchmark
	public void jdkAddMiss(PutHitState s, Blackhole bh) { boolean res = s.jdk.add(s.nextMissKey()); bh.consume(res); }

//	@Benchmark
	public void fastutilAddMiss(PutMissState s, Blackhole bh) { boolean res = s.fastutil.add(s.nextMissKey()); bh.consume(res); }

//	@Benchmark
	public void unifiedAddMiss(PutMissState s, Blackhole bh) { boolean res = s.unified.add(s.nextMissKey()); bh.consume(res); }

	private static String nextUuidString(Random rnd) {
		return new UUID(rnd.nextLong(), rnd.nextLong()).toString();
	}
}

