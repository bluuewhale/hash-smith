# HashSmith: High-performance hash tables for Java

<p align="center">
  <img src="images/logo.png" alt="HashSmith logo" width="330">
</p>

> Fast, memory-efficient open-addressing hash tables for the JVM (SwissMap with SIMD + RobinHoodMap with Robin Hood probing).

<!-- TODO: Add badges (CI, License, Release) -->

## Overview
- HashSmith provides multiple high-performance hash table implementations optimized for speed and memory efficiency on modern JVMs.
- Focus areas: SIMD-assisted probing (SwissMap), predictable probe lengths (Robin Hood), and minimal per-entry overhead.
- Built for JDK 21+; SwissMap uses the incubating Vector API for SIMD acceleration.

## Implementations
- **SwissMap**: Google's SwissTable-inspired design with SIMD probing, tombstone reuse, and optional scalar fallback. See `docs/SwissMap.md` for details.
- **RobinHoodMap**: Robin Hood hashing with backward-shift deletion. See `docs/RobinHoodMap.md` for detailed behavior and notes.

## Quick Start
```java
import com.donghyungko.hashsmith.SwissMap;
import com.donghyungko.hashsmith.RobinHoodMap;

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

## Requirements
- JDK 21+ (SwissMap needs `jdk.incubator.vector`)
- Gradle (wrapper provided)
- The JVM flag `--add-modules jdk.incubator.vector` is already configured for build, test, and JMH tasks.

## Build & Test
```bash
./gradlew build        # full build
./gradlew test         # JUnit 5 tests
```

## Benchmark (JMH, CPU ns/op)
```bash
./gradlew jmh
```
- Targets: `HashMap`, `SwissMap`, `RobinHoodMap` with `int -> int` random keys.
- Data sizes: pre-fill 100 / 1,000 / 10,000 entries before measuring.
- Workloads: get hit/miss (present/absent keys), put hit (overwrite), put miss (new insert), iterate (entrySet sum).
- Settings: JMH `@Warmup(3)` / `@Measurement(5)`, reporting average time (ns/op).

### Results
| get hit | get miss |
| --- | --- |
| ![CPU: get hit](images/cpu-get-hit.png) | ![CPU: get miss](images/cpu-get-miss.png) |

| put hit | put miss |
| --- | --- |
| ![CPU: put hit](images/cpu-put-hit.png) | ![CPU: put miss](images/cpu-put-miss.png) |

| iterate |  |
| --- | --- |
| ![CPU: iterate](images/cpu-iterate.png) |  |

## Memory Footprint (JOL)
- Compares retained heap of `HashMap` vs `SwissMap` vs `RobinHoodMap` for multiple payload sizes.
- Run:
```bash
./gradlew test --tests com.donghyungko.hashsmith.MapFootprintTest
```
### Results
- `SwissMap` and `RobinHoodMap` both use open addressing, reducing space overhead versus `HashMap`.
- `SwissMap` uses SIMD-assisted probing and keeps a relatively high default load factor (0.875), fitting more entries per capacity for better memory efficiency.
- Smaller value sizes amplify the memory efficiency gap; in payload-light cases, `SwissMap` cuts retained heap by up to 43.7% versus `HashMap`.
![Memory Foorprint](images/memory-footprint.png)

## Documentation
- SwissMap: `docs/SwissMap.md`
- RobinHoodMap: `docs/RobinHoodMap.md`

## Contributing
1) Open an issue for bugs/ideas  
2) Work on a feature branch and open a PR  
3) Keep tests/JMH green before submitting

## License
- This project is licensed under the MIT License. See [`LICENSE`](./LICENSE) for details.
