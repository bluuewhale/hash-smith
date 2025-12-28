package io.github.bluuewhale.hashsmith;

import com.google.common.collect.testing.ConcurrentMapTestSuiteBuilder;
import com.google.common.collect.testing.MapTestSuiteBuilder;
import com.google.common.collect.testing.TestMapGenerator;
import com.google.common.collect.testing.TestStringMapGenerator;
import com.google.common.collect.testing.features.CollectionFeature;
import com.google.common.collect.testing.features.CollectionSize;
import com.google.common.collect.testing.features.MapFeature;
import com.google.common.collect.testing.testers.*;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.jspecify.annotations.NullMarked;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Supplier;

@NullMarked
public final class GuavaMapTest extends TestCase {

    public static Test suite() {
        var suite = new TestSuite();
        suite.addTest(mapTest("SwissMap", generator(SwissMap::new)));
        suite.addTest(mapTest("SwissSimdMap", generator(SwissSimdMap::new)));
        suite.addTest(mapTest("RobinHoodMap", generator(RobinHoodMap::new)));
        suite.addTest(concurrentMapTest(
            "LockFreeSwissMap",
            // Fixed-size table (no resize yet): allocate generously for testlib.
            generator(() -> new LockFreeSwissMap<>(1 << 16))
        ));
        suite.addTest(concurrentMapTest(
            "ConcurrentSwissMap",
            generator(ConcurrentSwissMap::new),
            // FIXME: Iterator.next() must throw NoSuchElementException (not IndexOutOfBoundsException).
            suppress(CollectionIteratorTester.class, "testIteratorNoSuchElementException"),
            suppress(CollectionIteratorTester.class, "testIterator_unknownOrderRemoveSupported"),
            // FIXME: Decide/align null-value policy with Map features (Guava testlib expects NPE when null values are unsupported).
            suppress(MapCreationTester.class, "testCreateWithNullValueUnsupported"),
            suppress(MapEntrySetTester.class, "testSetValueWithNullValuesAbsent"),
            suppress(MapPutTester.class, "testPut_nullValueUnsupported"),
            suppress(MapPutTester.class, "testPut_replaceWithNullValueUnsupported"),
            suppress(MapPutAllTester.class, "testPutAll_nullValueUnsupported"),
            suppress(MapPutIfAbsentTester.class, "testPutIfAbsent_nullValueUnsupportedAndKeyAbsent"),
            suppress(MapReplaceTester.class, "testReplace_presentNullValueUnsupported"),
            suppress(MapReplaceEntryTester.class, "testReplaceEntry_presentNullValueUnsupported"),
            suppress(ConcurrentMapPutIfAbsentTester.class, "testPutIfAbsent_nullValueUnsupported"),
            suppress(ConcurrentMapReplaceTester.class, "testReplace_presentNullValueUnsupported"),
            suppress(ConcurrentMapReplaceEntryTester.class, "testReplaceEntry_presentNullValueUnsupported"),
            suppress(CollectionCreationTester.class, "testCreateWithNull_unsupported")
        ));
        return suite;
    }

    private static Test mapTest(String name, TestMapGenerator<?, ?> generator,
                                Method... suppressed) {
        // FIXME: Enable CollectionFeature.FAILS_FAST_ON_CONCURRENT_MODIFICATION?
        return MapTestSuiteBuilder
            .using(generator)
            .named(name)
            .withFeatures(
                CollectionSize.ANY,
                MapFeature.GENERAL_PURPOSE,
                MapFeature.ALLOWS_NULL_VALUES,
                MapFeature.ALLOWS_NULL_ENTRY_QUERIES,
                CollectionFeature.SUPPORTS_ITERATOR_REMOVE)
            .suppressing(suppressed)
            .createTestSuite();
    }

    private static Test concurrentMapTest(String name, TestMapGenerator<?, ?> generator,
                                          Method... suppressed) {
        return ConcurrentMapTestSuiteBuilder
            .using(generator)
            .named(name)
            .withFeatures(
                CollectionSize.ANY,
                MapFeature.GENERAL_PURPOSE,
                // ConcurrentHashMap view iterators support Iterator.remove().
                CollectionFeature.SUPPORTS_ITERATOR_REMOVE)
            .suppressing(suppressed)
            .createTestSuite();
    }

    private static TestStringMapGenerator generator(Supplier<Map<String, String>> supplier) {
        return new TestStringMapGenerator() {
            @Override protected Map<String, String> create(Map.Entry<String, String>[] entries) {
                Map<String, String> map = supplier.get();
                for (Map.Entry<String, String> entry : entries) {
                    map.put(entry.getKey(), entry.getValue());
                }
                return map;
            }
        };
    }

    private static Method suppress(Class<?> clazz, String method) {
        try {
            return clazz.getMethod(method);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
