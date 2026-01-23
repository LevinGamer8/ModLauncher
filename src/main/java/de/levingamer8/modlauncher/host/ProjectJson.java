package de.levingamer8.modlauncher.host;

public record ProjectJson(
        String projectId,
        String projectName,
        String mcVersion,
        String loader,
        String loaderVersion,
        String serverIP,
        String serverPort,
        boolean allowClientMods,
        boolean onlySelectedServer
) {}