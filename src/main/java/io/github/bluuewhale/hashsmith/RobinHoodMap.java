package io.github.bluuewhale.hashsmith;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Robin Hood hashing map (null keys NOT allowed, null values allowed).
 * Backward-shift deletion, linear probing, null-sentinel empty slots.
 */
public class RobinHoodMap<K, V> extends AbstractArrayMap<K, V> {

	/* Defaults */
	private static final int DEFAULT_INITIAL_CAPACITY = 16;
	private static final double DEFAULT_LOAD_FACTOR = 0.75d;

	/* Storage */
	private Object[] keys;
	private Object[] vals;
	private int[] dist; // probe distance (0-based)

	public RobinHoodMap() {
		this(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR);
	}

	public RobinHoodMap(int initialCapacity) {
		this(initialCapacity, DEFAULT_LOAD_FACTOR);
	}

	public RobinHoodMap(int initialCapacity, double loadFactor) {
		super(initialCapacity, loadFactor);
	}

	@Override
	protected void init(int initialCapacity) {
		resize(initialCapacity);
	}

	@Override
	public V put(K key, V value) {
		int h = hash(key);
		int mask = capacity - 1;
		int idx = h & mask;

		int curDist = 0;
		K curKey = key;
		V curVal = value;

		for (;;) {
			Object k = keys[idx];
			if (k == null) {
				setSlot(idx, curKey, curVal, curDist);
				size++;
				if (size > maxLoad) {
					resize(capacity << 1);
				}
				return null;
			}
			if (k.equals(curKey)) {
				V old = castValue(vals[idx]);
				vals[idx] = curVal;
				return old;
			}
			int slotDist = dist[idx];
			if (slotDist < curDist) {
				// Robin Hood swap
				K swapKey = castKey(k);
				V swapVal = castValue(vals[idx]);
				int swapDist = slotDist;

				setSlot(idx, curKey, curVal, curDist);

				curKey = swapKey;
				curVal = swapVal;
				curDist = swapDist;
			}
			idx = (idx + 1) & mask;
			curDist++;
		}
	}

	@Override
	public V remove(Object key) {
		int idx = findIndex(key);
		if (idx < 0) return null;
		V old = castValue(vals[idx]);
		deleteAt(idx);
		return old;
	}

	@Override
	public void clear() {
		for (int i = 0; i < capacity; i++) {
			keys[i] = null;
			vals[i] = null;
			dist[i] = 0;
		}
		size = 0;
	}

	@Override
	public Set<Map.Entry<K, V>> entrySet() {
		return new EntrySet();
	}

	/* Resize/rebuild helpers */
	private void resize(int newCapacity) {
		int targetCap = ceilPow2(Math.max(DEFAULT_INITIAL_CAPACITY, newCapacity));
		Object[] oldKeys = this.keys;
		Object[] oldVals = this.vals;

		this.capacity = targetCap;
		this.keys = new Object[targetCap];
		this.vals = new Object[targetCap];
		this.dist = new int[targetCap];
		this.size = 0;
		this.maxLoad = calcMaxLoad(targetCap);

		if (oldKeys == null || oldVals == null || oldKeys.length == 0) return;

		// Insert into a fresh table (empty, so no Robin Hood swaps needed)
		for (int i = 0; i < oldKeys.length; i++) {
			Object k = oldKeys[i];
			if (k == null) continue;
			K key = castKey(k);
			V val = castValue(oldVals[i]);
			int h = hash(key);
			int mask = capacity - 1;
			int idx = h & mask; // equivalent to h % capacity
			int d = 0;
			while (keys[idx] != null) {
				idx = (idx + 1) & mask;
				d++;
			}
			setSlot(idx, key, val, d);
			size++;
		}
	}

	/* Hash helpers */
	private int hash(Object key) {
		return hashNonNull(key);
	}

