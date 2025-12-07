package com.donghyungko.swisstable;

/**
 * Skeleton for a Robin Hood hashing map implementation.
 * Null keys are NOT allowed; null values are allowed.
 */
public class RobinHoodMap<K, V> {

	/* Defaults */
	private static final int DEFAULT_INITIAL_CAPACITY = 16;
	private static final double DEFAULT_LOAD_FACTOR = 0.75d;

	/* Storage */
	private Object[] keys;
	private Object[] vals;
	private byte[] dist; // probe distance
	private int size;
	private int capacity;
	private int maxLoad;
	private double loadFactor = DEFAULT_LOAD_FACTOR;

	public RobinHoodMap() {
		this(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR);
	}

	public RobinHoodMap(int initialCapacity) {
		this(initialCapacity, DEFAULT_LOAD_FACTOR);
	}

	public RobinHoodMap(int initialCapacity, double loadFactor) {
		validateLoadFactor(loadFactor);
		this.loadFactor = loadFactor;
		init(initialCapacity);
	}

	private void init(int desiredCapacity) {
		int cap = ceilPow2(Math.max(DEFAULT_INITIAL_CAPACITY, desiredCapacity));
		this.capacity = cap;
		this.keys = new Object[cap];
		this.vals = new Object[cap];
		this.dist = new byte[cap];
		this.size = 0;
		this.maxLoad = calcMaxLoad(cap);
	}

	private int calcMaxLoad(int cap) {
		int ml = (int) (cap * loadFactor);
		return Math.max(1, Math.min(ml, cap - 1));
	}

	/* Hash helpers */
	private int smear(int h) {
		h ^= (h >>> 16);
		return h;
	}

	/* Capacity helpers */
	private int ceilPow2(int x) {
		if (x <= 1) return 1;
		return Integer.highestOneBit(x - 1) << 1;
	}

	private void validateLoadFactor(double lf) {
		if (!(lf > 0.0d && lf < 1.0d)) {
			throw new IllegalArgumentException("loadFactor must be in (0,1): " + lf);
		}
	}
}

