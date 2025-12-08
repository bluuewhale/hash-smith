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
	 * Upper bound to keep table size as a power of two.
	 * Matches Guava's Ints.MAX_POWER_OF_TWO (1 << 30).
	 */
	private static final int MAX_TABLE_SIZE = 1 << 30;

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

	static int closedTableSize(int expectedEntries, double loadFactor) {
		expectedEntries = Math.max(expectedEntries, 2);
		int tableSize = Integer.highestOneBit(expectedEntries);
		if (expectedEntries > (int) (loadFactor * tableSize)) {
			tableSize <<= 1;
			return (tableSize > 0) ? tableSize : MAX_TABLE_SIZE;
		}
		return tableSize;
	}

	static boolean needsResizing(int size, int tableSize, double loadFactor) {
		return size > loadFactor * tableSize && tableSize < MAX_TABLE_SIZE;
	}
}
