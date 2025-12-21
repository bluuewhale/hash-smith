package io.github.bluuewhale.hashsmith;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MapTest {

	record MapSpec(
		String name,
		Supplier<Map<?, ?>> mapSupplier,
		IntFunction<Map<?, ?>> mapWithCapacitySupplier,
		boolean supportsNullKeys,
		boolean containsKeyNullThrows
	) {
		@Override public String toString() { return name; }
	}

	private static Stream<MapSpec> mapSpecs() {
		return Stream.of(
			new MapSpec(
				"SwissMap",
				SwissMap::new,
				SwissMap::new,
				false,
				true
			),
			new MapSpec(
				"ConcurrentSwissMap",
				ConcurrentSwissMap::new,
				ConcurrentSwissMap::new,
				false,
				true
			),
			new MapSpec(
				"SwissSimdMap",
				SwissSimdMap::new,
				SwissSimdMap::new,
				false,
				true
			),
			new MapSpec(
				"RobinHoodMap",
				RobinHoodMap::new,
				RobinHoodMap::new,
				false,
				true
			)
		);
	}

	@SuppressWarnings("unchecked")
	private static <K, V> Map<K, V> newMap(MapSpec spec) {
		return (Map<K, V>) spec.mapSupplier().get();
	}

	@SuppressWarnings("unchecked")
	private static <K, V> Map<K, V> newMap(MapSpec spec, int capacity) {
		return (Map<K, V>) spec.mapWithCapacitySupplier().apply(capacity);
	}

	@ParameterizedTest(name = "{0} isEmpty")
	@MethodSource("mapSpecs")
	void isEmpty(MapSpec spec) {
		var m = newMap(spec);

		assertTrue(m.isEmpty());

		m.put("a", 1);
		assertFalse(m.isEmpty());
	}

	@ParameterizedTest(name = "{0} containsKey")
	@MethodSource("mapSpecs")
	void containsKey(MapSpec spec) {
		var m = newMap(spec);
		m.put("a", 1);

		assertTrue(m.containsKey("a"));
		assertFalse(m.containsKey("b"));

		if (spec.containsKeyNullThrows()) {
			assertThrows(NullPointerException.class, () -> m.containsKey(null));
		} else {
			assertFalse(m.containsKey(null));
		}
	}

	@ParameterizedTest(name = "{0} basicCrud")
	@MethodSource("mapSpecs")
	void basicCrud(MapSpec spec) {
		var m = newMap(spec);

		var first = m.put("a", 1);
		var replaced = m.put("a", 2);
		var removed = m.remove("a");

		assertNull(first);
		assertEquals(1, replaced);
		assertEquals(2, removed);
		assertFalse(m.containsKey("a"));
		assertEquals(0, m.size());
	}

	@ParameterizedTest(name = "{0} nullKeyAndValue")
	@MethodSource("mapSpecs")
	void nullKeyAndValue(MapSpec spec) {
		var m = newMap(spec);

		if (spec.supportsNullKeys()) {
			m.put(null, 10);
			m.put("x", null);
			var replaced = m.put(null, 20);

			assertEquals(20, m.get(null));
			assertNull(m.get("x"));
			assertTrue(m.containsKey(null));
			assertTrue(m.containsValue(null));
			assertEquals(10, replaced);
		} else {
			assertThrows(NullPointerException.class, () -> m.put(null, 10));
			assertThrows(NullPointerException.class, () -> m.get(null));
			assertThrows(NullPointerException.class, () -> m.remove(null));

			m.put("x", null);
			assertNull(m.get("x"));
			assertTrue(m.containsValue(null));
		}
	}

	@ParameterizedTest(name = "{0} reuseTombstone")
	@MethodSource("mapSpecs")
	void reuseTombstone(MapSpec spec) {
		var m = newMap(spec);

		m.put("a", 1);
		m.remove("a");
		m.put("a", 2);

		assertEquals(2, m.get("a"));
	}

	@ParameterizedTest(name = "{0} rehashOnLoad")
	@MethodSource("mapSpecs")
	void rehashOnLoad(MapSpec spec) {
		var m = newMap(spec, 4);

		for (int i = 0; i < 32; i++) m.put(i, i * 10);
		for (int i = 0; i < 32; i++) assertEquals(i * 10, m.get(i));
	}

	@ParameterizedTest(name = "{0} entrySetRemoveByValue")
	@MethodSource("mapSpecs")
	void entrySetRemoveByValue(MapSpec spec) {
		var m = newMap(spec);

		m.put("a", 1);
		m.put("b", 2);

		assertTrue(m.entrySet().remove(Map.entry("a", 1)));
		assertFalse(m.containsKey("a"));
		assertEquals(1, m.size());

		assertFalse(m.entrySet().remove(Map.entry("b", 999)));
		assertTrue(m.containsKey("b"));
	}

	@ParameterizedTest(name = "{0} iteratorRemove")
	@MethodSource("mapSpecs")
	void iteratorRemove(MapSpec spec) {
		var m = newMap(spec);

		m.put("a", 1);
		m.put("b", 2);

		var it = m.keySet().iterator();
		assertTrue(it.hasNext());

		it.next();
		it.remove();

		assertEquals(1, m.size());
	}

	@ParameterizedTest(name = "{0} clearResetsState")
	@MethodSource("mapSpecs")
	void clearResetsState(MapSpec spec) {
		var m = newMap(spec);

		m.put("a", 1);
		m.clear();

		assertEquals(0, m.size());
		assertFalse(m.containsKey("a"));

		m.put("b", 2);
		assertEquals(2, m.get("b"));
	}

	@ParameterizedTest(name = "{0} entrySetSetValueReflectsInMap")
	@MethodSource("mapSpecs")
	void entrySetSetValueReflectsInMap(MapSpec spec) {
		var m = newMap(spec);
		m.put("k", 1);

		var it = m.entrySet().iterator();
		var e = it.next();
		e.setValue(99);

		assertEquals(99, m.get("k"));
	}

	@ParameterizedTest(name = "{0} iteratorRemovesAll")
	@MethodSource("mapSpecs")
	void iteratorRemovesAll(MapSpec spec) {
		var m = newMap(spec);
		for (int i = 0; i < 10; i++) m.put(i, i);

		var it = m.entrySet().iterator();
		while (it.hasNext()) {
			it.next();
			it.remove();
		}

		assertEquals(0, m.size());
		assertTrue(m.isEmpty());
	}

	@ParameterizedTest(name = "{0} highCollision")
	@MethodSource("mapSpecs")
	void highCollision(MapSpec spec) {
		record Fixed(int val) {
			@Override public int hashCode() { return 0x1234_5601; }
		}
		var m = newMap(spec);

		m.put(new Fixed(1), 10);
		m.put(new Fixed(2), 20);
		m.put(new Fixed(3), 30);
		m.remove(new Fixed(2));

		assertEquals(10, m.get(new Fixed(1)));
		assertEquals(30, m.get(new Fixed(3)));
		assertNull(m.get(new Fixed(2)));
	}

	@ParameterizedTest(name = "{0} putAllBulk")
	@MethodSource("mapSpecs")
	void putAllBulk(MapSpec spec) {
		var m = newMap(spec);
		var src = new java.util.HashMap<Integer, Integer>();
		for (int i = 0; i < 50; i++) src.put(i, i * 2);

		m.putAll(src);

		assertEquals(src.size(), m.size());
		for (int i = 0; i < 50; i++) assertEquals(i * 2, m.get(i));
	}

	@ParameterizedTest(name = "{0} valuesContainsAndIteratorRemove")
	@MethodSource("mapSpecs")
	void valuesContainsAndIteratorRemove(MapSpec spec) {
		var m = newMap(spec);
		m.put("a", 1);
		m.put("b", null);

		assertTrue(m.values().contains(1));
		assertTrue(m.values().contains(null));

		var it = m.values().iterator();
		assertTrue(it.hasNext());
		it.next();
		it.remove();

		assertEquals(1, m.size());
	}

	@ParameterizedTest(name = "{0} keySetRemoveAllRetainAll")
	@MethodSource("mapSpecs")
	void keySetRemoveAllRetainAll(MapSpec spec) {
		var m = newMap(spec);
		m.put("a", 1);
        m.put("b", 2);
        m.put("c", 3);
		var removeSet = java.util.Set.of("a", "x");

		m.keySet().removeAll(removeSet);
		assertFalse(m.containsKey("a"));
		assertEquals(2, m.size());

		m.keySet().retainAll(java.util.Set.of("b"));
		assertTrue(m.containsKey("b"));
		assertEquals(1, m.size());
	}

	@ParameterizedTest(name = "{0} iteratorRemoveIllegalState")
	@MethodSource("mapSpecs")
	void iteratorRemoveIllegalState(MapSpec spec) {
		var m = newMap(spec);

		m.put("a", 1);
		var it = m.entrySet().iterator();

		assertThrows(IllegalStateException.class, it::remove);
	}

	@ParameterizedTest(name = "{0} duplicateRemoveIllegalState")
	@MethodSource("mapSpecs")
	void duplicateRemoveIllegalState(MapSpec spec) {
		var m = newMap(spec);
		m.put("a", 1);
		var it = m.entrySet().iterator();
		it.next();
		it.remove();

		assertThrows(IllegalStateException.class, it::remove);
	}

	@ParameterizedTest(name = "{0} largeDeleteAndReinsert")
	@MethodSource("mapSpecs")
	void largeDeleteAndReinsert(MapSpec spec) {
		var m = newMap(spec);
		for (int i = 0; i < 500; i++) m.put(i, i);

		for (int i = 0; i < 400; i++) m.remove(i);
		for (int i = 0; i < 400; i++) m.put(i, i * 2);

		assertEquals(500, m.size());
		for (int i = 0; i < 500; i++) {
			int expected = (i < 400) ? i * 2 : i;
			assertEquals(expected, m.get(i));
		}
	}

	@ParameterizedTest(name = "{0} entrySetSetValueAcrossAll")
	@MethodSource("mapSpecs")
	void entrySetSetValueAcrossAll(MapSpec spec) {
		var m = newMap(spec);
		m.put("a", 1);
        m.put("b", 2);
        m.put("c", 3);

		for (var e : m.entrySet()) {
			e.setValue(100);
		}

		for (var e : m.entrySet()) assertEquals(100, e.getValue());
	}

	@ParameterizedTest(name = "{0} iteratorCoversAllEntries")
	@MethodSource("mapSpecs")
	void iteratorCoversAllEntries(MapSpec spec) {
		var m = newMap(spec);
		int n = 10000;
		int expectedSum = 0;
		for (int i = 0; i < n; i++) {
			int v = i + 1; // avoid zero to make sum check meaningful
			m.put(i, v);
			expectedSum += v;
		}

		int actualSum = 0;
		int count = 0;
		for (var e : m.entrySet()) {
			actualSum += (Integer) e.getValue();
			count++;
		}

		assertEquals(n, count);
		assertEquals(n, m.size());
		assertEquals(expectedSum, actualSum);
	}
}
