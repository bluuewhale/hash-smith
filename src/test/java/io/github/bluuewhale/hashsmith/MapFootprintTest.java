package io.github.bluuewhale.hashsmith;

import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.Map;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.openjdk.jol.info.GraphLayout;

/**
 * JUnit helper to print retained heap size for HashMap vs SwissSimdMap vs SwissMap vs RobinHoodMap.
 * Run with `./gradlew test --tests com.donghyungko.hashsmith.MapFootprintTest`.
 */
public class MapFootprintTest {

	private static final int MAX_ENTRIES = 1_000_000;
	private static final int STEP = 50_000;
	private static final int SHORT_STR_LEN = 8;
	private static final int LONG_STR_LEN = 100;

	private record MapSpec(String name, Supplier<Map<Integer, Object>> supplier) {
		Map<Integer, Object> newMap() {
			return supplier.get();
		}

		@Override
		public String toString() {
			return name;
		}
	}

	private static final List<MapSpec> MAP_SPECS = List.of(
//			new MapSpec("HashMap", HashMap::new)
			new MapSpec("SwissMap", SwissMap::new),
			new MapSpec("SwissSimdMap", SwissSimdMap::new),
			new MapSpec("UnifiedMap", UnifiedMap::new),
			new MapSpec("Object2ObjectOpenHashMap", Object2ObjectOpenHashMap::new)
	);

	private enum Payload {
		BOOLEAN,
//        INT, SHORT_STR, LONG_STR
	}

    private static Stream<Arguments> payloadsAndMaps() {
        return MAP_SPECS.stream()
                .flatMap(spec -> Stream.of(Payload.values()).map(p -> Arguments.of(spec, p)));
    }

    private static void measure(Map<Integer, Object> map, String mapName, Payload payload) {
        Random rnd = new Random();

        for (int i = 0; i <= MAX_ENTRIES; i++) {
            map.put(rnd.nextInt(), payloadValue(payload, rnd));

            if (i % STEP == 0 && i > 0) {
                long size = GraphLayout.parseInstance(map).totalSize();
                System.out.printf("map=%-10s payload=%-8s n=%-7d size=%-,12dB%n",
                        mapName, payload, i, size);
            }
        }
    }

    private static Object payloadValue(Payload payload, Random rnd) {
		return switch (payload) {
//			case INT -> rnd.nextInt();
			case BOOLEAN -> rnd.nextBoolean();
//			case SHORT_STR -> randomUtf8(rnd, SHORT_STR_LEN);
//			case LONG_STR -> randomUtf8(rnd, LONG_STR_LEN);
		};
    }

	private static String randomUtf8(Random rnd, int len) {
		byte[] buf = new byte[len];
		rnd.nextBytes(buf);
		return new String(buf, StandardCharsets.UTF_8);
	}


//	@ParameterizedTest(name = "{0} - {1} footprint growth")
//	@MethodSource("payloadsAndMaps")
	void printFootprint(MapSpec mapSpec, Payload payload) {
		measure(mapSpec.newMap(), mapSpec.name(), payload);
	}
}
