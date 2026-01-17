package de.levingamer8.modlauncher.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

public class ManifestModels {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Manifest(
            String packId,
            String packName,
            int packVersion,
            String minecraftVersion,
            Loader loader,
            String baseUrl,
            List<ManifestFile> files,
            Overrides overrides,
            String generatedAt
    ) {}


    public record Loader(String type, String version) {}

    public record Overrides(String url, String sha256) {}

    public record ManifestFile(
            String path,
            String sha256,
            long size,
            String side,          // client | server | both
            Download download
    ) {}

    public record Download(String url) {}

}
