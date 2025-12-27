package io.github.bluuewhale.hashsmith;

import java.lang.reflect.Field;
import java.util.concurrent.locks.StampedLock;

/**
 * A tiny harness to heat up {@code ConcurrentSwissMap.get} (optimistic read fast-path)
 * so that assembly output is focused on the real lookup hot-path.
 *
 * <p>Run via:
 *   ./gradlew jitAsmConcurrent
 *
 * <p>Note: {@code -XX:+PrintAssembly} requires an hsdis disassembler library to be present for your JDK.
 */
public final class ConcurrentSwissMapAsmProbe {
	private static final int N_KEYS = 1 << 14;
	private static volatile boolean stopWriter;

	public static void main(String[] args) {
		// Usage:
		//   java ... ConcurrentSwissMapAsmProbe [durationSeconds] [contended]
		// Defaults are chosen to make it easy to attach a profiler.
		int durationSeconds = (args.length >= 1) ? Integer.parseInt(args[0]) : 60;
		boolean contended = args.length >= 2 && Boolean.parseBoolean(args[1]);

		// Use multiple shards so the shard selection logic is present in the hot path.
		ConcurrentSwissMap<String, Integer> m = new ConcurrentSwissMap<>(64, N_KEYS, 0.875d);

		// Precompute keys so the hot loop does not allocate.
		String[] keys = new String[N_KEYS];
		for (int i = 0; i < N_KEYS; i++) {
			keys[i] = "k" + i;
		}

		// Pre-fill so lookups hit the probe loop inside SwissMap.
		for (int i = 0; i < N_KEYS; i++) {
			m.put(keys[i], i);
		}

		// Optional contention to make the slow-path (validate fail -> readLock fallback) show up.
		// This thread only toggles the locks and does NOT mutate the underlying SwissMap.
		Thread writer = contended ? startLockToggler(m) : null;

		long sum = 0;
		final int mask = N_KEYS - 1;
		long end = System.nanoTime() + durationSeconds * 1_000_000_000L;
		for (int i = 0; ; i++) {
			// Avoid calling System.nanoTime() on every iteration; it can dominate samples on macOS.
			if ((i & 1023) == 0 && System.nanoTime() >= end) break;
			int idx = i & mask;
			Integer v = m.get(keys[idx]);
			sum += v; // prevent DCE
		}

		if (writer != null) {
			stopWriter = true;
			try {
				writer.join(2_000);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}

		// Prevent dead-code elimination of the loop.
		System.out.println("sum=" + sum);
	}

	private static Thread startLockToggler(ConcurrentSwissMap<?, ?> map) {
		StampedLock[] locks = reflectLocks(map);
		Thread t = new Thread(() -> {
			while (!stopWriter) {
				for (StampedLock l : locks) {
					// Keep it non-blocking to avoid stalling the reader loop too much.
					long s = l.tryWriteLock();
					if (s != 0L) {
						// Hold briefly so readers have a chance to observe the write-bit/state change.
						for (int k = 0; k < 64; k++) {
							Thread.onSpinWait();
						}
						l.unlockWrite(s);
					}
				}
			}
		}, "asm-probe-lock-toggler");
		t.setDaemon(true);
		t.start();
		return t;
	}

	private static StampedLock[] reflectLocks(ConcurrentSwissMap<?, ?> map) {
		try {
			Field f = ConcurrentSwissMap.class.getDeclaredField("locks");
			f.setAccessible(true);
			return (StampedLock[]) f.get(map);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Failed to access ConcurrentSwissMap.locks via reflection", e);
		}
	}
}


