package de.levingamer8.modlauncher.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.levingamer8.modlauncher.core.ManifestModels.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class PackUpdater {

    private final ObjectMapper om = new ObjectMapper();
    private final HttpClientEx http = new HttpClientEx();
    private final StateStore stateStore = new StateStore();

    public void update(ProfileStore.Profile profile,
                       ProfileStore profileStore,
                       Consumer<String> log,
                       BiConsumer<Long, Long> progress) throws Exception {

        String manifestUrl = profile.manifestUrl();
        log.accept("Manifest laden: " + manifestUrl);

        Manifest manifest = fetchManifest(manifestUrl);
        if (manifest.packId() == null || manifest.packId().isBlank()) {
            throw new IllegalArgumentException("manifest.packId fehlt");
        }

        // Instanzordner wird am Profilnamen festgemacht (nicht packId), damit du Profile umbenennen kannst, wenn du willst.
        Path instanceDir = profileStore.instanceDir(profile.name());
        Path installDir = profileStore.instanceGameDir(profile.name());
        Files.createDirectories(instanceDir);
        Files.createDirectories(instanceDir.resolve(".state"));
        Files.createDirectories(instanceDir.resolve("downloads"));
        Files.createDirectories(installDir);
        Files.createDirectories(installDir.resolve("mods"));
        Files.createDirectories(installDir.resolve("config"));

        // Lock: verhindert Parallel-Updates
        Path lockFile = instanceDir.resolve(".state/install.lock");
        try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = ch.lock()) {

            Optional<Integer> installed = stateStore.readInstalledPackVersion(instanceDir);
            log.accept("Installiert: " + installed.map(String::valueOf).orElse("nichts") +
                    " | Manifest packVersion: " + manifest.packVersion());

            // Schritt 1: Dateien syncen
            List<ManifestFile> clientFiles = (manifest.files() == null ? List.<ManifestFile>of() : manifest.files())
                    .stream()
                    .filter(f -> isClientSide(f.side()))
                    .toList();

            long total = clientFiles.size() + (manifest.overrides() != null ? 1 : 0);
            long done = 0;
            progress.accept(done, total);

            for (ManifestFile f : clientFiles) {
                done++;
                progress.accept(done, total);

                if (f.path() == null || f.path().isBlank()) {
                    throw new IllegalArgumentException("Datei hat kein path im Manifest");
                }
                if (f.sha256() == null || f.sha256().isBlank()) {
                    throw new IllegalArgumentException("Datei " + f.path() + " hat kein sha256 im Manifest");
                }
                if (f.download() == null || f.download().url() == null || f.download().url().isBlank()) {
                    throw new IllegalArgumentException("Datei " + f.path() + " hat keine download.url im Manifest");
                }

                Path target = installDir.resolve(f.path()).normalize();
                if (!target.startsWith(installDir.normalize())) {
                    throw new SecurityException("Pfad verlässt Installationsordner: " + f.path());
                }
                boolean ok = Files.exists(target) && FileUtil.sha256(target).equalsIgnoreCase(f.sha256());
                if (ok) {
                    log.accept("OK: " + f.path());
                    continue;
                }

                log.accept("Download: " + f.path());
                Path tmp = instanceDir.resolve("downloads").resolve("dl-" + System.nanoTime() + ".part");
                http.downloadToFile(f.download().url(), tmp);

                String got = FileUtil.sha256(tmp);
                if (!got.equalsIgnoreCase(f.sha256())) {
                    Files.deleteIfExists(tmp);
                    throw new IOException("Hash mismatch: " + f.path() + " expected=" + f.sha256() + " got=" + got);
                }

                FileUtil.atomicReplace(tmp, target);
                log.accept("Installiert: " + f.path());
            }

            // Schritt 2: cleanup mods (löscht extra .jar in mods/)
            cleanupMods(installDir, clientFiles, log);



            // Schritt 3: overrides.zip (configs usw.)
            if (manifest.overrides() != null && manifest.overrides().url() != null && !manifest.overrides().url().isBlank()) {
                done++;
                progress.accept(done, total);

                Overrides o = manifest.overrides();
                if (o.sha256() == null || o.sha256().isBlank()) {
                    throw new IllegalArgumentException("overrides.sha256 fehlt");
                }

                log.accept("Overrides downloaden: " + o.url());
                Path tmpZip = instanceDir.resolve("downloads").resolve("overrides-" + System.nanoTime() + ".zip");
                http.downloadToFile(o.url(), tmpZip);

                String got = FileUtil.sha256(tmpZip);
                if (!got.equalsIgnoreCase(o.sha256())) {
                    Files.deleteIfExists(tmpZip);
                    throw new IOException("Overrides hash mismatch expected=" + o.sha256() + " got=" + got);
                }

                log.accept("Overrides entpacken...");
                FileUtil.unzipSafe(tmpZip, installDir);
                Files.deleteIfExists(tmpZip);
                log.accept("Overrides angewendet.");
            }

            // Schritt 4: State schreiben
            stateStore.writeState(instanceDir, manifest.packId(), manifest.packVersion());
            log.accept("State geschrieben: packVersion=" + manifest.packVersion());
        }
    }

    private Manifest fetchManifest(String url) throws IOException, InterruptedException {
        String json = http.getText(url);
        return om.readValue(json, Manifest.class);
    }

    private boolean isClientSide(String side) {
        if (side == null) return true; // default: nehmen wir mal als client/both
        return side.equalsIgnoreCase("client") || side.equalsIgnoreCase("both");
    }

    private void cleanupMods(Path installDir, List<ManifestFile> clientFiles, Consumer<String> log) throws IOException {
    Path modsDir = installDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) return;

        // Nur erlaubte Dateien in mods/ (aus Manifest)
        Set<Path> allowed = clientFiles.stream()
                .map(f -> installDir.resolve(f.path()).normalize())
                .collect(Collectors.toSet());

        try (var s = Files.list(modsDir)) {
            for (Path p : s.toList()) {
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
                    if (!allowed.contains(p.normalize())) {
                        Files.deleteIfExists(p);
                        log.accept("Entfernt (nicht im Manifest): " + installDir.relativize(p));
                    }
                }
            }
        }
    }

}