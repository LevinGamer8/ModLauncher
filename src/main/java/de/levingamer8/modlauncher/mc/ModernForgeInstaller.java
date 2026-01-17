package de.levingamer8.modlauncher.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.levingamer8.modlauncher.runtime.JavaRuntimeManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class ModernForgeInstaller {

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Installiert Forge (1.13+) stabil über den offiziellen Forge-Installer in einer Fake-.minecraftVersion
     * und kopiert danach:
     *  - versions/<forgeId> nach shared/versions/<targetId>
     *  - libraries/** nach shared/libraries/**
     *
     * targetId Schema: "forge-<mcVersion>-<forgeVersion>"
     *
     * @return targetVersionId (z.B. "forge-1.20.1-47.4.10")
     */
    public String installForge(Path sharedRoot, String mcVersion, String forgeVersion, Consumer<String> log) throws Exception {
        Consumer<String> L = log != null ? log : (s -> {});

        // Shared Pfade
        Path sharedVersions = sharedRoot.resolve("versions");
        Path sharedLibraries = sharedRoot.resolve("libraries");
        Path sharedInstallers = sharedRoot.resolve("installers").resolve("forge");
        Path sharedCache = sharedRoot.resolve("installer-cache").resolve("forge-install");

        Files.createDirectories(sharedVersions);
        Files.createDirectories(sharedLibraries);
        Files.createDirectories(sharedInstallers);
        Files.createDirectories(sharedCache);

        // Vanilla muss existieren
        Path vanillaDir = sharedVersions.resolve(mcVersion);
        Path vanillaJar = vanillaDir.resolve(mcVersion + ".jar");
        Path vanillaJson = vanillaDir.resolve(mcVersion + ".json");

        if (!Files.exists(vanillaJar) || Files.size(vanillaJar) < 1_000_000) {
            throw new IllegalStateException("Vanilla JAR fehlt/kaputt: " + vanillaJar);
        }
        if (!Files.exists(vanillaJson) || Files.size(vanillaJson) < 1000) {
            throw new IllegalStateException("Vanilla JSON fehlt/kaputt: " + vanillaJson);
        }

        // Ziel-VersionId
        String targetId = "forge-" + mcVersion + "-" + forgeVersion;

        // Wenn schon installiert -> fertig
        Path targetDir = sharedVersions.resolve(targetId);
        Path targetJson = targetDir.resolve(targetId + ".json");
        if (Files.exists(targetJson) && Files.size(targetJson) > 1000) {
            L.accept("[FORGE] Bereits installiert: " + targetId);
            return targetId;
        }

        // Forge Installer besorgen
        Path installerJar = ensureForgeInstaller(sharedInstallers, mcVersion, forgeVersion, L);

        // Fake environment
        Path tempRoot = sharedCache.resolve(mcVersion + "-" + forgeVersion);
        Path tempMc = tempRoot.resolve(".minecraftVersion");
        Path tempVersions = tempMc.resolve("versions");
        Path tempLibraries = tempMc.resolve("libraries");

        // Clean slate
        deleteRecursive(tempRoot);
        Files.createDirectories(tempVersions);
        Files.createDirectories(tempLibraries);

        // Vanilla in temp kopieren
        Path tempVanillaDir = tempVersions.resolve(mcVersion);
        Files.createDirectories(tempVanillaDir);
        Files.copy(vanillaJar, tempVanillaDir.resolve(mcVersion + ".jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(vanillaJson, tempVanillaDir.resolve(mcVersion + ".json"), StandardCopyOption.REPLACE_EXISTING);

        // launcher_profiles.json minimal anlegen (Forge Installer erwartet das)
        ensureLauncherProfiles(tempMc);

        // Java (Forge 1.20.1 => Java 17)
        Path javaExe = JavaRuntimeManager.ensureJava(mcVersion, L);

        // Installer ausführen
        L.accept("[FORGE] Running installer in temp .minecraftVersion: " + tempMc);
        int exit = runForgeInstaller(javaExe, installerJar, tempMc, L);
        if (exit != 0) {
            throw new RuntimeException("Forge installer failed exit=" + exit + " (MC=" + mcVersion + ", Forge=" + forgeVersion + ")");
        }

        // Forge Version Ordner finden (installer schreibt sowas wie 1.20.1-forge-47.4.10)
        Path producedForgeDir = findProducedForgeVersionDir(tempVersions, mcVersion, forgeVersion);
        String producedId = producedForgeDir.getFileName().toString();

        Path producedJson = producedForgeDir.resolve(producedId + ".json");
        if (!Files.exists(producedJson) || Files.size(producedJson) < 1000) {
            throw new IllegalStateException("Forge produced JSON fehlt/kaputt: " + producedJson);
        }

        // Produced JSON lesen, id auf targetId umschreiben
        JsonObject obj = JsonParser.parseString(Files.readString(producedJson, StandardCharsets.UTF_8)).getAsJsonObject();
        obj.addProperty("id", targetId);
        if (!obj.has("inheritsFrom")) obj.addProperty("inheritsFrom", mcVersion);
        if (!obj.has("jar")) obj.addProperty("jar", mcVersion);

        // Zielordner anlegen + JSON schreiben
        Files.createDirectories(targetDir);
        Files.writeString(targetJson, gson.toJson(obj), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        L.accept("[FORGE] Installed version JSON: " + targetJson);

        // Libraries mergen
        L.accept("[FORGE] Merging libraries...");
        mergeTree(tempLibraries, sharedLibraries, L);

        // Optional: version jar übernehmen (meist unnötig, aber falls existiert, nehmen wir es mit)
        Path producedJar = producedForgeDir.resolve(producedId + ".jar");
        if (Files.exists(producedJar) && Files.size(producedJar) > 10_000) {
            Path outJar = targetDir.resolve(targetId + ".jar");
            Files.copy(producedJar, outJar, StandardCopyOption.REPLACE_EXISTING);
            L.accept("[FORGE] Copied version jar: " + outJar);
        }

        // Cleanup (wie du wolltest)
        L.accept("[FORGE] Cleanup temp + installer...");
        deleteRecursive(tempRoot);
        // Installer löschen (du wolltest das so). Wenn du caching willst: kommentier das aus.
        Files.deleteIfExists(installerJar);

        L.accept("[FORGE] Done: " + targetId);
        return targetId;
    }

    private Path ensureForgeInstaller(Path sharedInstallersForgeDir, String mcVersion, String forgeVersion, Consumer<String> log) throws Exception {
        Path dir = sharedInstallersForgeDir.resolve(mcVersion + "-" + forgeVersion);
        Files.createDirectories(dir);

        String file = "forge-" + mcVersion + "-" + forgeVersion + "-installer.jar";
        Path out = dir.resolve(file);

        if (Files.exists(out) && Files.size(out) > 100_000) {
            log.accept("[FORGE] Installer vorhanden: " + out);
            return out;
        }

        String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                + mcVersion + "-" + forgeVersion + "/" + file;

        log.accept("[FORGE] Download Installer: " + url);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Forge installer download failed HTTP " + resp.statusCode() + ": " + url);
        }
        Files.write(out, resp.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        if (!Files.exists(out) || Files.size(out) < 100_000) {
            throw new IOException("Forge installer jar kaputt/zu klein: " + out);
        }
        return out;
    }

    private int runForgeInstaller(Path javaExe, Path installerJar, Path workDirMc, Consumer<String> log) throws Exception {
        // 1) --installClient probieren
        int exit = run(javaExe, installerJar, workDirMc, "--installClient", log);
        if (exit == 0) return 0;

        // 2) fallback --install-client
        log.accept("[FORGE] --installClient failed (" + exit + "), trying --install-client ...");
        exit = run(javaExe, installerJar, workDirMc, "--install-client", log);
        return exit;
    }

    private int run(Path javaExe, Path installerJar, Path workDirMc, String flag, Consumer<String> log) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                javaExe.toString(),
                "-Xms256m",
                "-Xmx1024m",
                "-Dfile.encoding=UTF-8",
                "-jar",
                installerJar.toAbsolutePath().toString(),
                flag
        );
        pb.directory(workDirMc.toFile());
        pb.redirectErrorStream(true);

        log.accept("[FORGE] " + String.join(" ", pb.command()));

        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.accept("[FORGE-INSTALL] " + line);
            }
        }
        return p.waitFor();
    }

    private Path findProducedForgeVersionDir(Path tempVersionsDir, String mcVersion, String forgeVersion) throws IOException {
        // Typisch: 1.20.1-forge-47.4.10
        String exact = mcVersion + "-forge-" + forgeVersion;

        Path exactDir = tempVersionsDir.resolve(exact);
        if (Files.isDirectory(exactDir)) return exactDir;

        // fallback: irgendwas, das mcVersion enthält und forgeVersion enthält und "forge" enthält
        try (Stream<Path> s = Files.list(tempVersionsDir)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.contains(mcVersion) && n.contains("forge") && n.contains(forgeVersion);
                    })
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Forge produced version dir nicht gefunden in " + tempVersionsDir));
        }
    }

    private static void ensureLauncherProfiles(Path mcDir) throws IOException {
        Files.createDirectories(mcDir);
        Path lp = mcDir.resolve("launcher_profiles.json");
        if (Files.exists(lp)) return;

        String json = """
                {
                  "profiles": {},
                  "selectedProfile": "",
                  "clientToken": "00000000-0000-0000-0000-000000000000",
                  "authenticationDatabase": {},
                  "launcherVersion": { "name": "ModLauncher", "format": 21 }
                }
                """;
        Files.writeString(lp, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void mergeTree(Path srcDir, Path dstDir, Consumer<String> log) throws IOException {
        if (!Files.exists(srcDir)) return;

        try (Stream<Path> walk = Files.walk(srcDir)) {
            walk.forEach(src -> {
                try {
                    Path rel = srcDir.relativize(src);
                    Path dst = dstDir.resolve(rel);

                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                        return;
                    }

                    // file
                    Files.createDirectories(dst.getParent());
                    if (!Files.exists(dst)) {
                        Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        if (log != null) log.accept("[FORGE] Libraries merged into: " + dstDir);
    }

    private static void deleteRecursive(Path p) {
        if (p == null) return;
        try {
            if (!Files.exists(p)) return;
            try (Stream<Path> walk = Files.walk(p)) {
                walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                    try {
                        Files.deleteIfExists(x);
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }
}
