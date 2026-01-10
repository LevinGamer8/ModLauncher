package de.levingamer8.modlauncher.mc;

import com.google.gson.JsonObject;
import de.levingamer8.modlauncher.core.LoaderType;
import de.levingamer8.modlauncher.runtime.JavaRuntimeManager;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class MinecraftLauncherService {

    public record AuthSession(String playerName, String uuid, String accessToken, String userType) {}
    public record LaunchSpec(String mcVersion, LoaderType loaderType, String loaderVersion, int memoryMb) {}

    private final MojangDownloader mojang = new MojangDownloader();
    private final MinecraftInstaller vanillaInstaller = new MinecraftInstaller(); // FlowUpdater wrapper
    private final FabricInstaller fabricInstaller = new FabricInstaller();
    private final ForgeInstaller1122Plus forgeInstaller = new ForgeInstaller1122Plus();
    private final MojangVersionResolver resolver = new MojangVersionResolver();
    private final LibraryService libraryService = new LibraryService();
    private final ArgsBuilder argsBuilder = new ArgsBuilder();

    public Process launch(
            Path sharedRoot,
            Path instanceGameDir,
            Path instanceRuntimeDir,
            LaunchSpec spec,
            AuthSession auth,
            Consumer<String> log
    ) throws Exception {

        Files.createDirectories(sharedRoot);
        Files.createDirectories(instanceGameDir);
        Files.createDirectories(instanceRuntimeDir);

        // 1) Vanilla assets/libs via FlowUpdater
        vanillaInstaller.ensureVanillaInstalled(sharedRoot, spec.mcVersion(), safeLog(log));

        // 2) Mojang version json + client jar garantieren
        mojang.ensureVersionJson(sharedRoot, spec.mcVersion());
        mojang.ensureClientJar(sharedRoot, spec.mcVersion());

        // 3) Loader versionId bestimmen
        String versionId;
        String effectiveLoaderVersion = spec.loaderVersion(); // nur für Logging/Forge

        if (spec.loaderType() == LoaderType.VANILLA) {
            versionId = spec.mcVersion();
        } else if (spec.loaderType() == LoaderType.FABRIC) {
            // >>> Production-Mode: immer latest Fabric Loader passend zur MC-Version <<<
            FabricInstaller.LatestFabric latest = fabricInstaller.fetchLatestLoaderForMc(spec.mcVersion());
            effectiveLoaderVersion = latest.loaderVersion();
            if (log != null) log.accept("[FABRIC] Verwende latest loader für " + spec.mcVersion() + ": " + effectiveLoaderVersion);

            versionId = fabricInstaller.ensureFabricVersion(sharedRoot, spec.mcVersion(), effectiveLoaderVersion);

            // Fabric API auto installieren (mit effektivem Loader)
            FabricApiAutoInstaller fai = new FabricApiAutoInstaller();
            FabricApiAutoInstaller.Result r = fai.ensureFabricApiInstalled(instanceGameDir, spec.mcVersion(), effectiveLoaderVersion, log);

            // Falls r.jarPath == null -> heißt: selbst mit latest Loader geht’s nicht (sollte praktisch nie passieren)
            if (r.jarPath() == null && r.requiredLoaderMinOrNull() != null) {
                throw new IllegalStateException("Fabric API benötigt Loader >= " + r.requiredLoaderMinOrNull()
                        + ", aber latest Loader ist " + effectiveLoaderVersion + " (unplausibel).");
            }
        } else if (spec.loaderType() == LoaderType.FORGE) {
            // Forge bleibt wie gehabt (bis Processor-Installer drin ist)
            versionId = forgeInstaller.ensureForgeVersion(sharedRoot, spec.mcVersion(), spec.loaderVersion());
        } else {
            throw new IllegalStateException("Unbekannter LoaderType: " + spec.loaderType());
        }

        // 4) merged version json
        JsonObject v = resolver.resolveMergedVersionJson(sharedRoot, versionId);

        // 5) classpath sicherstellen
        List<Path> cp = libraryService.ensureClasspath(sharedRoot, v);

        // 6) natives extrahieren (pro instanz)
        String fp = Integer.toHexString(Objects.hash(versionId, "windows-x64"));
        Path nativesDir = instanceRuntimeDir.resolve("natives").resolve(fp);
        if (Files.exists(nativesDir)) deleteRecursive(nativesDir);
        Files.createDirectories(nativesDir);

        libraryService.extractNativesX64(nativesDir, sharedRoot, v);

        // 7) assets index
        String assetsIndex = v.has("assetIndex") && v.getAsJsonObject("assetIndex").has("id")
                ? v.getAsJsonObject("assetIndex").get("id").getAsString()
                : (v.has("assets") ? v.get("assets").getAsString() : "legacy");

        // 8) vars
        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", auth.playerName());
        vars.put("auth_uuid", auth.uuid());
        vars.put("auth_access_token", auth.accessToken());
        vars.put("user_type", auth.userType());

        vars.put("version_name", v.get("id").getAsString());
        vars.put("version_type", v.has("type") ? v.get("type").getAsString() : "release");

        vars.put("game_directory", instanceGameDir.toString());
        vars.put("assets_root", sharedRoot.resolve("assets").toString());
        vars.put("assets_index_name", assetsIndex);
        vars.put("classpath", ArgsBuilder.joinClasspath(cp));
        vars.put("natives_directory", nativesDir.toString());

        vars.put("launcher_name", "ModLauncher");
        vars.put("launcher_version", "1");

        // defaults, damit weniger rausfliegt
        vars.put("resolution_width", "854");
        vars.put("resolution_height", "480");
        vars.put("clientid", "");
        vars.put("auth_xuid", "");
        vars.put("quickPlayPath", "");
        vars.put("quickPlaySingleplayer", "");
        vars.put("quickPlayMultiplayer", "");
        vars.put("quickPlayRealms", "");

        // 9) args
        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.add("-Xmx" + spec.memoryMb() + "M");
        jvmArgs.add("-Xms" + Math.min(512, spec.memoryMb()) + "M");
        jvmArgs.add("-Djava.library.path=" + nativesDir);
        jvmArgs.addAll(argsBuilder.buildJvmArgs(v, vars));
        jvmArgs = sanitizeJvmArgs(jvmArgs);

        List<String> gameArgs = argsBuilder.buildGameArgs(v, vars);
        gameArgs = sanitizeUnresolvedArgs(gameArgs);

        // 10) main + java
        String mainClass = v.get("mainClass").getAsString();
        Path javaExe = JavaRuntimeManager.ensureJava(spec.mcVersion(), safeLog(log));

        // 11) cmd
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toString());
        cmd.addAll(jvmArgs);
        cmd.add("-cp");
        cmd.add(ArgsBuilder.joinClasspath(cp));
        cmd.add(mainClass);
        cmd.addAll(gameArgs);

        if (log != null) log.accept("[LAUNCH] " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(instanceGameDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();

        // stdout -> log
        new Thread(() -> {
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (log != null) log.accept("[MC] " + line);
                }
            } catch (Exception ignored) {}
        }, "mc-stdout").start();

        return p;
    }

    private static Consumer<String> safeLog(Consumer<String> log) {
        return log != null ? log : (s) -> {};
    }

    private static List<String> sanitizeJvmArgs(List<String> in) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            String a = in.get(i);

            // -cp setzen wir selbst
            if ("-cp".equals(a) || "-classpath".equals(a)) {
                i++;
                continue;
            }
            // java.library.path setzen wir selbst
            if (a.startsWith("-Djava.library.path=")) continue;

            out.add(a);
        }
        return out;
    }

    private static List<String> sanitizeUnresolvedArgs(List<String> args) {
        // 1) Entferne unresolved ${...}
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);

            if (a.contains("${")) continue;

            out.add(a);
        }

        // 2) Entferne Flags, deren Value leer/fehlt ist
        List<String> cleaned = new ArrayList<>();
        Set<String> quickPlayFlags = Set.of(
                "--quickPlayPath",
                "--quickPlaySingleplayer",
                "--quickPlayMultiplayer",
                "--quickPlayRealms"
        );

        String chosenQuickPlayFlag = null;

        for (int i = 0; i < out.size(); i++) {
            String a = out.get(i);

            // QuickPlay: nur eine Option zulassen
            if (quickPlayFlags.contains(a)) {
                String v = (i + 1 < out.size()) ? out.get(i + 1) : null;

                // wenn value fehlt/leer -> komplett raus (Flag + ggf. value)
                if (v == null || v.isBlank() || v.startsWith("--")) {
                    continue;
                }

                // wenn schon eine QuickPlay-Option gewählt -> diese komplett skippen
                if (chosenQuickPlayFlag != null) {
                    i++; // auch value skippen
                    continue;
                }

                // erste gültige QuickPlay-Option behalten
                chosenQuickPlayFlag = a;
                cleaned.add(a);
                cleaned.add(v);
                i++; // value consumed
                continue;
            }

            // generisches: wenn Flag kommt und value fehlt/leer -> Flag skippen
            if (a.startsWith("--")) {
                if (i + 1 < out.size()) {
                    String v = out.get(i + 1);
                    if (v.isBlank()) {
                        i++; // value skippen
                        continue;
                    }
                    // Spezialfall: nächstes ist wieder ein Flag -> value fehlt
                    if (v.startsWith("--")) {
                        continue;
                    }
                }
            }

            cleaned.add(a);
        }

        return cleaned;
    }


    private static void deleteRecursive(Path dir) throws Exception {
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }
}
