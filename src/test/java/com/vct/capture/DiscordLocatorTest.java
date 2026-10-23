package com.vct.capture;

import com.vct.capture.DiscordLocator.Proc;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscordLocatorTest {

    private static final Instant T = Instant.parse("2026-10-23T10:00:00Z");

    @Test
    void picksTheRootOfTheDiscordTree() {
        List<Proc> ps = List.of(
                new Proc(1, 0, "explorer.exe", T),
                new Proc(500, 1, "Discord.exe", T.plusSeconds(1)),
                new Proc(501, 500, "Discord.exe", T.plusSeconds(2)), // GPU / renderer / voice children
                new Proc(502, 500, "Discord.exe", T.plusSeconds(2)),
                new Proc(503, 502, "Discord.exe", T.plusSeconds(3)),
                new Proc(600, 1, "chrome.exe", T));
        assertEquals(Optional.of(500L), DiscordLocator.findRootPid(ps));
    }

    @Test
    void recognizesPtbAndCanaryCaseInsensitively() {
        assertEquals(Optional.of(7L), DiscordLocator.findRootPid(List.of(new Proc(7, 1, "DISCORDPTB.EXE", T))));
        assertEquals(Optional.of(8L), DiscordLocator.findRootPid(List.of(new Proc(8, 1, "DiscordCanary.exe", T))));
    }

    @Test
    void prefersTheOldestRootWhenTwoClientsRun() {
        List<Proc> ps = List.of(
                new Proc(900, 1, "DiscordPTB.exe", T.plusSeconds(60)),
                new Proc(500, 1, "Discord.exe", T),
                new Proc(501, 500, "Discord.exe", T.plusSeconds(1)));
        assertEquals(Optional.of(500L), DiscordLocator.findRootPid(ps));
    }

    @Test
    void emptyWhenNotRunningOrNameUnknown() {
        assertEquals(Optional.empty(), DiscordLocator.findRootPid(List.of(
                new Proc(1, 0, "explorer.exe", T),
                new Proc(2, 1, null, null), // other users' processes may hide their command
                new Proc(3, 1, "DiscordHelper.exe", T))));
    }
}
