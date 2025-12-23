package io.github.bluuewhale.hashsmith;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared utilities for open-addressed structures.
 */
final class Utils {
	private Utils() {}

	static int calcMaxLoad(int cap, double loadFactor) {
		int ml = (int) (cap * loadFactor);
		return Math.max(1, Math.min(ml, cap - 1));
	}

	static int ceilPow2(int x) {
		if (x <= 1) return 1;
		return Integer.highestOneBit(x - 1) << 1;
	}

	static void validateLoadFactor(double lf) {
		if (!(lf > 0.0d && lf < 1.0d)) {
			throw new IllegalArgumentException("loadFactor must be in (0,1): " + lf);
		}
	}

	/**
	 * (start, step) generator to visit every slot in a power-of-two table.
	 */
	static class RandomCycle {
		final int start;
		final int step;
		final int mask;

		RandomCycle(int capacity) {
			if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
				throw new IllegalArgumentException("capacity must be a power of two");
			}
			this.mask = capacity - 1;
			ThreadLocalRandom r = ThreadLocalRandom.current();
			this.start = r.nextInt() & mask;
			this.step = r.nextInt() | 1; // odd step → full-cycle walk
		}

		/**
		 * Deterministic variant: same seed => same (start, step).
		 */
		RandomCycle(int capacity, long seed) {
			if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
				throw new IllegalArgumentException("capacity must be a power of two");
			}
			this.mask = capacity - 1;
			Random r = new Random(seed);
			this.start = r.nextInt() & mask;
			int s = (r.nextInt() | 1) & mask; // odd + within range
			this.step = (s != 0) ? s : 1;      // avoid 0 step
		}

		int indexAt(int iteration) {
			return (start + (iteration * step)) & mask;
		}
	}
}

