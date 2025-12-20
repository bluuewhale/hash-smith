package io.github.bluuewhale.hashsmith;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * SwissTable variant: packs control bytes into 8-byte words and uses SWAR
 * comparisons (no Vector API) while scanning 8 slots at a time.
 */
public class SwissMap<K, V> extends AbstractArrayMap<K, V> {

	/* Control byte values */
	private static final byte EMPTY = (byte) 0x80;    // empty slot
	private static final byte DELETED = (byte) 0xFE;  // tombstone

	/* Hash split masks: high bits choose group, low 7 bits stored in control byte */
	private static final int H1_MASK = 0xFFFFFF80;
	private static final int H2_MASK = 0x0000007F;

	/* Group sizing: SWAR fixed at 8 slots (1 word) */
	private static final int GROUP_SIZE = 8;

	/* Load factor: similar to Abseil SwissTable (7/8) */
	private static final double DEFAULT_LOAD_FACTOR = 0.875d;

	/* SWAR constants */
	private static final long BITMASK_LSB = 0x0101010101010101L;
	private static final long BITMASK_MSB = 0x8080808080808080L;

	/* Storage and state */
	private long[] ctrl;     // each long packs 8 control bytes
	private Object[] keys;   // key storage
	private Object[] vals;   // value storage
	private int tombstones;  // deleted slots
	private int numGroups;   // cached group count (updated on init/rehash)
	private int groupMask;   // cached (numGroups - 1), valid because numGroups is power-of-two

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
		int nGroups = Math.max(1, (desiredCapacity + GROUP_SIZE - 1) / GROUP_SIZE);
		nGroups = ceilPow2(nGroups);
		this.numGroups = nGroups;
		this.groupMask = nGroups - 1;
		this.capacity = nGroups * GROUP_SIZE;

