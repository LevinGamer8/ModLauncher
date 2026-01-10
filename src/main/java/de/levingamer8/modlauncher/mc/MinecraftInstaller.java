package de.levingamer8.modlauncher.mc;

import fr.flowarg.flowupdater.FlowUpdater;
import fr.flowarg.flowupdater.download.IProgressCallback;
import fr.flowarg.flowupdater.download.Step;
import fr.flowarg.flowupdater.utils.UpdaterOptions;
import fr.flowarg.flowupdater.versions.VanillaVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class MinecraftInstaller {

    public record VanillaSpec(String mcVersion) {}

    /**
     * Installiert Vanilla-Basis (assets/libraries/versions) nach sharedRoot.
     * Das ist die Grundlage für Vanilla, Fabric und Forge (inheritsFrom).
     */
    public void ensureVanillaInstalled(Path sharedRoot, String mcVersion, Consumer<String> log) throws Exception {
        Files.createDirectories(sharedRoot);

        log.accept("[INSTALL] Vanilla prüfen/installieren: " + mcVersion);
        VanillaVersion vanilla = new VanillaVersion.VanillaVersionBuilder()
                .withName(mcVersion)
                .build();

        UpdaterOptions options = new UpdaterOptions.UpdaterOptionsBuilder().build();

        IProgressCallback progress = new IProgressCallback() {
            @Override public void step(Step step) { log.accept("[FlowUpdater] Step: " + step.name()); }
            @Override public void update(fr.flowarg.flowupdater.download.DownloadList.DownloadInfo info) { /* weniger spam */ }
        };

        FlowUpdater updater = new FlowUpdater.FlowUpdaterBuilder()
                .withVanillaVersion(vanilla)
                .withUpdaterOptions(options)
                .withModLoaderVersion(null)
                .withProgressCallback(progress)
                .build();

        // gameDir von FlowUpdater = .minecraft root -> bei uns sharedRoot
        updater.update(sharedRoot);
        log.accept("[INSTALL] Vanilla ok.");
    }
}
