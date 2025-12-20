package io.github.bluuewhale.hashsmith;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

class SwissMapRehashResizeTest {
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
		var m = new SwissMap<Integer, Integer>(64);
        int initialCap = m.capacity;

		for (int i = 0; i < 32; i++) assertNull(m.put(i, i));

        // Make tombstones dominate (tombstones > size/2) without ever exceeding maxLoad.
		for (int i = 0; i < 32; i++) assertEquals(i, m.remove(i));

        // After the triggering removal, the tombstone-cleanup rehash should have occurred:
        // tombstones reset to 0, but capacity must remain unchanged.
        assertEquals(0, getIntField(m, "tombstones"));
        assertEquals(initialCap, m.capacity);
        assertEquals(0, m.size());
	}

	@Test
	void overMaxLoadRehashDoesResize() {
		var m = new SwissMap<Integer, Integer>(16);
		int cap0 = m.capacity;
		int maxLoad0 = m.maxLoad;

		// Fill up to maxLoad; resize happens on the *next* put (maybeResize runs before insert).
		for (int i = 0; i < maxLoad0; i++) m.put(i, i);
		m.put(maxLoad0, maxLoad0);

		assertTrue(m.capacity >= cap0 * 2, "capacity should grow when exceeding maxLoad");
		assertEquals(maxLoad0 + 1, m.size());
		for (int i = 0; i <= maxLoad0; i++) assertEquals(i, m.get(i));
	}

	@Test
	void putAllReusesTombstonesSoNoResizeNeeded() {
		// capacity=32, loadFactor=0.875 => maxLoad=28
		var m = new SwissMap<Integer, Integer>(32);
		int cap0 = m.capacity;
		assertEquals(32, cap0);
		assertEquals(28, m.maxLoad);

		// Fill close to maxLoad without resizing.
		for (int i = 0; i < 27; i++) m.put(i, i);
		assertEquals(27, m.size());
		assertEquals(cap0, m.capacity);

		// Create 9 tombstones without triggering maybeRehash()
		for (int i = 0; i < 9; i++) assertNotNull(m.remove(i));
		assertEquals(18, m.size());
		assertEquals(9, getIntField(m, "tombstones"));
		assertEquals(cap0, m.capacity);

		// Batch re-insert 8 of the removed keys: this should mostly reuse tombstones.
		var batch = new HashMap<Integer, Integer>();
		for (int i = 0; i < 8; i++) batch.put(i, i * 2);

		// If we pessimistically assume no tombstone reuse, we'd exceed maxLoad and would resize.
		int tombstonesBefore = getIntField(m, "tombstones");
		int naiveProjected = m.size + tombstonesBefore + batch.size();
		assertTrue(naiveProjected >= m.maxLoad);

		// With tombstone reuse accounted for, the pre-check can stay under maxLoad and skip rehash/resize.
		int projectedSize = m.size + tombstonesBefore + Math.max(0, batch.size() - tombstonesBefore);
		assertTrue(projectedSize < m.maxLoad);

		m.putAll(batch);

		// The whole point: no resize/rehash should have happened.
		assertEquals(cap0, m.capacity);
		assertEquals(26, m.size());

		// Tombstones should have been reused (9 -> 1).
		assertEquals(1, getIntField(m, "tombstones"));

		// Values updated for reinserted keys; key 8 stays absent.
		for (int i = 0; i < 8; i++) assertEquals(i * 2, m.get(i));
		assertNull(m.get(8));
	}
}


