package io.github.bluuewhale.hashsmith;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * SwissTable-inspired hash set (SIMD probing only).
 * Null elements are allowed (mirrors {@link java.util.HashSet}).
 */
public class SwissSet<E> extends AbstractSet<E> {

	/* Control byte values */
	private static final byte EMPTY = (byte) 0x80;    // empty slot
	private static final byte DELETED = (byte) 0xFE;  // tombstone
	private static final byte SENTINEL = (byte) 0xFF; // padding for SIMD overrun

	/* Hash split masks */
	private static final int H1_MASK = 0xFFFFFF80;
	private static final int H2_MASK = 0x0000007F;

	/* Group sizing */
	private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
	private static final int DEFAULT_GROUP_SIZE = SPECIES.length();

	/* Defaults */
	private static final int DEFAULT_INITIAL_CAPACITY = 16;
	private static final double DEFAULT_LOAD_FACTOR = 0.875d;

	/* Storage */
	private final double loadFactor;
	// Fixed per-instance seed (do not re-randomize per iterator creation)
	private final long iterationSeed;
	private int groupMask; // cached (nGroups - 1), valid because nGroups is power-of-two
	private byte[] ctrl;   // control bytes (EMPTY/DELETED/H2 fingerprint)
	private Object[] keys; // key storage
	private int capacity;
	private int size;
	private int tombstones; // deleted slots
	private int maxLoad;

	public SwissSet() {
		this(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR);
	}

	public SwissSet(int initialCapacity) {
		this(initialCapacity, DEFAULT_LOAD_FACTOR);
	}

	public SwissSet(int initialCapacity, double loadFactor) {
		Utils.validateLoadFactor(loadFactor);
		this.loadFactor = loadFactor;
		this.iterationSeed = ThreadLocalRandom.current().nextLong();
		init(initialCapacity);
	}

	private void init(int desiredCapacity) {
		int nGroups = Math.max(1, (desiredCapacity + DEFAULT_GROUP_SIZE - 1) / DEFAULT_GROUP_SIZE);
		nGroups = Utils.ceilPow2(nGroups);
		this.groupMask = nGroups - 1;
		this.capacity = nGroups * DEFAULT_GROUP_SIZE;

		this.ctrl = new byte[capacity + DEFAULT_GROUP_SIZE]; // sentinel padding
		Arrays.fill(this.ctrl, EMPTY);
		Arrays.fill(this.ctrl, capacity, this.ctrl.length, SENTINEL);
		this.keys = new Object[capacity];
		this.size = 0;
		this.tombstones = 0;
		this.maxLoad = Utils.calcMaxLoad(this.capacity, loadFactor);
	}

	/* Public API */
	@Override
	public int size() {
		return size;
	}

	@Override
	public boolean isEmpty() {
		return size == 0;
	}

	@Override
	public boolean contains(Object o) {
		return findIndex(o) >= 0;
	}

	@Override
	public boolean add(E e) {
		maybeRehash();
		int h = hash(e);
		int h1 = h1(h);
		byte h2 = h2(h);
		int mask = groupMask;
		int firstTombstone = -1;
		int g = h1 & mask;
		int step = 0; // triangular probing step over groups
		for (;;) {
			int base = g * DEFAULT_GROUP_SIZE;
			ByteVector v = loadCtrlVector(base);
			long eqMask = v.eq(h2).toLong();
			while (eqMask != 0) {
				int bit = Long.numberOfTrailingZeros(eqMask);
				int idx = base + bit;
				if (Objects.equals(keys[idx], e)) return false;
				eqMask &= eqMask - 1; // clear LSB
			}
			if (firstTombstone < 0) {
				long delMask = v.eq(DELETED).toLong();
				if (delMask != 0) firstTombstone = base + Long.numberOfTrailingZeros(delMask);
			}
			long emptyMask = v.eq(EMPTY).toLong();
			if (emptyMask != 0) {
				int idx = base + Long.numberOfTrailingZeros(emptyMask);
				int target = (firstTombstone >= 0) ? firstTombstone : idx;
				insertAt(target, e, h2);
				return true;
			}
			g = (g + (++step)) & mask; // triangular (quadratic) probing over groups
		}
	}

	@Override
	public boolean remove(Object o) {
		int idx = findIndex(o);
		if (idx < 0) return false;
		ctrl[idx] = DELETED;
		keys[idx] = null;
		size--;
		tombstones++;
		maybeRehash();
		return true;
	}

	@Override
	public void clear() {
		Arrays.fill(ctrl, 0, capacity, EMPTY);
		Arrays.fill(ctrl, capacity, ctrl.length, SENTINEL);
		Arrays.fill(keys, null);
		size = 0;
		tombstones = 0;
		maxLoad = Utils.calcMaxLoad(capacity, loadFactor);
	}

	@Override
	public Iterator<E> iterator() {
		return new KeyIter();
	}

	/* Internal helpers */
	private int hash(Object key) {
		return Hashing.smearedHash(key);
	}

	private int h1(int hash) {
		return (hash & H1_MASK) >>> 7;
	}

	private byte h2(int hash) {
		return (byte) (hash & H2_MASK);
	}

	private boolean isEmpty(byte c) { return c == EMPTY; }
	private boolean isDeleted(byte c) { return c == DELETED; }
	private boolean isFull(byte c) { return c >= 0 && c <= H2_MASK; }

