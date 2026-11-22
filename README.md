# mini-redis

[![CI](https://github.com/ogulcanogul1/mini-redis-java/actions/workflows/ci.yml/badge.svg)](https://github.com/ogulcanogul1/mini-redis-java/actions/workflows/ci.yml)

A Redis-compatible in-memory key-value store written from scratch in Java 21, as a learning project.

> **Status: work in progress.** The server does not exist yet. Right now the program only prints its version.

## Requirements

- JDK 21 (the build refuses any other major version)
- Maven 3.9 or newer

## Build

```bash
mvn -B verify
```

This compiles the code and runs the formatting check (Spotless), the tests and the enforcer rules.

## Run

```bash
mvn -B package
java -cp target/classes io.github.ogulcanogul1.miniredis.Main
```

Expected output: `mini-redis 0.1.0-SNAPSHOT`

The `java` command must be the same JDK 21 that Maven uses. Check with `java -version` before running; a different `java` on the `PATH` can run the class too, but it is not the supported setup.

## Test

```bash
mvn -B test
```

## Notes

Not affiliated with Redis Ltd. This is an educational project. "Redis" is a trademark of Redis Ltd.

## License

MIT. See [LICENSE](LICENSE).
