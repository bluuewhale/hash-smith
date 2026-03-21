package io.github.bluuewhale.hashsmith;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Concurrent read benchmarks for {@link ConcurrentSwissMap}.
 *
 * <p>This benchmark focuses on get-hit / get-miss under multi-threaded access,
 * comparing against {@link ConcurrentHashMap}.
 *
 * <p>Thread count is intentionally NOT hard-coded via {@code @Threads} so that it can be controlled
 * from the command line (e.g., {@code -t 1}, {@code -t 2}, {@code -t 4}, {@code -t 8}).
 */
@Fork(
	value = 2,
	jvmArgsAppend = {
		"--add-modules=jdk.incubator.vector",
		"--enable-preview"
	}
)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ConcurrentSwissMapGetTest {

	/**
	 * Number of operations performed per benchmark method invocation.
	 *
	 * <p>We use {@link OperationsPerInvocation} so JMH reports per-op average time by dividing the
	 * measured time by this count.
	 */
	private static final int OPS_PER_INVOCATION = 256;

	private static String randomUuidString(Random rnd) {
		return new UUID(rnd.nextLong(), rnd.nextLong()).toString();
	}

	@State(Scope.Benchmark)
	public static class SharedState {
		/**
		 * Entry count in the map.
		 */
		@Param({ "12000", "48000", "196000" })
		int size;

		ConcurrentSwissMap<String, Integer> concurrentSwiss;
		ConcurrentHashMap<String, Integer> chm;

		String[] keys;
		String[] misses;

		@Setup(Level.Trial)
		public void setup() {
			Random rnd = new Random(123);
			keys = new String[size];
			misses = new String[size];

			// Ensure keys are unique and misses do not overlap with keys.
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
				set.add(miss);
			}

			// Use the map's default shard count (based on available processors) and pre-size by entry count.
			concurrentSwiss = new ConcurrentSwissMap<>(size);
			chm = new ConcurrentHashMap<>(size);
			for (int i = 0; i < size; i++) {
				// Store numeric values to avoid hashCode/identityHashCode overhead in the benchmark loop.
				Integer v = i;
				concurrentSwiss.put(keys[i], v);
				chm.put(keys[i], v);
			}
		}
	}

	@State(Scope.Thread)
	public static class Cursor {
		int hitIndex;
		int missIndex;

		@Setup(Level.Iteration)
		public void reset() {
			hitIndex = 0;
			missIndex = 0;
		}
	}

	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(1)
	public void concurrentSwiss_getHit_t1(SharedState s, Cursor c, Blackhole bh) {
		final String[] keys = s.keys;
		final int len = keys.length;
		int idx = c.hitIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = keys[idx++];
			if (idx == len) idx = 0;
			Integer v = s.concurrentSwiss.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.hitIndex = idx;
		bh.consume(acc);
	}

//	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(1)
	public void concurrentHashMap_getHit_t1(SharedState s, Cursor c, Blackhole bh) {
		final String[] keys = s.keys;
		final int len = keys.length;
		int idx = c.hitIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = keys[idx++];
			if (idx == len) idx = 0;
			Integer v = s.chm.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.hitIndex = idx;
		bh.consume(acc);
	}

	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(2)
	public void concurrentSwiss_getHit_t2(SharedState s, Cursor c, Blackhole bh) {
		final String[] keys = s.keys;
		final int len = keys.length;
		int idx = c.hitIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = keys[idx++];
			if (idx == len) idx = 0;
			Integer v = s.concurrentSwiss.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.hitIndex = idx;
		bh.consume(acc);
	}

//	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(2)
	public void concurrentHashMap_getHit_t2(SharedState s, Cursor c, Blackhole bh) {
		final String[] keys = s.keys;
		final int len = keys.length;
		int idx = c.hitIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = keys[idx++];
			if (idx == len) idx = 0;
			Integer v = s.chm.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.hitIndex = idx;
		bh.consume(acc);
	}

	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(4)
	public void concurrentSwiss_getHit_t4(SharedState s, Cursor c, Blackhole bh) {
		final String[] keys = s.keys;
		final int len = keys.length;
		int idx = c.hitIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = keys[idx++];
			if (idx == len) idx = 0;
			Integer v = s.concurrentSwiss.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.hitIndex = idx;
		bh.consume(acc);
	}

