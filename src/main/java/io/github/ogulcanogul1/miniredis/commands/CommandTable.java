package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.commands.CommandSpec.Flag;
import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Looks commands up by name, case-insensitively. Immutable after construction. */
public final class CommandTable {

    private static final Bytes QUIT = Bytes.ascii("quit");

    private final Map<Bytes, CommandSpec> byName;

    /**
     * Creates a table from {@code specs}.
     *
     * @throws IllegalArgumentException if two specs have the same name
     */
    public CommandTable(List<CommandSpec> specs) {
        Map<Bytes, CommandSpec> map = new HashMap<>();
        for (CommandSpec spec : specs) {
            if (map.put(Bytes.ascii(spec.name()), spec) != null) {
                throw new IllegalArgumentException("duplicate command: " + spec.name());
            }
        }
        this.byName = Map.copyOf(map);
    }

    /** Returns the table with every command of the current phase (K11). */
    public static CommandTable standard() {
        return new CommandTable(List.of(
                new CommandSpec("ping", -1, Set.of(Flag.READONLY), ConnectionCommands::ping),
                new CommandSpec("echo", 2, Set.of(Flag.READONLY), ConnectionCommands::echo),
                new CommandSpec("quit", -1, Set.of(Flag.READONLY), ConnectionCommands::quit),
                new CommandSpec("set", -3, Set.of(Flag.WRITE), StringCommands::set),
                new CommandSpec("get", 2, Set.of(Flag.READONLY), StringCommands::get),
                new CommandSpec("incr", 2, Set.of(Flag.WRITE), StringCommands::incr),
                new CommandSpec("decr", 2, Set.of(Flag.WRITE), StringCommands::decr),
                new CommandSpec("incrby", 3, Set.of(Flag.WRITE), StringCommands::incrBy),
                new CommandSpec("decrby", 3, Set.of(Flag.WRITE), StringCommands::decrBy),
                new CommandSpec("mget", -2, Set.of(Flag.READONLY), StringCommands::mget),
                new CommandSpec("mset", -3, Set.of(Flag.WRITE), StringCommands::mset),
                new CommandSpec("del", -2, Set.of(Flag.WRITE), KeyCommands::del),
                new CommandSpec("unlink", -2, Set.of(Flag.WRITE), KeyCommands::del),
                new CommandSpec("exists", -2, Set.of(Flag.READONLY), KeyCommands::exists),
                new CommandSpec("rename", 3, Set.of(Flag.WRITE), KeyCommands::rename),
                new CommandSpec("expire", 3, Set.of(Flag.WRITE), KeyCommands::expire),
                new CommandSpec("pexpire", 3, Set.of(Flag.WRITE), KeyCommands::pexpire),
                new CommandSpec("pexpireat", 3, Set.of(Flag.WRITE), KeyCommands::pexpireAt),
                new CommandSpec("ttl", 2, Set.of(Flag.READONLY), KeyCommands::ttl),
                new CommandSpec("pttl", 2, Set.of(Flag.READONLY), KeyCommands::pttl),
                new CommandSpec("persist", 2, Set.of(Flag.WRITE), KeyCommands::persist),
                new CommandSpec("keys", 2, Set.of(Flag.READONLY), KeyCommands::keys),
                new CommandSpec("dbsize", 1, Set.of(Flag.READONLY), KeyCommands::dbsize),
                new CommandSpec("flushdb", -1, Set.of(Flag.WRITE), KeyCommands::flushdb),
                new CommandSpec("lpush", -3, Set.of(Flag.WRITE), ListCommands::lpush),
                new CommandSpec("rpush", -3, Set.of(Flag.WRITE), ListCommands::rpush),
                new CommandSpec("lpop", 2, Set.of(Flag.WRITE), ListCommands::lpop),
                new CommandSpec("rpop", 2, Set.of(Flag.WRITE), ListCommands::rpop),
                new CommandSpec("lrange", 4, Set.of(Flag.READONLY), ListCommands::lrange),
                new CommandSpec("llen", 2, Set.of(Flag.READONLY), ListCommands::llen),
                new CommandSpec("hset", -4, Set.of(Flag.WRITE), HashCommands::hset),
                new CommandSpec("hget", 3, Set.of(Flag.READONLY), HashCommands::hget),
                new CommandSpec("hdel", -3, Set.of(Flag.WRITE), HashCommands::hdel),
                new CommandSpec("hgetall", 2, Set.of(Flag.READONLY), HashCommands::hgetall),
                new CommandSpec("sadd", -3, Set.of(Flag.WRITE), SetCommands::sadd),
                new CommandSpec("srem", -3, Set.of(Flag.WRITE), SetCommands::srem),
                new CommandSpec("sismember", 3, Set.of(Flag.READONLY), SetCommands::sismember),
                new CommandSpec("smembers", 2, Set.of(Flag.READONLY), SetCommands::smembers),
                new CommandSpec("zadd", -4, Set.of(Flag.WRITE), ZSetCommands::zadd),
                new CommandSpec("zscore", 3, Set.of(Flag.READONLY), ZSetCommands::zscore),
                new CommandSpec("zrange", -4, Set.of(Flag.READONLY), ZSetCommands::zrange),
                new CommandSpec("zrangebyscore", -4, Set.of(Flag.READONLY), ZSetCommands::zrangeByScore),
                new CommandSpec("zrank", 3, Set.of(Flag.READONLY), ZSetCommands::zrank)));
    }

    /** Returns {@code true} if {@code argv} invokes the {@code QUIT} command (case-insensitive). */
    public static boolean isQuit(List<Bytes> argv) {
        Objects.requireNonNull(argv, "argv");
        return !argv.isEmpty() && argv.getFirst().toLowerAscii().equals(QUIT);
    }

    /** Finds the command called {@code name}, ignoring ASCII case. */
    public Optional<CommandSpec> lookup(Bytes name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(byName.get(name.toLowerAscii()));
    }
}
