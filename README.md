# fantastic-couscous

A small Kotlin project with two exercises:

- **`SimpleCache`** — a TTL cache backed by `ConcurrentHashMap`.
- **`Hierarchy`** — a forest stored as parallel arrays of node IDs and depths, with a `filter` function.

## Requirements

- JDK 21
- Gradle wrapper (already in the repo, no local install needed)
- Kotlin 2.3.20 (pulled by Gradle)

## Project structure

```
.
├── build.gradle.kts
├── settings.gradle.kts
├── docs/
│   └── caching.md              # Code review and notes for SimpleCache
├── src/
│   ├── main/kotlin/com/camilo/
│   │   ├── cache/SimpleCache.kt
│   │   └── hierarchy/Hierarchy.kt
│   └── test/kotlin/com/camilo/
│       ├── cache/SimpleCacheTest.kt
│       └── hierarchy/FilterTest.kt
└── README.md
```

## Run the tests

Run all tests:

```bash
./gradlew test
```

Run only the cache tests:

```bash
./gradlew test --tests "com.camilo.cache.SimpleCacheTest"
```

Run only the hierarchy tests:

```bash
./gradlew test --tests "com.camilo.hierarchy.FilterTest"
```

Test reports are written to `build/reports/tests/test/index.html`.

## Notes on caching

All notes, problems, and fixes for `SimpleCache` are in [`docs/caching.md`](docs/caching.md).
