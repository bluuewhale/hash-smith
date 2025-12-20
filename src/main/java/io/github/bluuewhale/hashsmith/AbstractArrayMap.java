package io.github.bluuewhale.hashsmith;

import java.util.AbstractMap;

/**
 * Shared array-backed Map boilerplate and utilities.
 */
abstract class AbstractArrayMap<K, V> extends AbstractMap<K, V> {

	protected int capacity;
	protected int size;
	protected int maxLoad;
	protected double loadFactor;

	protected AbstractArrayMap(int initialCapacity, double loadFactor) {
		if (initialCapacity < 0) {
			throw new IllegalArgumentException("initialCapacity must be >= 0: " + initialCapacity);
		}
		Utils.validateLoadFactor(loadFactor);
		this.loadFactor = loadFactor;
		init(initialCapacity);
	}

	@Override
	public int size() {
		return size;
	}

	@Override
	public boolean isEmpty() {
		return size == 0;
	}

	@Override
	public boolean containsKey(Object key) {
		return findIndex(key) >= 0;
	}

	@Override
	public V get(Object key) {
		int idx = findIndex(key);
		return (idx >= 0) ? valueAt(idx) : null;
	}

	/* Hooks for subclasses */
	protected abstract void init(int initialCapacity);
	protected abstract int findIndex(Object key);
	protected abstract V valueAt(int idx);

	/* Common utilities */
	protected int calcMaxLoad(int cap) {
		return Utils.calcMaxLoad(cap, loadFactor);
	}

	protected int ceilPow2(int x) {
		return Utils.ceilPow2(x);
	}

	protected int hashNonNull(Object key) {
		if (key == null) throw new NullPointerException("Null keys not supported");
		return Hashing.smearedHash(key);
	}

	protected int hashNullable(Object key) {
		return Hashing.smearedHash(key);
	}

	/**
	 * (start, step) generator to visit every slot in a power-of-two table.
	 */
	protected static final class RandomCycle extends Utils.RandomCycle {
		RandomCycle(int capacity) { super(capacity); }
	}
}

