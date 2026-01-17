package de.levingamer8.modlauncher.host;

public record LatestPointer(
        String projectId,
        String version,
        String manifestUrl
) {}
