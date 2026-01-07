package de.levingamer8.modlauncher.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class ProfileStore {

    public enum JoinMode {
        SERVERS_DAT,   // stabil
        DIRECT         // optional
    }

    public record Profile(
            String name,
            String manifestUrl,
            String serverHost,
            int serverPort,
            JoinMode joinMode
    ) {
        @Override public String toString() { return name; }
    }

    private final Path baseDir;
    private final Path profilesFile;
    private final ObjectMapper om;

    public ProfileStore() {
        this.baseDir = Path.of(System.getProperty("user.home"), ".modlauncher");
        this.profilesFile = baseDir.resolve("profiles.json");
        this.om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create base dir: " + baseDir, e);
        }
    }

    public Path baseDir() { return baseDir; }

    public Path instanceDir(String profileName) {
        return baseDir.resolve("instances").resolve(profileName);
    }

    /** eigener Minecraft Root pro Instanz */
    public Path minecraftDir(String profileName) {
        return instanceDir(profileName).resolve("minecraft");
    }

    public List<Profile> loadProfiles() {
        if (!Files.exists(profilesFile)) return List.of();
        try {
            Profile[] arr = om.readValue(profilesFile.toFile(), Profile[].class);
            return Arrays.asList(arr);
        } catch (Exception e) {
            return List.of();
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
