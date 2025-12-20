package io.github.bluuewhale.hashsmith;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

class SwissRemoveWithoutTombstoneTest {

	private static int tombstonesOf(Object m) {
		try {
			Field f = m.getClass().getDeclaredField("tombstones");
			f.setAccessible(true);
			return f.getInt(m);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Failed to access tombstones via reflection", e);
		}
	}

	record Collide(int v) {
		@Override public int hashCode() { return 0; }
	}

	@Test
	void swissMap_removeWithoutTombstone_rehashesAndClearsTombstones() {
		var m = new SwissMap<Collide, Integer>(64);
		for (int i = 0; i < 200; i++) m.put(new Collide(i), i);

		// Create tombstones first (so we verify the method actually clears them).
		for (int i = 0; i < 50; i++) assertEquals(i, m.remove(new Collide(i)));
		assertTrue(tombstonesOf(m) > 0);

		var removed = m.removeWithoutTombstone(new Collide(123));
		assertEquals(123, removed);
		assertEquals(0, tombstonesOf(m), "removeWithoutTombstone should leave no tombstones (rehash-based)");

		for (int i = 0; i < 200; i++) {
			Integer v = m.get(new Collide(i));
			if (i < 50 || i == 123) assertNull(v);
			else assertEquals(i, v);
		}
	}

	@Test
	void swissSimdMap_removeWithoutTombstone_rehashesAndClearsTombstones() {
		var m = new SwissSimdMap<Collide, Integer>(64);
		for (int i = 0; i < 200; i++) m.put(new Collide(i), i);

		for (int i = 0; i < 50; i++) assertEquals(i, m.remove(new Collide(i)));
		assertTrue(tombstonesOf(m) > 0);

		var removed = m.removeWithoutTombstone(new Collide(123));
		assertEquals(123, removed);
		assertEquals(0, tombstonesOf(m), "removeWithoutTombstone should leave no tombstones (rehash-based)");

		for (int i = 0; i < 200; i++) {
			Integer v = m.get(new Collide(i));
			if (i < 50 || i == 123) assertNull(v);
			else assertEquals(i, v);
		}
	}
}


