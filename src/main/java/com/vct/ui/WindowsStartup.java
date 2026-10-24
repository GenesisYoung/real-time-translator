package com.vct.ui;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * "Start with Windows": a value under {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run},
 * the per-user list of programs Windows starts at sign-in. Set with Windows' own {@code reg.exe}.
 * The app then starts in the tray ({@code --tray}).
 *
 * <p>Only for the installed app, whose launcher sets {@code jpackage.app-path} to its .exe.
 */
final class WindowsStartup {

    private static final String KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE = "VoiceChatTranslator";

    private WindowsStartup() {}

    /** The installed program, or null when started otherwise (then this option is not offered). */
    static String exe() {
        if (!System.getProperty("os.name").startsWith("Windows")) return null;
        return System.getProperty("jpackage.app-path");
    }

    static boolean available() {
        return exe() != null;
    }

    static boolean isOn() {
        return available() && reg(List.of("query", KEY, "/v", VALUE)) == 0;
    }

    /** @throws IOException if Windows refused the change */
    static void set(boolean on) throws IOException {
        if (!available() || on == isOn()) return;
        int code = on ? reg(List.of("add", KEY, "/v", VALUE, "/t", "REG_SZ", "/d", command(exe()), "/f"))
                : reg(List.of("delete", KEY, "/v", VALUE, "/f"));
        if (code != 0) throw new IOException("reg.exe exited with " + code);
    }

    /** The command Windows runs: quoted, because the path usually contains spaces. */
    static String command(String exe) {
        return "\"" + exe + "\" --tray";
    }

    private static int reg(List<String> args) {
        try {
            List<String> cmd = new java.util.ArrayList<>(List.of("reg.exe"));
            cmd.addAll(args);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(5, TimeUnit.SECONDS) ? p.exitValue() : -1;
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
