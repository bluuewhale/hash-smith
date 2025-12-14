package io.github.bluuewhale.hashsmith;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Skeleton for a SwissTable-inspired Map implementation.
 * All methods are not implemented yet; logic will be filled later.
 */
public class SwissMap<K, V> extends AbstractArrayMap<K, V> {

	/* Control byte values */
	private static final byte EMPTY = (byte) 0x80;    // empty slot
	private static final byte DELETED = (byte) 0xFE;  // tombstone
	private static final byte SENTINEL = (byte) 0xFF; // padding for SIMD overrun

	/* Hash split masks: high bits choose group, low 7 bits stored in control byte */
	private static final int H1_MASK = 0xFFFFFF80;
	private static final int H2_MASK = 0x0000007F;

	/* Group sizing: 1 << shift equals slots per group; align with SIMD width */
	private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
	private static final int DEFAULT_GROUP_SIZE = SPECIES.length(); // preferred SIMD width

	/* Load factor: similar to Abseil SwissTable (7/8) */
    private static final double DEFAULT_LOAD_FACTOR = 0.875d;

	/* Storage and state */
	private byte[] ctrl;     // control bytes (EMPTY/DELETED/H2 fingerprint)
	private Object[] keys;   // key storage
	private Object[] vals;   // value storage
	private int tombstones;  // deleted slots


	public SwissMap() {
		this(16, DEFAULT_LOAD_FACTOR);
	}

	public SwissMap(int initialCapacity) {
		this(initialCapacity, DEFAULT_LOAD_FACTOR);
	}

	public SwissMap(int initialCapacity, double loadFactor) {
		super(initialCapacity, loadFactor);
	}

	@Override
	protected void init(int desiredCapacity) {
		int nGroups = Math.max(1, (desiredCapacity + DEFAULT_GROUP_SIZE - 1) / DEFAULT_GROUP_SIZE);
		nGroups = ceilPow2(nGroups);
		this.capacity = nGroups * DEFAULT_GROUP_SIZE;

		this.ctrl = new byte[capacity + DEFAULT_GROUP_SIZE]; // extra for sentinel padding
		Arrays.fill(this.ctrl, EMPTY);
		Arrays.fill(this.ctrl, capacity, this.ctrl.length, SENTINEL);
		this.keys = new Object[capacity];
		this.vals = new Object[capacity];
		this.size = 0;
		this.tombstones = 0;
		this.maxLoad = calcMaxLoad(this.capacity);
	}

	/* Hash split helpers */
	private int h1(int hash) {
		return (hash & H1_MASK) >>> 7;
	}

	private byte h2(int hash) {
		return (byte) (hash & H2_MASK);
	}

	private int hash(Object key) {
		return hashNullable(key);
	}

	private int numGroups() {
		return capacity / DEFAULT_GROUP_SIZE;
	}

	/* Control byte inspectors */
	private boolean isEmpty(byte c) { return c == EMPTY; }
	private boolean isDeleted(byte c) { return c == DELETED; }
	private boolean isFull(byte c) { return c >= 0 && c <= H2_MASK; } // H2 in [0,127]

	/* SIMD helpers */
	private ByteVector loadCtrlVector(int base) {
		return ByteVector.fromArray(SPECIES, ctrl, base);
	}

	/* Resize/rehash */
	private void maybeRehash() {
		// trigger when over load or too many tombstones
		boolean overMaxLoad = (size + tombstones) >= maxLoad;
		boolean tooManyTombstones = tombstones > (size >>> 1);
		if (!overMaxLoad && !tooManyTombstones) return;

		// Only grow the table when we are actually over the max load threshold.
		// If we are rehashing just to clean up tombstones, keep the capacity.
		int newCap = overMaxLoad ? Math.max(capacity * 2, DEFAULT_GROUP_SIZE) : capacity;
		rehash(newCap);
	}

