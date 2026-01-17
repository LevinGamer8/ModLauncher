package de.levingamer8.modlauncher.host;

import de.levingamer8.modlauncher.core.LoaderType;

import java.nio.file.Path;

public record CreateHostProjectRequest(
        String projectId,
        String name,
        String mcVersion,
        LoaderType loader,
        String loaderVersion,   // <- neu
        String initialVersion,  // z.B. "1.0.0"
        String baseUrl,         // z.B. "https://mc.local/testpack/"
        Path outputFolder
) {}
