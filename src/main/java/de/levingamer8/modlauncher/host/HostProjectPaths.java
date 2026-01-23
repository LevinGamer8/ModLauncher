package de.levingamer8.modlauncher.host;

import java.nio.file.Path;

public record HostProjectPaths(
        Path projectRoot,
        Path projectJson,
        Path versionsJson,
        Path versionRoot,
        Path manifestJson,
        Path filesDir
) {}
