# CLAUDE.md — HashSmith

## 1. Project Overview

**HashSmith** is a high-performance Java library providing optimized hash table implementations (SwissMap, ConcurrentSwissMap, SwissSet, RobinHoodMap) for speed and memory efficiency over standard JDK `HashMap`.

- **Group**: `io.github.bluuewhale`
- **Artifact**: `hash-smith`
- **Java**: JDK 21+ (toolchain locked)
- **Build**: Gradle

---

## 2. Autonomous Agent Workflow

Claude operates as a **fully autonomous agent**. The superpowers skill system is the source of truth — if a skill exists for a situation, its instructions take precedence over this CLAUDE.md.

### Approximate flow

```
brainstorm → plan → execute → verify → review → cleanup
```

> This flow is illustrative. Always follow the relevant superpowers skill for the authoritative process at each stage (e.g., TDD is mandatory per `superpowers:test-driven-development` before writing implementation code).

---

## 3. Tech Stack

| Layer | Tool |
|---|---|
| Language | Java 21 |
| Build | Gradle (with wrapper) |
| Testing | JUnit Jupiter 5, Apache Commons Collections 4, Google Guava TestLib |
| Benchmarking | JMH |
| Performance analysis | Java Vector API (incubator), JOL, PrintAssembly |

---

## 4. Project Structure

```
src/
├── main/java/io/github/bluuewhale/hashsmith/   # Core implementations
├── test/java/                                   # Unit tests
├── apacheTest/java/                             # Apache Commons compatibility
├── googleTest/java/                             # Guava compatibility
└── jmh/java/                                    # JMH benchmarks
docs/                                            # Design docs (JIT-ASM.md, etc.)
```

---

## 5. Performance Philosophy

HashSmith is a **low-level data structure library**. Peak performance is the primary engineering goal — not convenience, not brevity, not conventional Java idioms.

### Core principle

> API compatibility is the hard constraint. Within that constraint, always pursue the maximum possible performance.

### What this means in practice

- **Optimize aggressively by default.** Do not wait to be asked. Every implementation decision should be evaluated for its performance impact.
- **Always consider low-level optimizations**: cache line alignment, branch prediction, memory layout, object overhead, JIT-friendliness, SWAR/SIMD techniques.
- **Micro-optimizations are appropriate here.** This is not application code. Bit manipulation, manual inlining, avoiding allocations, and other low-level tricks are expected and encouraged.
- **Benchmark before and after any significant change** using JMH (`./gradlew jmh`). Do not assume — measure.
- **Read JIT output** (`./gradlew jitAsm`) when optimizing hot paths. Verify that the compiler produces the expected assembly.
- **Avoid abstraction overhead** that degrades throughput — virtual dispatch, boxing, unnecessary object allocation are all suspect in hot paths.
- **Memory footprint matters** as much as throughput. Prefer compact in-memory representations.

### Optimization checklist (review for every hot-path change)

- [ ] Does this avoid unnecessary heap allocation?
- [ ] Is the memory layout cache-friendly?
- [ ] Can SWAR or Vector API be applied?
- [ ] Does the JIT produce clean assembly on this path?
- [ ] Have JMH numbers been compared before/after?

---

## 7. Coding Standards

- **Do not break API compatibility** — public classes implement standard Java `Map`/`Set` interfaces.
- Keep all implementations under `io.github.bluuewhale.hashsmith`.
- The Vector API (incubator) requires `--add-modules jdk.incubator.vector` JVM flag — always propagate this in Gradle JVM args when adding new tasks.
- Never remove or rename existing public methods without a deprecation cycle.

---

## 8. Testing Rules

- All new code **must** have corresponding tests in the appropriate test source set.
- Compatibility tests (Apache, Google) must remain green — these validate conformance to established contracts.
- Run the full test suite before marking any task complete:
  ```bash
  ./gradlew test apacheTest googleTest
  ```

---

## 9. Build Commands

```bash
./gradlew build              # Full build
./gradlew test               # Unit tests
./gradlew apacheTest         # Apache compatibility tests
./gradlew googleTest         # Google compatibility tests
./gradlew jmh                # Run JMH benchmarks
./gradlew jitAsm             # JIT assembly output (requires hsdis)
```

---

## 10. Git & Commit Rules

- Commit messages must be concise and written in imperative mood (`Add`, `Fix`, `Refactor`).
- Do not include tooling or AI-assistant references in commit messages or PR descriptions.
- Prefer small, focused commits scoped to a single logical change.
