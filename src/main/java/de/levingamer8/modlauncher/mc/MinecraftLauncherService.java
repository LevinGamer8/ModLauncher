package de.levingamer8.modlauncher.mc;

import com.google.gson.JsonObject;
import de.levingamer8.modlauncher.core.LoaderType;
import de.levingamer8.modlauncher.runtime.JavaRuntimeManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
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

    /**
     * Startet Minecraft, blockiert bis Minecraft beendet ist, und speichert Playtime garantiert.
     * @return exit code von Minecraft
     */
    public int launch(Path sharedRoot,
                      Path instanceGameDir,
                      Path instanceRuntimeDir,
                      LaunchSpec spec,
                      AuthSession auth,
                      Consumer<String> log) throws Exception {

        final Consumer<String> L = safeLog(log);

        Files.createDirectories(sharedRoot);
        Files.createDirectories(instanceGameDir);
        Files.createDirectories(instanceRuntimeDir);

        PlaytimeStore instancePlaytime = new PlaytimeStore(instanceRuntimeDir.resolve("playtime.properties"));
        PlaytimeStore globalPlaytime = new PlaytimeStore(sharedRoot.resolve("playtime_total.properties"));

        Path nativesDir = instanceRuntimeDir.resolve("natives");
        Files.createDirectories(nativesDir);

        vanillaInstaller.ensureVanillaInstalled(sharedRoot, spec.mcVersion(), L);
        mojang.ensureAssetIndex(sharedRoot, spec.mcVersion());
        cleanupSharedRootArtifacts(sharedRoot, spec.mcVersion(), L);

        Path assetIndex = sharedRoot.resolve("assets").resolve("indexes").resolve(spec.mcVersion() + ".json");
        if (!Files.exists(assetIndex)) {
            throw new IllegalStateException("Asset index fehlt nach Install: " + assetIndex);
        }

        mojang.ensureVersionJson(sharedRoot, spec.mcVersion());
        mojang.ensureClientJar(sharedRoot, spec.mcVersion());

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
            versionId = modernForge.installForge(sharedRoot, mc, forgeVer, L);

        } else {
            throw new IllegalStateException("Unbekannter LoaderType: " + spec.loaderType());
        }

        JsonObject v = resolver.resolveMergedVersionJson(sharedRoot, versionId);

        List<Path> cp = new ArrayList<>(libraryService.ensureClasspath(sharedRoot, v));
        if (spec.loaderType() == LoaderType.FORGE) {
            removeGameJarFromClasspath(cp, sharedRoot, v, versionId);
        } else {
            ensureGameJarOnClasspath(cp, sharedRoot, v, versionId);
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("natives_directory", nativesDir.toAbsolutePath().toString());
        vars.put("library_directory", sharedRoot.resolve("libraries").toAbsolutePath().toString());
        vars.put("classpath_separator", System.getProperty("path.separator"));
        vars.put("classpath", ArgsBuilder.joinClasspath(cp));

        vars.put("auth_player_name", auth.playerName());
        vars.put("auth_uuid", auth.uuid());
        vars.put("auth_access_token", auth.accessToken());
        vars.put("user_type", auth.userType());

        vars.put("version_name", versionId);
        vars.put("version_type", spec.loaderType().name().toLowerCase(Locale.ROOT));

        vars.put("game_directory", instanceGameDir.toAbsolutePath().toString());
        vars.put("assets_root", sharedRoot.resolve("assets").toAbsolutePath().toString());
        vars.put("assets_index_name", spec.mcVersion());

        vars.putIfAbsent("resolution_width", "854");
        vars.putIfAbsent("resolution_height", "480");

        vars.put("launcher_name", LAUNCHER_NAME);
        vars.put("launcher_version", LAUNCHER_VERSION);

        ArgsBuilder argsBuilder = new ArgsBuilder();

        List<String> jvmArgs = sanitizeUnresolvedArgs(argsBuilder.buildJvmArgs(v, vars));
        List<String> gameArgs = sanitizeUnresolvedArgs(argsBuilder.buildGameArgs(v, vars));

        gameArgs = dropOptionsMissingValue(gameArgs, Set.of(
                "--width", "--height",
                "--clientId", "--xuid",
                "--quickPlayPath", "--quickPlaySingleplayer", "--quickPlayMultiplayer", "--quickPlayRealms"
        ));
        gameArgs.removeIf("--demo"::equals);

        jvmArgs = ensureMemoryArgs(jvmArgs, spec.memoryMb());

        String mainClass = v.get("mainClass").getAsString();
        Path javaExe = JavaRuntimeManager.ensureJava(spec.mcVersion(), L);

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toString());
        cmd.addAll(jvmArgs);
        cmd.add(mainClass);
        cmd.addAll(gameArgs);

        L.accept("[LAUNCH] " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(instanceGameDir.toFile());
        pb.redirectErrorStream(true);

        Instant startedAt = Instant.now();
        Process p = pb.start();


        ExecutorService es = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mc-stdout");
            t.setDaemon(true);
            return t;
        });

        Future<?> reader = es.submit(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    L.accept("[MC] " + line);
                }
            } catch (Exception e) {
                L.accept("[MC] log-reader ended: " + e.getMessage());
            }
        });

        int exit;
        try {
            exit = p.waitFor();
            try { reader.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}

        } finally {
            es.shutdownNow();

            Instant endedAt = Instant.now();
            long addSec = java.time.Duration.between(startedAt, endedAt).getSeconds();
            L.accept("[PLAYTIME] Session seconds = " + addSec);


            instancePlaytime.addSession(startedAt, endedAt);
            globalPlaytime.addSession(startedAt, endedAt);


            try {
                instancePlaytime.save();
            } catch (Throwable ignored) {}

            try {
                globalPlaytime.save();
            } catch (Throwable ignored) {}

            L.accept("[PLAYTIME] Instanz: " + instancePlaytime.getTotalPretty());
            L.accept("[PLAYTIME] Gesamt:  " + globalPlaytime.getTotalPretty());


            if (p.isAlive()) {
                p.destroy();
                try { p.waitFor(1, TimeUnit.SECONDS); } catch (Exception ignored) {}
                if (p.isAlive()) p.destroyForcibly();
            }
        }


        if (exit != 0) {
            L.accept("[LAUNCH] Minecraft exited with code " + exit);
        }

        return exit;
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
            if (i + 1 >= args.size()) continue;
            String next = args.get(i + 1);
            if (next == null || next.isBlank() || next.startsWith("--")) continue;
            out.add(a);
            out.add(next);
            i++;
        }
        return out;
    }

    private static List<String> sanitizeUnresolvedArgs(List<String> args) {
        List<String> out = new ArrayList<>(args.size());
        for (String a : args) {
            if (a == null) continue;
            if (a.isBlank()) continue;
            if (a.contains("${")) continue;
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
            Path rootJson = sharedRoot.resolve(mcVersion + ".json");
            if (Files.exists(rootJson) && Files.size(rootJson) > 0) {
                String txt = Files.readString(rootJson);

                boolean looksLikeAssetIndex = txt.contains("\"objects\"");
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
}
