package de.levingamer8.modlauncher.mc;

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ForgeInstaller1122Plus {

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public String ensureForgeVersion(Path sharedRoot, String mcVersion, String forgeVersion) throws Exception {
        Files.createDirectories(sharedRoot.resolve("versions"));
        Files.createDirectories(sharedRoot.resolve("libraries"));

        String coord = mcVersion + "-" + forgeVersion;
        String installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                + coord + "/forge-" + coord + "-installer.jar";

        Path cacheDir = sharedRoot.resolve("installer-cache");
        Files.createDirectories(cacheDir);
        Path installerJar = cacheDir.resolve("forge-" + coord + "-installer.jar");

        downloadIfMissing(installerUrl, installerJar);

        JsonObject profile = readInstallProfile(installerJar);

        // ---- Path A: versionInfo vorhanden (best case) ----
        if (profile.has("versionInfo")) {
            JsonObject versionInfo = profile.getAsJsonObject("versionInfo");
            String forgeId = reqStr(versionInfo, "id");

            if (!versionInfo.has("inheritsFrom")) versionInfo.addProperty("inheritsFrom", mcVersion);
            if (!versionInfo.has("jar")) versionInfo.addProperty("jar", mcVersion);

            Path vDir = sharedRoot.resolve("versions").resolve(forgeId);
            Files.createDirectories(vDir);
            Files.writeString(vDir.resolve(forgeId + ".json"), gson.toJson(versionInfo), StandardCharsets.UTF_8);

            // libs installieren
            if (versionInfo.has("libraries")) {
                installLibraries(sharedRoot, versionInfo.getAsJsonArray("libraries"));
            }

            return forgeId;
        }

        // ---- Path B: Fallback wrapper (kein versionInfo) ----
        if (!profile.has("install") || !profile.has("libraries")) {
            throw new IllegalStateException("Forge install_profile hat kein versionInfo und kein install+libraries. Keys=" + profile.keySet());
        }

        JsonObject install = profile.getAsJsonObject("install");

        String forgeId =
                install.has("target") ? install.get("target").getAsString()
                        : install.has("profileName") ? install.get("profileName").getAsString()
                        : ("forge-" + coord);

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("id", forgeId);
        wrapper.addProperty("inheritsFrom", mcVersion);
        wrapper.addProperty("jar", mcVersion);

        // mainClass: meistens LaunchWrapper in 1.12.2
        String mainClass =
                install.has("mainClass") ? install.get("mainClass").getAsString()
                        : profile.has("mainClass") ? profile.get("mainClass").getAsString()
                        : "net.minecraft.launchwrapper.Launch";

        wrapper.addProperty("mainClass", mainClass);

        // legacy args wenn vorhanden
        if (install.has("minecraftArguments")) {
            wrapper.add("minecraftArguments", install.get("minecraftArguments"));
        }

        JsonArray libs = profile.getAsJsonArray("libraries");
        wrapper.add("libraries", libs);

        Path vDir = sharedRoot.resolve("versions").resolve(forgeId);
        Files.createDirectories(vDir);
        Files.writeString(vDir.resolve(forgeId + ".json"), gson.toJson(wrapper), StandardCharsets.UTF_8);

        installLibraries(sharedRoot, libs);

        return forgeId;
    }

    private void installLibraries(Path sharedRoot, JsonArray libs) throws Exception {
        for (JsonElement el : libs) {
            JsonObject lib = el.getAsJsonObject();
            if (!lib.has("name")) continue;

            String name = lib.get("name").getAsString();
            String baseUrl = lib.has("url") ? lib.get("url").getAsString() : "https://libraries.minecraft.net/";

            // modern
            if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
                JsonObject art = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
                String path = reqStr(art, "path");
                String url = art.has("url") ? art.get("url").getAsString() : (baseUrl + path);

                Path out = sharedRoot.resolve("libraries").resolve(path);
                Files.createDirectories(out.getParent());
                if (!Files.exists(out)) downloadTo(url, out);
            } else {
                String rel = mavenPathFromCoord(name);
                Path out = sharedRoot.resolve("libraries").resolve(rel);
                Files.createDirectories(out.getParent());
                if (!Files.exists(out)) downloadTo(baseUrl + rel, out);
            }
        }
    }

    private JsonObject readInstallProfile(Path installerJar) throws Exception {
        try (ZipFile zf = new ZipFile(installerJar.toFile())) {
            ZipEntry entry = zf.getEntry("install_profile.json");
            if (entry == null) throw new IllegalStateException("Forge installer hat kein install_profile.json");
            try (InputStream in = zf.getInputStream(entry);
                 Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(r).getAsJsonObject();
            }
        }
    }

    private void downloadIfMissing(String url, Path out) throws Exception {
        if (Files.exists(out) && Files.size(out) > 0) return;
        Files.createDirectories(out.getParent());
        downloadTo(url, out);
    }

    private void downloadTo(String url, Path out) throws Exception {
        Path tmp = out.resolveSibling(out.getFileName().toString() + ".part");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET().build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new IOException("Download HTTP " + resp.statusCode() + ": " + url);

        try (InputStream in = resp.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String reqStr(JsonObject o, String key) {
        if (!o.has(key)) throw new IllegalStateException("Fehlt Feld: " + key);
        return o.get(key).getAsString();
    }

    public static String mavenPathFromCoord(String coord) {
        String[] p = coord.split(":");
        if (p.length < 3) throw new IllegalArgumentException("Ungültige maven coord: " + coord);
        String group = p[0], artifact = p[1], version = p[2];
        String classifier = (p.length >= 4) ? p[3] : null;

        String base = group.replace('.', '/') + "/" + artifact + "/" + version + "/";
        String file = artifact + "-" + version + (classifier != null ? "-" + classifier : "") + ".jar";
        return base + file;
    }
}