	private void rehash(int newCapacity) {
		byte[] oldCtrl = this.ctrl;
		Object[] oldKeys = this.keys;
		Object[] oldVals = this.vals;
		int oldCap = (oldCtrl == null) ? 0 : oldCtrl.length - DEFAULT_GROUP_SIZE; // exclude sentinel padding

		int desiredGroups = Math.max(1, (Math.max(newCapacity, DEFAULT_GROUP_SIZE) + DEFAULT_GROUP_SIZE - 1) / DEFAULT_GROUP_SIZE);
		desiredGroups = ceilPow2(desiredGroups);
		this.capacity = desiredGroups * DEFAULT_GROUP_SIZE;
		this.ctrl = new byte[this.capacity + DEFAULT_GROUP_SIZE];
		Arrays.fill(this.ctrl, EMPTY);
		Arrays.fill(this.ctrl, capacity, this.ctrl.length, SENTINEL);
		this.keys = new Object[this.capacity];
		this.vals = new Object[this.capacity];
		this.size = 0;
		this.tombstones = 0;
		this.maxLoad = calcMaxLoad(this.capacity);

		if (oldCtrl == null) return;

		for (int i = 0; i < oldCap; i++) {
			byte c = oldCtrl[i];
			if (!isFull(c)) continue;
			@SuppressWarnings("unchecked")
			K k = (K) oldKeys[i];
			@SuppressWarnings("unchecked")
			V v = (V) oldVals[i];
			int h = hash(k);
			insertFresh(k, v, h1(h), h2(h));
		}
	}

	/* fresh-table insertion used only during rehash */
	private void insertFresh(K key, V value, int h1, byte h2) {
		int nGroups = numGroups();
		if (nGroups == 0) { throw new IllegalStateException("No groups allocated"); }
		int mask = nGroups - 1;
		int g = h1 & mask; // optimized modulo operation (same as h1 % nGroups)
		for (;;) {
			int base = g * DEFAULT_GROUP_SIZE;
			for (int j = 0; j < DEFAULT_GROUP_SIZE; j++) {
				int idx = base + j;
				if (isEmpty(ctrl[idx])) {
					ctrl[idx] = h2;
					keys[idx] = key;
					vals[idx] = value;
					size++;
					return;
				}
			}
			g = (g + 1) & mask;
		}
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
	public boolean containsValue(Object value) {
		// linear scan; acceptable for now
		for (int i = 0; i < capacity; i++) {
			if (isFull(ctrl[i])) {
				if (Objects.equals(vals[i], value)) return true;
			}
		}
		return false;
	}

	@Override
	public V put(K key, V value) {
		maybeRehash();
		return putVal(key, value);
	}

	@Override
	public V remove(Object key) {
		int idx = findIndex(key);
		if (idx < 0) return null;
		@SuppressWarnings("unchecked")
		V old = (V) vals[idx];
		ctrl[idx] = DELETED;
		keys[idx] = null;
		vals[idx] = null;
		size--;
		tombstones++;
		maybeRehash();
		return old;
	}

	/**
	 * Testing/benchmark only: delete without leaving a tombstone.
	 * Fills the hole via backward shift to keep probing contiguous.
	 */
	public V removeWithoutTombstone(Object key) {
		int idx = findIndex(key);
		if (idx < 0) return null;
		@SuppressWarnings("unchecked")
		V old = (V) vals[idx];
		backshiftDelete(idx);
		size--;
		return old;
	}

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        if (m.isEmpty()) return;

        // 预先检查是否需要扩容，保持与maybeRehash一致的逻辑
        boolean overMaxLoad = (size + tombstones + m.size()) >= maxLoad;
        boolean tooManyTombstones = tombstones > ((size + m.size()) >>> 1);

        if (overMaxLoad || tooManyTombstones) {
            // 直接使用 newSize 作为新的容量，rehash 方法会自动调整为合适的容量
            int newSize = this.size + m.size();
            int newCapacity = overMaxLoad ? Math.max(capacity * 2, DEFAULT_GROUP_SIZE) : capacity;
            // 确保容量足够大以容纳所有元素
            while (((int) (newCapacity * loadFactor)) < newSize) {
                newCapacity = Math.max(newCapacity * 2, DEFAULT_GROUP_SIZE);
            }
            rehash(newCapacity);
        }

        // 批量插入，避免在每次put时都检查是否需要扩容
        for (Entry<? extends K, ? extends V> e : m.entrySet()) {
            putVal(e.getKey(), e.getValue());
        }
    }

