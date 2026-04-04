package io.github.bluuewhale.hashsmith;

/**
 * Static helpers based on the hash utilities authored by Guava contributors.
 * Original code by Kevin Bourrillion, Jesse Wilson, and Austin Appleby,
 * derived from the MurmurHash3 intermediate step (public domain).
 */
final class Hashing {

	private Hashing() {}

	/*
	 * Use longs to preserve precision (mirrors the Guava implementation).
	 */
	private static final long C1 = 0xcc9e2d51L;
	private static final long C2 = 0x1b873593L;

	/*
	 * This method was rewritten in Java from an intermediate step of the Murmur hash function in
	 * http://code.google.com/p/smhasher/source/browse/trunk/MurmurHash3.cpp, which contained the
	 * following header:
	 *
	 * MurmurHash3 was written by Austin Appleby, and is placed in the public domain. The author
	 * hereby disclaims copyright to this source code.
	 */
	static int smear(int hashCode) {
		return (int) (C2 * Integer.rotateLeft((int) (hashCode * C1), 15));
	}

	static int smearedHash(Object o) {
		return smear((o == null) ? 0 : o.hashCode());
	}

	/*
	 * 64-bit Fibonacci (golden-ratio) multiplicative hash constant.
	 * Equivalent to floor(2^64 / phi); distributes keys uniformly with a single IMULQ.
	 * Reference: Knuth TAOCP Vol.3 §6.4.
	 */
	static final long FIBONACCI_MULT = 0x9e3779b97f4a7c15L;

	/**
	 * Faster hash for SwissTable: single 64-bit multiply replaces the Murmur3 smear chain.
	 * Suitable for SwissTable h2 fingerprint (7-bit) — distribution quality validated.
	 */
	static int fibonacciHash(int hashCode) {
		return (int) ((hashCode * FIBONACCI_MULT) >>> 32);
	}

}
