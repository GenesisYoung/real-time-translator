package com.vct.capture;

/** What a capture stream records. */
public sealed interface CaptureTarget {

    /** A capture device (microphone). {@code deviceId} null means the Windows default device. */
    record Device(String deviceId) implements CaptureTarget {
        public boolean followsDefault() {
            return deviceId == null;
        }
    }

    /** Everything a render device plays. {@code deviceId} null means the Windows default output. */
    record Loopback(String deviceId) implements CaptureTarget {
        public boolean followsDefault() {
            return deviceId == null;
        }
    }

    /**
     * Audio played by a process tree (INCLUDE, e.g. Discord), or all audio except a process tree
     * (EXCLUDE, e.g. everything except this app, so its own speech is never captured).
     */
    record ProcessTree(long pid, Mode mode) implements CaptureTarget {
        public enum Mode { INCLUDE, EXCLUDE }

        public ProcessTree {
            if (pid <= 0 || pid > 0xFFFF_FFFFL) throw new IllegalArgumentException("bad pid " + pid);
        }
    }
}
