package de.levingamer8.modlauncher.host;

import de.levingamer8.modlauncher.core.LoaderType;

import java.util.List;

public record HostManifest(
        String projectId,
        String name,
        String mcVersion,
        LoaderType loader,
        String loaderVersion,   // <- das fehlte
        String version,
        String generatedAt,     // ISO-8601 String (einfach & kompatibel)
        String baseUrl,         // .../versions/<ver>/files/
        List<ManifestFileEntry> files
) {}
