package de.levingamer8.modlauncher.host;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ManifestBuilder {

    private ManifestBuilder() {}

    /**
     * Baut eine manifest.json für dein Pack.
     *
     * @param packId      z.B. "test-pack"
     * @param packName    z.B. "Test Pack"
     * @param packVersion int, hochzählen bei Änderungen (wird in deinem Client als State genutzt)
     * @param mcVersion   z.B. "1.20.1"
     * @param loaderType  "vanilla" | "fabric" | "forge"
     * @param loaderVer   bei fabric/forge: Version, bei vanilla: "" oder null
     * @param baseUrl     z.B. "http://localhost:8080/pack/test-pack/"  (muss mit / enden)
     * @param packRoot    lokaler Ordner mit mods/config/...
     * @param outManifest Zielpfad manifest.json (lokal)
     * @param createOverrides wenn true: erstellt overrides.zip aus "non-mods" (config/options/etc)
     */
    public static void buildManifest(
            String packId,
            String packName,
            int packVersion,
            String mcVersion,
            String loaderType,
            String loaderVer,
            String baseUrl,
            Path packRoot,
            Path outManifest,
            boolean createOverrides
    ) throws Exception {

        Objects.requireNonNull(packId);
        Objects.requireNonNull(packName);
        Objects.requireNonNull(mcVersion);
        Objects.requireNonNull(loaderType);
        Objects.requireNonNull(baseUrl);
        Objects.requireNonNull(packRoot);
        Objects.requireNonNull(outManifest);

        if (!baseUrl.endsWith("/")) baseUrl = baseUrl + "/";

        if (!Files.isDirectory(packRoot)) {
            throw new IllegalArgumentException("packRoot ist kein Ordner: " + packRoot);
        }

        // 1) Alle Dateien sammeln (rekursiv), aber manifest.json selbst ignorieren
        List<Path> allFiles = new ArrayList<>();
        try (var walk = Files.walk(packRoot)) {
            walk.filter(Files::isRegularFile).forEach(allFiles::add);
        }

        // 2) optional overrides.zip erstellen (alles außer mods/)
        JsonObject overridesObj = null;
        Path overridesZip = packRoot.resolve("overrides.zip");
        if (createOverrides) {
            createOverridesZip(packRoot, overridesZip);
            FileInfo oi = fileInfo(overridesZip);
            overridesObj = new JsonObject();
            overridesObj.addProperty("url", baseUrl + "overrides.zip");
            overridesObj.addProperty("sha256", oi.sha256Hex);
        }

        // 3) files[] bauen: Standard: alles als einzelne Datei unter /files/hosten
        //    Optional: Wenn createOverrides==true, dann packe "non-mods" NICHT in files[] (weil in overrides.zip)
        JsonArray filesArr = new JsonArray();

        for (Path f : allFiles) {
            Path rel = packRoot.relativize(f);
            String relUnix = rel.toString().replace("\\", "/");

            // manifest + overrides nicht als normale Dateien listen
            if (relUnix.equals("manifest.json")) continue;
            if (relUnix.equals("overrides.zip")) continue;

            boolean isMod = relUnix.startsWith("mods/");
            if (createOverrides && !isMod) {
                // steckt in overrides.zip -> nicht einzeln listen
                continue;
            }

            FileInfo info = fileInfo(f);

            JsonObject entry = new JsonObject();
            entry.addProperty("path", relUnix);
            entry.addProperty("sha256", info.sha256Hex.toUpperCase(Locale.ROOT));
            entry.addProperty("size", info.size);
            entry.addProperty("side", "client");

            JsonObject dl = new JsonObject();
            dl.addProperty("type", "url");
            // Host-Regel: alle listed files liegen unter baseUrl + "files/" + rel
            dl.addProperty("url", baseUrl + "files/" + relUnix);

            entry.add("download", dl);
            filesArr.add(entry);
        }

        // 4) Manifest root
        JsonObject root = new JsonObject();
        root.addProperty("packId", packId);
        root.addProperty("packName", packName);
        root.addProperty("packVersion", packVersion);
        root.addProperty("minecraft", mcVersion);

        JsonObject loader = new JsonObject();
        loader.addProperty("type", loaderType.toLowerCase(Locale.ROOT));
        if (!"vanilla".equalsIgnoreCase(loaderType)) {
            if (loaderVer == null || loaderVer.isBlank()) {
                throw new IllegalArgumentException("loaderVer fehlt für loaderType=" + loaderType);
            }
            loader.addProperty("version", loaderVer);
        }
        root.add("loader", loader);

        root.addProperty("baseUrl", baseUrl);
        root.add("files", filesArr);

        if (overridesObj != null) root.add("overrides", overridesObj);

        // 5) Schreiben (pretty)
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(outManifest, gson.toJson(root), StandardCharsets.UTF_8);

        // 6) Optional: Info ausgeben (Host kann das loggen)
        System.out.println("[MANIFEST] geschrieben: " + outManifest.toAbsolutePath());
        System.out.println("[MANIFEST] files=" + filesArr.size() + (createOverrides ? " (+overrides.zip)" : ""));
    }

    // ---------- helpers ----------

    private static void createOverridesZip(Path packRoot, Path outZip) throws Exception {
        // Alles außer mods/ ins overrides.zip
        if (Files.exists(outZip)) Files.delete(outZip);

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outZip)))) {
            try (var walk = Files.walk(packRoot)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        Path rel = packRoot.relativize(p);
                        String relUnix = rel.toString().replace("\\", "/");
                        if (relUnix.equals("manifest.json")) return;
                        if (relUnix.equals("overrides.zip")) return;

                        // alles außer mods/
                        if (relUnix.startsWith("mods/")) return;

                        ZipEntry ze = new ZipEntry(relUnix);
                        zos.putNextEntry(ze);
                        Files.copy(p, zos);
                        zos.closeEntry();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    private static FileInfo fileInfo(Path p) throws Exception {
        long size = Files.size(p);
        String sha = sha256Hex(p);
        return new FileInfo(size, sha);
    }

    private static String sha256Hex(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(Files.newInputStream(p))) {
            byte[] buf = new byte[1024 * 64];
            int r;
            while ((r = in.read(buf)) != -1) md.update(buf, 0, r);
        }
        byte[] dig = md.digest();
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private record FileInfo(long size, String sha256Hex) {}
}
