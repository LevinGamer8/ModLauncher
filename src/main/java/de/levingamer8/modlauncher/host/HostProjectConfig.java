package de.levingamer8.modlauncher.host;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.levingamer8.modlauncher.core.LoaderType;
import de.levingamer8.modlauncher.core.ManifestModels;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HostProjectConfig(
        String projectId,
        String projectName,
        String mcVersion,
        ManifestModels.Loader loader,
        String serverIP,
        int serverPort,
        boolean allowClientMods,
        boolean onlySelectedServer
) {}
