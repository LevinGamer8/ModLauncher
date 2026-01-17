package de.levingamer8.modlauncher.host;

import java.nio.file.Path;

public record HostProjectPaths(
        Path projectRoot,
        Path latestJson,
        Path manifestJson,
        Path filesDir
) {}
