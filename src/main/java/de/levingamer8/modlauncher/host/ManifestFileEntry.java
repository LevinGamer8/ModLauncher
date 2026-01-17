package de.levingamer8.modlauncher.host;

public record ManifestFileEntry(
        String path,      // z.B. "mods/sodium.jar"
        String sha256,    // hex
        long size         // bytes
) {}
