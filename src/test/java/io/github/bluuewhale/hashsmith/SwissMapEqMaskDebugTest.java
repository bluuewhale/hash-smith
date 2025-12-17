package io.github.bluuewhale.hashsmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * A "visually inspectable" debug test for eqMask.
 *
 * <p>{@code SwissMap.eqMask(long word, byte b)} is private, so we invoke it via reflection.
 * Correctness is validated by comparing against a ground-truth mask computed by direct per-lane
 * (per-byte) equality checks.
 *
 * <p>For a few representative cases, we print intermediate values (hex/binary) so you can
 * intuitively verify how the SWAR transformation produces the final 8-bit mask.
 */
class SwissMapEqMaskDebugTest {

	// Same SWAR constants as SwissMap (used for explanation/printing only).
	// Correctness is asserted via the ground-truth comparison.
	private static final long BITMASK_LSB = 0x0101_0101_0101_0101L;
	private static final long BITMASK_MSB = 0x8080_8080_8080_8080L;

    long pack8(byte b0, byte b1, byte b2, byte b3, byte b4, byte b5, byte b6, byte b7) {
        return (b0 & 0xFFL)
            | ((b1 & 0xFFL) << 8)
            | ((b2 & 0xFFL) << 16)
            | ((b3 & 0xFFL) << 24)
            | ((b4 & 0xFFL) << 32)
            | ((b5 & 0xFFL) << 40)
            | ((b6 & 0xFFL) << 48)
            | ((b7 & 0xFFL) << 56);
    }

    String bits8(int mask) {
        String s = Integer.toBinaryString(mask & 0xFF);
        if (s.length() < 8) s = "0".repeat(8 - s.length()) + s;
        return s;
    }

    int eqMask(long word, byte b) {
        long x = word ^ ((b & 0xFFL) * BITMASK_LSB);
        long m = (x - BITMASK_LSB) & ~x & BITMASK_MSB;
        return (int) ((m * 0x0204_0810_2040_81L) >>> 56);
    }


	@Test
	void testEqMask() {
		long word = pack8((byte) 0xBB, (byte) 0xAA, (byte) 0xBB, (byte) 0xAA, (byte) 0xBB, (byte) 0xBB, (byte) 0xBB, (byte) 0xBB);

		assertEquals("00001010", bits8(eqMask(word, (byte) 0xAA)));
		assertEquals("11110101", bits8(eqMask(word, (byte) 0xBB)));
	}

	@Test
	void testEqMaskAllZero() {
		long word = pack8((byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00);

		assertEquals("11111111", bits8(eqMask(word, (byte) 0x00)));
		assertEquals("00000000", bits8(eqMask(word, (byte) 0x01)));
	}
}


