# SwissSimdMap (HashSmith)

> SwissTable-inspired hash map with SIMD acceleration via the JDK Vector API (incubator).

## Overview
- Open addressing with fixed control bytes (`EMPTY`, `DELETED`) and tombstone reuse.
- SIMD probing on control bytes to find candidate slots quickly; SIMD path is always used.
- Group-to-group movement uses triangular (quadratic) probing to reduce primary clustering.
- Load factor around 7/8 to balance speed and memory.
- Null keys are not supported (consistent with other HashSmith maps); null values are allowed.

## Requirements
- JDK 21+ (needs `jdk.incubator.vector`)
- Gradle (use the provided wrapper)
- JVM flag `--add-modules jdk.incubator.vector` (already configured in Gradle tasks)

## Quick Start
```java
import io.github.bluuewhale.hashsmith.SwissSimdMap;

public class Demo {
    public static void main(String[] args) {
        var map = new SwissSimdMap<String, Integer>();
        map.put("a", 1);
        map.put("b", 2);
        map.remove("a");
        map.put("a", 3);
        System.out.println(map.get("a")); // 3
    }
}
```

## Highlights
- **SIMD probing**: 128-bit vector comparisons on control bytes to locate candidate slots.
- **Load management**: Resizes near a 7/8 load factor.
- **Tombstone reuse**: Reclaims deleted slots to reduce fragmentation.
- **Complete views**: `keySet`, `values`, and `entrySet` support iterator remove/set semantics.

## Design Notes
- Control bytes: `EMPTY=0x80`, `DELETED=0xFE`; low 7 bits store the `h2` fingerprint.
- Group size: 16 slots (aligned to SIMD width). Load factor ~7/8 triggers resize.
- Rehash reinserts all entries into a fresh table to clear tombstones.
- Quadratic probing makes backward-shift deletion invalid; `removeWithoutTombstone` is implemented as same-capacity rehash.

## Notes
- SIMD path uses the JDK Vector API incubator module; ensure the JVM flag is present for any custom runs.