		this.ctrl = new long[nGroups];
		Arrays.fill(this.ctrl, broadcast(EMPTY));
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
		return hashNonNull(key);
	}

	/* Control byte inspectors */
	private boolean isEmpty(byte c) { return c == EMPTY; }
	private boolean isDeleted(byte c) { return c == DELETED; }
	private boolean isFull(byte c) { return c >= 0 && c <= H2_MASK; } // H2 in [0,127]

	/* SWAR helpers */
	private static long toUnsignedByte(byte b) {
		// Unsigned widening to avoid sign extension on negative bytes
		return b & 0xFFL;
	}

	private long broadcast(byte b) {
		// Broadcast a single byte to all 8 byte lanes
		return toUnsignedByte(b) * BITMASK_LSB;
	}

	private long loadCtrlWord(int group) {
		return ctrl[group];
	}

	/**
	 * Compare bytes in word against b; return packed 8-bit mask of matches.
	 * see: https://stackoverflow.com/questions/68695913/how-to-write-a-swar-comparison-which-puts-0xff-in-a-lane-on-matches/68701617#68701617
	 */
	protected int eqMask(long word, byte b) {
		long x = word ^ broadcast(b);
		long m = (((x >>> 1) | BITMASK_MSB) - x) & BITMASK_MSB;
		return (int) ((m * 0x0204_0810_2040_81L) >>> 56);
	}

	private byte ctrlAt(long[] ctrl, int idx) {
		int group = idx >> 3;
		int offset = (idx & 7) << 3;
		byte result = (byte) (ctrl[group] >>> offset);
		return result;
	}

	private void setCtrlAt(long[] ctrl, int idx, byte value) {
		int group = idx >> 3;
		int offset = (idx & 7) << 3;
		long word = ctrl[group];
		long mask = 0xFFL << offset;
		ctrl[group] = (word & ~mask) | (toUnsignedByte(value) << offset);
	}

	private void setEntryAt(int idx, K key, V value) {
		keys[idx] = key;
		vals[idx] = value;
	}

	/* Resize/rehash */
	private void maybeRehash() {
		// trigger when over load or too many tombstones
		boolean overMaxLoad = (size + tombstones) >= maxLoad;
		boolean tooManyTombstones = tombstones > (size >>> 1);
		if (!overMaxLoad && !tooManyTombstones) return;

		// Only grow the table when we are actually over the max load threshold.
		// If we are rehashing just to clean up tombstones, keep the capacity.
		int newCap = overMaxLoad ? Math.max(capacity * 2, GROUP_SIZE) : capacity;
		rehash(newCap);
	}

	private void rehash(int newCapacity) {
		long[] oldCtrl = this.ctrl;
		Object[] oldKeys = this.keys;
		Object[] oldVals = this.vals;
		int oldCap = (oldCtrl == null) ? 0 : oldCtrl.length * GROUP_SIZE;

		int desiredGroups = Math.max(1, (Math.max(newCapacity, GROUP_SIZE) + GROUP_SIZE - 1) / GROUP_SIZE);
		desiredGroups = ceilPow2(desiredGroups);
		this.numGroups = desiredGroups;
		this.groupMask = desiredGroups - 1;
		this.capacity = desiredGroups * GROUP_SIZE;
		this.ctrl = new long[desiredGroups];
		Arrays.fill(this.ctrl, broadcast(EMPTY));
		this.keys = new Object[this.capacity];
		this.vals = new Object[this.capacity];
		this.size = 0;
		this.tombstones = 0;
		this.maxLoad = calcMaxLoad(this.capacity);

		if (oldCtrl == null) return;

		for (int i = 0; i < oldCap; i++) {
			byte c = ctrlAt(oldCtrl, i);
			if (!isFull(c)) continue;
			K k = castKey(oldKeys[i]);
			V v = castValue(oldVals[i]);
			insertFresh(k, v);
		}
	}

	/* fresh-table insertion used only during rehash */
	private void insertFresh(K key, V value) {
		int h = hash(key);
		int h1 = h1(h);
		byte h2 = h2(h);
		int mask = groupMask; // Local snapshot of the group mask
		int g = h1 & mask; // optimized modulo operation (same as h1 % nGroups)
		for (;;) {
			int base = g * GROUP_SIZE;
			long word = loadCtrlWord(g);
			int emptyMask = eqMask(word, EMPTY);
			if (emptyMask != 0) {
				int idx = base + Integer.numberOfTrailingZeros(emptyMask);
				setCtrlAt(ctrl, idx, h2);
				setEntryAt(idx, key, value);
				size++;
				return;
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
		for (int i = 0; i < capacity; i++) {
			if (isFull(ctrlAt(ctrl, i))) {
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
		V old = castValue(vals[idx]);
		setCtrlAt(ctrl, idx, DELETED);
		setEntryAt(idx, null, null);
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
		V old = castValue(vals[idx]);
		backshiftDelete(idx);
		size--;
		return old;
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
        if (m.isEmpty()) return;

        // Pre-check if resizing is needed, keeping consistent logic with maybeRehash
		// account for tombstone reuse when projecting load before rehash
		// TODO: consider overlap-heavy putAll cases to avoid overestimating pre-size
		int projectedSize = size + tombstones + Math.max(0, m.size() - tombstones);
        boolean overMaxLoad = projectedSize >= maxLoad;

        if (overMaxLoad) {
            // Directly use newSize as the new capacity, rehash method will automatically adjust to appropriate capacity
            int newSize = this.size + m.size();
            int newCapacity = Math.max(capacity * 2, GROUP_SIZE);
            // Ensure capacity is large enough to accommodate all elements
            while (((int) (newCapacity * loadFactor)) < newSize) {
                newCapacity = Math.max(newCapacity * 2, GROUP_SIZE);
            }
            rehash(newCapacity);
        }

        // Batch insert, avoiding checking if resizing is needed on each put
        for (Entry<? extends K, ? extends V> e : m.entrySet()) {
            putVal(e.getKey(), e.getValue());
        }
	}

    private V putVal(K key, V value) {
        int h = hash(key);
        int h1 = h1(h);
        byte h2 = h2(h);
        int mask = groupMask; // Local snapshot of the mask for the probe loop
        int firstTombstone = -1; 
        int visitedGroups = 0;
        int g = h1 & mask; // optimized modulo operation (same as h1 % nGroups)
        for (;;) {
            int base = g * GROUP_SIZE;
            long word = loadCtrlWord(g);
			int eqMask = eqMask(word, h2);
            while (eqMask != 0) {
                int idx = base + Integer.numberOfTrailingZeros(eqMask);
                Object k = keys[idx];
                if (k == key || k.equals(key)) {
                    V old = castValue(vals[idx]);
                    vals[idx] = value;
                    return old;
                }
                eqMask &= eqMask - 1; // clear LSB
            }
            if (firstTombstone < 0) {
				int delMask = eqMask(word, DELETED);
                if (delMask != 0) firstTombstone = base + Integer.numberOfTrailingZeros(delMask);
            }
			int emptyMask = eqMask(word, EMPTY);
            if (emptyMask != 0) {
                int idx = base + Integer.numberOfTrailingZeros(emptyMask);
                int target = (firstTombstone >= 0) ? firstTombstone : idx;
                return insertAt(target, key, value, h2);
            }
            if (++visitedGroups >= numGroups) {
                throw new IllegalStateException("Probe cycle exhausted; table appears full of tombstones");
            }
            g = (g + 1) & mask;
        }
    }

	@Override
	public void clear() {
		Arrays.fill(ctrl, broadcast(EMPTY));
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
		// Disallow null keys even on empty maps for consistent Map semantics in this project.
		int h = hashNonNull(key);
		if (size == 0) return -1;
		int h1 = h1(h);
		byte h2 = h2(h);
		int mask = groupMask; // Local snapshot of the mask for the probe loop.
		int visitedGroups = 0;
		int g = h1 & mask; // optimized modulo operation (same as h1 % nGroups)
		for (;;) {
			int base = g * GROUP_SIZE;
			long word = loadCtrlWord(g);
			int eqMask = eqMask(word, h2);
			while (eqMask != 0) {
				int idx = base + Integer.numberOfTrailingZeros(eqMask);
                Object k = keys[idx];
                if (k == key || k.equals(key)) {
					return idx;
				}
				eqMask &= eqMask - 1; // clear LSB
			}
			int emptyMask = eqMask(word, EMPTY);
			if (emptyMask != 0) {
				return -1;
			}
			if (++visitedGroups >= numGroups) {
				return -1;
			}
			g = (g + 1) & mask;
		}
	}

	private V insertAt(int idx, K key, V value, byte h2) {
		if (isDeleted(ctrlAt(ctrl, idx))) tombstones--; // TODO: do not recalculate tombstones here
		setCtrlAt(ctrl, idx, h2);
		setEntryAt(idx, key, value);
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

		final int nGroups = this.numGroups;
		final int mask = this.groupMask;

		for (;;) {
			int next = (hole + 1 == capacity) ? 0 : hole + 1;
			byte c = ctrlAt(ctrl, next);

			// EMPTY means end of cluster; finish by marking current hole empty.
			if (isEmpty(c)) {
				setCtrlAt(ctrl, hole, EMPTY);
				return;
			}

			// Clear any existing tombstone and move hole forward.
			if (isDeleted(c)) {
				if (tombstones > 0) tombstones--;
				setCtrlAt(ctrl, hole, EMPTY);
				hole = next;
				continue;
			}

			// Compute how far next is from its home group.
			int h = hash(keys[next]);
			int home = h1(h) & mask;
			int group = next / GROUP_SIZE;
			int dist = (group - home + nGroups) & mask;

			// If entry is in its home group, stop shifting and mark hole empty.
			if (dist == 0) {
				setCtrlAt(ctrl, hole, EMPTY);
				return;
			}

			// Move displaced entry into the hole.
			setCtrlAt(ctrl, hole, c);
			setEntryAt(hole, castKey(keys[next]), castValue(vals[next]));
			setEntryAt(next, null, null);
			setCtrlAt(ctrl, next, EMPTY);
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
				if (isFull(ctrlAt(ctrl, idx))) {
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
			if (isFull(ctrlAt(ctrl, last))) {
				setCtrlAt(ctrl, last, DELETED);
				setEntryAt(last, null, null);
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
		public K getKey() {
			return castKey(keys[idx]);
		}

		@Override
		public V getValue() {
			return castValue(vals[idx]);
		}

		@Override
		public V setValue(V value) {
			V old = castValue(vals[idx]);
			vals[idx] = value;
			return old;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Entry)) return false;
			Entry<?, ?> e = (Entry<?, ?>) o;
			return Objects.equals(getKey(), e.getKey()) && Objects.equals(getValue(), e.getValue());
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
		}
	}

	private final class KeyView extends java.util.AbstractSet<K> {
		@Override
		public Iterator<K> iterator() {
			return new KeyIter();
		}

		@Override
		public int size() { return SwissMap.this.size(); }
	}

	private final class ValuesView extends java.util.AbstractCollection<V> {
		@Override
		public Iterator<V> iterator() {
			return new ValueIter();
		}

		@Override
		public int size() { return SwissMap.this.size(); }
	}

	private final class EntryView extends java.util.AbstractSet<Entry<K, V>> {
		@Override
		public Iterator<Entry<K, V>> iterator() {
			return new EntryIter();
		}

		@Override
		public int size() { return SwissMap.this.size(); }
	}
}

