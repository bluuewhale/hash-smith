package io.github.bluuewhale.hashsmith;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.eclipse.collections.impl.set.mutable.UnifiedSet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openjdk.jol.info.GraphLayout;

public class SetFootprintTest {

	private static final int MAX_ENTRIES = 1_000_000;
	private static final int STEP = 50_000;

	private record SetSpec(String name, Supplier<Set<Integer>> supplier) {
		Set<Integer> newSet() {
			return supplier.get();
		}

		@Override
		public String toString() {
			return name;
		}
	}

	private enum Payload {
		UUID_STRING
	}

	private static final SetSpec HASH_SET = new SetSpec("HashSet", HashSet::new);
	private static final SetSpec SWISS_SET = new SetSpec("SwissSet", SwissSet::new);
	private static final SetSpec OBJECT_OPEN_HASH_SET = new SetSpec("ObjectOpenHashSet", ObjectOpenHashSet::new);
	private static final SetSpec UNIFIED_SET = new SetSpec("UnifiedSet", UnifiedSet::new);

	private static Stream<Arguments> payloadsAndSets() {
		return Stream.of(
            HASH_SET, SWISS_SET, OBJECT_OPEN_HASH_SET, UNIFIED_SET)
			.flatMap(spec -> Stream.of(Payload.values()).map(p -> Arguments.of(spec, p)));
	}

	private static void measure(Set<Integer> set, String setName, Payload payload) {
		Random rnd = new Random();
		for (int i = 0; i <= MAX_ENTRIES; i++) {
			set.add(payloadValue(payload, rnd));

			if (i % STEP == 0 && i > 0) {
				long size = GraphLayout.parseInstance(set).totalSize();
				System.out.printf("set=%-8s payload=%-12s n=%-7d size=%-,12dB%n",
					setName, payload, i, size);
			}
		}
	}

	private static Integer payloadValue(Payload payload, Random rnd) {
		return switch (payload) {
			case UUID_STRING -> nextUuidString(rnd).hashCode(); // store as int to keep Set<Integer>
		};
	}

	private static String nextUuidString(Random rnd) {
		return new java.util.UUID(rnd.nextLong(), rnd.nextLong()).toString();
	}

//	@ParameterizedTest(name = "{0} - {1} footprint growth")
//	@MethodSource("payloadsAndSets")
	void printFootprint(SetSpec setSpec, Payload payload) {
		measure(setSpec.newSet(), setSpec.name(), payload);
	}
}

