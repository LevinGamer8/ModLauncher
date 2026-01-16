package de.levingamer8.modlauncher.mc;

import com.google.gson.*;
import de.levingamer8.modlauncher.runtime.JavaRuntimeManager;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Deprecated
public class ForgeInstaller1122Plus {
    @Deprecated
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    @Deprecated
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Prism patched ForgeWrapper build (NOT the upstream github one)
    @Deprecated
    private static final String PRISM_FORGEWRAPPER_VERSION = "prism-2024-02-29";
    private static final String PRISM_FORGEWRAPPER_JAR = "ForgeWrapper-" + PRISM_FORGEWRAPPER_VERSION + ".jar";
    private static final String PRISM_FORGEWRAPPER_URL =
            "https://files.prismlauncher.org/maven/io/github/zekerzhayard/ForgeWrapper/"
                    + PRISM_FORGEWRAPPER_VERSION + "/"
                    + PRISM_FORGEWRAPPER_JAR;

    /**
     * Sorgt dafür, dass eine Forge-Version im shared/versions/ vorhanden ist
     * und die benötigten Libraries im shared/libraries/.
     *
     * @param sharedVersionsDir z.B. shared/versions
     * @param sharedLibrariesDir z.B. shared/libraries
     * @param mcVersion z.B. "1.12.2" oder "1.20.1"
     * @param forgeInstallerJar Pfad zur Forge-Installer JAR (downloaded)
     * @return forgeVersionId (z.B. "1.20.1-forge-47.4.10" oder was im JSON steht)
     */
    @Deprecated
    public String ensureForgeVersion(Path sharedVersionsDir, Path sharedLibrariesDir, String mcVersion, Path forgeInstallerJar) throws Exception {
        JsonObject installProfile = readInstallProfile(forgeInstallerJar);

        // --- A) "modern" / 1.13+ Installer: versionInfo existiert ---
        if (installProfile.has("versionInfo") && installProfile.get("versionInfo").isJsonObject()) {
            JsonObject versionInfo = installProfile.getAsJsonObject("versionInfo");
            if (!versionInfo.has("inheritsFrom")) versionInfo.addProperty("inheritsFrom", mcVersion);
            if (!versionInfo.has("jar")) versionInfo.addProperty("jar", mcVersion);

            String id = getStringOr(versionInfo, "id", null);
            if (id == null || id.isBlank()) {
                throw new RuntimeException("Forge versionInfo hat kein 'id'.");
            }

            Path versionDir = sharedVersionsDir.resolve(id);
            Files.createDirectories(versionDir);
            Path versionJson = versionDir.resolve(id + ".json");
            Files.writeString(versionJson, gson.toJson(versionInfo), StandardCharsets.UTF_8);

            if (versionInfo.has("libraries") && versionInfo.get("libraries").isJsonArray()) {
                installLibraries(sharedLibrariesDir, versionInfo.getAsJsonArray("libraries"));
            }

            return id;
        }

        // --- B) einige Installer: install.libraries existiert ---
        if (installProfile.has("install") && installProfile.get("install").isJsonObject()) {
            JsonObject install = installProfile.getAsJsonObject("install");

            // Manche Forge-Installer liefern kein komplettes versionInfo, aber ein "version" Feld
            String id = getStringOr(installProfile, "version", null);
            if (id == null || id.isBlank()) {
                // fallback: manchmal in "profile"
                id = getStringOr(installProfile, "profile", null);
            }
            if (id == null || id.isBlank()) {
                throw new RuntimeException("Forge install_profile hat kein 'version'/'profile' als ID.");
            }

            Path versionDir = sharedVersionsDir.resolve(id);
            Files.createDirectories(versionDir);

            // Wenn die Version-JSON NICHT im install_profile steckt, ist das für deinen Launcher nicht genug.
            // Daher: Falls install_profile zusätzlich "json" hat, extrahieren wir sie.
            if (installProfile.has("json")) {
                String jsonEntry = installProfile.get("json").getAsString();
                JsonObject versionJsonObj = readJsonEntryFromInstaller(forgeInstallerJar, jsonEntry);

                // id/inheritsFrom absichern
                versionJsonObj.addProperty("id", id);
                if (!versionJsonObj.has("inheritsFrom")) versionJsonObj.addProperty("inheritsFrom", mcVersion);

                Path versionJson = versionDir.resolve(id + ".json");
                Files.writeString(versionJson, gson.toJson(versionJsonObj), StandardCharsets.UTF_8);

                // libraries aus versionJson + install.libraries
                if (versionJsonObj.has("libraries")) {
                    installLibraries(sharedLibrariesDir, versionJsonObj.getAsJsonArray("libraries"));
                }
                if (install.has("libraries")) {
                    installLibraries(sharedLibrariesDir, install.getAsJsonArray("libraries"));
                }
            } else {
                // letzter Ausweg: nur install.libraries (kann aber für Forge nicht reichen)
                if (!install.has("libraries")) {
                    throw new RuntimeException("Forge install_profile hat weder versionInfo noch install.libraries noch json.");
                }
                installLibraries(sharedLibrariesDir, install.getAsJsonArray("libraries"));
            }

            return id;
        }

        // --- C) LEGACY Forge (1.12.2 typisch): json + libraries, kein versionInfo/install ---
        if (installProfile.has("json") && (installProfile.has("version") || installProfile.has("profile"))) {

            String id = getStringOr(installProfile, "version", null);
            if (id == null || id.isBlank()) {
                id = getStringOr(installProfile, "profile", null);
            }
            if (id == null || id.isBlank()) {
                throw new RuntimeException("Legacy Forge install_profile hat kein 'version'/'profile' als ID.");
            }

            String jsonEntry = installProfile.get("json").getAsString();
            JsonObject versionJsonObj = readJsonEntryFromInstaller(forgeInstallerJar, jsonEntry);

            versionJsonObj.addProperty("id", id);
            if (!versionJsonObj.has("inheritsFrom")) versionJsonObj.addProperty("inheritsFrom", mcVersion);

            Path versionDir = sharedVersionsDir.resolve(id);
            Files.createDirectories(versionDir);
            Path versionJson = versionDir.resolve(id + ".json");
            Files.writeString(versionJson, gson.toJson(versionJsonObj), StandardCharsets.UTF_8);

            // libs aus version json
            if (versionJsonObj.has("libraries") && versionJsonObj.get("libraries").isJsonArray()) {
                installLibraries(sharedLibrariesDir, versionJsonObj.getAsJsonArray("libraries"));
            }

            // libs aus install_profile.libraries
            if (installProfile.has("libraries") && installProfile.get("libraries").isJsonArray()) {
                installLibraries(sharedLibrariesDir, installProfile.getAsJsonArray("libraries"));
            }

            return id;
        }

        throw new RuntimeException("Forge install_profile Format unbekannt. Keys=" + installProfile.keySet());
    }

