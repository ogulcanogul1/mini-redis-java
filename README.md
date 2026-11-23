# mini-redis

[![CI](https://github.com/ogulcanogul1/mini-redis-java/actions/workflows/ci.yml/badge.svg)](https://github.com/ogulcanogul1/mini-redis-java/actions/workflows/ci.yml)

A Redis-compatible in-memory key-value store written from scratch in Java 21, as a learning project.

> **Status:** Phase 3 complete (single-client blocking TCP server with RESP2, CLI/config parser, and Redis 7.0.15 differential tests). Multithreading, thread pool, and sharded locking arrive in Phase 4.

## Requirements

- JDK 21 (the build refuses any other major version)
- Maven 3.9 or newer
- Docker (optional, for the differential compatibility suite `-Pcompat` and running via container)

## Supported Commands

- Connection: `PING [message]`, `ECHO message`, `QUIT`
- Strings & Keys: `SET key value`, `GET key`, `DEL key [key ...]`, `EXISTS key [key ...]`, `KEYS pattern`, `DBSIZE`, `FLUSHDB`

## Build & Test

```bash
mvn -B verify
```

This compiles the code, checks formatting (Spotless), runs unit tests (Surefire), ArchUnit architecture rules, JaCoCo coverage report, and TCP integration tests (`*IT`, Failsafe).

To run only unit tests:

```bash
mvn -B test
```

To run the differential compatibility suite (`RedisCompatIT`) against real Redis `7.0.15`:

```bash
docker run -d --rm --name redis-ref -p 6390:6379 redis:7.0.15 redis-server --save '' --appendonly no
mvn -B verify -Pcompat
docker stop redis-ref
```

## Run

Build the JAR and copy runtime dependencies (`slf4j-api`, `logback-classic`, etc.):

```bash
mvn -B -DskipTests package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
java -cp "target/classes:target/lib/*" io.github.ogulcanogul1.miniredis.Main --port 6380
```

On Windows PowerShell/CMD use `;` instead of `:` in `-cp`: `"target/classes;target/lib/*"`.

Check version:

```bash
java -cp "target/classes:target/lib/*" io.github.ogulcanogul1.miniredis.Main --version
```

Expected output: `mini-redis 0.1.0-SNAPSHOT`

The `java` command must be the same JDK 21 that Maven uses. Check with `java -version` before running.

Or build and run with Docker:

```bash
docker build -t mini-redis .
docker run --rm -p 6380:6380 mini-redis --bind 0.0.0.0 --port 6380
```

Test with `redis-cli`:

```bash
docker run --rm redis:7.0.15 redis-cli -h host.docker.internal -p 6380 PING
```

## Compatibility (Redis 7.0.15)

`RedisCompatIT` (`mvn -B verify -Pcompat`, run in CI against the `redis:7.0.15` service container) sends identical raw RESP2 byte streams to both Redis `7.0.15` and `mini-redis` and asserts byte-for-byte identical wire replies and connection-close behavior across **11 differential scenarios** in [faz-03.txt](src/test/resources/compat/faz-03.txt):

- Multibulk and inline `PING`, `ECHO`, `SET`, `GET`, `DBSIZE`, `FLUSHDB`
- Binary-safe keys and values, `DEL`, `EXISTS`, `KEYS` (unordered comparison for `KEYS *`), and pipelined commands
- Command errors (`arity`, case-insensitive command names, `FLUSHDB extra`, empty bulk command name) and Redis-matching `128`-byte error argument truncation and `\r`/`\n` sanitization
- Inline command whitespace (`\r`, `\n`, `\t`, `\v`), single/double/hex escapes (`\xHH`), ignored non-positive multibulk counts (`*0`, `*-1`, `*-5`), and unchecked trailing bytes after bulk payload
- `QUIT` (with/without extra arguments) closing the connection without running subsequent pipelined commands, plus protocol error handling and immediate disconnect

### Intentional Differences

1. **Requests containing NUL (`\0`) bytes:** Redis uses C strings on inline/error paths and truncates or stalls on `\0`. `mini-redis` stays strictly binary-safe end-to-end.
2. **`KEYS` character ranges crossing `0x7F`/`0x80` (e.g. `[\x01-\x80]`):** Redis compares range bounds as signed `char` on x86 (platform-dependent; on x86 `0x80` becomes `-128`). `mini-redis` compares byte values unsigned across all platforms, consistent with `memcmp` byte order.

## Notes

Not affiliated with Redis Ltd. This is an educational project. "Redis" is a trademark of Redis Ltd.

## License

MIT. See [LICENSE](LICENSE).
