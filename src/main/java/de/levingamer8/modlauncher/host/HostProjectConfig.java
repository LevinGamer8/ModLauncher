package de.levingamer8.modlauncher.host;

import de.levingamer8.modlauncher.core.LoaderType;

public record HostProjectConfig(
        String projectId,
        String name,
        String mcVersion,
        LoaderType loader,
        String loaderVersion,
        boolean allowClientMods // TODO: später sauber regeln (optional mods, whitelist, etc.)
) {}
