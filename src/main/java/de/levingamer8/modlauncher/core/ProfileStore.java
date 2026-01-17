package de.levingamer8.modlauncher.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class ProfileStore {

    public enum JoinMode { SERVERS_DAT, DIRECT }

    public record Profile(
            String name,
            String manifestUrl,
            String serverHost,
            int serverPort,
            JoinMode joinMode
    ) {}

    private final Path baseDir;
    private final Path profilesFile;
    private final ObjectMapper om;

    public ProfileStore() {
        this.baseDir = Path.of(System.getenv("APPDATA"), ".modlauncher");
        this.profilesFile = baseDir.resolve("profiles.json");
        this.om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create base dir: " + baseDir, e);
        }
    }

    public Path baseDir() { return baseDir; }

    // ---- Shared cache (global) ----
    public Path sharedRoot() { return baseDir.resolve("shared"); }
    public Path sharedAssetsDir() { return sharedRoot().resolve("assets"); }
    public Path sharedLibrariesDir() { return sharedRoot().resolve("libraries"); }
    public Path sharedVersionsDir() { return sharedRoot().resolve("versions"); }

    // ---- Instance dirs (per profile) ----
    public Path instanceDir(String profileName) {
        return baseDir.resolve("instances").resolve(profileName);
    }

    public Path instanceGameDir(String profileName) {
        return instanceDir(profileName).resolve("game");
    }

    public Path instanceRuntimeDir(String profileName) {
        return instanceDir(profileName).resolve("runtime");
    }

    /** Backwards compat: früher war das "minecraftVersion root". Jetzt ist es gameDir. */
    public Path minecraftDir(String profileName) {
        return instanceGameDir(profileName);
    }

    public List<Profile> loadProfiles() {
        if (!Files.exists(profilesFile)) return List.of();
        try {
            Profile[] arr = om.readValue(profilesFile.toFile(), Profile[].class);
            return Arrays.asList(arr);
        } catch (Exception e) {
            // NICHT stillschweigend schlucken, das ist Debug-Hölle
            throw new RuntimeException("Failed to read profiles.json: " + e.getMessage(), e);
        }
    }

    public void saveOrUpdateProfile(Profile p) {
        List<Profile> all = new ArrayList<>(loadProfiles());
        all.removeIf(x -> x.name().equalsIgnoreCase(p.name()));
        all.add(p);
        all.sort(Comparator.comparing(Profile::name, String.CASE_INSENSITIVE_ORDER));
        try {
            Files.createDirectories(profilesFile.getParent());
            om.writeValue(profilesFile.toFile(), all);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write profiles.json: " + e.getMessage(), e);
        }
    }

    public void deleteProfile(String name) {
        List<Profile> all = new ArrayList<>(loadProfiles());
        all.removeIf(x -> x.name().equalsIgnoreCase(name));
        try {
            om.writeValue(profilesFile.toFile(), all);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write profiles.json: " + e.getMessage(), e);
        }
    }
}
