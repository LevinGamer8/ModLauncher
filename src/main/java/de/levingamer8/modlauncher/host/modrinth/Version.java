package de.levingamer8.modlauncher.host.modrinth;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;


@JsonIgnoreProperties(ignoreUnknown = true)
public record Version(
        String id,
        String name,
        String version_number,
        List<String> game_versions,
        List<String> loaders,
        List<VersionFile> files
) {}