package de.levingamer8.modlauncher.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.levingamer8.modlauncher.core.ManifestModels;

import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class HostReleaseManager {

    private final ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final HostManifestGenerator manifestGen = new HostManifestGenerator();

    public HostProjectPaths createNextPatchRelease(Path projectRoot, String baseUrl) throws Exception {
        Path versionsJson = projectRoot.resolve("versions.json");
        VersionsIndex idx = om.readValue(versionsJson.toFile(), VersionsIndex.class);

        String oldVer = idx.latestVersion();
        if (oldVer == null) throw new IllegalStateException("versions.json hat kein latest");

        String newVer = Semver.parse(oldVer).bumpPatch().toString();

        Path versionsDir = projectRoot.resolve("versions");
        Path oldDir = versionsDir.resolve(oldVer);
        Path newDir = versionsDir.resolve(newVer);

        if (!Files.isDirectory(oldDir)) throw new IllegalStateException("Alte Version fehlt: " + oldDir);
        if (Files.exists(newDir)) throw new IllegalStateException("Neue Version existiert schon: " + newDir);

        // 1) copy
        copyDir(oldDir, newDir);

        // 2) update manifest baseUrl + packVersion + generatedAt (im neuen manifest!)
        Path manifestPath = newDir.resolve("manifest.json");
        ManifestModels.Manifest m = om.readValue(manifestPath.toFile(), ManifestModels.Manifest.class);

        String filesBaseUrl = ensureSlash(baseUrl) + "versions/" + newVer + "/files/";
        Semver sv = Semver.parse(newVer);

        ManifestModels.Manifest updated = new ManifestModels.Manifest(
                m.packId(),
                m.packName(),
                sv.toIntPackVersion(),
                m.minecraftVersion(),
                m.loader(),
                filesBaseUrl,
                m.files(),
                m.overrides(),
                Instant.now().toString(),
                m.changelogUrl()
        );
        om.writeValue(manifestPath.toFile(), updated);

        // 3) regenerate files list (hashes+urls) based on newDir/files
        Path filesDir = newDir.resolve("files");
        manifestGen.generate(manifestPath, filesDir);

        // 4) update versions.json
        List<VersionsIndex.VersionEntry> list = new ArrayList<>();
        if (idx.versions() != null) list.addAll(idx.versions());
        String manifestUrl = ensureSlash(baseUrl) + "versions/" + newVer + "/manifest.json";
        list.add(new VersionsIndex.VersionEntry(newVer, manifestUrl));
        list.sort(Comparator.comparing(e -> Semver.parse(e.version())));

        VersionsIndex newIdx = new VersionsIndex(newVer, list);
        om.writeValue(versionsJson.toFile(), newIdx);

        return new HostProjectPaths(
                projectRoot,
                projectRoot.resolve("project.json"),
                versionsJson,
                newDir,
                manifestPath,
                filesDir
        );
    }

    private static void copyDir(Path src, Path dst) throws Exception {
        Files.createDirectories(dst);
        try (var s = Files.walk(src)) {
            for (Path p : s.toList()) {
                Path rel = src.relativize(p);
                Path t = dst.resolve(rel);
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static String ensureSlash(String s) { return s.endsWith("/") ? s : s + "/"; }
}
