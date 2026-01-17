package de.levingamer8.modlauncher.mc;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProcessWatcher {

    public interface Listener {
        default void onStarted(Process process, Instant startedAt) {}
        default void onExited(Process process, Instant startedAt, Instant endedAt, int exitCode) {}
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Process process;
    private volatile Instant startedAt;

    public void addListener(Listener l) {
        listeners.add(Objects.requireNonNull(l));
    }

    public boolean isRunning() {
        Process p = this.process;
        return p != null && p.isAlive();
    }

    public synchronized Process start(ProcessBuilder pb) throws Exception {
        if (running.get()) throw new IllegalStateException("ProcessWatcher: already running");

        Process p = pb.start();
        Instant start = Instant.now();

        this.process = p;
        this.startedAt = start;
        running.set(true);

        for (Listener l : listeners) {
            try { l.onStarted(p, start); } catch (Exception ignored) {}
        }

        Thread t = new Thread(() -> watch(p, start), "process-watcher");
        t.setDaemon(true);
        t.start();

        return p;
    }

    private void watch(Process p, Instant start) {
        int code = -1;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!p.isAlive()) {
                try { code = p.exitValue(); } catch (Exception ignored) {}
            }
        } finally {
            Instant end = Instant.now();
            running.set(false);

            for (Listener l : listeners) {
                try { l.onExited(p, start, end, code); } catch (Exception ignored) {}
            }

            synchronized (this) {
                if (this.process == p) {
                    this.process = null;
                    this.startedAt = null;
                }
            }
        }
    }
}
