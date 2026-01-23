package de.levingamer8.modlauncher.core;

import com.fasterxml.jackson.annotation.JsonCreator;
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
            String generatedAt,
            String changelogUrl
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Loader(String type, String version) {

        // Wichtig: akzeptiert auch alten Style "loader": "FORGE"
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Loader fromString(String type) {
            return new Loader(type, "");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Overrides(String url, String sha256) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ManifestFile(
            String path,
            String sha256,
            long size,
            String side,
            Download download
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Download(String url) {}
}
