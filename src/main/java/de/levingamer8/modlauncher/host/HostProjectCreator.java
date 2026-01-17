package de.levingamer8.modlauncher.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.levingamer8.modlauncher.core.LoaderType;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.regex.Pattern;

public class HostProjectCreator {

    private static final Pattern PROJECT_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,31}$"); // 2..32 chars
    private final ObjectMapper om;

    public HostProjectCreator() {
        this.om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public HostProjectPaths create(CreateHostProjectRequest req) throws IOException {
        validate(req);

        Path projectRoot = req.outputFolder().resolve(req.projectId());

        var cfg = new HostProjectConfig(
                req.projectId(),
                req.name(),
                req.mcVersion(),
                req.loader(),
                req.loaderVersion(),
                false // TODO: UI-Option später
        );

        Path projectJson = projectRoot.resolve("project.json");
        writeJsonAtomic(projectJson, cfg);


        Path versionsDir = projectRoot.resolve("versions");
        Path vDir = versionsDir.resolve(req.initialVersion());
        Path filesDir = vDir.resolve("files");

        // Ordnerstruktur
        Files.createDirectories(filesDir.resolve("mods"));
        Files.createDirectories(filesDir.resolve("config"));
        Files.createDirectories(filesDir.resolve("resourcepacks"));
        Files.createDirectories(filesDir.resolve("shaderpacks"));

        // latest.json
        String manifestUrl = normalizeBaseUrl(req.baseUrl()) + "versions/" + req.initialVersion() + "/manifest.json";
        LatestPointer latest = new LatestPointer(req.projectId(), req.initialVersion(), manifestUrl);

        // manifest.json (leer)
        String baseUrlForFiles = normalizeBaseUrl(req.baseUrl()) + "versions/" + req.initialVersion() + "/files/";
        HostManifest manifest = new HostManifest(
                req.projectId(),
                req.name(),
                req.mcVersion(),
                req.loader(),
                req.loaderVersion(),
                req.initialVersion(),
                Instant.now().toString(),
                baseUrlForFiles,
                new ArrayList<>()
        );

        Path latestPath = projectRoot.resolve("latest.json");
        Path manifestPath = vDir.resolve("manifest.json");

        writeJsonAtomic(latestPath, latest);
        writeJsonAtomic(manifestPath, manifest);

        return new HostProjectPaths(projectRoot, latestPath, manifestPath, filesDir);
    }

    private void validate(CreateHostProjectRequest req) {
        if (req == null) throw new IllegalArgumentException("request is null");
        if (req.outputFolder() == null) throw new IllegalArgumentException("outputFolder is null");
        if (req.projectId() == null || !PROJECT_ID.matcher(req.projectId()).matches())
            throw new IllegalArgumentException("projectId invalid (use a-z 0-9 _ - , length 2..32)");
        if (isBlank(req.name())) throw new IllegalArgumentException("name is empty");
        if (isBlank(req.mcVersion())) throw new IllegalArgumentException("mcVersion is empty");
        if (req.loader() == null) throw new IllegalArgumentException("loader is null");
        if (req.loader() != LoaderType.VANILLA && isBlank(req.loaderVersion()))
            throw new IllegalArgumentException("loaderVersion required for " + req.loader());
        if (isBlank(req.initialVersion())) throw new IllegalArgumentException("initialVersion is empty");
        if (isBlank(req.baseUrl())) throw new IllegalArgumentException("baseUrl is empty");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String normalizeBaseUrl(String url) {
        String u = url.trim();
        if (!u.endsWith("/")) u += "/";
        return u;
    }

    private void writeJsonAtomic(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.createDirectories(target.getParent());

        om.writeValue(tmp.toFile(), value);

        // atomar ersetzen (Windows/Linux)
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
