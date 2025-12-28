package io.github.bluuewhale.hashsmith;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free (non-blocking) SwissTable-inspired map skeleton.
 *
 * Design notes (initial milestone):
 * - No cooperative resizing/helping yet (fixed table size).
 * - Insertion uses {@code key CAS(null->K) -> value CAS(null->V) -> ctrl: EMPTY -> FULL(h2)} publish protocol.
 * - ctrl becomes immutable after reaching FULL(h2). Deletion is represented by value TOMBSTONE.
 * - Reads are ctrl-driven; seeing FULL implies key/value publication for inserts (acquire/release).
 *
 * Limitations (intentional for the first cut):
 * - Resizing is single-threaded (no cooperative helping yet). Threads spin/retry while a resize is in progress.
 * - Iteration views are snapshot-based and may allocate.
 */
public final class LockFreeSwissMap<K, V> implements ConcurrentMap<K, V> {

	/* Control byte values */
	private static final byte EMPTY = (byte) 0x80;

	/* Hash split masks: high bits choose group, low 7 bits stored in control byte */
	private static final int H1_MASK = 0xFFFFFF80;
	private static final int H2_MASK = 0x0000007F;

	/* Group sizing: SWAR fixed at 8 slots (1 word) */
	private static final int GROUP_SIZE = 8;

	/* SWAR constants */
	private static final long BITMASK_LSB = 0x0101010101010101L;
	private static final long BITMASK_MSB = 0x8080808080808080L;

	/* PutIfMatch sentinels (NBHM-style) */
	public static final Object NO_MATCH_OLD = new Object();
	public static final Object MATCH_ANY = new Object();
	private static final Object TOMBSTONE = new Object();
	/** Key marker used during resizing to freeze an old-table slot (NBHM copy_slot-style). */
	private static final Object MOVED_KEY = new Object();
	private static final Prime TOMBSTONE_PRIME = new Prime(TOMBSTONE);

	/** Internal retry signal for table-scoped primitives (caller should retry with a fresh table). */
	private static final Object RETRY = new Object();
	/** Internal signal meaning "no EMPTY slot observed" (caller may trigger resize). */
	private static final Object NEED_RESIZE = new Object();

	/**
	 * Resizing marker (NBHM-style): stored in the value array to indicate a slot is being copied.
	 * For the initial single-threaded resize milestone, readers/writers spin+retry if they observe a Prime.
	 */
	private static final class Prime {
		final Object value;
		Prime(Object value) { this.value = value; }
		static Object unbox(Object v) { return (v instanceof Prime p) ? p.value : v; }
	}

	private static final VarHandle CTRL_WORD = MethodHandles.arrayElementVarHandle(long[].class);
	private static final VarHandle KEYS = MethodHandles.arrayElementVarHandle(Object[].class);
	private static final VarHandle VALS = MethodHandles.arrayElementVarHandle(Object[].class);
	private static final VarHandle TABLE;
	private static final VarHandle META_NEW_TABLE;

	/** Sentinel published to {@link Metadata#newTable} while a single thread performs the resize. */
	private static final Object[] RESIZE_IN_PROGRESS = new Object[0];

