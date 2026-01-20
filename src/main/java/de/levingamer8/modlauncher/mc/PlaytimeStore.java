package de.levingamer8.modlauncher.mc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

public final class PlaytimeStore {
    private static final String KEY_TOTAL_SECONDS = "totalSeconds";

    private final Path file;
    private final Properties props = new Properties();

    public PlaytimeStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized long getTotalSeconds() {
        return parseLong(props.getProperty(KEY_TOTAL_SECONDS), 0L);
    }

    public synchronized void addSession(Instant start, Instant end) {
        long add = Math.max(0, Duration.between(start, end).getSeconds());
        long total = getTotalSeconds() + add;
        props.setProperty(KEY_TOTAL_SECONDS, Long.toString(total));
        save();
    }

    public synchronized String getTotalPretty() {
        long total = getTotalSeconds();
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return String.format("%dh %02dm %02ds", h, m, s);
    }

    private synchronized void load() {
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            }
            if (props.getProperty(KEY_TOTAL_SECONDS) == null) {
                props.setProperty(KEY_TOTAL_SECONDS, "0");
                save();
            }
        } catch (IOException e) {
            props.setProperty(KEY_TOTAL_SECONDS, "0");
        }
    }

    synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), "ModLauncher playtime");
            }
        } catch (IOException ignored) {}
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }
}
