package de.levingamer8.modlauncher.mc;

import com.google.gson.JsonObject;
import de.levingamer8.modlauncher.core.LoaderType;
import de.levingamer8.modlauncher.runtime.JavaRuntimeManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class MinecraftLauncherService {

    public record AuthSession(String playerName, String uuid, String accessToken, String userType) {}
    public record LaunchSpec(String mcVersion, LoaderType loaderType, String loaderVersion, int memoryMb) {}

    private static final String LAUNCHER_NAME = "ModLauncher";
    private static final String LAUNCHER_VERSION = "1.0";

    private final MojangDownloader mojang = new MojangDownloader();
    private final MinecraftInstaller vanillaInstaller = new MinecraftInstaller(); // FlowUpdater wrapper
    private final FabricInstaller fabricInstaller = new FabricInstaller();

    // Forge
    private final ModernForgeInstaller modernForge = new ModernForgeInstaller();

    private final MojangVersionResolver resolver = new MojangVersionResolver();
    private final LibraryService libraryService = new LibraryService();

    public void launch(Path sharedRoot,
                       Path instanceGameDir,
                       Path instanceRuntimeDir,
                       LaunchSpec spec,
                       AuthSession auth,
                       Consumer<String> log) throws Exception {

        final Consumer<String> L = safeLog(log);

        // --- dirs ---
        Files.createDirectories(sharedRoot);
        Files.createDirectories(instanceGameDir);
        Files.createDirectories(instanceRuntimeDir);

        // natives dir (wichtig, wird in JSON über ${natives_directory} genutzt)
        Path nativesDir = instanceRuntimeDir.resolve("natives");
        Files.createDirectories(nativesDir);

        // --- 1) Vanilla Basis (Assets/Index/Client/Libraries via FlowUpdater) ---
        vanillaInstaller.ensureVanillaInstalled(sharedRoot, spec.mcVersion(), L);
        mojang.ensureAssetIndex(sharedRoot, spec.mcVersion());

        // optional: Root-Müll bereinigen (best-effort)
        cleanupSharedRootArtifacts(sharedRoot, spec.mcVersion(), L);

        Path assetIndex = sharedRoot.resolve("assets").resolve("indexes").resolve(spec.mcVersion() + ".json");
        if (!Files.exists(assetIndex)) {
            throw new IllegalStateException("Asset index fehlt nach Install: " + assetIndex);
        }


        // --- 2) Version JSON + Client Jar garantieren (Vanilla) ---
        mojang.ensureVersionJson(sharedRoot, spec.mcVersion());
        mojang.ensureClientJar(sharedRoot, spec.mcVersion());

        // --- 3) Loader vorbereiten -> versionId bestimmen ---
        String versionId;
        if (spec.loaderType() == LoaderType.VANILLA) {
            versionId = spec.mcVersion();

        } else if (spec.loaderType() == LoaderType.FABRIC) {
            FabricInstaller.LatestFabric latest = fabricInstaller.fetchLatestLoaderForMc(spec.mcVersion());
            String loaderVer = latest.loaderVersion();
            L.accept("[FABRIC] Verwende latest Fabric Loader für " + spec.mcVersion() + ": " + loaderVer);

            versionId = fabricInstaller.ensureFabricVersion(sharedRoot, spec.mcVersion(), loaderVer);

        } else if (spec.loaderType() == LoaderType.FORGE) {
            String mc = spec.mcVersion();
            String forgeVer = spec.loaderVersion();
            if (forgeVer == null || forgeVer.isBlank()) {
                throw new IllegalStateException("Forge loaderVersion fehlt im Manifest/LaunchSpec (z.B. 47.4.10).");
            }

                // Modern Forge (1.13+) Probably doesnt work for older versions NEED TO TEST
                versionId = modernForge.installForge(sharedRoot, mc, forgeVer, L);


        } else {
            throw new IllegalStateException("Unbekannter LoaderType: " + spec.loaderType());
        }

        // --- 4) merged version json (inheritsFrom auflösen) ---
        JsonObject v = resolver.resolveMergedVersionJson(sharedRoot, versionId);

        // --- 5) Classpath sicherstellen (Libraries + ggf. Version-Jar) ---
        List<Path> cp = new ArrayList<>(libraryService.ensureClasspath(sharedRoot, v));

        // Forge BootstrapLauncher: Game-JAR darf NICHT in -cp sein, sonst JPMS Konflikte
        if (spec.loaderType() == LoaderType.FORGE) {
            removeGameJarFromClasspath(cp, sharedRoot, v, versionId);
        } else {
            ensureGameJarOnClasspath(cp, sharedRoot, v, versionId);
        }

        // --- 6) Vars für ${...} Platzhalter aus JSON ---
        Map<String, String> vars = new HashMap<>();

        // Standard Launcher vars (wichtig!)
        vars.put("natives_directory", nativesDir.toAbsolutePath().toString());
        vars.put("library_directory", sharedRoot.resolve("libraries").toAbsolutePath().toString());
        vars.put("classpath_separator", System.getProperty("path.separator"));
        vars.put("classpath", ArgsBuilder.joinClasspath(cp));

        // Mojang / Game vars
        vars.put("auth_player_name", auth.playerName());
        vars.put("auth_uuid", auth.uuid());
        vars.put("auth_access_token", auth.accessToken());
        vars.put("user_type", auth.userType());

        vars.put("version_name", versionId);
        vars.put("version_type", spec.loaderType().name().toLowerCase(Locale.ROOT));

        vars.put("game_directory", instanceGameDir.toAbsolutePath().toString());
        vars.put("assets_root", sharedRoot.resolve("assets").toAbsolutePath().toString());
        vars.put("assets_index_name", spec.mcVersion());

        // Defaults, damit --width/--height nie ohne Wert bleiben
        vars.putIfAbsent("resolution_width", "854");
        vars.putIfAbsent("resolution_height", "480");



        // Launcher identity (kommt in manchen JSONs vor)
        vars.put("launcher_name", LAUNCHER_NAME);
        vars.put("launcher_version", LAUNCHER_VERSION);

        // --- 7) Args aus JSON bauen (sauber, ohne nachträgliches Gepfusche) ---
        ArgsBuilder argsBuilder = new ArgsBuilder();

        List<String> jvmArgs = sanitizeUnresolvedArgs(argsBuilder.buildJvmArgs(v, vars));
        List<String> gameArgs = sanitizeUnresolvedArgs(argsBuilder.buildGameArgs(v, vars));

        gameArgs = dropOptionsMissingValue(gameArgs, Set.of(
                "--width", "--height",
                "--clientId", "--xuid",
                "--quickPlayPath", "--quickPlaySingleplayer", "--quickPlayMultiplayer", "--quickPlayRealms"
        ));
        gameArgs.removeIf("--demo"::equals);


        // Memory: falls JSON nichts setzt, setzen wir es hier (ohne Forge/Fabric kaputt zu machen)
        jvmArgs = ensureMemoryArgs(jvmArgs, spec.memoryMb());

        // --- 8) MainClass + Java ---
        String mainClass = v.get("mainClass").getAsString();
        Path javaExe = JavaRuntimeManager.ensureJava(spec.mcVersion(), L);

        // --- 9) Cmd (WICHTIG: JVM-Args VOR mainClass, keine extra -cp Hacks!) ---
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toString());
        cmd.addAll(jvmArgs);
        cmd.add(mainClass);
        cmd.addAll(gameArgs);

        L.accept("[LAUNCH] " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(instanceGameDir.toFile());
        pb.redirectErrorStream(true);

        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                L.accept("[MC] " + line);
            }
        }

        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Minecraft exited with code " + exit);
        }
    }

    // ---------------- helpers ----------------

    private static Consumer<String> safeLog(Consumer<String> log) {
        return log != null ? log : (s -> {});
    }

    private static List<String> dropOptionsMissingValue(List<String> args, Set<String> valueOpts) {
        List<String> out = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (!valueOpts.contains(a)) {
                out.add(a);
                continue;
            }

            // Option erwartet einen Wert
            if (i + 1 >= args.size()) continue;
            String next = args.get(i + 1);

            // fehlt oder sieht aus wie nächste Option => Option droppen
            if (next == null || next.isBlank() || next.startsWith("--")) continue;

            out.add(a);
            out.add(next);
            i++; // value consumed
        }
        return out;
    }


    /**
     * Entfernt nur wirklich unresolved Tokens, die NICHT ersetzt wurden.
     * Wenn ArgsBuilder korrekt ersetzt, bleibt hier praktisch alles drin.
     */
    private static List<String> sanitizeUnresolvedArgs(List<String> args) {
        List<String> out = new ArrayList<>(args.size());
        for (String a : args) {
            if (a == null) continue;
            if (a.isBlank()) continue;     // WICHTIG
            if (a.contains("${")) continue; // Platzhalter nicht aufgelöst -> raus
            out.add(a);
        }
        return out;
    }


    private static List<String> ensureMemoryArgs(List<String> jvmArgs, int memoryMb) {
        if (memoryMb <= 0) return jvmArgs;

        boolean hasXmx = false;
        for (String a : jvmArgs) {
            if (a != null && a.startsWith("-Xmx")) { hasXmx = true; break; }
        }
        if (hasXmx) return jvmArgs;

        List<String> out = new ArrayList<>(jvmArgs.size() + 1);
        out.addAll(jvmArgs);
        out.add("-Xmx" + memoryMb + "M");
        return out;
    }

    private static void ensureGameJarOnClasspath(List<Path> cp, Path sharedRoot, JsonObject merged, String versionId) throws Exception {
        // Mojang/Forge: "jar" sagt, welches versions/<jarId>/<jarId>.jar genutzt wird.
        // Wenn nicht vorhanden, fallback auf inheritsFrom, sonst versionId.
        String jarId = versionId;

        if (merged.has("jar") && merged.get("jar").isJsonPrimitive()) {
            jarId = merged.get("jar").getAsString();
        } else if (merged.has("inheritsFrom") && merged.get("inheritsFrom").isJsonPrimitive()) {
            jarId = merged.get("inheritsFrom").getAsString();
        }

        Path jar = sharedRoot.resolve("versions").resolve(jarId).resolve(jarId + ".jar");
        if (!Files.exists(jar)) {
            throw new IllegalStateException("Game-Jar fehlt: " + jar + " (versionId=" + versionId + ", jarId=" + jarId + ")");
        }

        Path norm = jar.toAbsolutePath().normalize();
        for (Path p : cp) {
            if (p.toAbsolutePath().normalize().equals(norm)) return;
        }
        cp.add(jar);
    }


    private static Path downloadForgeInstallerJar(Path sharedRoot, String mcVersion, String forgeVersion, Consumer<String> log) throws Exception {
        Path dir = sharedRoot.resolve("installers").resolve("forge").resolve(mcVersion + "-" + forgeVersion);
        Files.createDirectories(dir);

        String file = "forge-" + mcVersion + "-" + forgeVersion + "-installer.jar";
        Path out = dir.resolve(file);

        if (Files.exists(out) && Files.size(out) > 100_000) {
            log.accept("[FORGE] Installer vorhanden: " + out);
            return out;
        }

        String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                + mcVersion + "-" + forgeVersion + "/"
                + file;

        log.accept("[FORGE] Download Installer: " + url);

        var client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                .build();

        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build();
        var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("Forge installer download failed HTTP " + resp.statusCode() + ": " + url);
        }

        Files.write(out, resp.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return out;
    }

    private static void removeGameJarFromClasspath(List<Path> cp, Path sharedRoot, JsonObject merged, String versionId) {
        String jarId = versionId;

        if (merged.has("jar") && merged.get("jar").isJsonPrimitive()) {
            jarId = merged.get("jar").getAsString();
        } else if (merged.has("inheritsFrom") && merged.get("inheritsFrom").isJsonPrimitive()) {
            jarId = merged.get("inheritsFrom").getAsString();
        }

        Path gameJar = sharedRoot.resolve("versions").resolve(jarId).resolve(jarId + ".jar")
                .toAbsolutePath().normalize();

        cp.removeIf(p -> p != null && p.toAbsolutePath().normalize().equals(gameJar));
    }


    private static void cleanupSharedRootArtifacts(Path sharedRoot, String mcVersion, Consumer<String> log) {
        try {
            // --- 1) shared/<mcVersion>.json ---
            Path rootJson = sharedRoot.resolve(mcVersion + ".json");
            if (Files.exists(rootJson) && Files.size(rootJson) > 0) {
                String txt = Files.readString(rootJson);

                boolean looksLikeAssetIndex = txt.contains("\"objects\"");     // Asset-Index hat immer objects
                boolean looksLikeVersionJson = txt.contains("\"libraries\"") || txt.contains("\"downloads\"") || txt.contains("\"mainClass\"");

                if (looksLikeAssetIndex && !looksLikeVersionJson) {
                    Path dst = sharedRoot.resolve("assets").resolve("indexes").resolve(mcVersion + ".json");
                    Files.createDirectories(dst.getParent());

                    if (Files.exists(dst) && Files.size(dst) > 0) {
                        Files.deleteIfExists(rootJson);
                        log.accept("[CLEANUP] deleted duplicate " + rootJson);
                    } else {
                        Files.move(rootJson, dst, StandardCopyOption.REPLACE_EXISTING);
                        log.accept("[CLEANUP] moved asset index " + rootJson + " -> " + dst);
                    }
                } else {
                    // als Version JSON behandeln
                    Path dst = sharedRoot.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".json");
                    Files.createDirectories(dst.getParent());

                    if (Files.exists(dst) && Files.size(dst) > 0) {
                        Files.deleteIfExists(rootJson);
                        log.accept("[CLEANUP] deleted duplicate " + rootJson);
                    } else {
                        Files.move(rootJson, dst, StandardCopyOption.REPLACE_EXISTING);
                        log.accept("[CLEANUP] moved version json " + rootJson + " -> " + dst);
                    }
                }
            }

            // --- 2) shared/client.jar ---
            Path rootClientJar = sharedRoot.resolve("client.jar");
            if (Files.exists(rootClientJar) && Files.size(rootClientJar) > 0) {
                Path dst = sharedRoot.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".jar");
                Files.createDirectories(dst.getParent());

                if (Files.exists(dst) && Files.size(dst) > 0) {
                    Files.deleteIfExists(rootClientJar);
                    log.accept("[CLEANUP] deleted duplicate " + rootClientJar);
                } else {
                    Files.move(rootClientJar, dst, StandardCopyOption.REPLACE_EXISTING);
                    log.accept("[CLEANUP] moved client jar " + rootClientJar + " -> " + dst);
                }
            }

        } catch (Exception e) {
            log.accept("[CLEANUP] warning: " + e.getMessage());
        }
    }



    private static boolean isAtLeast13(String mcVersion) {
        try {
            String[] p = mcVersion.split("\\.");
            if (p.length < 2) return false;
            int major = Integer.parseInt(p[0]);
            int minor = Integer.parseInt(p[1]);
            return major > 1 || (major == 1 && minor >= 13);
        } catch (Exception e) {
            return false;
        }
    }
}
