package de.levingamer8.modlauncher.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.levingamer8.modlauncher.core.ManifestModels;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public class HostProjectCreator {

    private final ObjectMapper om = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public HostProjectPaths create(CreateHostProjectRequest req) throws Exception {

        // -------- Root --------
        Path root = req.outputFolder().resolve(req.projectId());
        Files.createDirectories(root);

        // -------- Version --------
        String version = req.initialVersion();

        Path versionsDir = root.resolve("versions");
        Path versionDir = versionsDir.resolve(version);
        Path filesDir = versionDir.resolve("files");

        Files.createDirectories(filesDir.resolve("mods"));
        Files.createDirectories(filesDir.resolve("config"));
        Files.createDirectories(filesDir.resolve("resourcepacks"));
        Files.createDirectories(filesDir.resolve("shaderpacks"));

        // -------- URLs --------
        String baseUrl = ensureSlash(req.baseUrl());
        String manifestUrl = baseUrl + "versions/" + version + "/manifest.json";
        String filesBaseUrl = baseUrl + "versions/" + version + "/files/";

        // -------- project.json --------
        HostProjectConfig project = new HostProjectConfig(
                req.projectId(),
                req.name(),
                req.mcVersion(),
                new ManifestModels.Loader(req.loader().name(), req.loaderVersion()),
                "",          // serverIP
                25565,
                false,
                false
        );


        Path projectJson = root.resolve("project.json");
        om.writeValue(projectJson.toFile(), project);

        // -------- versions.json --------
        VersionsIndex versionsIndex = new VersionsIndex(
                version,
                List.of(new VersionsIndex.VersionEntry(version, manifestUrl))
        );

        Path versionsJson = root.resolve("versions.json");
        om.writeValue(versionsJson.toFile(), versionsIndex);

        // -------- manifest.json --------
        ManifestModels.Manifest manifest = new ManifestModels.Manifest(
                req.projectId(),
                req.name(),
                parseVersionInt(version),
                req.mcVersion(),
                new ManifestModels.Loader(req.loader().name(), req.loaderVersion()),
                filesBaseUrl,
                List.of(),
                null,
                Instant.now().toString(),
                "" // changelogUrl
        );


        Path manifestJson = versionDir.resolve("manifest.json");
        om.writeValue(manifestJson.toFile(), manifest);

        return new HostProjectPaths(
                root,
                projectJson,
                versionsJson,
                versionDir,
                manifestJson,
                filesDir
        );
    }

    // ---------------- helpers ----------------

    private static String ensureSlash(String s) {
        return s.endsWith("/") ? s : s + "/";
    }

    private static int parseVersionInt(String v) {
        // "1.0.0" -> 100
        try {
            String[] p = v.split("\\.");
            int major = Integer.parseInt(p[0]);
            int minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            return major * 100 + minor;
        } catch (Exception e) {
            return 1;
        }
    }
}
