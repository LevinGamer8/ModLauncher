package de.levingamer8.modlauncher.mc;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.flowarg.flowupdater.FlowUpdater;
import fr.flowarg.flowupdater.download.IProgressCallback;
import fr.flowarg.flowupdater.download.Step;
import fr.flowarg.flowupdater.utils.UpdaterOptions;
import fr.flowarg.flowupdater.versions.VanillaVersion;
import fr.flowarg.flowupdater.versions.forge.ForgeVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.Consumer;

public final class MinecraftInstaller {

    public record InstallSpec(String mcVersion, String forgeVersionFull) {}

    private record InstallMarker(String mcVersion, String forgeVersionFull) {}

    public static final InstallSpec DEFAULT = new InstallSpec(
            "1.20.1",
            "1.20.1-47.4.13"
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MinecraftInstaller() {}

    public static void ensureInstalled(Path gameDir, InstallSpec spec, Consumer<String> logLine) throws Exception {
        Objects.requireNonNull(gameDir, "gameDir");
        Objects.requireNonNull(spec, "spec");

        // Marker: pro Instanz
        Path instanceDir = gameDir.getFileName().toString().equalsIgnoreCase("minecraft") ? gameDir.getParent() : gameDir;
        Path stateDir = instanceDir.resolve(".state");
        Path markerFile = stateDir.resolve("mc-install.json");

        // Schneller Skip wenn schon installiert (und Kernordner existieren)
        if (Files.exists(markerFile)
                && Files.isDirectory(gameDir.resolve("libraries"))
                && Files.isDirectory(gameDir.resolve("assets"))
                && Files.isDirectory(gameDir.resolve("versions"))) {

            InstallMarker m = MAPPER.readValue(markerFile.toFile(), InstallMarker.class);
            if (spec.mcVersion().equals(m.mcVersion()) && spec.forgeVersionFull().equals(m.forgeVersionFull())) {
                logLine.accept("Minecraft/Forge bereits installiert (skip).");
                return;
            }
        }

        Files.createDirectories(stateDir);

        logLine.accept("Minecraft/Forge Install prüfen...");
        logLine.accept("MC=" + spec.mcVersion() + " Forge=" + spec.forgeVersionFull());

        VanillaVersion vanilla = new VanillaVersion.VanillaVersionBuilder()
                .withName(spec.mcVersion())
                .build();

        ForgeVersion forge = new fr.flowarg.flowupdater.versions.forge.ForgeVersionBuilder()
                .withForgeVersion(spec.forgeVersionFull())
                .build();

        UpdaterOptions options = new UpdaterOptions.UpdaterOptionsBuilder().build();

        IProgressCallback progress = new IProgressCallback() {
            @Override public void step(Step step) {
                logLine.accept("[FlowUpdater] Step: " + step.name());
            }
            @Override public void update(fr.flowarg.flowupdater.download.DownloadList.DownloadInfo info) {
                // Weniger Spam: nur grob
                logLine.accept("[FlowUpdater] Downloading...");
            }
        };

        FlowUpdater updater = new FlowUpdater.FlowUpdaterBuilder()
                .withVanillaVersion(vanilla)
                .withModLoaderVersion(forge)
                .withUpdaterOptions(options)
                .withProgressCallback(progress)
                .build();

        updater.update(gameDir);
        fixVersionsLayout(gameDir, spec, logLine);


        // Marker schreiben
        MAPPER.writeValue(markerFile.toFile(), new InstallMarker(spec.mcVersion(), spec.forgeVersionFull()));
        logLine.accept("Minecraft/Forge Install fertig.");
    }

    private static void fixVersionsLayout(Path gameDir, InstallSpec spec, Consumer<String> log) throws IOException {
        Path versionsDir = gameDir.resolve("versions");
        Files.createDirectories(versionsDir);

        String mc = spec.mcVersion();
        String forgeId = forgeProfileId(spec); // "1.20.1-forge-47.4.13"

        Path mcDir = versionsDir.resolve(mc);
        Path forgeDir = versionsDir.resolve(forgeId);

        Files.createDirectories(mcDir);
        Files.createDirectories(forgeDir);

        // 1) Vanilla JSON aus Root -> versions/<mc>/<mc>.json
        moveIfExists(gameDir.resolve(mc + ".json"), mcDir.resolve(mc + ".json"), log);

        // 2) Vanilla JAR: client.jar MUSS im Root bleiben (OLL FLOW_UPDATER_1_19_SUP)
        //    Wir legen NUR eine Kopie als versions/<mc>/<mc>.jar an (falls noch nicht vorhanden)
        Path clientJar = gameDir.resolve("client.jar");
        Path mcJar = mcDir.resolve(mc + ".jar");

        if (Files.exists(clientJar) && !Files.exists(mcJar)) {
            Files.copy(clientJar, mcJar, StandardCopyOption.REPLACE_EXISTING);
            log.accept("Versions-Fix: client.jar copy -> " + mcJar);
        }

        // 3) Forge JSON aus Root -> versions/<forgeId>/<forgeId>.json
        moveIfExists(gameDir.resolve(forgeId + ".json"), forgeDir.resolve(forgeId + ".json"), log);

        // 4) Forge JAR sicherstellen (für OLL ist oft irgendeine jar im Forge-Ordner nötig)
        Path forgeJar = forgeDir.resolve(forgeId + ".jar");
        if (!Files.exists(forgeJar) && Files.exists(mcJar)) {
            Files.copy(mcJar, forgeJar, StandardCopyOption.REPLACE_EXISTING);
            log.accept("Versions-Fix: Forge jar erzeugt (copy) -> " + forgeJar);
        }
    }


    private static void moveIfExists(Path from, Path to, Consumer<String> log) throws IOException {
        if (!Files.exists(from)) return;
        Files.createDirectories(to.getParent());
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        log.accept("Versions-Fix: " + from.getFileName() + " -> " + to);
    }


    public static String forgeProfileId(InstallSpec spec) {
        // "1.20.1-47.4.13" -> "1.20.1-forge-47.4.13"
        String[] parts = spec.forgeVersionFull().split("-", 2);
        if (parts.length != 2) return spec.mcVersion();
        return parts[0] + "-forge-" + parts[1];
    }

}
