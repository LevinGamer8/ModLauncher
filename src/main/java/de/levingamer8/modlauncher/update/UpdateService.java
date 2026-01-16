package de.levingamer8.modlauncher.update;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;

public final class UpdateService {
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String owner;
    private final String repo;

    public UpdateService(String owner, String repo) {
        this.owner = owner;
        this.repo = repo;
    }

    public UpdateInfo fetchLatest() throws IOException, InterruptedException {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("GitHub API error: " + res.statusCode());

        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
        String tag = root.get("tag_name").getAsString();          // e.g. v1.0.1
        String version = tag.startsWith("v") ? tag.substring(1) : tag;

        String msiUrl = null;
        JsonArray assets = root.getAsJsonArray("assets");
        for (JsonElement e : assets) {
            JsonObject a = e.getAsJsonObject();
            String name = a.get("name").getAsString().toLowerCase();
            if (name.endsWith(".msi")) {
                msiUrl = a.get("browser_download_url").getAsString();
                break;
            }
        }
        return new UpdateInfo(version, msiUrl);
    }

    public Path downloadToTemp(String url, String fileName) throws IOException, InterruptedException {
        Path target = Paths.get(System.getProperty("java.io.tmpdir")).resolve(fileName);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("Download failed: " + res.statusCode());

        try (InputStream in = res.body();
             OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(out);
        }
        return target;
    }

    public static String currentVersionOrZero() {
        String v = UpdateService.class.getPackage() != null ? UpdateService.class.getPackage().getImplementationVersion() : null;
        return (v == null || v.isBlank()) ? "0.0.0" : v;
    }
}
