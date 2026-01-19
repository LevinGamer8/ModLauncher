package de.levingamer8.modlauncher.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.levingamer8.modlauncher.core.ManifestModels;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ManifestService {

    private final ObjectMapper mapper = new ObjectMapper();

    public ManifestModels.Manifest loadAndValidate(String url) throws Exception {
        if (url == null || url.isBlank())
            throw new IllegalArgumentException("Manifest-URL leer");

        // latest.json automatisch auflösen
        if (!url.endsWith(".json"))
            url = url.endsWith("/") ? url + "latest.json" : url + "/latest.json";

        ManifestModels.Manifest manifest = fetch(url);

        validate(manifest);

        return manifest;
    }

    private ManifestModels.Manifest fetch(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);
        con.setRequestMethod("GET");

        if (con.getResponseCode() != 200)
            throw new IllegalStateException("Manifest HTTP " + con.getResponseCode());

        try (InputStream in = con.getInputStream()) {
            return mapper.readValue(in, ManifestModels.Manifest.class);
        }
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