    /**
     * 不检查扩容的put方法，用于批量插入优化
     */
    private V putVal(K key, V value) {
        int h = hash(key);
        int h1 = h1(h);
        byte h2 = h2(h);
        int nGroups = numGroups();
        int mask = nGroups - 1;
        int firstTombstone = -1;
        int visitedGroups = 0;
        int g = h1 & mask; // optimized modulo operation (same as h1 % nGroups)
        for (;;) {
            int base = g * DEFAULT_GROUP_SIZE;
            ByteVector v = loadCtrlVector(base);
            long eqMask = v.eq(h2).toLong();
            while (eqMask != 0) {
                int bit = Long.numberOfTrailingZeros(eqMask);
                int idx = base + bit;
                if (Objects.equals(keys[idx], key)) { // almost always true; too bad I can't hint the compiler
                    @SuppressWarnings("unchecked") V old = (V) vals[idx];
                    vals[idx] = value;
                    return old;
                }
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
                return insertAt(target, key, value, h2);
            }
            if (++visitedGroups >= nGroups) {
                throw new IllegalStateException("Probe cycle exhausted; table appears full of tombstones");
            }
            g = (g + 1) & mask;
        }
    }

	@Override
	public void clear() {
		Arrays.fill(ctrl, 0, capacity, EMPTY);
		Arrays.fill(ctrl, capacity, ctrl.length, SENTINEL);
		Arrays.fill(keys, null);
		Arrays.fill(vals, null);
		size = 0;
		tombstones = 0;
		maxLoad = calcMaxLoad(capacity);
	}

	@Override
	public Set<K> keySet() {
		return new KeyView();
	}

	@Override
	public Collection<V> values() {
		return new ValuesView();
	}

	@Override
	public Set<Entry<K, V>> entrySet() {
		return new EntryView();
	}

	/* lookup utilities */
	@Override
	protected int findIndex(Object key) {
		if (size == 0) return -1;
		int h = hash(key);
		int h1 = h1(h);
		byte h2 = h2(h);
		int nGroups = numGroups();
		int mask = nGroups - 1;
		int visitedGroups = 0;
		int g = h1 & mask; // optimized modulo operation (same as h1 % nGroups)
		for (;;) {
			int base = g * DEFAULT_GROUP_SIZE;
			ByteVector v = loadCtrlVector(base);
			long eqMask = v.eq(h2).toLong();
			while (eqMask != 0) {
				int bit = Long.numberOfTrailingZeros(eqMask);
				int idx = base + bit;
				if (Objects.equals(keys[idx], key)) { // almost always true
					return idx;
				}
				eqMask &= eqMask - 1;
			}
			long emptyMask = v.eq(EMPTY).toLong();
			if (emptyMask != 0) { // almost always true
				return -1;
			}
			if (++visitedGroups >= nGroups) { // guard against infinite probe when table is full of tombstones
				return -1;
			}
			g = (g + 1) & mask;
		}
	}

	private V insertAt(int idx, K key, V value, byte h2) {
		if (isDeleted(ctrl[idx])) tombstones--;
		ctrl[idx] = h2;
		keys[idx] = key;
		vals[idx] = value;
		size++;
		return null;
	}

	/**
	 * Backward shift delete: pull following entries left to fill the hole with no tombstones.
	 */
	private void backshiftDelete(int hole) {
		// Null out immediately so GC can reclaim.
		keys[hole] = null;
		vals[hole] = null;

		int nGroups = numGroups();
		int mask = nGroups - 1;
		int gsize = DEFAULT_GROUP_SIZE;

		for (;;) {
			int next = (hole + 1 == capacity) ? 0 : hole + 1;
			byte c = ctrl[next];

			// EMPTY means end of cluster; finish by marking current hole empty.
			if (isEmpty(c)) {
				ctrl[hole] = EMPTY;
				return;
			}

			// Clear any existing tombstone and move hole forward.
			if (isDeleted(c)) {
				if (tombstones > 0) tombstones--;
				ctrl[hole] = EMPTY;
				hole = next;
				continue;
			}

			// Compute how far next is from its home group.
			int h = hash(keys[next]);
			int home = h1(h) & mask;
			int group = next / gsize;
			int dist = (group - home + nGroups) & mask;

			// If entry is in its home group, stop shifting and mark hole empty.
			if (dist == 0) {
				ctrl[hole] = EMPTY;
				return;
			}

			// Move displaced entry into the hole.
			ctrl[hole] = c;
			keys[hole] = keys[next];
			vals[hole] = vals[next];

			// 새 hole은 next 위치
			keys[next] = null;
			vals[next] = null;
			ctrl[next] = EMPTY;
			hole = next;
		}
	}

	@SuppressWarnings("unchecked")
	private V castValue(Object v) {
		return (V) v;
	}

