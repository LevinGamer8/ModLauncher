package de.levingamer8.modlauncher.mc;

import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;

public final class FabricInstaller {

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final Gson gson = new Gson();

    public record LatestFabric(String loaderVersion) {}

    /**
     * Holt den neuesten stabilen Fabric Loader für eine MC-Version.
     * Quelle: meta.fabricmc.net
     */
    public LatestFabric fetchLatestLoaderForMc(String mcVersion) throws Exception {
        // v2/versions/loader/<mc> -> Liste mit loader+intermediary+launcherMeta
        String url = "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion;

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET().build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("Fabric meta HTTP " + resp.statusCode() + ": " + url);

        JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
        if (arr.isEmpty()) throw new IllegalStateException("Fabric meta: keine Loader-Versionen für " + mcVersion);

        // In der Praxis ist das erste Element die neueste.
        JsonObject first = arr.get(0).getAsJsonObject();
        JsonObject loader = first.getAsJsonObject("loader");
        if (loader == null || !loader.has("version")) throw new IllegalStateException("Fabric meta: loader.version fehlt");

        return new LatestFabric(loader.get("version").getAsString());
    }

    public String ensureFabricVersion(Path sharedRoot, String mcVersion, String loaderVersion) throws Exception {
        String id = "fabric-loader-" + loaderVersion + "-" + mcVersion;

        Path vDir = sharedRoot.resolve("versions").resolve(id);
        Path vJson = vDir.resolve(id + ".json");
        Files.createDirectories(vDir);

        // Wenn vorhanden, prüfen ob jar + libraries ok sind
        if (Files.exists(vJson)) {
            JsonObject existing = JsonParser.parseString(Files.readString(vJson)).getAsJsonObject();
            boolean okJar = existing.has("jar") && mcVersion.equals(existing.get("jar").getAsString());
            boolean okInh = existing.has("inheritsFrom") && mcVersion.equals(existing.get("inheritsFrom").getAsString());
            boolean okLibs = existing.has("libraries") && existing.getAsJsonArray("libraries").size() > 0;
            boolean okMain = existing.has("mainClass");

            if (okJar && okInh && okLibs && okMain) return id;

            // kaputt/alt -> neu erzeugen
            Files.delete(vJson);
        }

        String url = "https://meta.fabricmc.net/v2/versions/loader/" + enc(mcVersion) + "/" + enc(loaderVersion);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("Fabric meta HTTP " + resp.statusCode());

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonObject launcherMeta = root.getAsJsonObject("launcherMeta");
        if (launcherMeta == null) throw new IllegalStateException("Fabric meta: launcherMeta fehlt");

        String clientMain = launcherMeta.getAsJsonObject("mainClass").get("client").getAsString();

        JsonObject libsObj = launcherMeta.getAsJsonObject("libraries");
        if (libsObj == null) throw new IllegalStateException("Fabric meta: launcherMeta.libraries fehlt");

// common + client zusammenführen
        JsonArray libsCommon = libsObj.has("common") ? libsObj.getAsJsonArray("common") : new JsonArray();
        JsonArray libsClient = libsObj.has("client") ? libsObj.getAsJsonArray("client") : new JsonArray();

        JsonArray libsAll = new JsonArray();
        for (var e : libsCommon) libsAll.add(e);
        for (var e : libsClient) libsAll.add(e);

        if (libsAll.isEmpty()) {
            throw new IllegalStateException("Fabric meta: libraries.common+client leer");
        }

        // Zusätzlich garantieren: fabric-loader + intermediary drin
        ensureLibPresent(libsAll, "net.fabricmc:fabric-loader:" + loaderVersion, "https://maven.fabricmc.net/");
        ensureLibPresent(libsAll, "net.fabricmc:intermediary:" + mcVersion, "https://maven.fabricmc.net/");


// Wrapper JSON
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("id", id);
        wrapper.addProperty("inheritsFrom", mcVersion);
        wrapper.addProperty("jar", mcVersion);
        wrapper.addProperty("type", "release");
        wrapper.addProperty("mainClass", clientMain);
        wrapper.add("libraries", libsAll);


        Files.writeString(vJson, gson.toJson(wrapper), StandardCharsets.UTF_8);
        return id;
    }


    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void ensureLibPresent(JsonArray libs, String name, String url) {
        for (JsonElement e : libs) {
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("name") && name.equals(o.get("name").getAsString())) return;
            }
        }
        JsonObject lib = new JsonObject();
        lib.addProperty("name", name);
        lib.addProperty("url", url);
        libs.add(lib);
    }


}
