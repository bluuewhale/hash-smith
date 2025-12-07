package com.donghyungko.hashsmith;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MapSmokeTest {

	record MapSpec(
		String name,
		Supplier<Map<?, ?>> mapSupplier,
		IntFunction<Map<?, ?>> mapWithCapacitySupplier
	) {
		@Override public String toString() { return name; }
	}

	private static Stream<MapSpec> mapSpecs() {
		return Stream.of(
			new MapSpec("SwissMap", SwissMap::new, SwissMap::new),
			new MapSpec("RobinHoodMap", RobinHoodMap::new, RobinHoodMap::new)
		);
	}

	@SuppressWarnings("unchecked")
	private static <K, V> Map<K, V> newMap(MapSpec spec) {
		return (Map<K, V>) spec.mapSupplier().get();
	}

	@ParameterizedTest(name = "{0} smokeLargeInsertDeleteReinsert")
	@MethodSource("mapSpecs")
	void smokeLargeInsertDeleteReinsert(MapSpec spec) {
		var m = newMap(spec);
		int n = 100_000;

		for (int i = 0; i < n; i++) m.put(i, i * 2);
		for (int i = 0; i < n; i++) assertEquals(i * 2, m.get(i));

		for (int i = 0; i < n; i += 2) m.remove(i);
		for (int i = 0; i < n; i++) {
            Object v = m.get(i);
			if (i % 2 == 0) assertNull(v);
			else assertEquals(i * 2, v);
		}

		for (int i = 0; i < n; i += 2) m.put(i, i * 3);
		for (int i = 0; i < n; i++) {
			int expected = (i % 2 == 0) ? i * 3 : i * 2;
			assertEquals(expected, m.get(i));
		}
		assertEquals(n, m.size());
	}

	@ParameterizedTest(name = "{0} smokeHighCollisionLoop")
	@MethodSource("mapSpecs")
	void smokeHighCollisionLoop(MapSpec spec) {
		record Collide(int v) { @Override public int hashCode() { return 0; } }
		var m = newMap(spec);
		int n = 10_000;

		for (int i = 0; i < n; i++) m.put(new Collide(i), i);
		for (int i = 0; i < n; i++) assertEquals(i, m.get(new Collide(i)));

		for (int i = 0; i < n; i += 3) m.remove(new Collide(i));
		for (int i = 0; i < n; i++) {
			Object v = m.get(new Collide(i));
			if (i % 3 == 0) assertNull(v);
			else assertEquals(i, v);
		}
	}
}
