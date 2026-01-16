package de.levingamer8.modlauncher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class MicrosoftSessionStore {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public MicrosoftSessionStore(Path file) {
        this.file = file;
    }

    public MicrosoftMinecraftAuth.MinecraftSession loadOrNull() {
        try {
            if (!Files.exists(file)) return null;
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return gson.fromJson(json, MicrosoftMinecraftAuth.MinecraftSession.class);
        } catch (Exception e) {
            return null;
        }
    }

    public void save(MicrosoftMinecraftAuth.MinecraftSession session) {
        try {
            Files.createDirectories(file.getParent());
            String json = gson.toJson(session);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {}
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {}
    }

    public Path file() {
        return file;
    }
}
