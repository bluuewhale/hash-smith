package io.github.bluuewhale.hashsmith;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.StampedLock;

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
public final class ConcurrentSwissMap<K, V> extends AbstractMap<K, V> {

	private static final int DEFAULT_INITIAL_CAPACITY = 16;
	private static final double DEFAULT_LOAD_FACTOR = 0.875d;

	private final StampedLock[] locks;
	private final SwissMap<K, V>[] maps;
	private final int shardBits;
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
		// SwissMap stores H2 in the lower 7 bits (control byte tag). Do not use those bits for sharding.
		// We shard by the high bits of H1 (hash >>> 7), mirroring hashbrown's "leave tag bits out" approach.
		int shift = (Integer.SIZE - 7) - shardBits;
		if (shift < 0) {
			throw new IllegalArgumentException("shardCount too large: max shards is 2^(Integer.SIZE-7)");
		}
		this.shardShift = shift;

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
		// Leave the lower 7 bits for SwissMap's H2 tag; shard by the remaining bits (H1).
		int idx = (smearedHash >>> 7) >>> shardShift;
		return idx;
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
		V v = map.get(key, h);
		if (lock.validate(stamp)) return v;

		// Fallback to read lock.
		stamp = lock.readLock();
		try {
			return map.get(key, h);
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
		boolean ok = map.containsKey(key, h);
		if (lock.validate(stamp)) return ok;

		// Fallback to read lock.
		stamp = lock.readLock();
		try {
			return map.containsKey(key, h);
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
			return map.put(key, value, h);
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
			return map.remove(key, h);
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
				if (!map.containsKey(key, h)) return false;
				Object actual = map.get(key, h);
				if (!Objects.equals(actual, expected)) return false;
				map.remove(key, h);
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
	}

}


