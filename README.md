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
- Defaults: warmup 1, iterations 2, fork 1, mode `thrpt`, time unit `ms`.

## Design Notes
- Control bytes: `EMPTY=0x80`, `DELETED=0xFE`, low 7 bits store `h2` fingerprint.
- Group size: 16 slots (aligned to 128-bit SIMD). Load factor ~7/8 triggers resize.
- Rehash reinserts all entries into a fresh table to clear tombstones.

## Contributing
1) Open an issue for bugs/ideas  
2) Work on a feature branch and open a PR  
3) Keep tests/JMH green before submitting

## License
- This project is licensed under the MIT License. See [`LICENSE`](./LICENSE) for details.