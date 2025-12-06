# SwissTable for Java (SwissMap)

> SwissTable-inspired hash map with SIMD acceleration via the JDK Vector API (incubator).

<!-- TODO: Add badges (CI, License, Release) -->

## Overview
- Experimental map implementation inspired by Google Abseil’s SwissTable design, ported to Java.
- Supports both SIMD and scalar paths.
- Open addressing with fixed control bytes (`EMPTY`, `DELETED`) and tombstone reuse.
- Null keys and null values are allowed.

## Highlights
- **SIMD probing**: 128-bit vector comparisons on control bytes to find candidate slots quickly.
- **Load management**: Resizes around a 7/8 load factor to balance speed and memory.
- **Tombstone reuse**: Reclaims deleted slots to reduce fragmentation.
- **Complete views**: `keySet`, `values`, and `entrySet` support iterator remove/set semantics.

## Requirements
- JDK 21+ (needs `jdk.incubator.vector`)
- Gradle (use the provided wrapper)
- JVM flag `--add-modules jdk.incubator.vector` (already configured in Gradle).

## Design Notes
- Control bytes: `EMPTY=0x80`, `DELETED=0xFE`, low 7 bits store `h2` fingerprint.
- Group size: 16 slots (aligned to 128-bit SIMD). Load factor ~7/8 triggers resize.
- Rehash reinserts all entries into a fresh table to clear tombstones.

## Quick Start
```java
import com.donghyungko.swisstable.SwissMap;

public class Demo {
    public static void main(String[] args) {
        var map = new SwissMap<String, Integer>(SwissMap.Path.SIMD);
        map.put("a", 1);
        map.put("b", 2);
        map.remove("a");
        map.put("a", 3);   // tombstone reuse
        System.out.println(map.get("a")); // 3

        var scalar = new SwissMap<String, Integer>(SwissMap.Path.SCALAR);
        scalar.put("x", 42);
    }
}
```

## Build & Test
```bash
./gradlew build        # full build
./gradlew test         # JUnit 5 tests
```

## Benchmark (JMH)
```bash
./gradlew jmh
```

### Current Results (ns/op, size = 10,000)
| Benchmark | ns/op (≈) |
| --- | --- |
| jdkGetHit | 16.93 |
| swissGetHit | 24.93 |
| jdkGetMiss | 14.80 |
| swissGetMiss | 12.40 |
| jdkIterate | 47,130 | <!-- ≈47.13 µs -->
| swissIterate | 21,360 | <!-- ≈21.36 µs -->
| jdkPutHit | 7.72 |
| swissPutHit | 12.78 |
| jdkPutMiss | 31.65 |
| swissPutMiss | 26.09 |

## Memory Footprint (JOL)
- JUnit helper at `src/test/java/com/donghyungko/swisstable/MapFootprint.java`
- Compares retained heap of HashMap vs SwissMap for multiple sizes and payloads:
  - `INT`, `SHORT_STR` (8 chars), `LONG_STR` (200 chars)
- Run:
```bash
./gradlew test --tests com.donghyungko.swisstable.MapFootprint
```
- SwissMap uses open addressing (no per-entry node objects), so space overhead per entry is lower.
- Gap vs HashMap is more pronounced for smaller payloads (INT, short strings) because node/boxing overhead dominates; as payload grows, the value size masks the overhead.

![Per-entry memory footprint: INT](images/memory-footprint-int.png)
![Per-entry memory footprint: SHORT_STR](images/memory-footprint-short-string.png)
![Per-entry memory footprint: LONG_STR](images/memory-footprint-long-string.png)

## Contributing
1) Open an issue for bugs/ideas  
2) Work on a feature branch and open a PR  
3) Keep tests/JMH green before submitting

## License
- This project is licensed under the MIT License. See [`LICENSE`](./LICENSE) for details.
