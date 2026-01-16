package de.levingamer8.modlauncher.mc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public final class ForgeVersionResolver {

    private static final String PROMOS_URL =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";

    public static String resolveRecommendedOrLatest(String mcVersion) throws Exception {
        if (mcVersion == null || mcVersion.isBlank()) return "";

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest req = HttpRequest.newBuilder(URI.create(PROMOS_URL))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Forge promos HTTP " + resp.statusCode());
        }

        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(resp.body());
        JsonNode promos = root.get("promos");
        if (promos == null || !promos.isObject()) return "";

        String recKey = mcVersion.trim() + "-recommended";
        String latKey = mcVersion.trim() + "-latest";

        JsonNode rec = promos.get(recKey);
        if (rec != null && rec.isTextual()) return rec.asText();

        JsonNode lat = promos.get(latKey);
        if (lat != null && lat.isTextual()) return lat.asText();

        return "";
    }

    private ForgeVersionResolver() {}
}
