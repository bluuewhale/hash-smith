package io.github.bluuewhale.hashsmith;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

class SwissSetRehashResizeTest {

	private static int getIntField(Object target, String name) {
		try {
			Field f = target.getClass().getDeclaredField(name);
			f.setAccessible(true);
			return f.getInt(target);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Failed to read field: " + name, e);
		}
	}

	@Test
	void tombstoneRehashDoesNotResize() {
		var s = new SwissSet<Integer>(64);
		int initialCap = getIntField(s, "capacity");

		for (int i = 0; i < 32; i++) assertTrue(s.add(i));

		// Make tombstones dominate (tombstones > size/2) without ever exceeding maxLoad.
		for (int i = 0; i < 32; i++) assertTrue(s.remove(i));

		// After the triggering removal, the tombstone-cleanup rehash should have occurred:
		// tombstones reset to 0, but capacity must remain unchanged.
		assertEquals(0, getIntField(s, "tombstones"));
		assertEquals(initialCap, getIntField(s, "capacity"));
		assertEquals(0, s.size());
	}

	@Test
	void overMaxLoadRehashDoesResize() {
		var s = new SwissSet<Integer>(16);
		int cap0 = getIntField(s, "capacity");
		int maxLoad0 = getIntField(s, "maxLoad");

		for (int i = 0; i < maxLoad0; i++) assertTrue(s.add(i));
		assertTrue(s.add(maxLoad0)); // triggers rehash/grow before insert

		assertTrue(getIntField(s, "capacity") >= cap0 * 2, "capacity should grow when exceeding maxLoad");
		assertEquals(maxLoad0 + 1, s.size());
		for (int i = 0; i <= maxLoad0; i++) assertTrue(s.contains(i));
	}
}