	@SuppressWarnings("unchecked")
	private K castKey(Object k) {
		return (K) k;
	}

	@Override
	protected V valueAt(int idx) {
		return castValue(vals[idx]);
	}

	/* iterator base */
	private abstract class BaseIter<T> implements Iterator<T> {
		private final int start;
		private final int step;
		private final int mask;
		private int iter = 0;
		private int next = -1;
		private int last = -1;

		BaseIter() {
			RandomCycle cycle = new RandomCycle(capacity);
			this.start = cycle.start;
			this.step = cycle.step;
			this.mask = cycle.mask;
			advance();
		}

		private void advance() {
			next = -1;
			while (iter < capacity) {
				// & mask == mod capacity; iter grows, step scrambles the visit order without extra buffers.
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
				vals[last] = null;
				size--;
				tombstones++;
			}
			last = -1;
		}
	}

	private class KeyIter extends BaseIter<K> {
		@Override
		public K next() {
			return castKey(keys[nextIndex()]);
		}
	}

	private class ValueIter extends BaseIter<V> {
		@Override
		public V next() {
			return castValue(vals[nextIndex()]);
		}
	}

	private class EntryIter extends BaseIter<Entry<K, V>> {
		@Override
		public Entry<K, V> next() {
			int idx = nextIndex();
			return new EntryRef(idx);
		}
	}

	private class EntryRef implements Entry<K, V> {
		private final int idx;
		EntryRef(int idx) { this.idx = idx; }

		@Override
		public K getKey() { return castKey(keys[idx]); }

		@Override
		public V getValue() { return castValue(vals[idx]); }

		@Override
		public V setValue(V v) {
			if (!isFull(ctrl[idx])) throw new IllegalStateException();
			V old = castValue(vals[idx]);
			vals[idx] = v;
			return old;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Entry<?,?> e)) return false;
			return Objects.equals(getKey(), e.getKey()) && Objects.equals(getValue(), e.getValue());
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
		}
	}

	private class KeyView extends java.util.AbstractSet<K> {
		@Override
		public int size() { return size; }

		@Override
		public void clear() { SwissMap.this.clear(); }

		@Override
		public boolean contains(Object o) { return containsKey(o); }

		@Override
		public boolean remove(Object o) {
			int idx = SwissMap.this.findIndex(o);
			if (idx < 0) return false;
			ctrl[idx] = DELETED;
			keys[idx] = null;
			vals[idx] = null;
			size--;
			tombstones++;
			// NOTE: do not rehash from iterator.remove().
			// Some JDK algorithms (e.g. AbstractCollection.retainAll) prefetch iterator state (next index) before calling remove().
			// Rehash would rebuild ctrl/keys and can invalidate that prefetched index, causing the iterator to yield null/empty slots.
			return true;
		}

		@Override
		public Iterator<K> iterator() { return new KeyIter(); }
	}

	private class ValuesView extends java.util.AbstractCollection<V> {
		@Override
		public int size() { return size; }

		@Override
		public void clear() { SwissMap.this.clear(); }

		@Override
		public boolean contains(Object o) { return containsValue(o); }

		@Override
		public Iterator<V> iterator() { return new ValueIter(); }
	}

	private class EntryView extends java.util.AbstractSet<Entry<K, V>> {
		@Override
		public int size() { return size; }

		@Override
		public void clear() { SwissMap.this.clear(); }

		@Override
		public boolean contains(Object o) {
			if (!(o instanceof Entry<?,?> e)) return false;
			int idx = findIndex(e.getKey());
			return idx >= 0 && Objects.equals(vals[idx], e.getValue());
		}

		@Override
		public boolean remove(Object o) {
			if (!(o instanceof Entry<?,?> e)) return false;
			int idx = findIndex(e.getKey());
			if (idx < 0 || !Objects.equals(vals[idx], e.getValue())) return false;
			ctrl[idx] = DELETED;
			keys[idx] = null;
			vals[idx] = null;
			size--;
			tombstones++;
			// NOTE: do not rehash from iterator.remove().
			// Some JDK algorithms (e.g. AbstractCollection.retainAll) prefetch iterator state (next index) before calling remove().
			// Rehash would rebuild ctrl/keys and can invalidate that prefetched index, causing the iterator to yield null/empty slots.
			return true;
		}

		@Override
		public Iterator<Entry<K, V>> iterator() { return new EntryIter(); }
	}
}
