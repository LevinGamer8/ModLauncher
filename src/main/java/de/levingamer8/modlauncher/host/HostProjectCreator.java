package de.levingamer8.modlauncher.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.levingamer8.modlauncher.core.ManifestModels;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class HostProjectCreator {

    private final ObjectMapper om;
    private final HostManifestGenerator manifestGenerator;

    public HostProjectCreator() {
        this.om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.manifestGenerator = new HostManifestGenerator();
    }

    /**
     * Backward compatible entry point if other code still calls (cfg, req).
     * Uses req.outputFolder + req.baseUrl as source of truth.
     */
    public HostProjectPaths create(HostProjectConfig cfg, CreateHostProjectRequest req) throws Exception {
        return createInternal(cfg, req);
    }

    /**
     * Preferred entry point.
     */
    public HostProjectPaths create(CreateHostProjectRequest req) throws Exception {
        return createInternal(null, req);
    }

    private HostProjectPaths createInternal(HostProjectConfig cfg, CreateHostProjectRequest req) throws Exception {
        if (req == null) throw new IllegalArgumentException("req is null");

        String projectId = requireText(req.projectId(), "projectId");
        String projectName = requireText(req.name(), "name");
        String mcVersion = requireText(req.mcVersion(), "mcVersion");
        String initialVersion = requireText(req.initialVersion(), "initialVersion");
        String baseUrl = requireText(req.baseUrl(), "baseUrl");

        Path root = req.outputFolder();
        if (root == null) throw new IllegalArgumentException("outputFolder is null");

        // Optional from cfg (if you have it)
        String serverIP = (cfg != null && cfg.serverIP() != null) ? cfg.serverIP() : "";
        int serverPortInt = (cfg != null) ? cfg.serverPort() : 25565;
        boolean allowClientMods = (cfg != null) ? cfg.allowClientMods() : true;
        boolean onlySelectedServer = (cfg != null) ? cfg.onlySelectedServer() : false;

        // Layout:
        // <root>/project.json
        // <root>/versions.json
        // <root>/versions/<ver>/manifest.json
        // <root>/versions/<ver>/files/...
        Files.createDirectories(root);

        Path versionsDir = root.resolve("versions");
        Files.createDirectories(versionsDir);

        Path versionDir = versionsDir.resolve(initialVersion);
        Files.createDirectories(versionDir);

        Path filesDir = versionDir.resolve("files");
        Files.createDirectories(filesDir);

        // 1) project.json (meta)
        ProjectJson pj = new ProjectJson(
                projectId,
                projectName,
                mcVersion,
                req.loader() != null ? req.loader().name() : "VANILLA",
                req.loaderVersion() == null ? "" : req.loaderVersion(),
                serverIP == null ? "" : serverIP,
                Integer.toString(serverPortInt),
                allowClientMods,
                onlySelectedServer
        );
        Path projectJson = root.resolve("project.json");
        om.writeValue(projectJson.toFile(), pj);

        // 2) versions.json (latest + list)
        String manifestUrl = ensureSlash(baseUrl) + "versions/" + initialVersion + "/manifest.json";
        VersionsIndex versionsIndex = new VersionsIndex(
                initialVersion, // latest
                List.of(new VersionsIndex.VersionEntry(initialVersion, manifestUrl))
        );
        Path versionsJson = root.resolve("versions.json");
        om.writeValue(versionsJson.toFile(), versionsIndex);

        // 3) manifest.json
        String filesBaseUrl = ensureSlash(baseUrl) + "versions/" + initialVersion + "/files/";
        int packVersion = Semver.parse(initialVersion).toIntPackVersion();

        ManifestModels.Loader loader = new ManifestModels.Loader(
                req.loader() != null ? req.loader().name() : "VANILLA",
                req.loaderVersion() == null ? "" : req.loaderVersion()
        );

        ManifestModels.Manifest manifest = new ManifestModels.Manifest(
                projectId,
                projectName,
                packVersion,
                mcVersion,
                loader,
                filesBaseUrl,
                List.of(),     // generated
                null,          // overrides optional
                Instant.now().toString(),
                ""             // changelogUrl optional
        );

        Path manifestJson = versionDir.resolve("manifest.json");
        om.writeValue(manifestJson.toFile(), manifest);

        // Write/refresh files[] (empty initially, but consistent)
        manifestGenerator.generate(manifestJson, filesDir);

        return new HostProjectPaths(
                root,
                projectJson,
                versionsJson,
                versionDir,
                manifestJson,
                filesDir
        );
    }

    private static String requireText(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " is blank");
        return v;
    }

    private static String ensureSlash(String s) {
        return s.endsWith("/") ? s : (s + "/");
    }
}
