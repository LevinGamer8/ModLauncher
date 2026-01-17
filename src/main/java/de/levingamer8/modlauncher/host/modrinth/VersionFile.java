package de.levingamer8.modlauncher.host.modrinth;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;


@JsonIgnoreProperties(ignoreUnknown = true)
public record VersionFile(
        String url,
        String filename,
        boolean primary,
        Map<String, String> hashes
) {}