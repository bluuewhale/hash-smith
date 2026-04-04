package io.github.bluuewhale.hashsmith;

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.StampedLock;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A sharded, thread-safe wrapper around {@link SwissMap}.
 *
 * <p>Concurrency model:
 * <ul>
 *   <li><b>Shard selection</b>: choose a shard by the high bits of {@code Hashing.smearedHash(key)}.</li>
 *   <li><b>Reads</b>: {@link StampedLock#tryOptimisticRead()} first, fallback to {@code readLock()}.</li>
 *   <li><b>Writes</b>: {@code writeLock()} per shard, covering put/remove/clear/rehash inside the shard.</li>
 * </ul>
 *
 * <p>Note: The underlying {@link SwissMap} is written for single-threaded use. If an optimistic read
 * overlaps with a writer, the core map may still throw (e.g., NPE) unless the core publish/NULL-safety
 * rules are strengthened. This class is intended to be used together with those follow-up changes.
 */
public final class ConcurrentSwissMap<K, V> implements ConcurrentMap<K, V> {

	private static final int DEFAULT_INITIAL_CAPACITY = 16;
	private static final double DEFAULT_LOAD_FACTOR = 0.875d;

	private final StampedLock[] locks;
	private final SwissMap<K, V>[] maps;
	private final int shardBits;
	/** Right-shift count to extract shard bits from the MSBs of the smeared hash. */
	private final int shardShift;

	public ConcurrentSwissMap() {
		this(defaultShardCount(), DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR);
	}

	public ConcurrentSwissMap(int initialCapacity) {
		this(defaultShardCount(), initialCapacity, DEFAULT_LOAD_FACTOR);
	}

	public ConcurrentSwissMap(int initialCapacity, double loadFactor) {
		this(defaultShardCount(), initialCapacity, loadFactor);
	}

	public ConcurrentSwissMap(int shardCount, int initialCapacity, double loadFactor) {
		if (shardCount <= 0) throw new IllegalArgumentException("shardCount must be > 0");
		int sc = Utils.ceilPow2(shardCount);
		this.shardBits = Integer.numberOfTrailingZeros(sc);
		this.shardShift = Integer.SIZE - shardBits;
		// SwissMap stores H2 in the lower 7 bits (control byte tag). Do not use those bits for sharding.
		// We shard by the high bits of H1 (hash >>> 7), mirroring hashbrown's "leave tag bits out" approach.
		int shift = shardShift - 7;
		if (shift < 0) {
			throw new IllegalArgumentException("shardCount too large: max shards is 2^(Integer.SIZE-7)");
		}

		StampedLock[] locks = new StampedLock[sc];
		@SuppressWarnings("unchecked")
		SwissMap<K, V>[] maps = (SwissMap<K, V>[]) new SwissMap[sc];

		int cap = Math.max(DEFAULT_INITIAL_CAPACITY, initialCapacity);
		int perShard = Math.max(1, (cap + sc - 1) / sc);
		for (int i = 0; i < sc; i++) {
			locks[i] = new StampedLock();
			maps[i] = new SwissMap<>(perShard, loadFactor);
		}
		this.locks = locks;
		this.maps = maps;
	}

	private static int defaultShardCount() {
		int cores = Runtime.getRuntime().availableProcessors();
		return Utils.ceilPow2(Math.max(1, cores * 4));
	}

	private static int smearedHashNonNull(Object key) {
		if (key == null) throw new NullPointerException("Null keys not supported");
		return Hashing.smearedHash(key);
	}

	private int shardOfHash(int smearedHash) {
		if (shardBits == 0) return 0;
		// shardBits are taken from the MSBs of the smeared hash.
		return smearedHash >>> shardShift;
	}

	private int shardOf(Object key) {
		return shardOfHash(smearedHashNonNull(key));
	}

	@Override
	public V get(Object key) {
		int h = smearedHashNonNull(key);
		int idx = shardOfHash(h);
		StampedLock lock = locks[idx];
		SwissMap<K, V> map = maps[idx];

		long stamp = lock.tryOptimisticRead();
		if (stamp != 0L) {
			V v = map.getConcurrent(key, h);
			if (lock.validate(stamp)) return v;
		}

		// Fallback to read lock.
		stamp = lock.readLock();
		try {
			return map.getConcurrent(key, h);
		} finally {
			lock.unlockRead(stamp);
		}
	}

	@Override
	public boolean containsKey(Object key) {
		int h = smearedHashNonNull(key);
		int idx = shardOfHash(h);
		StampedLock lock = locks[idx];
		SwissMap<K, V> map = maps[idx];

		long stamp = lock.tryOptimisticRead();
		if (stamp != 0L) {
			boolean ok = map.containsKeyConcurrent(key, h);
			if (lock.validate(stamp)) return ok;
		}

		// Fallback to read lock.
		stamp = lock.readLock();
		try {
			return map.containsKeyConcurrent(key, h);
		} finally {
			lock.unlockRead(stamp);
		}
	}

	@Override
	public boolean containsValue(Object value) {
		// Read-only scan; lock each shard to avoid concurrent structural changes.
		for (int i = 0; i < maps.length; i++) {
			StampedLock lock = locks[i];
			SwissMap<K, V> map = maps[i];
			long stamp = lock.readLock();
			try {
				if (map.containsValue(value)) return true;
			} finally {
				lock.unlockRead(stamp);
			}
		}
		return false;
	}

	@Override
	public V put(K key, V value) {
		int h = smearedHashNonNull(key);
		int idx = shardOfHash(h);
		StampedLock lock = locks[idx];
		SwissMap<K, V> map = maps[idx];

		long stamp = lock.writeLock();
		try {
			return map.putConcurrent(key, value, h);
		} finally {
			lock.unlockWrite(stamp);
		}
	}

	@Override
	public V remove(Object key) {
		int h = smearedHashNonNull(key);
		int idx = shardOfHash(h);
		StampedLock lock = locks[idx];
		SwissMap<K, V> map = maps[idx];

		long stamp = lock.writeLock();
		try {
			return map.removeConcurrent(key, h);
		} finally {
			lock.unlockWrite(stamp);
		}
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
		// Batch by shard to reduce lock acquisitions.
		@SuppressWarnings("unchecked")
		ArrayList<Entry<? extends K, ? extends V>>[] buckets = (ArrayList<Entry<? extends K, ? extends V>>[])
			new ArrayList[maps.length];

		for (Entry<? extends K, ? extends V> e : m.entrySet()) {
			int idx = shardOf(e.getKey());
			ArrayList<Entry<? extends K, ? extends V>> b = buckets[idx];
			if (b == null) {
				b = new ArrayList<>();
				buckets[idx] = b;
			}
			b.add(e);
		}

		for (int i = 0; i < buckets.length; i++) {
			ArrayList<Entry<? extends K, ? extends V>> b = buckets[i];
			if (b == null) continue;
			StampedLock lock = locks[i];
			SwissMap<K, V> map = maps[i];
			long stamp = lock.writeLock();
			try {
				for (Entry<? extends K, ? extends V> e : b) {
					map.put(e.getKey(), e.getValue());
				}
			} finally {
				lock.unlockWrite(stamp);
			}
		}
	}

	@Override
	public void clear() {
		for (int i = 0; i < maps.length; i++) {
			StampedLock lock = locks[i];
			SwissMap<K, V> map = maps[i];
			long stamp = lock.writeLock();
			try {
				map.clear();
			} finally {
				lock.unlockWrite(stamp);
			}
		}
	}

	@Override
	public int size() {
		long sum = 0L;
		for (int i = 0; i < maps.length; i++) {
			StampedLock lock = locks[i];
			SwissMap<K, V> map = maps[i];
			long stamp = lock.readLock();
			try {
				sum += map.size();
			} finally {
				lock.unlockRead(stamp);
			}
			if (sum > Integer.MAX_VALUE) return Integer.MAX_VALUE;
		}
		return (int) sum;
	}

	@Override
	public boolean isEmpty() {
		for (int i = 0; i < maps.length; i++) {
			StampedLock lock = locks[i];
			SwissMap<K, V> map = maps[i];
			long stamp = lock.readLock();
			try {
				if (!map.isEmpty()) return false;
			} finally {
				lock.unlockRead(stamp);
			}
		}
		return true;
	}

	@Override
	public V putIfAbsent(K key, V value) {
		int idx = shardOf(key);
		StampedLock lock = locks[idx];
		SwissMap<K, V> map = maps[idx];
		long stamp = lock.writeLock();
		try {
			// Map/ConcurrentMap contract: treat "mapped-to-null" as absent and insert the value.
			V cur = map.get(key);
			if (cur != null) return cur;
			map.put(key, value);
			return null;
		} finally {
			lock.unlockWrite(stamp);
		}
	}

	@Override
	public boolean remove(Object key, Object value) {
		int idx = shardOf(key);
		StampedLock lock = locks[idx];
		SwissMap<K, V> map = maps[idx];
		long stamp = lock.writeLock();
		try {
			if (!map.containsKey(key)) return false;
			Object cur = map.get(key);
			if (!Objects.equals(cur, value)) return false;
			map.remove(key);
			return true;
		} finally {
			lock.unlockWrite(stamp);
		}
	}

	@Override
	public boolean replace(K key, V oldValue, V newValue) {
		int idx = shardOf(key);
		StampedLock lock = locks[idx];
		SwissMap<K, V> map = maps[idx];
		long stamp = lock.writeLock();
		try {
			if (!map.containsKey(key)) return false;
			Object cur = map.get(key);
			if (!Objects.equals(cur, oldValue)) return false;
			map.put(key, newValue);
			return true;
		} finally {
			lock.unlockWrite(stamp);
		}
	}

	@Override
	public V replace(K key, V value) {
		int idx = shardOf(key);
		StampedLock lock = locks[idx];
		SwissMap<K, V> map = maps[idx];
		long stamp = lock.writeLock();
		try {
			if (!map.containsKey(key)) return null;
			return map.put(key, value);
		} finally {
			lock.unlockWrite(stamp);
		}
	}

	@Override
	public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
		Objects.requireNonNull(mappingFunction, "mappingFunction");
		int idx = shardOf(key);
		StampedLock lock = locks[idx];
		SwissMap<K, V> map = maps[idx];
		long stamp = lock.writeLock();
		try {
			V cur = map.get(key);
			if (cur != null) return cur;
			V newVal = mappingFunction.apply(key);
			if (newVal == null) return null; // do not create a new mapping
			map.put(key, newVal);
			return newVal;
		} finally {
			lock.unlockWrite(stamp);
		}
	}

	@Override
	public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
		Objects.requireNonNull(remappingFunction, "remappingFunction");
		int idx = shardOf(key);
		StampedLock lock = locks[idx];
		SwissMap<K, V> map = maps[idx];
		long stamp = lock.writeLock();
		try {
			V cur = map.get(key);
			if (cur == null) return null; // treat mapped-to-null as absent
			V newVal = remappingFunction.apply(key, cur);
			if (newVal == null) {
				map.remove(key);
				return null;
			}
			map.put(key, newVal);
			return newVal;
		} finally {
			lock.unlockWrite(stamp);
		}
	}

	@Override
	public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
		Objects.requireNonNull(remappingFunction, "remappingFunction");
		int idx = shardOf(key);
		StampedLock lock = locks[idx];
		SwissMap<K, V> map = maps[idx];
		long stamp = lock.writeLock();
		try {
			V oldVal = map.get(key);
			V newVal = remappingFunction.apply(key, oldVal);
			if (newVal == null) {
                map.remove(key);
				return null;
			}
			map.put(key, newVal);
			return newVal;
		} finally {
			lock.unlockWrite(stamp);
		}
	}

	@Override
	public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
		Objects.requireNonNull(value, "value");
		Objects.requireNonNull(remappingFunction, "remappingFunction");
		int idx = shardOf(key);
		StampedLock lock = locks[idx];
		SwissMap<K, V> map = maps[idx];
		long stamp = lock.writeLock();
		try {
			V oldVal = map.get(key);
			if (oldVal == null) {
				map.put(key, value);
				return value;
			}
			V newVal = remappingFunction.apply(oldVal, value);
			if (newVal == null) {
				map.remove(key);
				return null;
			}
			map.put(key, newVal);
			return newVal;
		} finally {
			lock.unlockWrite(stamp);
		}
	}

	@Override
	public void forEach(BiConsumer<? super K, ? super V> action) {
		Objects.requireNonNull(action, "action");
		for (Entry<K, V> e : snapshotEntries()) {
			action.accept(e.getKey(), e.getValue());
		}
	}

	@Override
	public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
		Objects.requireNonNull(function, "function");
		for (int i = 0; i < maps.length; i++) {
			StampedLock lock = locks[i];
			SwissMap<K, V> map = maps[i];
			long stamp = lock.writeLock();
			try {
				// Avoid mutating while iterating the core map by snapshotting keys first.
				ArrayList<K> keys = new ArrayList<>(map.size());
				for (Entry<K, V> e : map.entrySet()) {
					keys.add(e.getKey());
				}
				for (K k : keys) {
					V oldVal = map.get(k);
					V newVal = function.apply(k, oldVal);
					map.put(k, newVal);
				}
			} finally {
				lock.unlockWrite(stamp);
			}
		}
	}

	@Override
	public int hashCode() {
		int h = 0;
		for (Entry<K, V> e : entrySet()) {
			h += e.hashCode();
		}
		return h;
	}

	@Override
	public boolean equals(Object o) {
		if (o == this) return true;
		if (!(o instanceof Map<?, ?> m)) return false;
		if (m.size() != this.size()) return false;
		try {
			for (Entry<K, V> e : entrySet()) {
				K key = e.getKey();
				V value = e.getValue();
				Object other = m.get(key);
				if (!Objects.equals(value, other)) return false;
				if (value == null && !m.containsKey(key)) return false;
			}
		} catch (ClassCastException | NullPointerException unused) {
			return false;
		}
		return true;
	}

	@Override
	public String toString() {
		Iterator<Entry<K, V>> i = entrySet().iterator();
		if (!i.hasNext()) return "{}";
		StringBuilder sb = new StringBuilder();
		sb.append('{');
		for (;;) {
			Entry<K, V> e = i.next();
			K k = e.getKey();
			V v = e.getValue();
			sb.append(k == this ? "(this Map)" : k);
			sb.append('=');
			sb.append(v == this ? "(this Map)" : v);
			if (!i.hasNext()) return sb.append('}').toString();
			sb.append(',').append(' ');
		}
	}

	@Override
	public Set<K> keySet() {
		return new KeyView();
	}

	private final class KeyView extends AbstractSet<K> {
		@Override
		public int size() {
			return ConcurrentSwissMap.this.size();
		}

		@Override
		public void clear() {
			ConcurrentSwissMap.this.clear();
		}

		@Override
		public boolean contains(Object o) {
			return ConcurrentSwissMap.this.containsKey(o);
		}

		@Override
		public boolean remove(Object o) {
			int idx = shardOf(o);
			StampedLock lock = locks[idx];
			SwissMap<K, V> map = maps[idx];
			long stamp = lock.writeLock();
			try {
				if (!map.containsKey(o)) return false;
				map.remove(o);
				return true;
			} finally {
				lock.unlockWrite(stamp);
			}
		}

		@Override
		public Iterator<K> iterator() {
			ArrayList<Entry<K, V>> snap = snapshotEntries();
			return new Iterator<>() {
				private int i = 0;
				private K lastKey;
				private boolean canRemove;

				@Override
				public boolean hasNext() {
					return i < snap.size();
				}

				@Override
				public K next() {
					Entry<K, V> e = snap.get(i++);
					lastKey = e.getKey();
					canRemove = true;
					return lastKey;
				}

				@Override
				public void remove() {
					if (!canRemove) throw new IllegalStateException();
					ConcurrentSwissMap.this.remove(lastKey);
					canRemove = false;
				}
			};
		}
	}

	@Override
	public Collection<V> values() {
		return new ValuesView();
	}

	private final class ValuesView extends AbstractCollection<V> {
		@Override
		public int size() {
			return ConcurrentSwissMap.this.size();
		}

		@Override
		public void clear() {
			ConcurrentSwissMap.this.clear();
		}

		@Override
		public boolean contains(Object o) {
			return ConcurrentSwissMap.this.containsValue(o);
		}

		@Override
		public Iterator<V> iterator() {
			ArrayList<Entry<K, V>> snap = snapshotEntries();
			return new Iterator<>() {
				private int i = 0;
				private K lastKey;
				private boolean canRemove;

				@Override
				public boolean hasNext() {
					return i < snap.size();
				}

				@Override
				public V next() {
					Entry<K, V> e = snap.get(i++);
					lastKey = e.getKey();
					canRemove = true;
					return e.getValue();
				}

				@Override
				public void remove() {
					if (!canRemove) throw new IllegalStateException();
					ConcurrentSwissMap.this.remove(lastKey);
					canRemove = false;
				}
			};
		}

		@Override
		public boolean remove(Object o) {
			// Remove one occurrence (first match).
			for (int i = 0; i < maps.length; i++) {
				StampedLock lock = locks[i];
				SwissMap<K, V> map = maps[i];
				long stamp = lock.writeLock();
				try {
					for (Entry<K, V> e : map.entrySet()) {
						if (Objects.equals(e.getValue(), o)) {
							map.remove(e.getKey());
							return true;
						}
					}
				} finally {
					lock.unlockWrite(stamp);
				}
			}
			return false;
		}
	}

	@Override
	public Set<Entry<K, V>> entrySet() {
		return new EntrySetView();
	}

	private final class EntrySetView extends AbstractSet<Entry<K, V>> {
		@Override
		public int size() {
			return ConcurrentSwissMap.this.size();
		}

		@Override
		public void clear() {
			ConcurrentSwissMap.this.clear();
		}

		@Override
		public boolean contains(Object o) {
			if (!(o instanceof Entry<?, ?> e)) return false;
			Object key = e.getKey();
			Object expected = e.getValue();
			Object actual = ConcurrentSwissMap.this.get(key);
			return Objects.equals(actual, expected) && ConcurrentSwissMap.this.containsKey(key);
		}

		@Override
		public boolean remove(Object o) {
			if (!(o instanceof Entry<?, ?> e)) return false;
			Object key = e.getKey();
			Object expected = e.getValue();
			int h = smearedHashNonNull(key);
			int idx = shardOfHash(h);
			StampedLock lock = locks[idx];
			SwissMap<K, V> map = maps[idx];
			long stamp = lock.writeLock();
			try {
				if (!map.containsKeyConcurrent(key, h)) return false;
				Object actual = map.getConcurrent(key, h);
				if (!Objects.equals(actual, expected)) return false;
				map.removeConcurrent(key, h);
				return true;
			} finally {
				lock.unlockWrite(stamp);
			}
		}

		@Override
		public Iterator<Entry<K, V>> iterator() {
			// Phase 1: snapshot iterator (weakly consistent). iterator.remove delegates to map.remove.
			ArrayList<Entry<K, V>> snap = snapshotEntries();
			return new SnapshotEntryIterator(snap);
		}
	}

	private ArrayList<Entry<K, V>> snapshotEntries() {
		ArrayList<Entry<K, V>> out = new ArrayList<>();
		for (int i = 0; i < maps.length; i++) {
			StampedLock lock = locks[i];
			SwissMap<K, V> map = maps[i];
			long stamp = lock.readLock();
			try {
				for (Entry<K, V> e : map.entrySet()) {
					out.add(new SnapshotEntry(e.getKey(), e.getValue()));
				}
			} finally {
				lock.unlockRead(stamp);
			}
		}
		return out;
	}

	private final class SnapshotEntryIterator implements Iterator<Entry<K, V>> {
		private final ArrayList<Entry<K, V>> snap;
		private int idx = 0;
		private K lastKey;
		private boolean canRemove;

		SnapshotEntryIterator(ArrayList<Entry<K, V>> snap) {
			this.snap = snap;
		}

		@Override
		public boolean hasNext() {
			return idx < snap.size();
		}

		@Override
		public Entry<K, V> next() {
			Entry<K, V> e = snap.get(idx++);
			lastKey = e.getKey();
			canRemove = true;
			return e;
		}

		@Override
		public void remove() {
			if (!canRemove) throw new IllegalStateException();
			ConcurrentSwissMap.this.remove(lastKey);
			canRemove = false;
		}
	}

	private final class SnapshotEntry implements Entry<K, V> {
		private final K key;
		private V value;

		SnapshotEntry(K key, V value) {
			this.key = key;
			this.value = value;
		}

		@Override
		public K getKey() {
			return key;
		}

		@Override
		public V getValue() {
			return value;
		}

		@Override
		public V setValue(V newValue) {
			V old = ConcurrentSwissMap.this.put(key, newValue);
			this.value = newValue;
			return old;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Entry<?, ?> e)) return false;
			return Objects.equals(key, e.getKey()) && Objects.equals(value, e.getValue());
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(key) ^ Objects.hashCode(value);
		}

		@Override
		public String toString() {
			return key + "=" + value;
		}
	}

}