//	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(4)
	public void concurrentHashMap_getHit_t4(SharedState s, Cursor c, Blackhole bh) {
		final String[] keys = s.keys;
		final int len = keys.length;
		int idx = c.hitIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = keys[idx++];
			if (idx == len) idx = 0;
			Integer v = s.chm.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.hitIndex = idx;
		bh.consume(acc);
	}

	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(8)
	public void concurrentSwiss_getHit_t8(SharedState s, Cursor c, Blackhole bh) {
		final String[] keys = s.keys;
		final int len = keys.length;
		int idx = c.hitIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = keys[idx++];
			if (idx == len) idx = 0;
			Integer v = s.concurrentSwiss.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.hitIndex = idx;
		bh.consume(acc);
	}

//	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(8)
	public void concurrentHashMap_getHit_t8(SharedState s, Cursor c, Blackhole bh) {
		final String[] keys = s.keys;
		final int len = keys.length;
		int idx = c.hitIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = keys[idx++];
			if (idx == len) idx = 0;
			Integer v = s.chm.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.hitIndex = idx;
		bh.consume(acc);
	}

	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(1)
	public void concurrentSwiss_getMiss_t1(SharedState s, Cursor c, Blackhole bh) {
		final String[] misses = s.misses;
		final int len = misses.length;
		int idx = c.missIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = misses[idx++];
			if (idx == len) idx = 0;
			Integer v = s.concurrentSwiss.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.missIndex = idx;
		bh.consume(acc);
	}

//	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(1)
	public void concurrentHashMap_getMiss_t1(SharedState s, Cursor c, Blackhole bh) {
		final String[] misses = s.misses;
		final int len = misses.length;
		int idx = c.missIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = misses[idx++];
			if (idx == len) idx = 0;
			Integer v = s.chm.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.missIndex = idx;
		bh.consume(acc);
	}

	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(2)
	public void concurrentSwiss_getMiss_t2(SharedState s, Cursor c, Blackhole bh) {
		final String[] misses = s.misses;
		final int len = misses.length;
		int idx = c.missIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = misses[idx++];
			if (idx == len) idx = 0;
			Integer v = s.concurrentSwiss.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.missIndex = idx;
		bh.consume(acc);
	}

//	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(2)
	public void concurrentHashMap_getMiss_t2(SharedState s, Cursor c, Blackhole bh) {
		final String[] misses = s.misses;
		final int len = misses.length;
		int idx = c.missIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = misses[idx++];
			if (idx == len) idx = 0;
			Integer v = s.chm.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.missIndex = idx;
		bh.consume(acc);
	}

	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(4)
	public void concurrentSwiss_getMiss_t4(SharedState s, Cursor c, Blackhole bh) {
		final String[] misses = s.misses;
		final int len = misses.length;
		int idx = c.missIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = misses[idx++];
			if (idx == len) idx = 0;
			Integer v = s.concurrentSwiss.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.missIndex = idx;
		bh.consume(acc);
	}

//	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(4)
	public void concurrentHashMap_getMiss_t4(SharedState s, Cursor c, Blackhole bh) {
		final String[] misses = s.misses;
		final int len = misses.length;
		int idx = c.missIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = misses[idx++];
			if (idx == len) idx = 0;
			Integer v = s.chm.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.missIndex = idx;
		bh.consume(acc);
	}

	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(8)
	public void concurrentSwiss_getMiss_t8(SharedState s, Cursor c, Blackhole bh) {
		final String[] misses = s.misses;
		final int len = misses.length;
		int idx = c.missIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = misses[idx++];
			if (idx == len) idx = 0;
			Integer v = s.concurrentSwiss.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.missIndex = idx;
		bh.consume(acc);
	}

//	@Benchmark
	@OperationsPerInvocation(OPS_PER_INVOCATION)
	@Threads(8)
	public void concurrentHashMap_getMiss_t8(SharedState s, Cursor c, Blackhole bh) {
		final String[] misses = s.misses;
		final int len = misses.length;
		int idx = c.missIndex;
		int acc = 0;
		for (int n = 0; n < OPS_PER_INVOCATION; n++) {
			String k = misses[idx++];
			if (idx == len) idx = 0;
			Integer v = s.chm.get(k);
			acc += (v == null) ? 1 : v;
		}
		c.missIndex = idx;
		bh.consume(acc);
	}
}


