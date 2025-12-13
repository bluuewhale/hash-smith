# HashSmith: Fast & memory efficient hash tables for Java

<p align="center">
  <img src="images/logo.png" alt="HashSmith logo" width="330">
</p>

<!-- TODO: Add badges (CI, License, Release) -->

> ⚠️ This project is experimental and not ready for production use.

## Overview
- HashSmith provides multiple high-performance hash table implementations optimized for speed and memory efficiency on modern JVMs.
- Focus areas: SIMD-assisted probing (SwissMap), predictable probe lengths (Robin Hood), and minimal per-entry overhead.
- Built for JDK 21+; SwissMap uses the incubating Vector API for SIMD acceleration.
- More memory-efficient than the built-in JDK `HashMap`; performance depends on workload.

## Implementations
- **SwissMap**: Google's SwissTable-inspired design with SIMD probing and tombstone reuse. See `docs/SwissMap.md` for details.
- **SwissSet**: SwissTable-style hash set with SIMD control-byte probing, tombstone reuse, and null-element support 
- **RobinHoodMap**: Robin Hood hashing with backward-shift deletion. See `docs/RobinHoodMap.md` for detailed behavior and notes.

## Blog / Write-up
- If you want a guided tour with design notes and benchmarks, see **[this write-up](https://bluuewhale.github.io/posts/building-a-fast-and-memory-efficient-hash-table-in-java-by-borrowing-the-best-ideas/)**.

## Quick Start
```java
import io.github.bluuewhale.hashsmith.SwissMap;
import io.github.bluuewhale.hashsmith.RobinHoodMap;
import io.github.bluuewhale.hashsmith.SwissSet;

public class Demo {
    public static void main(String[] args) {
        // SwissMap
        var swiss = new SwissMap<String, Integer>();
        swiss.put("a", 1);
        swiss.put("b", 2);
        System.out.println(swiss.get("a")); // 1

        // SwissSet
        var swissSet = new SwissSet<String>();
        swissSet.add("k");
        swissSet.add(null); // nulls allowed
        System.out.println(swissSet.contains("k")); // true
    }
}
```

## Install
- Gradle (Kotlin DSL):
```kotlin
dependencies {
    implementation("io.github.bluuewhale:hashsmith:0.1.5")
}
```
- Gradle (Groovy):
```groovy
dependencies {
    implementation 'io.github.bluuewhale:hashsmith:0.1.5'
}
```
- Maven:
```xml
<dependency>
  <groupId>io.github.bluuewhale</groupId>
  <artifactId>hashsmith</artifactId>
  <version>0.1.5</version>
</dependency>
```

## Requirements
- JDK 21+ (SwissMap needs `jdk.incubator.vector`)
- Gradle (wrapper provided)
- The JVM flag `--add-modules jdk.incubator.vector` is already configured for build, test, and JMH tasks.

## Build & Test
```bash
./gradlew build        # full build
./gradlew test         # JUnit 5 tests
```

## Memory Footprint 
- Compares retained heap for both maps (`HashMap` vs `SwissMap` vs fastutil `Object2ObjectOpenHashMap` vs Eclipse Collections `UnifiedMap`) and sets (`HashSet` vs `SwissSet` vs fastutil `ObjectOpenHashSet` vs Eclipse Collections `UnifiedSet`).
- Set benchmarks use UUID `String` keys (HashSet, SwissSet, ObjectOpenHashSet, UnifiedSet). Primitive-specialized collections (e.g., fastutil primitive sets) are excluded because their memory profile is driven by primitive storage, whereas these tests target general reference workloads.
### Results
- Maps: `SwissMap` uses open addressing to cut space; `SwissMap` keeps a 0.875 default load factor and shows up to 53.3% retained-heap reduction in payload-light cases vs `HashMap`.
- Sets: `SwissSet` (SwissHashSet) mirrors the SwissTable layout with SIMD control-byte probing and reuses tombstones to stay denser than `HashSet` across tested payloads, showing up to ~62% retained-heap reduction in lighter payload cases.
<table>
  <tr>
    <th>Map</th>
    <th>Set</th>
  </tr>
  <tr>
    <td><img src="images/map-memory-bool.png" alt="HashMap Memory Footprint" width="420"></td>
    <td><img src="images/set-memory-uuid.png" alt="HashSet Memory Footprint" width="420"></td>
  </tr>
</table>

## Benchmark (JMH, CPU ns/op)
- All benchmarks were run on Windows 11 (x64) with Eclipse Temurin JDK 21.0.9, on an AMD Ryzen 5 5600 (6C/12T).
- At high load factors SwissMap keeps competitive throughput against other open-addressing tables and stays close to JDK HashMap performance. 

| get hit | get miss |
| --- | --- |
| ![CPU: get hit](images/map-cpu-get-hit.png) | ![CPU: get miss](images/map-cpu-get-miss.png) |

| put hit                                     | put miss                                      |
|---------------------------------------------|-----------------------------------------------|
| ![CPU: put hit](images/map-cpu-put-hit.png) | ![CPU: put miss](images/map-cpu-put-miss.png) |


## Contributing
1) Open an issue for bugs/ideas  
2) Work on a feature branch and open a PR  
3) Keep tests/JMH green before submitting

## License
- This project is licensed under the MIT License. See [`LICENSE`](./LICENSE) for details.
