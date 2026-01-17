package de.levingamer8.modlauncher.host.modrinth;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

public class ModrinthClient {

    private static final String API = "https://api.modrinth.com/v2";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Page-Suche mit Pagination.
     */
    public SearchResponse searchModsPage(String query, String loader, String mcVersion, int limit, int offset) throws Exception {
        String facetsJson = """
            [
              ["project_type:mod"],
              ["categories:%s"],
              ["versions:%s"]
            ]
            """.formatted(loader.toLowerCase(), mcVersion);

        String url = API + "/search?query=" + enc(query)
                + "&limit=" + limit
                + "&offset=" + offset
                + "&facets=" + enc(facetsJson);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "ModLauncher/1.0 (host-mode)")
                .GET()
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("Modrinth search failed: HTTP " + res.statusCode() + " - " + res.body());
        }
        return om.readValue(res.body(), SearchResponse.class);
    }

    /**
     * Convenience: alte Methode, wenn du sie noch irgendwo verwendest.
     */
    public List<SearchHit> searchMods(String query, String loader, String mcVersion, int limit) throws Exception {
        return searchModsPage(query, loader, mcVersion, limit, 0).hits();
    }

    public Version getBestVersion(String projectIdOrSlug, String loader, String mcVersion) throws Exception {
        String url = API + "/project/" + encPath(projectIdOrSlug) + "/version"
                + "?loaders=" + enc("[\"" + loader.toLowerCase() + "\"]")
                + "&game_versions=" + enc("[\"" + mcVersion + "\"]");

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "ModLauncher/1.0 (host-mode)")
                .GET()
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("Modrinth versions failed: HTTP " + res.statusCode() + " - " + res.body());
        }

        Version[] versions = om.readValue(res.body(), Version[].class);
        if (versions.length == 0) throw new RuntimeException("No compatible version found for " + loader + " " + mcVersion);

        return List.of(versions).stream()
                .sorted(Comparator.comparing(Version::version_number).reversed())
                .findFirst()
                .orElseThrow();
    }

    public Path downloadPrimaryJar(Version v, Path modsDir) throws Exception {
        VersionFile f = v.files().stream().filter(VersionFile::primary).findFirst()
                .orElse(v.files().get(0));

        Files.createDirectories(modsDir);
        Path target = modsDir.resolve(f.filename());
        Path tmp = target.resolveSibling(f.filename() + ".tmp");

        HttpRequest req = HttpRequest.newBuilder(URI.create(f.url()))
                .header("User-Agent", "ModLauncher/1.0 (host-mode)")
                .GET()
                .build();

        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new RuntimeException("Download failed: HTTP " + res.statusCode());
        }

        try (InputStream in = res.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }

        return target;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String encPath(String s) {
        return s.replace(" ", "%20");
    }
}
