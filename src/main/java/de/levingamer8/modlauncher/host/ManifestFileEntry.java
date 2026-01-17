package de.levingamer8.modlauncher.host;

import de.levingamer8.modlauncher.core.ManifestModels;

public record ManifestFileEntry(
        String path,
        String sha256,
        Long size,
        ManifestModels.Download download,
        String side
) {}
