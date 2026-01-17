package de.levingamer8.modlauncher.mc;

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class LibraryService {

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final MojangDownloader mojang = new MojangDownloader();

    public List<Path> ensureClasspath(Path sharedRoot, JsonObject versionJson) throws Exception {
        List<Path> cp = new ArrayList<>();

        String id = versionJson.get("id").getAsString();

// Basis-Version bestimmen (Vanilla), niemals Wrapper
        String baseId = null;

        if (versionJson.has("inheritsFrom")) baseId = versionJson.get("inheritsFrom").getAsString();
        if ((baseId == null || baseId.isBlank()) && versionJson.has("jar")) baseId = versionJson.get("jar").getAsString();

        if (baseId == null || baseId.isBlank()) {
            // Fallback: aus "1.20.1-forge-47.4.10" -> "1.20.1"
            int idx = id.indexOf("-forge-");
            if (idx > 0) baseId = id.substring(0, idx);

            // Fabric: "fabric-loader-0.xx-1.20.1" -> base aus inheritsFrom kommt normalerweise,
            // aber falls nicht: letzte "-" getrennte Komponente
            if (baseId == null && id.startsWith("fabric-loader-")) {
                int lastDash = id.lastIndexOf('-');
                if (lastDash > 0 && lastDash + 1 < id.length()) baseId = id.substring(lastDash + 1);
            }
        }

        if (baseId == null || baseId.isBlank()) {
            throw new IllegalStateException("Kann baseId nicht bestimmen für: " + id + " (kein inheritsFrom/jar und keine ableitbare base)");
        }

// Sicherstellen, dass Vanilla-JAR existiert
        mojang.ensureClientJar(sharedRoot, baseId);

        Path vJar = sharedRoot.resolve("versions").resolve(baseId).resolve(baseId + ".jar");
        if (!Files.exists(vJar)) throw new IllegalStateException("Vanilla Version JAR fehlt: " + vJar);


        // libraries (artifact only)
        JsonArray libs = versionJson.getAsJsonArray("libraries");
        if (libs != null) {
            for (JsonElement el : libs) {
                JsonObject lib = el.getAsJsonObject();
                if (!allowedOnWindows(lib)) continue;
                if (!lib.has("name")) continue;

                String baseUrl = lib.has("url") ? lib.get("url").getAsString() : null;

                if (baseUrl == null || baseUrl.isBlank()) {
                    String name = lib.has("name") ? lib.get("name").getAsString() : "";
                    String group = name.contains(":") ? name.substring(0, name.indexOf(':')) : "";

                    // Heuristik: Forge-Ökosystem + MCP + net.minecraftVersion (srg/extra) -> Forge Maven
                    if (group.startsWith("net.minecraftforge")
                            || group.startsWith("cpw.mods")
                            || group.startsWith("de.oceanlabs.mcp")
                            || group.startsWith("net.minecraftVersion")) {
                        baseUrl = "https://maven.minecraftforge.net/";
                    } else {
                        baseUrl = "https://libraries.minecraft.net/";
                    }
                }

                if (!baseUrl.endsWith("/")) baseUrl += "/";



                // Artifact
                Path outJar = resolveArtifactJar(sharedRoot, lib, baseUrl);
                if (outJar != null) cp.add(outJar);
            }
        }

        // vanilla jar LAST
        cp.add(vJar);
        return cp;
    }

    public Path extractNativesX64(Path nativesDir, Path sharedRoot, JsonObject versionJson) throws Exception {
        Files.createDirectories(nativesDir);

        JsonArray libs = versionJson.getAsJsonArray("libraries");
        if (libs == null) return nativesDir;

        for (JsonElement el : libs) {
            JsonObject lib = el.getAsJsonObject();
            if (!allowedOnWindows(lib)) continue;

            if (!lib.has("natives")) continue;
            JsonObject natives = lib.getAsJsonObject("natives");

            // wir nehmen x64: natives-windows (nicht x86/arm64)
            if (!natives.has("windows")) continue;
            String classifierKey = natives.get("windows").getAsString(); // meistens "natives-windows"

            String baseUrl = lib.has("url") ? lib.get("url").getAsString() : "https://libraries.minecraft.net/";
            if (!baseUrl.endsWith("/")) baseUrl += "/";


            Path nativesJar = resolveClassifierJar(sharedRoot, lib, baseUrl, classifierKey);
            if (nativesJar == null) continue;

            extractJar(nativesJar, nativesDir, lib);
        }

        return nativesDir;
    }

    private Path resolveArtifactJar(Path sharedRoot, JsonObject lib, String baseUrl) throws Exception {
        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
            JsonObject art = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
            String path = art.get("path").getAsString();
            String url = art.has("url") ? art.get("url").getAsString() : (baseUrl + path);

            Path out = sharedRoot.resolve("libraries").resolve(path);
            Files.createDirectories(out.getParent());
            if (!Files.exists(out)) downloadTo(url, out);
            return out;
        }

        // fallback: maven path
        String name = lib.get("name").getAsString();
        String rel = mavenPathFromCoord(name);
        Path out = sharedRoot.resolve("libraries").resolve(rel);
        Files.createDirectories(out.getParent());
        if (!Files.exists(out)) downloadTo(baseUrl + rel, out);
        return out;
    }

    private Path resolveClassifierJar(Path sharedRoot, JsonObject lib, String baseUrl, String classifierKey) throws Exception {
        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("classifiers")) {
            JsonObject cls = lib.getAsJsonObject("downloads").getAsJsonObject("classifiers");
            if (!cls.has(classifierKey)) return null;

            JsonObject nat = cls.getAsJsonObject(classifierKey);
            String path = nat.get("path").getAsString();
            String url = nat.has("url") ? nat.get("url").getAsString() : (baseUrl + path);

            Path out = sharedRoot.resolve("libraries").resolve(path);
            Files.createDirectories(out.getParent());
            if (!Files.exists(out)) downloadTo(url, out);
            return out;
        }

        // fallback: maven coord + classifier
        String name = lib.get("name").getAsString();
        String[] p = name.split(":");
        if (p.length < 3) return null;

        String coordWithClassifier = p[0] + ":" + p[1] + ":" + p[2] + ":" + classifierKey;
        String rel = mavenPathFromCoord(coordWithClassifier);

        Path out = sharedRoot.resolve("libraries").resolve(rel);
        Files.createDirectories(out.getParent());
        if (!Files.exists(out)) downloadTo(baseUrl + rel, out);
        return out;
    }

    private void extractJar(Path jar, Path outDir, JsonObject lib) throws Exception {
        Set<String> excludes = new HashSet<>();
        if (lib.has("extract") && lib.getAsJsonObject("extract").has("exclude")) {
            for (JsonElement e : lib.getAsJsonObject("extract").getAsJsonArray("exclude")) {
                excludes.add(e.getAsString());
            }
        }

        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry je = en.nextElement();
                if (je.isDirectory()) continue;

                String name = je.getName();
                if (shouldExclude(name, excludes)) continue;

                Path out = outDir.resolve(name).normalize();
                if (!out.startsWith(outDir)) throw new IllegalStateException("ZipSlip natives: " + name);

                Files.createDirectories(out.getParent());
                try (InputStream in = jf.getInputStream(je)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private boolean shouldExclude(String entry, Set<String> excludes) {
        for (String ex : excludes) {
            if (entry.startsWith(ex)) return true;
        }
        return false;
    }

    private boolean allowedOnWindows(JsonObject lib) {
        if (!lib.has("rules")) return true;

        boolean allowed = false;
        for (JsonElement e : lib.getAsJsonArray("rules")) {
            JsonObject r = e.getAsJsonObject();
            String action = r.get("action").getAsString();
            boolean osMatch = true;
            if (r.has("os")) {
                JsonObject os = r.getAsJsonObject("os");
                if (os.has("name")) osMatch = "windows".equalsIgnoreCase(os.get("name").getAsString());
            }
            if (osMatch) allowed = "allow".equalsIgnoreCase(action);
        }
        return allowed;
    }

    public static String mavenPathFromCoord(String coord) {
        String ext = "jar";
        String c = coord;

        int at = c.lastIndexOf('@');
        if (at >= 0) {
            ext = c.substring(at + 1).trim();
            c = c.substring(0, at);
            if (ext.isEmpty()) ext = "jar";
        }

        String[] p = c.split(":");
        if (p.length < 3) throw new IllegalArgumentException("Ungültige maven coord: " + coord);

        String group = p[0];
        String artifact = p[1];
        String version = p[2];
        String classifier = (p.length >= 4) ? p[3] : null;

        String base = group.replace('.', '/') + "/" + artifact + "/" + version + "/";
        String file = artifact + "-" + version + (classifier != null ? "-" + classifier : "") + "." + ext;
        return base + file;
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
}
