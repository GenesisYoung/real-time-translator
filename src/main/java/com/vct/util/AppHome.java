package com.vct.util;

import java.nio.file.Path;

/**
 * Where the app's own files are: the native libraries ({@code native\}) and the models
 * ({@code models\}). Relative paths in the settings and the default library paths are resolved
 * against it.
 *
 * <p>In the installed app it is the folder next to the program, passed by the launcher as
 * {@code -Dvct.home}. Started from the repository ({@code gradlew run}), it is the working directory.
 */
public final class AppHome {

    public static final String PROPERTY = "vct.home";

    private AppHome() {}

    public static Path dir() {
        String home = System.getProperty(PROPERTY);
        return home == null || home.isBlank() ? Path.of("").toAbsolutePath() : Path.of(home);
    }

    /** True in the installed app, false when started from the repository. */
    public static boolean installed() {
        String home = System.getProperty(PROPERTY);
        return home != null && !home.isBlank();
    }

    /** {@code path} itself if absolute, else resolved against {@link #dir()}. */
    public static Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : dir().resolve(p);
    }

    /** {@link #resolve(String)} for several parts, e.g. {@code resolve("native", "vct_audio.dll")}. */
    public static Path resolve(String first, String... more) {
        return resolve(Path.of(first, more).toString());
    }
}