	/* SIMD helpers: load once per group and reuse the ByteVector for multiple comparisons */
	private ByteVector loadCtrlVector(int base) {
		return ByteVector.fromArray(SPECIES, ctrl, base);
	}

	private void maybeRehash() {
		boolean overMaxLoad = (size + tombstones) >= maxLoad;
		boolean tooManyTombstones = tombstones > (size >>> 1);
		if (!overMaxLoad && !tooManyTombstones) return;

		int newCap = overMaxLoad ? Math.max(capacity * 2, DEFAULT_GROUP_SIZE) : capacity;
		rehash(newCap);
	}

	private void rehash(int newCapacity) {
		byte[] oldCtrl = this.ctrl;
		Object[] oldKeys = this.keys;
		int oldCap = (oldCtrl == null) ? 0 : oldCtrl.length - DEFAULT_GROUP_SIZE;

		int desiredGroups = Math.max(1, (Math.max(newCapacity, DEFAULT_GROUP_SIZE) + DEFAULT_GROUP_SIZE - 1) / DEFAULT_GROUP_SIZE);
		desiredGroups = Utils.ceilPow2(desiredGroups);
		this.groupMask = desiredGroups - 1;
		this.capacity = desiredGroups * DEFAULT_GROUP_SIZE;
		this.ctrl = new byte[this.capacity + DEFAULT_GROUP_SIZE];
		Arrays.fill(this.ctrl, EMPTY);
		Arrays.fill(this.ctrl, capacity, this.ctrl.length, SENTINEL);
		this.keys = new Object[this.capacity];
		this.size = 0;
		this.tombstones = 0;
		this.maxLoad = Utils.calcMaxLoad(this.capacity, loadFactor);

		if (oldCtrl == null) return;

		for (int i = 0; i < oldCap; i++) {
			byte c = oldCtrl[i];
			if (!isFull(c)) continue;
			@SuppressWarnings("unchecked")
			E k = (E) oldKeys[i];
			int h = hash(k);
			insertFresh(k, h1(h), h2(h));
		}
	}

	private void insertFresh(E key, int h1, byte h2) {
		int mask = groupMask;
		int g = h1 & mask;
		int step = 0; // triangular probing step over groups
		for (;;) {
			int base = g * DEFAULT_GROUP_SIZE;
			for (int j = 0; j < DEFAULT_GROUP_SIZE; j++) {
				int idx = base + j;
				if (isEmpty(ctrl[idx])) {
					ctrl[idx] = h2;
					keys[idx] = key;
					size++;
					return;
				}
			}
			g = (g + (++step)) & mask; // triangular (quadratic) probing over groups
		}
	}

	private int findIndex(Object key) {
		if (size == 0) return -1;
		int h = hash(key);
		int h1 = h1(h);
		byte h2 = h2(h);
		int mask = groupMask;
		int g = h1 & mask;
		int step = 0; // triangular probing step over groups
		for (;;) {
			int base = g * DEFAULT_GROUP_SIZE;
			ByteVector v = loadCtrlVector(base);
			long eqMask = v.eq(h2).toLong();
			while (eqMask != 0) {
				int bit = Long.numberOfTrailingZeros(eqMask);
				int idx = base + bit;
				if (Objects.equals(keys[idx], key)) {
					return idx;
				}
				eqMask &= eqMask - 1;
			}
			long emptyMask = v.eq(EMPTY).toLong();
			if (emptyMask != 0) {
				return -1;
			}
			g = (g + (++step)) & mask; // triangular (quadratic) probing over groups
		}
	}

	private void insertAt(int idx, E key, byte h2) {
		if (isDeleted(ctrl[idx])) tombstones--;
		ctrl[idx] = h2;
		keys[idx] = key;
		size++;
	}

	/* Iteration */
	private abstract class BaseIter implements Iterator<E> {
		private final int start;
		private final int step;
		private final int mask;
		private int iter = 0;
		private int next = -1;
		private int last = -1;

		BaseIter() {
			Utils.RandomCycle cycle = new Utils.RandomCycle(capacity, iterationSeed);
			this.start = cycle.start;
			this.step = cycle.step;
			this.mask = cycle.mask;
			advance();
		}

		private void advance() {
			next = -1;
			while (iter < capacity) {
				int idx = (start + (iter++ * step)) & mask;
				if (isFull(ctrl[idx])) {
					next = idx;
					return;
				}
			}
		}

		@Override
		public boolean hasNext() {
			return next >= 0;
		}

		int nextIndex() {
			if (!hasNext()) throw new NoSuchElementException();
			int i = next;
			last = i;
			advance();
			return i;
		}

		@Override
		public void remove() {
			if (last < 0) throw new IllegalStateException();
			if (isFull(ctrl[last])) {
				ctrl[last] = DELETED;
				keys[last] = null;
				size--;
				tombstones++;
				// NOTE: do not rehash from iterator.remove().
				// Some JDK algorithms (e.g. AbstractCollection.retainAll) prefetch iterator state (next index) before calling remove().
				// Rehash would rebuild ctrl/keys and can invalidate that prefetched index, causing the iterator to yield null/empty slots.
			}
			last = -1;
		}
	}

	private class KeyIter extends BaseIter {
		@Override
		public E next() {
			return elementAt(nextIndex());
		}
	}

	@SuppressWarnings("unchecked")
	private E elementAt(int idx) {
		return (E) keys[idx];
	}

	/* Random full-cycle permutation (capacity must be power-of-two) */
}

