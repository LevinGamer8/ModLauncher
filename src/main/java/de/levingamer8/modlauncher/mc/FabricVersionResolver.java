package de.levingamer8.modlauncher.mc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public final class FabricVersionResolver {

    private static final String FABRIC_LOADER_URL =
            "https://meta.fabricmc.net/v2/versions/loader";

    /**
     * @return empfohlene Fabric Loader Version für diese MC-Version
     */
    public static String resolveLatestStable(String mcVersion) throws Exception {
        if (mcVersion == null || mcVersion.isBlank()) return "";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest req = HttpRequest.newBuilder(URI.create(FABRIC_LOADER_URL))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Fabric meta HTTP " + resp.statusCode());
        }

        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(resp.body());

        String fallback = "";

        for (JsonNode entry : root) {
            String mc = entry.get("gameVersion").asText();
            if (!mcVersion.equals(mc)) continue;

            JsonNode loader = entry.get("loader");
            String version = loader.get("version").asText();
            boolean stable = loader.get("stable").asBoolean(false);

            // bester Fall
            if (stable) return version;

            // fallback, falls es keine stabile gibt
            if (fallback.isEmpty()) fallback = version;
        }

        return fallback; // kann leer sein
    }

    private FabricVersionResolver() {}
}
