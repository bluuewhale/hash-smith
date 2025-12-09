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
- **SwissMap**: Google's SwissTable-inspired design with SIMD probing, tombstone reuse, and optional scalar fallback. See `docs/SwissMap.md` for details.
- **RobinHoodMap**: Robin Hood hashing with backward-shift deletion. See `docs/RobinHoodMap.md` for detailed behavior and notes.

## Quick Start
```java
import io.github.bluuewhale.hashsmith.SwissMap;
import io.github.bluuewhale.hashsmith.RobinHoodMap;

public class Demo {
    public static void main(String[] args) {
        // SwissMap
        var swiss = new SwissMap<String, Integer>();
        swiss.put("a", 1);
        swiss.put("b", 2);
        System.out.println(swiss.get("a")); // 1

        // RobinHoodMap
        var robin = new RobinHoodMap<String, Integer>();
        robin.put("x", 42);
        robin.put("y", 99);
        robin.remove("x");
        System.out.println(robin.get("y")); // 99
    }
}
```

## Install
- Gradle (Kotlin DSL):
```kotlin
dependencies {
    implementation("io.github.bluuewhale:hashsmith:0.1.2")
}
```
- Gradle (Groovy):
```groovy
dependencies {
    implementation 'io.github.bluuewhale:hashsmith:0.1.2'
}
```
- Maven:
```xml
<dependency>
  <groupId>io.github.bluuewhale</groupId>
  <artifactId>hashsmith</artifactId>
  <version>0.1.2</version>
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

## Memory Footprint (JOL)
- Compares retained heap of `HashMap` vs `SwissMap` vs `RobinHoodMap` for multiple payload sizes.
- Run:
```bash
./gradlew test --tests io.github.bluuewhale.hashsmith.MapFootprintTest
```
### Results
- `SwissMap` and `RobinHoodMap` both use open addressing, reducing space overhead versus `HashMap`.
- `SwissMap` uses SIMD-assisted probing and keeps a relatively high default load factor (0.875), fitting more entries per capacity for better memory efficiency.
- Smaller value sizes amplify the memory efficiency gap; in payload-light cases, `SwissMap` cuts retained heap by up to 53.3% versus `HashMap`.
![Memory Foorprint](images/memory-boolean.png)

## Benchmark (JMH, CPU ns/op)
```bash
./gradlew jmh
```

### Results
| get hit | get miss |
| --- | --- |
| ![CPU: get hit](images/cpu-get-hit.png) | ![CPU: get miss](images/cpu-get-miss.png) |

| put hit | put miss |
| --- | --- |
| ![CPU: put hit](images/cpu-put-hit.png) | ![CPU: put miss](images/cpu-put-miss.png) |


## Documentation
- SwissMap: `docs/SwissMap.md`
- RobinHoodMap: `docs/RobinHoodMap.md`

## Contributing
1) Open an issue for bugs/ideas  
2) Work on a feature branch and open a PR  
3) Keep tests/JMH green before submitting

## License
- This project is licensed under the MIT License. See [`LICENSE`](./LICENSE) for details.