    private JsonObject readInstallProfile(Path installerJar) throws Exception {
        try (ZipFile zf = new ZipFile(installerJar.toFile())) {
            ZipEntry e = zf.getEntry("install_profile.json");
            if (e == null) throw new RuntimeException("install_profile.json nicht im Installer gefunden.");
            try (InputStream in = zf.getInputStream(e)) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return JsonParser.parseString(json).getAsJsonObject();
            }
        }
    }

    @Deprecated
    private Path findAndEnsureLibraryJar(Path sharedRoot, Path forgeInstallerJar, String group, String artifact, Consumer<String> log) throws Exception {
        JsonObject profile = readInstallProfile(forgeInstallerJar);

        // sammle library arrays aus allen möglichen Stellen
        List<JsonArray> arrays = new ArrayList<>();
        if (profile.has("libraries") && profile.get("libraries").isJsonArray()) arrays.add(profile.getAsJsonArray("libraries"));
        if (profile.has("versionInfo") && profile.get("versionInfo").isJsonObject()) {
            JsonObject vi = profile.getAsJsonObject("versionInfo");
            if (vi.has("libraries") && vi.get("libraries").isJsonArray()) arrays.add(vi.getAsJsonArray("libraries"));
        }
        if (profile.has("install") && profile.get("install").isJsonObject()) {
            JsonObject ins = profile.getAsJsonObject("install");
            if (ins.has("libraries") && ins.get("libraries").isJsonArray()) arrays.add(ins.getAsJsonArray("libraries"));
        }

        String wantedPrefix = group + ":" + artifact + ":";

        for (JsonArray arr : arrays) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject lib = el.getAsJsonObject();
                if (!lib.has("name")) continue;

                String name = lib.get("name").getAsString();
                if (!name.startsWith(wantedPrefix)) continue;

                // URL bestimmen
                String baseUrl = lib.has("url") ? lib.get("url").getAsString() : "https://maven.minecraftforge.net/";
                if (!baseUrl.endsWith("/")) baseUrl += "/";

                String rel = mavenPathFromCoord(name);
                Path out = sharedRoot.resolve("libraries").resolve(rel);

                if (!Files.exists(out)) {
                    Files.createDirectories(out.getParent());
                    if (log != null) log.accept("[FORGEWRAPPER] Download dep: " + name);
                    downloadFile(baseUrl + rel, out);
                }
                return out;
            }
        }

        throw new IllegalStateException("Konnte required lib nicht im install_profile finden: " + wantedPrefix + "*");
    }


    /**
     * Liest eine JSON-Datei aus dem Forge-Installer JAR (z.B. "version.json" Pfad aus install_profile.json -> "json")
     */
    @Deprecated
    private JsonObject readJsonEntryFromInstaller(Path installerJar, String entryPath) throws Exception {
        String fixed = entryPath;
        while (fixed.startsWith("/")) fixed = fixed.substring(1);

        try (ZipFile zf = new ZipFile(installerJar.toFile())) {
            ZipEntry e = zf.getEntry(fixed);
            if (e == null) {
                throw new RuntimeException("JSON Entry nicht im Installer gefunden: " + entryPath);
            }
            try (InputStream in = zf.getInputStream(e)) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return JsonParser.parseString(json).getAsJsonObject();
            }
        }
    }

    @Deprecated
    private void installLibraries(Path sharedLibrariesDir, JsonArray libs) throws Exception {
        if (libs == null) return;

        for (JsonElement el : libs) {
            if (!el.isJsonObject()) continue;
            JsonObject lib = el.getAsJsonObject();
            if (!lib.has("name")) continue;

            String coord = lib.get("name").getAsString();
            String url = null;

            if (lib.has("url")) url = lib.get("url").getAsString();
            if (url == null || url.isBlank()) {
                url = "https://maven.minecraftforge.net/";
            }
            if (!url.endsWith("/")) url += "/";

            String relPath = mavenPathFromCoord(coord);
            Path out = sharedLibrariesDir.resolve(relPath);

            if (Files.exists(out)) continue;

            Files.createDirectories(out.getParent());
            downloadFile(url + relPath, out);
        }
    }

    @Deprecated
    public String installForgeClient(Path sharedRoot,
                                     String mcVersion,
                                     String forgeVersionId,
                                     Path forgeInstallerJar,
                                     Consumer<String> log) throws Exception {

        Path sharedVersions = sharedRoot.resolve("versions");
        Path sharedLibraries = sharedRoot.resolve("libraries");
        Files.createDirectories(sharedVersions);
        Files.createDirectories(sharedLibraries);

        // Vanilla client jar must exist
        Path vanillaJar = sharedVersions.resolve(mcVersion).resolve(mcVersion + ".jar");
        if (!Files.exists(vanillaJar) || Files.size(vanillaJar) < 1_000_000) {
            throw new IllegalStateException("Vanilla client jar fehlt/kaputt: " + vanillaJar);
        }

        if (!Files.exists(forgeInstallerJar) || Files.size(forgeInstallerJar) < 100_000) {
            throw new IllegalStateException("Forge installer jar fehlt/kaputt/zu klein: " + forgeInstallerJar
                    + " size=" + (Files.exists(forgeInstallerJar) ? Files.size(forgeInstallerJar) : -1));
        }

        // 1) Write forge version json + download declared libs
        String forgeId = ensureForgeVersion(sharedVersions, sharedLibraries, mcVersion, forgeInstallerJar);
        if (log != null) log.accept("[FORGE] install_profile ausgewertet, forgeId=" + forgeId);

        Path log4jApi = ensureMavenJar(sharedRoot, "https://repo1.maven.org/maven2/", "org.apache.logging.log4j:log4j-api:2.20.0", log);
        Path log4jCore = ensureMavenJar(sharedRoot, "https://repo1.maven.org/maven2/", "org.apache.logging.log4j:log4j-core:2.20.0", log);
        Path bootstrap = ensureMavenJar(sharedRoot, "https://maven.minecraftforge.net/", "cpw.mods:bootstraplauncher:1.1.2", log);


        // 2) Ensure ForgeWrapper jar (Prism build)
        Path forgeWrapperJar = ensureForgeWrapperJar(sharedRoot, log);



        // 3) Run ForgeWrapper in CLI mode (no detector needed)
        Path javaExe = JavaRuntimeManager.ensureJava(mcVersion, log);
        Path workDir = sharedRoot.resolve("installer-cache").resolve("forgewrapper-work");
        Files.createDirectories(workDir);

        Path gsonJar = ensureGsonJar(sharedRoot, log);
        Path modLauncherJar = ensureLibFromVersionJson(sharedRoot, forgeId, "cpw.mods", "modlauncher", log);

        // Forge core runtime libs that must be present for ModLauncher services (e.g. distmarker OnlyIn)
        Path fmlcore = ensureLibFromVersionJson(sharedRoot, forgeId, "net.minecraftforge", "fmlcore", log);
        Path javaFmlLang = ensureLibFromVersionJson(sharedRoot, forgeId, "net.minecraftforge", "javafmllanguage", log);
        Path mcLang = ensureLibFromVersionJson(sharedRoot, forgeId, "net.minecraftforge", "mclanguage", log);
        Path lowCodeLang = ensureLibFromVersionJson(sharedRoot, forgeId, "net.minecraftforge", "lowcodelanguage", log);

        // Full classpath for the ForgeWrapper installer (Prism/MultiMC style): all jars from the version tree, but NO natives.
        List<Path> extraCp = classpathFromVersionTreeNoNatives(sharedRoot, forgeId, log);


        int exit = runForgeWrapperInstaller(
                javaExe,
                forgeWrapperJar,
                gsonJar,
                bootstrap,
                log4jApi,
                log4jCore,
                modLauncherJar,
                fmlcore,
                javaFmlLang,
                mcLang,
                lowCodeLang,
                extraCp,
                sharedLibraries,
                forgeInstallerJar,
                vanillaJar,
                workDir,
                log
        );


        if (exit != 0) {
            throw new RuntimeException("ForgeWrapper Installer failed, exit=" + exit
                    + " (Forge=" + forgeVersionId + ", MC=" + mcVersion + ")");
        }

        if (log != null) log.accept("[FORGE] ForgeWrapper ok.");
        return forgeId;
    }

    @Deprecated
    private Path ensureForgeWrapperJar(Path sharedRoot, Consumer<String> log) throws Exception {
        Path cacheDir = sharedRoot.resolve("installer-cache").resolve("forgewrapper");
        Files.createDirectories(cacheDir);

        Path jar = cacheDir.resolve(PRISM_FORGEWRAPPER_JAR);
        if (Files.exists(jar) && Files.size(jar) > 0) return jar;

        if (log != null) log.accept("[FORGEWRAPPER] Download (Prism Maven): " + PRISM_FORGEWRAPPER_URL);
        downloadFile(PRISM_FORGEWRAPPER_URL, jar);

        if (!Files.exists(jar) || Files.size(jar) == 0) {
            throw new IOException("ForgeWrapper Download kaputt/leer: " + jar);
        }
        return jar;
    }

    private int runForgeWrapperInstaller(
            Path javaExe,
            Path forgeWrapperJar,
            Path gsonJar,
            Path bootstrapJar,
            Path log4jApiJar,
            Path log4jCoreJar,
            Path modLauncherJar,
            Path fmlcoreJar,
            Path javaFmlLanguageJar,
            Path mcLanguageJar,
            Path lowCodeLanguageJar,
            List<Path> extraCp,
            Path librariesDir,
            Path forgeInstallerJar,
            Path vanillaMinecraftJar,
            Path workDir,
            Consumer<String> log
    ) throws Exception {

        // ForgeWrapper will eine "Instance". Minimal reicht: <instance>/.minecraft/
        Path instanceDir = workDir.resolve("instance");
        Path mcDir = instanceDir.resolve(".minecraft");
        Files.createDirectories(mcDir);

        // Minimal MultiMC instance metadata (some ForgeWrapper builds expect this)
        Path instanceCfg = instanceDir.resolve("instance.cfg");
        if (!Files.exists(instanceCfg)) {
            Files.writeString(instanceCfg, "InstanceType=OneSix\nname=ForgeWrapperTemp\n", StandardCharsets.UTF_8);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toString());

        cmd.add("-Xms256m");
        cmd.add("-Xmx1024m");
        cmd.add("-XX:+UseG1GC");


        // Die 3 Props sind wichtig
        cmd.add("-Dforgewrapper.librariesDir=" + librariesDir.toAbsolutePath());
        cmd.add("-Dforgewrapper.installer=" + forgeInstallerJar.toAbsolutePath());
        cmd.add("-Dforgewrapper.minecraft=" + vanillaMinecraftJar.toAbsolutePath());

        // WICHTIG: -jar (nicht -cp + Main)
        // statt:
        // cmd.add("-jar");
        // cmd.add(forgeWrapperJar.toAbsolutePath().toString());

        // Prism/MultiMC style: everything on the classpath (no module-path). This avoids missing module issues
        // and makes sure Forge core jars (fmlcore, languages, etc.) are visible.
        java.util.LinkedHashSet<String> cp = new java.util.LinkedHashSet<>();
        cp.add(forgeWrapperJar.toAbsolutePath().toString());
        cp.add(gsonJar.toAbsolutePath().toString());

        // ModLauncher bootstrap pieces
        cp.add(modLauncherJar.toAbsolutePath().toString());
        cp.add(bootstrapJar.toAbsolutePath().toString());
        cp.add(log4jApiJar.toAbsolutePath().toString());
        cp.add(log4jCoreJar.toAbsolutePath().toString());

        // Forge runtime pieces needed by ModLauncher services (fixes: ClassNotFound OnlyIn)
        cp.add(fmlcoreJar.toAbsolutePath().toString());
        cp.add(javaFmlLanguageJar.toAbsolutePath().toString());
        cp.add(mcLanguageJar.toAbsolutePath().toString());
        cp.add(lowCodeLanguageJar.toAbsolutePath().toString());

        // And finally all jars from the version tree (Forge + vanilla libs), but no natives
        for (Path p : extraCp) {
            if (p == null) continue;
            cp.add(p.toAbsolutePath().toString());
        }

        cmd.add("-Dbsl.debug=true");
        cmd.add("-cp");
        cmd.add(String.join(File.pathSeparator, cp));

        cmd.add("io.github.zekerzhayard.forgewrapper.installer.Main");


// CLI args trotzdem mitgeben!
        cmd.add("--installer=" + forgeInstallerJar.toAbsolutePath());
        cmd.add("--instance=" + instanceDir.toAbsolutePath());


        if (log != null) log.accept("[FORGEWRAPPER] " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (log != null) log.accept("[FORGEWRAPPER] " + line);
            }
        }

        return p.waitFor();
    }

    @Deprecated
    private Path ensureMavenJar(Path sharedRoot, String baseUrl, String coord, Consumer<String> log) throws Exception {
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        String rel = mavenPathFromCoord(coord);
        Path out = sharedRoot.resolve("libraries").resolve(rel);
        if (Files.exists(out) && Files.size(out) > 10_000) return out;

        Files.createDirectories(out.getParent());
        String url = baseUrl + rel;
        if (log != null) log.accept("[FORGEWRAPPER] Download dep: " + coord + " -> " + url);
        downloadFile(url, out);
        return out;
    }

    @Deprecated
    private void downloadFile(String url, Path out) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("Download fehlgeschlagen (" + resp.statusCode() + "): " + url);
        }
        Files.write(out, resp.body());
    }

    @Deprecated
    private String getStringOr(JsonObject o, String key, String def) {
        if (o == null || !o.has(key)) return def;
        try {
            return o.get(key).getAsString();
        } catch (Exception ignored) {
            return def;
        }
    }

    @Deprecated
    private Path ensureGsonJar(Path sharedRoot, Consumer<String> log) throws Exception {
        // lege es in shared/libraries (Maven-Layout)
        String coord = "com.google.code.gson:gson:2.10.1";
        String rel = mavenPathFromCoord(coord);
        Path out = sharedRoot.resolve("libraries").resolve(rel);

        if (Files.exists(out) && Files.size(out) > 10_000) return out;

        Files.createDirectories(out.getParent());
        String url = "https://repo1.maven.org/maven2/" + rel;

        if (log != null) log.accept("[FORGEWRAPPER] Download gson: " + url);
        downloadFile(url, out);

        if (!Files.exists(out) || Files.size(out) < 10_000) {
            throw new IOException("gson download kaputt: " + out);
        }

        return out;
    }

    @Deprecated
    private Path ensureLibFromVersionJson(Path sharedRoot, String versionId, String group, String artifact, Consumer<String> log) throws Exception {
        Path vJson = sharedRoot.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (!Files.exists(vJson)) {
            throw new IllegalStateException("Version JSON fehlt: " + vJson);
        }

        JsonObject v = JsonParser.parseString(Files.readString(vJson, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!v.has("libraries") || !v.get("libraries").isJsonArray()) {
            throw new IllegalStateException("Version JSON hat keine libraries: " + vJson);
        }

        String prefix = group + ":" + artifact + ":";

        for (JsonElement el : v.getAsJsonArray("libraries")) {
            if (!el.isJsonObject()) continue;
            JsonObject lib = el.getAsJsonObject();
            if (!lib.has("name")) continue;

            String name = lib.get("name").getAsString();
            if (!name.startsWith(prefix)) continue;

            String baseUrl = lib.has("url") ? lib.get("url").getAsString() : "https://maven.minecraftforge.net/";
            if (!baseUrl.endsWith("/")) baseUrl += "/";

            String rel = mavenPathFromCoord(name);
            Path out = sharedRoot.resolve("libraries").resolve(rel);

            if (!Files.exists(out) || Files.size(out) < 10_000) {
                Files.createDirectories(out.getParent());
                if (log != null) log.accept("[FORGEWRAPPER] Download dep: " + name);
                downloadFile(baseUrl + rel, out);
            }

            return out;
        }

        throw new IllegalStateException("Konnte required lib nicht in version json finden: " + prefix + "* (versionId=" + versionId + ")");
    }

    /**
     * Builds a Prism/MultiMC style classpath: traverse inheritsFrom and collect every "artifact" jar,
     * but skip natives/classifiers. Missing jars are downloaded.
     *
     * This is critical for ForgeWrapper install on modern Forge because ModLauncher services
     * are loaded and they expect core Forge runtime jars (fmlcore, languages, etc.) to be present.
     */
    @Deprecated
    private List<Path> classpathFromVersionTreeNoNatives(Path sharedRoot, String versionId, Consumer<String> log) throws Exception {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        List<Path> out = new ArrayList<>();

        String current = versionId;
        while (current != null && !current.isBlank()) {
            Path vJson = sharedRoot.resolve("versions").resolve(current).resolve(current + ".json");
            if (!Files.exists(vJson)) {
                throw new IllegalStateException("Version JSON fehlt: " + vJson);
            }

            JsonObject v = JsonParser.parseString(Files.readString(vJson, StandardCharsets.UTF_8)).getAsJsonObject();

            if (v.has("libraries") && v.get("libraries").isJsonArray()) {
                for (JsonElement el : v.getAsJsonArray("libraries")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject lib = el.getAsJsonObject();
                    if (!isAllowedOnWindows(lib)) continue;

                    // Skip natives and classifier-only entries
                    if (lib.has("natives")) continue;

                    Path jarPath = resolveLibraryJarPath(sharedRoot, lib);
                    if (jarPath == null) continue;

                    if (!Files.exists(jarPath) || Files.size(jarPath) < 10_000) {
                        downloadLibraryJarIfPossible(lib, jarPath, log);
                    }

                    if (Files.exists(jarPath) && Files.size(jarPath) >= 10_000) {
                        String abs = jarPath.toAbsolutePath().toString();
                        if (seen.add(abs)) out.add(jarPath);
                    }
                }
            }

            current = v.has("inheritsFrom") ? v.get("inheritsFrom").getAsString() : null;
        }

        return out;
    }

    @Deprecated
    private Path resolveLibraryJarPath(Path sharedRoot, JsonObject lib) {
        // Prefer the official "downloads.artifact.path" if present
        if (lib.has("downloads") && lib.get("downloads").isJsonObject()) {
            JsonObject dl = lib.getAsJsonObject("downloads");
            if (dl.has("artifact") && dl.get("artifact").isJsonObject()) {
                JsonObject art = dl.getAsJsonObject("artifact");
                if (art.has("path")) {
                    return sharedRoot.resolve("libraries").resolve(art.get("path").getAsString());
                }
            }
        }

        // Fallback to maven coord "name"
        if (lib.has("name")) {
            String coord = lib.get("name").getAsString();
            return sharedRoot.resolve("libraries").resolve(mavenPathFromCoord(coord));
        }
        return null;
    }

    @Deprecated
    private void downloadLibraryJarIfPossible(JsonObject lib, Path out, Consumer<String> log) throws Exception {
        Files.createDirectories(out.getParent());

        // 1) If the artifact has a direct URL, use it.
        if (lib.has("downloads") && lib.get("downloads").isJsonObject()) {
            JsonObject dl = lib.getAsJsonObject("downloads");
            if (dl.has("artifact") && dl.get("artifact").isJsonObject()) {
                JsonObject art = dl.getAsJsonObject("artifact");
                if (art.has("url")) {
                    String url = art.get("url").getAsString();
                    if (log != null && lib.has("name")) log.accept("[FORGEWRAPPER] Download dep: " + lib.get("name").getAsString() + " -> " + url);
                    downloadFile(url, out);
                    return;
                }
            }
        }

        // 2) Otherwise use "url" base + computed maven path
        if (lib.has("name")) {
            String coord = lib.get("name").getAsString();
            String rel = mavenPathFromCoord(coord);
            String baseUrl = lib.has("url") ? lib.get("url").getAsString() : "https://maven.minecraftforge.net/";
            if (!baseUrl.endsWith("/")) baseUrl += "/";
            String url = baseUrl + rel;
            if (log != null) log.accept("[FORGEWRAPPER] Download dep: " + coord + " -> " + url);
            downloadFile(url, out);
        }
    }

    @Deprecated
    private boolean isAllowedOnWindows(JsonObject lib) {
        if (!lib.has("rules") || !lib.get("rules").isJsonArray()) return true;

        boolean allowed = false;
        for (JsonElement rEl : lib.getAsJsonArray("rules")) {
            if (!rEl.isJsonObject()) continue;
            JsonObject r = rEl.getAsJsonObject();
            String action = r.has("action") ? r.get("action").getAsString() : "allow";

            boolean matches = true;
            if (r.has("os") && r.get("os").isJsonObject()) {
                JsonObject os = r.getAsJsonObject("os");
                if (os.has("name")) {
                    matches = "windows".equalsIgnoreCase(os.get("name").getAsString());
                }
            }

            if (matches) {
                if ("disallow".equalsIgnoreCase(action)) return false;
                if ("allow".equalsIgnoreCase(action)) allowed = true;
            }
        }

        // If rules exist and no allow matched, treat as not allowed
        return allowed;
    }


    @Deprecated
    public static String mavenPathFromCoord(String coord) {
        // Supported:
        // group:artifact:version
        // group:artifact:version:classifier
        // group:artifact:version@ext
        // group:artifact:version:classifier@ext
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
}
