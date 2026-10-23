package com.vct.capture;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Finds the Discord process whose tree plays voice chat audio.
 *
 * <p>Discord runs as several {@code Discord.exe} processes. Voice audio is played by a child, so
 * process loopback must target the root of the tree in INCLUDE_TARGET_PROCESS_TREE mode.
 */
public final class DiscordLocator {

    /** A process as seen by the locator (a seam so the selection logic is testable). */
    public record Proc(long pid, long parentPid, String executable, Instant start) {}

    static final Set<String> EXECUTABLES = Set.of("discord.exe", "discordptb.exe", "discordcanary.exe");

    private DiscordLocator() {}

    /** Root process id of the running Discord client, if any. */
    public static Optional<Long> findRootPid() {
        return findRootPid(ProcessHandle.allProcesses().map(DiscordLocator::toProc).toList());
    }

    static Optional<Long> findRootPid(List<Proc> processes) {
        Map<Long, Proc> discord = processes.stream()
                .filter(p -> p.executable() != null && EXECUTABLES.contains(p.executable().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toMap(Proc::pid, Function.identity(), (a, b) -> a));
        // A root is a Discord process whose parent is not Discord. Several roots (e.g. stable and PTB
        // both running) are possible: prefer the one started first.
        return discord.values().stream()
                .filter(p -> !discord.containsKey(p.parentPid()))
                .min(Comparator.comparing(Proc::start, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingLong(Proc::pid))
                .map(Proc::pid);
    }

    /** Resolver for {@link CaptureLoop}: Discord's tree, or NOT_FOUND while Discord is not running. */
    public static CaptureLoop.TargetResolver resolver() {
        return () -> findRootPid()
                .map(pid -> (CaptureTarget) new CaptureTarget.ProcessTree(pid, CaptureTarget.ProcessTree.Mode.INCLUDE))
                .orElseThrow(() -> new CaptureException(CaptureException.Kind.NOT_FOUND,
                        "Discord is not running. Waiting for it to start."));
    }

    private static Proc toProc(ProcessHandle h) {
        ProcessHandle.Info info = h.info();
        String exe = info.command().map(c -> {
            Path name = Path.of(c).getFileName();
            return name == null ? c : name.toString();
        }).orElse(null);
        long parent = h.parent().map(ProcessHandle::pid).orElse(-1L);
        return new Proc(h.pid(), parent, exe, info.startInstant().orElse(null));
    }
}
