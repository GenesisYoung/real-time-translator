package com.vct.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Keeps the app to one copy: a second one would fail to register the hotkeys and load the GPU model
 * twice. The first copy holds a lock file and listens on a local port; a second copy finds the lock
 * taken, asks the first to show its window, and quits.
 */
final class SingleInstance implements AutoCloseable {

    private static final byte[] SHOW = "show\n".getBytes(StandardCharsets.US_ASCII);

    private final FileChannel channel;
    private final FileLock lock;
    private final ServerSocket server;

    private SingleInstance(FileChannel channel, FileLock lock, ServerSocket server) {
        this.channel = channel;
        this.lock = lock;
        this.server = server;
    }

    /**
     * Becomes the running copy, or hands over to the one already running.
     *
     * @param dir    where the lock and port files are
     * @param onShow called (on a background thread) when another copy was started
     * @return the running copy, or null if another one was already running (it was asked to show itself)
     */
    static SingleInstance acquire(Path dir, Runnable onShow) throws IOException {
        Files.createDirectories(dir);
        FileChannel ch = FileChannel.open(dir.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = ch.tryLock(); // null when another process holds it
        } catch (java.nio.channels.OverlappingFileLockException e) {
            lock = null; // held by this JVM (tests)
        }
        Path portFile = dir.resolve("instance.port");
        if (lock == null) {
            ch.close();
            askToShow(portFile);
            return null;
        }
        ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        Files.writeString(portFile, Integer.toString(server.getLocalPort()));
        Thread.ofPlatform().name("vct-single-instance").daemon().start(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept(); InputStream in = s.getInputStream()) {
                    s.setSoTimeout(1_000);
                    if (java.util.Arrays.equals(in.readNBytes(SHOW.length), SHOW)) onShow.run();
                } catch (IOException e) {
                    // closed, or a stray connection: keep listening
                }
            }
        });
        return new SingleInstance(ch, lock, server);
    }

    private static void askToShow(Path portFile) {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(Files.readString(portFile).strip()));
             OutputStream out = s.getOutputStream()) {
            out.write(SHOW);
        } catch (IOException | NumberFormatException e) {
            // the first copy is still starting, or closing: nothing to show
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        lock.release();
        channel.close();
    }
}