	/* Internal helpers */
	@Override
	protected int findIndex(Object key) {
		if (key == null) throw new NullPointerException("Null keys not supported");
		int h = hash(key);
		int mask = capacity - 1;
		int idx = h & mask;      // ideal slot
		int d = 0;               // probe distance while scanning
		for (;;) {
			Object k = keys[idx];
			if (k == null) return -1;
			if (k.equals(key)) return idx;
			int slotDist = dist[idx];
			if (slotDist < d) return -1; // early stop
			idx = (idx + 1) & mask;
			d++;
		}
	}

	private void deleteAt(int idx) {
		// Backward shift: pull following cluster left to fill the hole.
		int mask = capacity - 1;
		int cur = idx;
		for (;;) {
			int next = (cur + 1) & mask;
			Object nk = keys[next];
			if (nk == null) { // end of cluster
				clearSlot(cur);
				break;
			}
			int nd = dist[next];
			if (nd == 0) { // end of cluster
				clearSlot(cur);
				break;
			}
			setSlot(cur, nk, vals[next], nd - 1);
			cur = next;
		}
		size--;
	}

	/* Slot helpers */
	private void setSlot(int idx, Object key, Object value, int distance) {
		keys[idx] = key;
		vals[idx] = value;
		dist[idx] = distance;
	}

	private void clearSlot(int idx) {
		keys[idx] = null;
		vals[idx] = null;
		dist[idx] = 0;
	}

	@Override
	protected V valueAt(int idx) {
		return castValue(vals[idx]);
	}

	@SuppressWarnings("unchecked")
	private K castKey(Object key) {
		return (K) key;
	}

	@SuppressWarnings("unchecked")
	private V castValue(Object value) {
		return (V) value;
	}

	/* ------------ EntrySet / Iterator ------------ */

	private final class EntrySet extends AbstractSet<Map.Entry<K, V>> {
		@Override
		public int size() {
			return RobinHoodMap.this.size;
		}

		@Override
		public void clear() {
			RobinHoodMap.this.clear();
		}

		@Override
		public Iterator<Map.Entry<K, V>> iterator() {
			return new EntryIterator();
		}
	}

	private final class EntryIterator implements Iterator<Map.Entry<K, V>> {
		private final int start;
		private final int step;
		private final int mask = capacity - 1;
		private int iter = 0;
		private int nextIdx = -1;
		private K lastKey;
		private boolean canRemove;

		EntryIterator() {
			RandomCycle cycle = new RandomCycle(capacity, iterationSeed);
			this.start = cycle.start;
			this.step = cycle.step;
			advance();
		}

		private void advance() {
			nextIdx = -1;
			while (iter < capacity) {
				// & mask == mod capacity; iter grows monotonically, step scrambles the visit order.
				int idx = (start + (iter++ * step)) & mask;
				if (keys[idx] != null) {
					nextIdx = idx;
					return;
				}
			}
		}

		@Override
		public boolean hasNext() {
			return nextIdx >= 0;
		}

		@Override
		public Map.Entry<K, V> next() {
			if (nextIdx < 0) throw new NoSuchElementException();
			K key = castKey(keys[nextIdx]);
			lastKey = key;
			canRemove = true;
			advance();
			return new EntryView(lastKey);
		}

		@Override
		public void remove() {
			if (!canRemove) throw new IllegalStateException();
			RobinHoodMap.this.remove(lastKey);
			canRemove = false;
		}
	}

	private final class EntryView implements Map.Entry<K, V> {
		private final K key;

		EntryView(K key) {
			this.key = key;
		}

		@Override
		public K getKey() {
			return key;
		}

		@Override
		public V getValue() {
			return RobinHoodMap.this.get(key);
		}

		@Override
		public V setValue(V value) {
			return RobinHoodMap.this.put(key, value);
		}

		@Override
		public int hashCode() {
			V v = getValue();
			return (key == null ? 0 : key.hashCode()) ^ (v == null ? 0 : v.hashCode());
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof Map.Entry<?, ?> e)) return false;
			return Objects.equals(key, e.getKey()) && Objects.equals(getValue(), e.getValue());
		}

		@Override
		public String toString() {
			return key + "=" + getValue();
		}
	}
}

