package de.levingamer8.modlauncher.host;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VersionsIndex(
        @JsonAlias({"latest", "version"}) String latest,
        List<VersionEntry> versions
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VersionEntry(String version, String manifestUrl) {}

    public String latestVersion() {
        if (latest != null && !latest.isBlank()) return latest;
        if (versions == null || versions.isEmpty()) return null;
        return versions.get(versions.size() - 1).version();
    }

    public String latestManifestUrl() {
        if (versions == null || versions.isEmpty()) return null;

        String lv = latestVersion();
        if (lv != null) {
            for (VersionEntry e : versions) {
                if (lv.equalsIgnoreCase(e.version())) return e.manifestUrl();
            }
        }
        return versions.get(versions.size() - 1).manifestUrl();
    }
}