	static {
		try {
			TABLE = MethodHandles.lookup().findVarHandle(LockFreeSwissMap.class, "table", Object[].class);
			META_NEW_TABLE = MethodHandles.lookup().findVarHandle(Metadata.class, "newTable", Object[].class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static long ctrlWordAcquire(long[] ctrl, int group) {
		return (long) CTRL_WORD.getAcquire(ctrl, group);
	}

	private static long ctrlWordVolatile(long[] ctrl, int group) {
		return (long) CTRL_WORD.getVolatile(ctrl, group);
	}

	@SuppressWarnings("UnusedReturnValue")
	private static boolean casCtrlByte(long[] ctrl, int idx, byte expected, byte update) {
		int group = idx >> 3;
		int shift = (idx & 7) << 3;
		long mask = 0xFFL << shift;
		for (;;) {
			long oldWord = ctrlWordVolatile(ctrl, group);
			byte cur = (byte) (oldWord >>> shift);
			if (cur != expected) return false;
			long newWord = (oldWord & ~mask) | ((update & 0xFFL) << shift);
			if (CTRL_WORD.compareAndSet(ctrl, group, oldWord, newWord)) return true;
		}
	}

	private static int h1(int smearedHash) {
		return (smearedHash & H1_MASK) >>> 7;
	}

	private static byte h2(int smearedHash) {
		return (byte) (smearedHash & H2_MASK);
	}

	private static int smearedHashNonNull(Object key) {
		if (key == null) throw new NullPointerException("Null keys not supported");
		return Hashing.smearedHash(key);
	}

	private static long broadcast(byte b) {
		return (b & 0xFFL) * BITMASK_LSB;
	}

	/**
	 * Compare bytes in word against b; return packed 8-bit mask of matches.
	 * Derived from the SWAR comparison used in {@link SwissMap}.
	 */
	private static int eqMask(long word, byte b) {
		long x = word ^ broadcast(b);
		long m = (((x >>> 1) | BITMASK_MSB) - x) & BITMASK_MSB;
		return (int) ((m * 0x0204_0810_2040_81L) >>> 56);
	}

	/**
	 * Control-plane meta for (future) resizing; stored in {@code table[0]}.
	 *
	 * For now we intentionally do NOT cache derived sizes (groupMask/capacity). Compute on-demand from arrays.
	 */
	private static final class Metadata {
		/**
		 * Non-null while a resize is in-progress (or has published a successor table).
		 * For the single-threaded milestone, other threads spin until {@link LockFreeSwissMap#table} swaps.
		 */
		volatile Object[] newTable;

		Metadata() {}
	}

	/**
	 * NBHM-style table layout (single reference, swappable via one CAS):
	 * table[0]: Metadata (control plane)
	 * table[1]: ctrl (long[])
	 * table[2]: keys (Object[])
	 * table[3]: values (Object[])
	 */
	@SuppressWarnings("FieldMayBeFinal")
	private volatile Object[] table;

	@SuppressWarnings("unused")
	private boolean casTable(Object[] expect, Object[] update) {
		return TABLE.compareAndSet(this, expect, update);
	}

	private static Metadata meta(Object[] table) { return (Metadata) table[0]; }
	private static long[] ctrl(Object[] table) { return (long[]) table[1]; }
	private static Object[] keys(Object[] table) { return (Object[]) table[2]; }
	private static Object[] vals(Object[] table) { return (Object[]) table[3]; }

	private static int groupMask(long[] ctrl) { return ctrl.length - 1; }

	private static boolean isResizing(Object[] table) {
		return meta(table).newTable != null;
	}

	private void spinUntilTableSwapped(Object[] expectedTable) {
		while (this.table == expectedTable) {
			Thread.onSpinWait();
		}
	}

	private static Object[] allocTable(int nGroups) {
		long[] ctrl = new long[nGroups];
		for (int i = 0; i < nGroups; i++) ctrl[i] = broadcast(EMPTY);
		Object[] keys = new Object[nGroups * GROUP_SIZE];
		Object[] vals = new Object[nGroups * GROUP_SIZE];
		Metadata m = new Metadata();
		return new Object[]{m, ctrl, keys, vals};
	}

	private void resizeOrWait(Object[] oldTable) {
		Metadata m = meta(oldTable);

		// Somebody already started resizing this table; wait until swap.
		if (m.newTable != null) {
			spinUntilTableSwapped(oldTable);
			return;
		}

		// Attempt to become the single resizer by claiming newTable.
        // prevent mutliple threads trying to create bunch of new tables at the same time
		if (!META_NEW_TABLE.compareAndSet(m, null, RESIZE_IN_PROGRESS)) {
			spinUntilTableSwapped(oldTable);
			return;
		}

		// Allocate successor and publish "resizing" state.
		long[] oldCtrl = ctrl(oldTable);
		Object[] newTable = allocTable(oldCtrl.length << 1);
        META_NEW_TABLE.setRelease(m, newTable);

		// Copy live entries; mark old values as Prime to advertise in-flight.
		Object[] oldKeys = keys(oldTable);
		Object[] oldVals = vals(oldTable);
		for (int g = 0; g < oldCtrl.length; g++) {
			int base = g << 3;
			for (int lane = 0; lane < 8; lane++) {
				int idx = base + lane;
				copySlot(oldKeys, oldVals, newTable, idx);
			}
		}

		// Publish successor as current table.
		TABLE.compareAndSet(this, oldTable, newTable);
        META_NEW_TABLE.setRelease(m, null); // volatile write publishes the new arrays
	}

	/**
	 * NBHM-style primitive: all mutations are expressed via this method.
	 *
	 * {@code oldVal} meanings:
	 * {@link #NO_MATCH_OLD}: wildcard, always attempt (insert if absent)
	 * {@link #MATCH_ANY}: only update/delete if key exists and is not deleted
	 * {@code null}: only insert if absent (putIfAbsent)
	 * any other object: update/delete if {@code Objects.equals(oldVal, currentValue)}
	 *
	 * {@code newVal}: a non-null new value, or {@link #DELETE} to remove.
	 *
	 * Returns the observed current value (null if absent).
	 */
	private Object putIfMatch(Object[] table, Object key, Object newVal, Object expectedOld, boolean updateSize) {
		Metadata m0 = meta(table);
		if (m0.newTable != null) return RETRY;

		// LockFreeSwissMap does NOT support null values.
		// Deletion is expressed via TOMBSTONE; insertion/update values must be non-null.
		if (newVal == null) throw new NullPointerException("Null values not supported");

		int h = smearedHashNonNull(key);
		byte tag = h2(h);

		long[] ctrl = ctrl(table);
		Object[] keys = keys(table);
		Object[] vals = vals(table);

		int mask = groupMask(ctrl);
		int g = h1(h) & mask;
		int step = 0;

		boolean retry = false;

		for (int probes = 0; probes <= mask; probes++) {
			long word = ctrlWordAcquire(ctrl, g);
			int base = g << 3;

			int mm = eqMask(word, tag);
			while (mm != 0) {
				int lane = Integer.numberOfTrailingZeros(mm);
				int idx = base + lane;

				Object k = keys[idx]; // safe after seeing FULL tag (ctrl acquire)
				if (k == MOVED_KEY) { retry = true; break; }
				if (k == key || k.equals(key)) {
					Object cur = VALS.getVolatile(vals, idx);
					assert cur != null;
					if (cur instanceof Prime) { retry = true; break; }

					if (cur == TOMBSTONE) {
						if (newVal == DELETE) return null;
						if (!(expectedOld == NO_MATCH_OLD || expectedOld == null)) return null;
						if (m0.newTable != null) return RETRY;
						if (VALS.compareAndSet(vals, idx, TOMBSTONE, newVal)) {
							if (updateSize) liveSize.increment();
							return null;
						}
						retry = true;
						break;
					}

					if (expectedOld != NO_MATCH_OLD) {
						if (expectedOld == MATCH_ANY) {
							// cur is non-TOMBSTONE here
						} else if (!((cur == expectedOld) || cur.equals(expectedOld))) {
							return cur;
						}
					}

					if (newVal == DELETE) {
						if (m0.newTable != null) return RETRY;
						if (VALS.compareAndSet(vals, idx, cur, TOMBSTONE)) {
							if (updateSize) liveSize.decrement();
							return cur;
						}
						retry = true;
						break;
					}

					if (m0.newTable != null) return RETRY;
					if (VALS.compareAndSet(vals, idx, cur, newVal)) return cur;
					retry = true;
					break;
				}

				mm &= mm - 1;
			}
			if (retry) break;

			int empty = eqMask(word, EMPTY);
			if (empty != 0) {
				if (newVal == DELETE) return null;
				if (!(expectedOld == NO_MATCH_OLD || expectedOld == null)) return null;

				int lane = Integer.numberOfTrailingZeros(empty);
				int ins = base + lane;

				if (m0.newTable != null) return RETRY;
				if (!KEYS.compareAndSet(keys, ins, null, key)) { retry = true; break; }
				if (m0.newTable != null) return RETRY;
				if (!VALS.compareAndSet(vals, ins, null, newVal)) { retry = true; break; }

				if (m0.newTable != null) return RETRY;
				if (!casCtrlByte(ctrl, ins, EMPTY, tag)) { retry = true; break; }

				if (updateSize) liveSize.increment();
				return null;
			}

			g = (g + (++step)) & mask;
		}

		return retry ? RETRY : NEED_RESIZE;
	}

	private Object putIfMatch(Object key, Object newVal, Object oldVal) {
		for (;;) {
			Object[] table = this.table;
			if (isResizing(table)) {
				spinUntilTableSwapped(table);
				continue;
			}

			Object r = putIfMatch(table, key, newVal, oldVal, true);
			if (r == RETRY) {
				Thread.onSpinWait();
				continue;
			}
			if (r == NEED_RESIZE) {
				resizeOrWait(table);
				continue;
			}
			return r;
		}
	}

	private void copySlot(Object[] oldKeys, Object[] oldVals, Object[] newTable, int idx) {
		// 1) Freeze key (NBHM copy_slot-style)
		Object key;
		for (;;) {
			key = oldKeys[idx];
			assert key != null; // Seeing FULL in ctrl implies the key was published before ctrl publish.
			if (key == MOVED_KEY) return; // already copied/frozen
			if (KEYS.compareAndSet(oldKeys, idx, key, MOVED_KEY)) break;
		}

		// 2) Box/observe old value to prevent old-table updates being "missed"
		Object oldVal = VALS.getVolatile(oldVals, idx);
		assert oldVal != null;
		if (oldVal == TOMBSTONE) return;
		Prime boxed;
		if (oldVal instanceof Prime p) {
			boxed = p;
		} else {
			boxed = new Prime(oldVal);
			VALS.compareAndSet(oldVals, idx, oldVal, boxed); // best-effort
		}

		Object unboxed = Prime.unbox(boxed);
		if (unboxed == TOMBSTONE) return;

		// 3) Copy into new table using the shared primitive
		Object r = putIfMatch(newTable, key, unboxed, null, false);
		if (r == RETRY) throw new IllegalStateException("unexpected retry while copying into new table");
		if (r == NEED_RESIZE) throw new IllegalStateException("unexpected resize while copying into new table");
		// r should be null (absent) in a fresh new table.

		// 4) Mark old value as copied (speed optimization; also makes readers retry via Prime check)
		for (;;) {
			Object cur = VALS.getVolatile(oldVals, idx);
			if (cur == TOMBSTONE_PRIME) break;
			if (VALS.compareAndSet(oldVals, idx, cur, TOMBSTONE_PRIME)) break;
		}
	}


	private static final Object DELETE = new Object();

	private final LongAdder liveSize = new LongAdder();

	public LockFreeSwissMap() {
		this(16);
	}

	public LockFreeSwissMap(int initialCapacity) {
		int desiredCapacity = Math.max(1, initialCapacity);
		int nGroups = Math.max(1, (desiredCapacity + GROUP_SIZE - 1) / GROUP_SIZE);
		nGroups = Utils.ceilPow2(nGroups);
		this.table = allocTable(nGroups);
	}

	@Override
	public V get(Object key) {
		Objects.requireNonNull(key, "key");
		int h = smearedHashNonNull(key);
		byte tag = h2(h);
		for (;;) {
			Object[] table = this.table;
			if (isResizing(table)) {
				spinUntilTableSwapped(table);
				continue;
			}
			long[] ctrl = ctrl(table);
			Object[] keys = keys(table);
			Object[] vals = vals(table);

			int mask = groupMask(ctrl);
			int g = h1(h) & mask;
			int step = 0;
			boolean retry = false;

			for (int probes = 0; probes <= mask; probes++) {
				long word = ctrlWordAcquire(ctrl, g);
				int base = g << 3;

				int m = eqMask(word, tag);
				while (m != 0) {
					int lane = Integer.numberOfTrailingZeros(m);
					int idx = base + lane;
					Object k = keys[idx]; // safe after seeing FULL tag (ctrl acquire)
					if (k == MOVED_KEY) {
						retry = true;
						break;
					}
					if (k == key || k.equals(key)) {
						Object v = VALS.getVolatile(vals, idx);
						if (v instanceof Prime) {
							retry = true;
							break;
						}
						// Value is never null in this map; TOMBSTONE means absent.
						return (v == TOMBSTONE) ? null : castValue(v);
					}
					m &= m - 1;
				}
				if (retry) break;

				int empty = eqMask(word, EMPTY);
				if (empty != 0) {
					return null;
				}

				g = (g + (++step)) & mask;
			}

			Thread.onSpinWait();
		}
	}

	@Override
	public boolean containsKey(Object key) {
		return get(key) != null;
	}

	@Override
	public V put(K key, V value) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(value, "value");
		return castValue(putIfMatch(key, value, NO_MATCH_OLD));
	}

	@Override
	public V putIfAbsent(K key, V value) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(value, "value");
		return castValue(putIfMatch(key, value, null));
	}

	@Override
	public V remove(Object key) {
		return castValue(putIfMatch(key, DELETE, MATCH_ANY));
	}

	@Override
	public boolean remove(Object key, Object value) {
		Objects.requireNonNull(value, "value");
		Object prev = putIfMatch(key, DELETE, value);
		return Objects.equals(prev, value);
	}

	@Override
	public boolean replace(K key, V oldValue, V newValue) {
		Objects.requireNonNull(oldValue, "oldValue");
		Objects.requireNonNull(newValue, "newValue");
		Object prev = putIfMatch(key, newValue, oldValue);
		return Objects.equals(prev, oldValue);
	}

	@Override
	public V replace(K key, V value) {
		Objects.requireNonNull(value, "value");
		return castValue(putIfMatch(key, value, MATCH_ANY));
	}

	@Override
	public int size() {
		long n = liveSize.sum();
		if (n > Integer.MAX_VALUE) return Integer.MAX_VALUE;
		return (int) n;
	}

	@Override
	public boolean isEmpty() {
		return size() == 0;
	}

	@Override
	public boolean containsValue(Object value) {
		if (value == null) return false;
		for (Entry<K, V> e : entrySet()) {
			if (Objects.equals(e.getValue(), value)) return true;
		}
		return false;
	}

	@Override
	public void clear() {
		for (;;) {
			Object[] table = this.table;
			if (isResizing(table)) {
				spinUntilTableSwapped(table);
				continue;
			}

			// Clear by tombstoning values for FULL slots; ctrl stays FULL forever once published.
			long[] ctrl = ctrl(table);
			Object[] vals = vals(table);

			for (int g = 0; g < ctrl.length; g++) {
				long word = ctrlWordAcquire(ctrl, g);
				int base = g << 3;
				for (int lane = 0; lane < 8; lane++) {
					byte c = (byte) (word >>> (lane << 3));
					if ((c & 0x80) != 0) continue; // EMPTY
					int idx = base + lane;
					Object v = VALS.getVolatile(vals, idx);
					if (v instanceof Prime) continue;
					if (v == TOMBSTONE) continue;
					if (VALS.compareAndSet(vals, idx, v, TOMBSTONE)) liveSize.decrement();
				}
			}
			return;
		}
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
		for (Entry<? extends K, ? extends V> e : m.entrySet()) {
			put(e.getKey(), e.getValue());
		}
	}

	@Override
	public Set<K> keySet() {
		return new AbstractSet<>() {
			@Override
			public int size() {
				return LockFreeSwissMap.this.size();
			}

			@Override
			public Iterator<K> iterator() {
				return new SnapshotKeyIterator(snapshotEntries());
			}
		};
	}

	@Override
	public Collection<V> values() {
		return new AbstractCollection<>() {
			@Override
			public int size() {
				return LockFreeSwissMap.this.size();
			}

			@Override
			public Iterator<V> iterator() {
				return new SnapshotValueIterator(snapshotEntries());
			}
		};
	}

	@Override
	public Set<Entry<K, V>> entrySet() {
		return new AbstractSet<>() {
			@Override
			public int size() {
				return LockFreeSwissMap.this.size();
			}

			@Override
			public Iterator<Entry<K, V>> iterator() {
				return new SnapshotEntryIterator(snapshotEntries());
			}
		};
	}

	private final class SnapshotKeyIterator implements Iterator<K> {
		private final ArrayList<Entry<K, V>> snap;
		private int idx = 0;
		private K lastKey;
		private boolean canRemove;

		SnapshotKeyIterator(ArrayList<Entry<K, V>> snap) {
			this.snap = snap;
		}

		@Override
		public boolean hasNext() {
			return idx < snap.size();
		}

		@Override
		public K next() {
			if (!hasNext()) throw new NoSuchElementException();
			Entry<K, V> e = snap.get(idx++);
			lastKey = e.getKey();
			canRemove = true;
			return lastKey;
		}

		@Override
		public void remove() {
			if (!canRemove) throw new IllegalStateException();
			LockFreeSwissMap.this.remove(lastKey);
			canRemove = false;
		}
	}

	private final class SnapshotValueIterator implements Iterator<V> {
		private final ArrayList<Entry<K, V>> snap;
		private int idx = 0;
		private K lastKey;
		private boolean canRemove;

		SnapshotValueIterator(ArrayList<Entry<K, V>> snap) {
			this.snap = snap;
		}

		@Override
		public boolean hasNext() {
			return idx < snap.size();
		}

		@Override
		public V next() {
			if (!hasNext()) throw new NoSuchElementException();
			Entry<K, V> e = snap.get(idx++);
			lastKey = e.getKey();
			canRemove = true;
			return e.getValue();
		}

		@Override
		public void remove() {
			if (!canRemove) throw new IllegalStateException();
			LockFreeSwissMap.this.remove(lastKey);
			canRemove = false;
		}
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
			if (!hasNext()) throw new NoSuchElementException();
			Entry<K, V> e = snap.get(idx++);
			lastKey = e.getKey();
			canRemove = true;
			return e;
		}

		@Override
		public void remove() {
			if (!canRemove) throw new IllegalStateException();
			LockFreeSwissMap.this.remove(lastKey);
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
			Objects.requireNonNull(newValue, "newValue");
			V old = LockFreeSwissMap.this.put(key, newValue);
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

	private ArrayList<Entry<K, V>> snapshotEntries() {
		for (;;) {
			Object[] table = this.table;
			if (isResizing(table)) {
				spinUntilTableSwapped(table);
				continue;
			}
			return snapshotEntriesStable(table);
		}
	}

	private ArrayList<Entry<K, V>> snapshotEntriesStable(Object[] table) {
		ArrayList<Entry<K, V>> out = new ArrayList<>();
		long[] ctrl = ctrl(table);
		Object[] keys = keys(table);
		Object[] vals = vals(table);

		for (int g = 0; g < ctrl.length; g++) {
			long word = ctrlWordAcquire(ctrl, g);
			int base = g << 3;
			// FULL bytes are in [0..127] => sign bit is 0
			// We find non-empty by selecting lanes with MSB=0.
			// For the initial milestone we keep it simple: scan all lanes.
			for (int lane = 0; lane < 8; lane++) {
				byte c = (byte) (word >>> (lane << 3));
				if ((c & 0x80) != 0) continue; // EMPTY
				int idx = base + lane;
				@SuppressWarnings("unchecked")
				K k = (K) keys[idx];
				Object v = VALS.getVolatile(vals, idx);
				if (v instanceof Prime) continue;
				if (v == TOMBSTONE) continue;
				@SuppressWarnings("unchecked")
				V vv = (V) v;
				out.add(new SnapshotEntry(k, vv));
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private V castValue(Object v) {
		return (V) v;
	}

	// ConcurrentMap additional methods default to AbstractMap implementations or inherited defaults.
	@Override
	public V getOrDefault(Object key, V defaultValue) {
		V v = get(key);
		return (v != null) ? v : defaultValue;
	}

	@Override
	public V computeIfAbsent(K key, java.util.function.Function<? super K, ? extends V> mappingFunction) {
		Objects.requireNonNull(mappingFunction, "mappingFunction");
		V cur = get(key);
		if (cur != null) return cur;
		V created = mappingFunction.apply(key);
		if (created == null) return null;
		V prev = putIfAbsent(key, created);
		return (prev == null) ? created : prev;
	}

	@Override
	public V computeIfPresent(K key, java.util.function.BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
		Objects.requireNonNull(remappingFunction, "remappingFunction");
		for (;;) {
			V cur = get(key);
			if (cur == null) return null;
			V next = remappingFunction.apply(key, cur);
			if (next == null) {
				if (remove(key, cur)) return null;
				continue;
			}
			if (replace(key, cur, next)) return next;
		}
	}

	@Override
	public V compute(K key, java.util.function.BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
		Objects.requireNonNull(remappingFunction, "remappingFunction");
		for (;;) {
			V cur = get(key);
			V next = remappingFunction.apply(key, cur);
			if (next == null) {
				if (cur == null) return null;
				if (remove(key, cur)) return null;
				continue;
			}
			if (cur == null) {
				V prev = putIfAbsent(key, next);
				if (prev == null) return next;
				continue;
			}
			if (replace(key, cur, next)) return next;
		}
	}

	@Override
	public V merge(K key, V value, java.util.function.BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
		Objects.requireNonNull(value, "value");
		Objects.requireNonNull(remappingFunction, "remappingFunction");
		for (;;) {
			V cur = get(key);
			if (cur == null) {
				V prev = putIfAbsent(key, value);
				if (prev == null) return value;
				continue;
			}
			V next = remappingFunction.apply(cur, value);
			if (next == null) {
				if (remove(key, cur)) return null;
				continue;
			}
			if (replace(key, cur, next)) return next;
		}
	}

	// ForEach/ReplaceAll can delegate to snapshot iteration for now.
	@Override
	public void forEach(java.util.function.BiConsumer<? super K, ? super V> action) {
		Objects.requireNonNull(action, "action");
		for (Entry<K, V> e : entrySet()) action.accept(e.getKey(), e.getValue());
	}

	@Override
	public void replaceAll(java.util.function.BiFunction<? super K, ? super V, ? extends V> function) {
		Objects.requireNonNull(function, "function");
		for (Entry<K, V> e : entrySet()) {
			K k = e.getKey();
			V cur = e.getValue();
			V next = function.apply(k, cur);
			if (next == null) throw new NullPointerException("replaceAll function returned null");
			// best-effort
			replace(k, cur, next);
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
}


