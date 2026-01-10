package de.levingamer8.modlauncher.mc;

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;

public final class MojangDownloader {

    private static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public Path ensureVersionJson(Path sharedRoot, String versionId) throws Exception {
        Path vDir = sharedRoot.resolve("versions").resolve(versionId);
        Path vJson = vDir.resolve(versionId + ".json");
        if (Files.exists(vJson)) return vJson;

        Files.createDirectories(vDir);

        JsonObject manifest = getJson(MANIFEST_URL);
        JsonArray versions = manifest.getAsJsonArray("versions");

        String versionUrl = null;
        for (JsonElement e : versions) {
            JsonObject v = e.getAsJsonObject();
            if (versionId.equals(v.get("id").getAsString())) {
                versionUrl = v.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) throw new IllegalStateException("MC Version nicht gefunden: " + versionId);

        JsonObject versionJson = getJson(versionUrl);
        Files.writeString(vJson, gson.toJson(versionJson), StandardCharsets.UTF_8);
        return vJson;
    }

    public Path ensureClientJar(Path sharedRoot, String versionId) throws Exception {
        Path vDir = sharedRoot.resolve("versions").resolve(versionId);
        Path vJar = vDir.resolve(versionId + ".jar");
        if (Files.exists(vJar) && Files.size(vJar) > 0) return vJar;

        Path vJsonPath = ensureVersionJson(sharedRoot, versionId);
        JsonObject vJson = JsonParser.parseString(Files.readString(vJsonPath)).getAsJsonObject();

        JsonObject downloads = vJson.getAsJsonObject("downloads");
        if (downloads == null || !downloads.has("client")) {
            throw new IllegalStateException("downloads.client fehlt in " + versionId);
        }
        String url = downloads.getAsJsonObject("client").get("url").getAsString();

        Files.createDirectories(vDir);
        downloadTo(url, vJar);
        return vJar;
    }

    private JsonObject getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " für " + url);
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private void downloadTo(String url, Path out) throws Exception {
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName().toString() + ".part");

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " für " + url);

        try (InputStream in = resp.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
