# mini-redis

[![CI](https://github.com/ogulcanogul1/mini-redis-java/actions/workflows/ci.yml/badge.svg)](https://github.com/ogulcanogul1/mini-redis-java/actions/workflows/ci.yml)

A Redis-compatible, multithreaded, persistent in-memory key-value store written from scratch in Java 21 (Maven, zero frameworks), as a systems-engineering learning project.

> **Status:** Complete through **Phase 10.4** — custom `Dict` with SipHash-2-4, RESP2 parser/writer, 5 selectable I/O models (`multi-reactor` default), sharded lock-ordered storage, TTL + approximate LRU eviction (`--maxmemory`), Lists/Hashes/Sets/Sorted Sets (`SkipList`), AOF + RDB snapshot persistence (`SAVE`/`BGSAVE`), `INFO`/`AUTH`/`protected-mode`, Transactions (`MULTI`/`EXEC`/`WATCH`), Pub/Sub, Cluster hash slots, and Master-Replica replication (`--replicaof`).

## Requirements

- JDK 21 (Maven Enforcer refuses any other major version)
- Maven 3.9 or newer
- Docker (optional, for the differential compatibility suite `-Pcompat` and container runs)

## Supported Commands

- **Connection & Security:** `PING [message]`, `ECHO message`, `AUTH password`, `INFO [section]`, `QUIT`
- **Strings & Atomic Counters:** `SET key value [EX sec | PX ms | EXAT ts | PXAT ts] [NX | XX]`, `GET key`, `INCR key`, `DECR key`, `INCRBY key increment`, `DECRBY key decrement`, `MGET key [key ...]`, `MSET key value [key value ...]`
- **Keys & TTL:** `DEL key [key ...]`, `UNLINK key [key ...]`, `EXISTS key [key ...]`, `RENAME source target`, `KEYS pattern`, `DBSIZE`, `FLUSHDB`, `EXPIRE key sec`, `PEXPIRE key ms`, `PEXPIREAT key ts`, `TTL key`, `PTTL key`, `PERSIST key`
- **Lists:** `LPUSH`, `RPUSH`, `LPOP`, `RPOP`, `LRANGE`, `LLEN`
- **Hashes:** `HSET`, `HGET`, `HDEL`, `HGETALL`
- **Sets:** `SADD`, `SREM`, `SISMEMBER`, `SMEMBERS`
- **Sorted Sets (`SkipList` + `Dict`):** `ZADD`, `ZSCORE`, `ZRANGE`, `ZRANGEBYSCORE`, `ZRANK`
- **Persistence:** `SAVE`, `BGSAVE`
- **Transactions:** `MULTI`, `EXEC`, `DISCARD`, `WATCH`, `UNWATCH`
- **Pub/Sub:** `SUBSCRIBE`, `UNSUBSCRIBE`, `PUBLISH`
- **Cluster & Replication:** `CLUSTER KEYSLOT`, `CLUSTER INFO`, `REPLCONF`, `PSYNC`, `ROLE`

## Build & Test

```bash
mvn -B verify
```

This compiles the code, checks formatting (Spotless), runs unit tests (Surefire), ArchUnit architecture rules, JaCoCo coverage report, and TCP integration tests (`*IT`, Failsafe).

To run concurrency and connection stress tests (`ConcurrencyStressTest`, `C10kStressTest`):

```bash
mvn -B verify -Pstress
```

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

Configuration can be supplied via a Redis-style config file (`key value` lines) as the first argument and/or CLI overrides:
- `--bind <addr>` (default `127.0.0.1`), `--port <port>` (default `6380`)
- `--io-model <multi-reactor|virtual|thread-pool|thread-per-conn|single>` (default `multi-reactor`)
- `--io-threads <n>` (default `4`; `1` = single-threaded Redis-style event loop), `--maxclients <n>` (default `10000`)
- `--client-output-buffer-limit <bytes>` (default `16777216`), `--idle-timeout-seconds <sec>` (default `0`, disabled)
- `--worker-threads <n>` (default `8`), `--queue-capacity <n>` (default `256`)
- `--shards <power-of-two>` (default `16`), `--lock-type <rwlock|mutex>` (default `rwlock`)
- `--maxmemory <bytes>` (default `0`, unlimited), `--maxmemory-policy <noeviction|allkeys-lru|volatile-lru|allkeys-random|volatile-random>` (default `noeviction`)
- `--appendonly <yes|no>` (default `no`), `--appendfilename <path>` (default `appendonly.aof`), `--appendfsync <always|everysec|no>` (default `everysec`)
- `--dbfilename <path>` (default `dump.rdb`)
- `--requirepass <password>` (default unset), `--protected-mode <yes|no>` (default `yes`)
- `--replicaof <host> <port>` (default unset; starts server as a read-only replica)
- `--loglevel <debug|verbose|notice|warning>` (default `notice`), `--proto-max-bulk-len <bytes>` (default `536870912`)

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
docker run --rm -p 6380:6380 mini-redis --bind 0.0.0.0 --port 6380 --protected-mode no
```

Test with `redis-cli`:

```bash
docker run --rm redis:7.0.15 redis-cli -h host.docker.internal -p 6380 PING
```

## Concurrency Correctness

Sharded storage uses ascending shard-index lock ordering (`ShardedKeyspace`, K3) so single-key read-modify-write operations and cross-shard multi-key operations remain atomic without deadlocks:

- **`ShardedKeyspaceTest`:** `8` threads × `2,000` concurrent `INCR` operations (`16,000` exact) alongside `2` threads running `500` cross-shard `MSET`, `MGET`, and `RENAME` loops in opposite key orders — confirms ascending shard locking prevents deadlocks and multi-key reads/writes never observe partial updates.
- **`MultiClientServerIT`:** `16` concurrent TCP clients × `100` `INCR` operations (`1,600` exact) over real sockets in both `thread-per-conn` and `thread-pool` models, plus verification that an idle client does not block other clients.
- **`MultiReactorServerIT`:** `16` concurrent TCP clients × `100` `INCR` operations (`1,600` exact) plus split-write, idle-client, and `QUIT` verification across `virtual`, `multi-reactor` (`io-threads=4`), and single-reactor (`io-threads=1`) configurations.
- **`ConcurrencyStressTest` (`mvn -B verify -Pstress`):** `24` concurrent TCP clients × `500` `INCR` operations (`12,000` exact) interleaved with cross-shard `MSET`/`MGET` pairs, finishing with `0` lost updates and `0` deadlocks.
- **`C10kStressTest` (`mvn -B verify -Pstress`):** `1,000` simultaneous open TCP connections maintained across `4` NIO `ReactorEventLoop` threads (`io-model=multi-reactor`), executing `PING` and `INCR` on every connection with `0` leaked clients after clean shutdown.

## Compatibility (Redis 7.0.15)

`RedisCompatIT` (`mvn -B verify -Pcompat`, run in CI against the `redis:7.0.15` service container) sends identical raw RESP2 byte streams to both Redis `7.0.15` and `mini-redis` and asserts byte-for-byte identical wire replies and connection-close behavior across **12 differential scenarios** in [faz-03.txt](src/test/resources/compat/faz-03.txt):

- Multibulk and inline `PING`, `ECHO`, `SET`, `GET`, `DBSIZE`, `FLUSHDB`
- Atomic `INCR`, `DECR`, `INCRBY`, `DECRBY` (including `Long.MAX_VALUE`/`Long.MIN_VALUE` overflow, `ERR decrement would overflow` for `-Long.MIN_VALUE`, and non-integer rejection for `01`/`" 1"`/`""`) and multi-key `MSET`, `MGET`, `RENAME`
- Binary-safe keys and values, `DEL`, `EXISTS`, `KEYS` (unordered comparison for `KEYS *`), and pipelined commands
- Command errors (`arity`, case-insensitive command names, `FLUSHDB extra`, empty bulk command name) and Redis-matching `128`-byte error argument truncation and `\r`/`\n` sanitization
- Inline command whitespace (`\r`, `\n`, `\t`, `\v`), single/double/hex escapes (`\xHH`), ignored non-positive multibulk counts (`*0`, `*-1`, `*-5`), and unchecked trailing bytes after bulk payload
- `QUIT` (with/without extra arguments) closing the connection without running subsequent pipelined commands, plus protocol error handling and immediate disconnect

### Intentional Differences

1. **Requests containing NUL (`\0`) bytes:** Redis uses C strings on inline/error paths and truncates or stalls on `\0`. `mini-redis` stays strictly binary-safe end-to-end.
2. **`KEYS` character ranges crossing `0x7F`/`0x80` (e.g. `[\x01-\x80]`):** Redis compares range bounds as signed `char` on x86 (platform-dependent; on x86 `0x80` becomes `-128`). `mini-redis` compares byte values unsigned across all platforms, consistent with `memcmp` byte order.
3. **Custom RDB snapshot format (`MINIRDB\0`):** `SAVE`/`BGSAVE` use `mini-redis`'s own big-endian binary snapshot format with trailing `CRC32` rather than Redis's LZF-compressed RDB format.

## Notes

Not affiliated with Redis Ltd. This is an educational project. "Redis" is a trademark of Redis Ltd.
