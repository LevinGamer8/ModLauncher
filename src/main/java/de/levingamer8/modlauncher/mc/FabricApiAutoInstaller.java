package de.levingamer8.modlauncher.mc;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class FabricApiAutoInstaller {

    private static final String PROJECT_SLUG = "fabric-api";
    private static final String MODRINTH_API = "https://api.modrinth.com/v2";
    private static final String MODRINTH_UA = "modlauncher/1.0 (fabric-api-auto)";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final Gson gson = new Gson();

    public record Result(Path jarPath, String fabricApiVersion, String requiredLoaderMinOrNull) {}

    /**
     * Installiert Fabric API kompatibel zur Loader-Version.
     * @param loaderVersion z.B. "0.15.11"
     */
    public Result ensureFabricApiInstalled(Path instanceGameDir, String mcVersion, String loaderVersion, Consumer<String> log) throws Exception {
        Path modsDir = instanceGameDir.resolve("mods");
        Files.createDirectories(modsDir);

        Path existing = findExistingFabricApiJar(modsDir);
        if (existing != null) {
            if (log != null) log.accept("[FABRIC-API] Bereits installiert: " + existing.getFileName());
            // keine Aussage über required loader, weil existing evtl alt/neu ist
            return new Result(existing, "installed", null);
        }

        if (log != null) log.accept("[FABRIC-API] Fehlt -> suche kompatible Fabric API für MC " + mcVersion + " (Loader " + loaderVersion + ")...");

        List<ModrinthVersion> candidates = fetchFabricApiVersions(mcVersion);
        if (candidates.isEmpty()) throw new IllegalStateException("Fabric API: Keine Versionen auf Modrinth für " + mcVersion);

        // 1) Versuche: Kandidat der zu loaderVersion passt
        ModrinthVersion bestCompatible = null;
        String bestCompatibleReq = null;

        for (ModrinthVersion v : candidates) {
            // Download in temp? nein -> wir nutzen dependency info aus Modrinth nicht zuverlässig,
            // deshalb: lade jar in temp, lies fabric.mod.json, check depends.fabricloader
            Path tmpJar = downloadToTemp(v);
            try {
                String reqMin = readRequiredFabricLoaderMin(tmpJar); // kann null sein
                if (reqMin == null || versionGte(loaderVersion, reqMin)) {
                    bestCompatible = v;
                    bestCompatibleReq = reqMin;
                    // candidates sind newest-first -> erstes passendes ist best
                    break;
                }
            } finally {
                try { Files.deleteIfExists(tmpJar); } catch (Exception ignored) {}
            }
        }

        if (bestCompatible == null) {
            // Keine kompatible gefunden -> nimm neueste und sag welche loaderMin gebraucht wird
            ModrinthVersion newest = candidates.getFirst();
            Path tmpJar = downloadToTemp(newest);
            String reqMin;
            try {
                reqMin = readRequiredFabricLoaderMin(tmpJar);
            } finally {
                try { Files.deleteIfExists(tmpJar); } catch (Exception ignored) {}
            }
            if (log != null) log.accept("[FABRIC-API] Keine kompatible Fabric API für Loader " + loaderVersion + " gefunden. Neueste benötigt Loader >= " + reqMin);
            // installiere trotzdem nicht blind -> gib required zurück, damit caller loader upgraden kann
            return new Result(null, null, reqMin);
        }

        // Installiere bestCompatible
        String apiVersion = bestCompatible.versionNumber != null ? bestCompatible.versionNumber : bestCompatible.id;
        String safeVersion = sanitizeFilePart(apiVersion);

        deleteAnyFabricApiJars(modsDir, log);

        Path out = modsDir.resolve("fabric-api-" + safeVersion + ".jar");
        if (log != null) log.accept("[FABRIC-API] Installiere kompatible Version: " + apiVersion + (bestCompatibleReq != null ? " (req loader >= " + bestCompatibleReq + ")" : ""));
        downloadTo(bestCompatible.fileUrl, out);

        return new Result(out, apiVersion, bestCompatibleReq);
    }

    // ------------------ Modrinth ------------------

    private List<ModrinthVersion> fetchFabricApiVersions(String mcVersion) throws Exception {
        String url = MODRINTH_API + "/project/" + PROJECT_SLUG + "/version"
                + "?loaders=" + urlEncJsonArray("fabric")
                + "&game_versions=" + urlEncJsonArray(mcVersion);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", MODRINTH_UA)
                .GET().build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("Modrinth HTTP " + resp.statusCode());

        JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
        List<ModrinthVersion> out = new ArrayList<>();
        for (JsonElement e : arr) {
            JsonObject v = e.getAsJsonObject();
            String id = optStr(v, "id");
            String versionNumber = optStr(v, "version_number");

            String fileUrl = null;
            String fileName = null;

            JsonArray files = v.getAsJsonArray("files");
            if (files != null) {
                for (JsonElement fe : files) {
                    JsonObject f = fe.getAsJsonObject();
                    boolean primary = f.has("primary") && f.get("primary").getAsBoolean();
                    if (primary) {
                        fileUrl = optStr(f, "url");
                        fileName = optStr(f, "filename");
                        break;
                    }
                }
                if (fileUrl == null && !files.isEmpty()) {
                    JsonObject f0 = files.get(0).getAsJsonObject();
                    fileUrl = optStr(f0, "url");
                    fileName = optStr(f0, "filename");
                }
            }
            if (fileUrl != null) out.add(new ModrinthVersion(id, versionNumber, fileUrl, fileName));
        }
        return out; // Modrinth: newest-first
    }

    private Path downloadToTemp(ModrinthVersion v) throws Exception {
        Path tmp = Files.createTempFile("fabric-api-", ".jar");
        downloadTo(v.fileUrl, tmp);
        return tmp;
    }

    private void downloadTo(String url, Path out) throws Exception {
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName().toString() + ".part");

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", MODRINTH_UA)
                .GET().build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new IOException("Download HTTP " + resp.statusCode() + ": " + url);

        try (InputStream in = resp.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------ Detect existing Fabric API ------------------

    private Path findExistingFabricApiJar(Path modsDir) {
        try (var s = Files.list(modsDir)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .filter(this::jarContainsFabricApiMod)
                    .findFirst()
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private boolean jarContainsFabricApiMod(Path jarPath) {
        try (JarFile jf = new JarFile(jarPath.toFile())) {
            JarEntry entry = jf.getJarEntry("fabric.mod.json");
            if (entry == null) return false;
            try (InputStream in = jf.getInputStream(entry)) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                String id = obj.has("id") ? obj.get("id").getAsString() : "";
                return "fabric-api".equalsIgnoreCase(id) || "fabric".equalsIgnoreCase(id);
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private void deleteAnyFabricApiJars(Path modsDir, Consumer<String> log) {
        try (var s = Files.list(modsDir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .filter(this::jarContainsFabricApiMod)
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                            if (log != null) log.accept("[FABRIC-API] Entfernt alte Version: " + p.getFileName());
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    // ------------------ Read required loader min from fabric.mod.json ------------------

    /**
     * Liest in fabric.mod.json: depends.fabricloader oder depends["fabricloader"] (>=x.y.z)
     * Gibt nur "minVersion" zurück (z.B. "0.16.10") oder null wenn nicht angegeben/parsebar.
     */
    private String readRequiredFabricLoaderMin(Path jarPath) {
        try (JarFile jf = new JarFile(jarPath.toFile())) {
            JarEntry entry = jf.getJarEntry("fabric.mod.json");
            if (entry == null) return null;

            String json;
            try (InputStream in = jf.getInputStream(entry)) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("depends")) return null;

            JsonObject depends = obj.getAsJsonObject("depends");
            if (depends == null) return null;

            // Keys in freier Wildbahn: "fabricloader" und manchmal "fabric-loader" (seltener)
            String expr = null;
            if (depends.has("fabricloader")) expr = depends.get("fabricloader").getAsString();
            else if (depends.has("fabric-loader")) expr = depends.get("fabric-loader").getAsString();

            if (expr == null) return null;

            // Wir nehmen nur die erste >= ... Angabe
            // Beispiele: ">=0.16.10", ">=0.14.0 <0.17.0", "[0.16.10,)"
            return parseMinVersion(expr);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String parseMinVersion(String expr) {
        if (expr == null) return null;
        String s = expr.trim();

        // Form: ">=0.16.10 ..."
        int idx = s.indexOf(">=");
        if (idx >= 0) {
            String rest = s.substring(idx + 2).trim();
            return takeVersionToken(rest);
        }

        // Form: "[0.16.10,)" oder "[0.16.10,0.17.0)"
        if (s.startsWith("[") || s.startsWith("(")) {
            int comma = s.indexOf(',');
            if (comma > 1) {
                String left = s.substring(1, comma).trim();
                return takeVersionToken(left);
            }
        }

        // Wenn es nur eine Version ist
        if (Character.isDigit(s.isEmpty() ? ' ' : s.charAt(0))) return takeVersionToken(s);

        return null;
    }

    private static String takeVersionToken(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '.') b.append(c);
            else break;
        }
        String v = b.toString();
        return v.isBlank() ? null : v;
    }

    // ------------------ Version compare ------------------

    private static boolean versionGte(String a, String b) {
        if (b == null) return true;
        int[] va = parseSemver(a);
        int[] vb = parseSemver(b);
        for (int i = 0; i < 3; i++) {
            if (va[i] > vb[i]) return true;
            if (va[i] < vb[i]) return false;
        }
        return true; // equal
    }

    private static int[] parseSemver(String s) {
        int[] out = new int[]{0,0,0};
        if (s == null) return out;
        String[] p = s.trim().split("\\.");
        for (int i = 0; i < Math.min(3, p.length); i++) {
            try { out[i] = Integer.parseInt(p[i].replaceAll("[^0-9]", "")); }
            catch (Exception ignored) {}
        }
        return out;
    }

    // ------------------ Utils ------------------

    private static String urlEncJsonArray(String... items) {
        JsonArray a = new JsonArray();
        for (String s : items) a.add(s);
        return URLEncoder.encode(a.toString(), StandardCharsets.UTF_8);
    }

    private static String optStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String sanitizeFilePart(String s) {
        if (s == null || s.isBlank()) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9._+-]", "_");
    }

    private record ModrinthVersion(String id, String versionNumber, String fileUrl, String fileName) {}
}
