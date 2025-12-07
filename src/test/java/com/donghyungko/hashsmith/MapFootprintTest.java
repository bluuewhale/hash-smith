package com.donghyungko.hashsmith;

import java.util.HashMap;
import java.util.Random;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

/**
 * JUnit helper to print retained heap size for HashMap vs SwissMap vs RobinHoodMap.
 * Run with `./gradlew test --tests com.donghyungko.hashsmith.MapFootprintTest`.
 */
public class MapFootprintTest {

	private static final int[] SIZES = { 100, 1_000, 10_000, 100_000 };
	private static final long SEED = 1234L;
	private static final int SHORT_STR_LEN = 8;
	private static final int LONG_STR_LEN = 200;

	private enum Payload {
		BOOLEAN, INT, SHORT_STR, LONG_STR
	}

	@Test
	void printFootprint() {
		for (Payload payload : Payload.values()) {
			for (int n : SIZES) {
				measure(n, payload);
			}
		}
	}

	private static void measure(int entries, Payload payload) {
		var keyRnd = new Random(SEED);
		var valueRnd = new Random(SEED);

		var hash = new HashMap<Integer, Object>();
		var swiss = new SwissMap<Integer, Object>();
		var robin = new RobinHoodMap<Integer, Object>();

		Object[] values = new Object[entries];
		Supplier<Object> factory = valueFactory(payload, valueRnd);
		for (int i = 0; i < entries; i++) {
			values[i] = factory.get();
		}

		for (int i = 0; i < entries; i++) {
			int k = keyRnd.nextInt();
			hash.put(k, values[i]);
			swiss.put(k, values[i]);
			robin.put(k, values[i]);
		}

		// Reduce transient garbage before measuring
		System.gc();
		System.gc();

		long emptyHash = GraphLayout.parseInstance(new HashMap<Integer, Object>()).totalSize();
		long emptySwiss = GraphLayout.parseInstance(new SwissMap<Integer, Object>()).totalSize();
		long emptyRobin = GraphLayout.parseInstance(new RobinHoodMap<Integer, Object>()).totalSize();

		long hashSize = GraphLayout.parseInstance(hash).totalSize();
		long swissSize = GraphLayout.parseInstance(swiss).totalSize();
		long robinSize = GraphLayout.parseInstance(robin).totalSize();

		double hashPerEntry = (hashSize - emptyHash) / (double) entries;
		double swissPerEntry = (swissSize - emptySwiss) / (double) entries;
		double robinPerEntry = (robinSize - emptyRobin) / (double) entries;

		System.out.printf("payload=%-8s n=%-7d%n", payload, entries);
		System.out.printf("  hash:  %-,10dB (empty %-,8dB)  per entry: %.1fB%n", hashSize, emptyHash, hashPerEntry);
		System.out.printf("  swiss: %-,10dB (empty %-,8dB)  per entry: %.1fB%n", swissSize, emptySwiss, swissPerEntry);
		System.out.printf("  robin: %-,10dB (empty %-,8dB)  per entry: %.1fB%n", robinSize, emptyRobin, robinPerEntry);
	}

	private static Supplier<Object> valueFactory(Payload payload, Random rnd) {
		return switch (payload) {
		case INT -> rnd::nextInt;
		case BOOLEAN -> rnd::nextBoolean;
		case SHORT_STR -> () -> randomAscii(rnd, SHORT_STR_LEN);
		case LONG_STR -> () -> randomAscii(rnd, LONG_STR_LEN);
		};
	}

	private static String randomAscii(Random rnd, int len) {
		char[] buf = new char[len];
		for (int i = 0; i < len; i++) {
			buf[i] = (char) ('a' + rnd.nextInt(26));
		}
		return new String(buf);
	}
}
