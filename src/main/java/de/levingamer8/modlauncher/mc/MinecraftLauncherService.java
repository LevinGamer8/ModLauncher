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
    private final ForgeInstaller1122Plus legacyForge = new ForgeInstaller1122Plus();

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

        // optional: Root-Müll bereinigen (best-effort)
        cleanupSharedRootArtifacts(sharedRoot, L);

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

            if (isAtLeast13(mc)) {
                // Modern Forge (1.13+)
                versionId = modernForge.installForge(sharedRoot, mc, forgeVer, L);
            } else {
                // Legacy Forge (z.B. 1.12.2)
                Path installerJar = downloadForgeInstallerJar(sharedRoot, mc, forgeVer, L);
                versionId = legacyForge.installForgeClient(sharedRoot, mc, forgeVer, installerJar, L);
            }

        } else {
            throw new IllegalStateException("Unbekannter LoaderType: " + spec.loaderType());
        }

        // --- 4) merged version json (inheritsFrom auflösen) ---
        JsonObject v = resolver.resolveMergedVersionJson(sharedRoot, versionId);

        // --- 5) Classpath sicherstellen (Libraries + ggf. Version-Jar) ---
        List<Path> cp = new ArrayList<>(libraryService.ensureClasspath(sharedRoot, v));
        ensureVersionJarOnClasspath(cp, sharedRoot, versionId);

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

        // Launcher identity (kommt in manchen JSONs vor)
        vars.put("launcher_name", LAUNCHER_NAME);
        vars.put("launcher_version", LAUNCHER_VERSION);

        // --- 7) Args aus JSON bauen (sauber, ohne nachträgliches Gepfusche) ---
        ArgsBuilder argsBuilder = new ArgsBuilder();

        List<String> jvmArgs = sanitizeUnresolvedArgs(argsBuilder.buildJvmArgs(v, vars));
        List<String> gameArgs = sanitizeUnresolvedArgs(argsBuilder.buildGameArgs(v, vars));

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

    /**
     * Entfernt nur wirklich unresolved Tokens, die NICHT ersetzt wurden.
     * Wenn ArgsBuilder korrekt ersetzt, bleibt hier praktisch alles drin.
     */
    private static List<String> sanitizeUnresolvedArgs(List<String> args) {
        List<String> out = new ArrayList<>(args.size());
        for (String a : args) {
            if (a == null) continue;
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

    private static void ensureVersionJarOnClasspath(List<Path> cp, Path sharedRoot, String versionId) throws Exception {
        Path jar = sharedRoot.resolve("versions").resolve(versionId).resolve(versionId + ".jar");
        if (!Files.exists(jar)) {
            // falls es ein inherited/proxy versionId ist, kann das Jar auch beim base mcVersion liegen
            // aber normalerweise sollte es existieren, sonst stimmt der Install/FlowUpdater nicht.
            throw new IllegalStateException("Version-Jar fehlt: " + jar);
        }
        for (Path p : cp) {
            if (p.toAbsolutePath().normalize().equals(jar.toAbsolutePath().normalize())) return;
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

    private static void cleanupSharedRootArtifacts(Path sharedRoot, Consumer<String> log) {
        try (var s = Files.list(sharedRoot)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();

                if (name.equalsIgnoreCase("client.jar")) {
                    try {
                        Files.deleteIfExists(p);
                        log.accept("[CLEANUP] gelöscht: " + p);
                    } catch (Exception ignored) {}
                    return;
                }

                // sharedRoot/1.20.1.json -> versions/1.20.1/1.20.1.json
                if (name.endsWith(".json") && name.matches("[0-9]+\\.[0-9]+(\\.[0-9]+)?\\.json")) {
                    String ver = name.substring(0, name.length() - 5);
                    Path dst = sharedRoot.resolve("versions").resolve(ver).resolve(ver + ".json");
                    try {
                        Files.createDirectories(dst.getParent());
                        if (!Files.exists(dst)) {
                            Files.move(p, dst, StandardCopyOption.REPLACE_EXISTING);
                            log.accept("[CLEANUP] moved " + p + " -> " + dst);
                        } else {
                            Files.deleteIfExists(p);
                            log.accept("[CLEANUP] deleted duplicate " + p);
                        }
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
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
