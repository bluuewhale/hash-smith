package io.github.bluuewhale.hashsmith;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
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
		IntFunction<Map<?, ?>> mapWithCapacitySupplier,
        Double loadFactor
	) {
		@Override public String toString() { return name + " lf=" + loadFactor; }
	}

	private static Stream<MapSpec> mapSpecs() {
		return Stream.of(
            new MapSpec(
                "HashMap",
                () -> new HashMap<>(16, 0.9f),
                cap -> new HashMap<>(cap, 0.9f),
                0.9
            ),
			new MapSpec(
				"ConcurrentSwissMap",
				() -> new ConcurrentSwissMap<>(16, 0.5),
				cap -> new ConcurrentSwissMap<>(cap, 0.5),
				0.5
			),
			new MapSpec(
				"ConcurrentSwissMap",
				() -> new ConcurrentSwissMap<>(16, 0.75),
				cap -> new ConcurrentSwissMap<>(cap, 0.75),
				0.75
			),
			new MapSpec(
				"ConcurrentSwissMap",
				() -> new ConcurrentSwissMap<>(16, 0.875),
				cap -> new ConcurrentSwissMap<>(cap, 0.875),
				0.875
			),
			new MapSpec(
                "SwissMap",
                () -> new SwissMap<>(16, 0.5),
                cap -> new SwissMap<>(cap, 0.5),
                0.5
            ),
            new MapSpec(
                "SwissMap",
                () -> new SwissMap<>(16, 0.75),
                cap -> new SwissMap<>(cap, 0.75),
                0.75
            ),
            new MapSpec(
                "SwissMap",
                () -> new SwissMap<>(16, 0.875),
                cap -> new SwissMap<>(cap, 0.875),
                0.875
            ),
            new MapSpec(
                "SwissSimdMap",
                () -> new SwissSimdMap<>(16, 0.5),
                cap -> new SwissSimdMap<>(cap, 0.5),
                0.5
            ),
            new MapSpec(
                "SwissSimdMap",
                () -> new SwissSimdMap<>(16, 0.75),
                cap -> new SwissSimdMap<>(cap, 0.75),
                0.75
            ),
			new MapSpec(
                "SwissSimdMap",
                () -> new SwissSimdMap<>(16, 0.875),
                cap -> new SwissSimdMap<>(cap, 0.875),
                0.875
            ),
            new MapSpec(
                "RobinHoodMap",
                () -> new RobinHoodMap<>(16, 0.5),
                cap -> new RobinHoodMap<>(cap, 0.5),
                0.5
            ),
            new MapSpec(
                "RobinHoodMap",
                () -> new RobinHoodMap<>(16, 0.75),
                cap -> new RobinHoodMap<>(cap, 0.75),
                0.75
            ),
			new MapSpec(
                "RobinHoodMap",
                () -> new RobinHoodMap<>(16, 0.875),
                cap -> new RobinHoodMap<>(cap, 0.875),
                0.875
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

    /**
     * test case for bug found in Rust's HashMap implementation
     * see https://accidentallyquadratic.tumblr.com/post/153545455987/rust-hash-iteration-reinsertion for more details
     * @param spec
     */
	@ParameterizedTest(name = "{0} insertAndReinsertTimings")
	@MethodSource("mapSpecs")
	void insertAndReinsertTimings(MapSpec spec) {
		int n = 3_000_000;

		long insertStart = System.nanoTime();
		var one = newMap(spec, n);
		for (int i = 1; i <= n; i++) {
			one.put(i, i);
		}
		long insertEnd = System.nanoTime();

		long reinsertStart = System.nanoTime();
		var two = newMap(spec, n/2);
		for (var e : one.entrySet()) {
			two.put(e.getKey(), e.getValue());
		}
		long reinsertEnd = System.nanoTime();

		assertEquals(n, one.size());
		assertEquals(one.size(), two.size());
		assertEquals(one.get(1234), two.get(1234));

		double insertMs = (insertEnd - insertStart) / 1_000_000.0;
		double reinsertMs = (reinsertEnd - reinsertStart) / 1_000_000.0;
		System.out.printf(
			"%s insert %,d entries: %.2f ms, reinsert: %.2f ms%n",
			spec.name(), n, insertMs, reinsertMs
		);
	}
}
