package de.levingamer8.modlauncher.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.levingamer8.modlauncher.core.ManifestModels;
import de.levingamer8.modlauncher.core.ProtocolFetcher;

public class ManifestService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolFetcher fetcher = new ProtocolFetcher();

    public ManifestModels.Manifest loadAndValidate(String url) throws Exception {
        if (url == null || url.isBlank())
            throw new IllegalArgumentException("Manifest-URL leer");

        // If no file extension, auto-append latest.json
        if (!url.endsWith(".json") && !url.endsWith(".yml") && !url.endsWith(".yaml"))
            url = url.endsWith("/") ? url + "latest.json" : url + "/latest.json";

        ManifestModels.Manifest manifest = fetch(url);
        validate(manifest);
        return manifest;
    }

    private ManifestModels.Manifest fetch(String url) throws Exception {
        String body = fetcher.getText(url);
        return mapper.readValue(body, ManifestModels.Manifest.class);
    }

    private void validate(ManifestModels.Manifest m) {
        if (m.minecraftVersion() == null || m.minecraftVersion().isBlank())
            throw new IllegalStateException("minecraftVersion fehlt");

        if (m.loader() == null || m.loader().type() == null || m.loader().type().isBlank())
            throw new IllegalStateException("loader.type fehlt");

        if (m.packName() == null || m.packName().isBlank())
            throw new IllegalStateException("packName fehlt");
    }
}
