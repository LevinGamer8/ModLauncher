package de.levingamer8.modlauncher.mc;

import de.levingamer8.modlauncher.runtime.JavaRuntimeManager;
import fr.flowarg.openlauncherlib.NewForgeVersionDiscriminator;
import fr.theshark34.openlauncherlib.LaunchException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class MinecraftLauncherService {

    private MinecraftLauncherService() {}

    private static void mustExist(Path p, String what) throws LaunchException {
        if (!Files.exists(p)) throw new LaunchException("Fehlt: " + what + " (" + p + ")");
    }

    private static String quoteIfNeeded(String s) {
        if (s.indexOf(' ') >= 0 || s.indexOf('\t') >= 0) return "\"" + s + "\"";
        return s;
    }

    private static List<Path> collectAllLibraryJars(Path gameDir) throws LaunchException {
        Path libs = gameDir.resolve("libraries");
        mustExist(libs, "libraries");

        try (Stream<Path> s = Files.walk(libs)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted()
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception e) {
            throw new LaunchException("Libraries sammeln fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    // --------- Normalizer (damit Module-Resolution nicht wieder explodiert) ---------

    private static List<Path> normalizeAsm(List<Path> jars) {
        List<Path> asmJars = jars.stream()
                .filter(p -> p.toString().replace('\\','/').toLowerCase().contains("/org/ow2/asm/"))
                .collect(Collectors.toList());
        if (asmJars.isEmpty()) return jars;

        List<String> required = List.of("asm", "asm-tree", "asm-analysis", "asm-commons", "asm-util");
        Map<String, Set<String>> byArtifact = new HashMap<>();

        for (Path p : asmJars) {
            String path = p.toString().replace('\\','/');
            String[] parts = path.split("/");

            // .../org/ow2/asm/<artifact>/<version>/<jar>
            int idx = -1;
            for (int i = 0; i < parts.length; i++) {
                if (i >= 2 && parts[i].equalsIgnoreCase("asm")
                        && parts[i-1].equalsIgnoreCase("ow2")
                        && parts[i-2].equalsIgnoreCase("org")) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0 && idx + 2 < parts.length) {
                String artifact = parts[idx + 1];
                String version  = parts[idx + 2];
                byArtifact.computeIfAbsent(artifact, k -> new HashSet<>()).add(version);
            }
        }

        Set<String> candidates = null;
        for (String art : required) {
            Set<String> versions = byArtifact.getOrDefault(art, Set.of());
            if (candidates == null) candidates = new HashSet<>(versions);
            else candidates.retainAll(versions);
        }
        if (candidates == null || candidates.isEmpty()) return jars;

        // höchste Version nehmen
        String chosen = candidates.stream().sorted().reduce((a,b) -> b).orElse(null);
        if (chosen == null) return jars;

        final String v = chosen.toLowerCase();
        return jars.stream().filter(p -> {
            String path = p.toString().replace('\\','/').toLowerCase();
            if (!path.contains("/org/ow2/asm/")) return true;

            for (String art : required) {
                String a = art.toLowerCase();
                if (path.contains("/org/ow2/asm/" + a + "/")) {
                    return path.contains("/org/ow2/asm/" + a + "/" + v + "/");
                }
            }
            return true;
        }).collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<Path> keepOnlyOneVersionByFolder(List<Path> jars, String folderMarker, boolean preferStable) {
        // folderMarker z.B. "/net/sf/jopt-simple/jopt-simple/"
        List<Path> hits = jars.stream()
                .filter(p -> p.toString().replace('\\','/').toLowerCase().contains(folderMarker))
                .collect(Collectors.toList());
        if (hits.size() <= 1) return jars;

        Map<String, List<Path>> byVersion = new HashMap<>();
        for (Path p : hits) {
            String path = p.toString().replace('\\','/');
            String[] parts = path.split("/");
            for (int i = 0; i < parts.length - 2; i++) {
                if (parts[i].toLowerCase().equals(folderMarker.substring(folderMarker.lastIndexOf('/')+1).replace("/",""))) {
                    // nicht zuverlässig - deshalb: besser Version aus dem direkten Parent nehmen:
                    // wir suchen das Segment NACH dem marker im Pfad
                }
            }
            // robust: Version ist i.d.R. das Parent-Verzeichnis der Jar-Datei
            Path parent = p.getParent();
            if (parent != null) {
                String version = parent.getFileName().toString();
                byVersion.computeIfAbsent(version, k -> new ArrayList<>()).add(p);
            }
        }
        if (byVersion.isEmpty()) return jars;

        List<String> versions = byVersion.keySet().stream().sorted().collect(Collectors.toList());

        String chosen = null;
        if (preferStable) {
            List<String> stable = versions.stream().filter(v -> {
                String lv = v.toLowerCase();
                return !(lv.contains("alpha") || lv.contains("beta") || lv.contains("rc"));
            }).collect(Collectors.toList());
            if (!stable.isEmpty()) chosen = stable.get(stable.size()-1);
        }
        if (chosen == null) chosen = versions.get(versions.size()-1);

        Set<Path> keep = new HashSet<>(byVersion.getOrDefault(chosen, List.of()));

        return jars.stream().filter(p -> {
            String s = p.toString().replace('\\','/').toLowerCase();
            if (!s.contains(folderMarker)) return true;
            return keep.contains(p);
        }).collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<Path> normalizeJoptSimple(List<Path> jars) {
        // Forge/Minecraft brauchen praktisch immer jopt-simple 5.x, 6-alpha ist Mist hier.
        return keepOnlyOneVersionByFolder(jars, "/net/sf/jopt-simple/jopt-simple/", true);
    }

    private static List<Path> normalizeGson(List<Path> jars) {
        return keepOnlyOneVersionByFolder(jars, "/com/google/code/gson/gson/", false);
    }

    // --------- module-path filter ---------

    private static boolean isBadForModulePath(Path p) {
        String s = p.toString().replace('\\','/').toLowerCase();
        String name = p.getFileName().toString().toLowerCase();

        // diese Minecraft client jars sind NICHT modulfähig -> killt boot layer
        if (s.contains("/net/minecraft/client/")) return true;
        if (name.contains("-slim.jar")) return true;
        if (name.contains("-srg.jar")) return true;
        if (name.contains("-extra.jar")) return true;

        // optional: manche haben irgendwo random top-level classes (unnamed package)
        // das kann man nicht sicher erkennen ohne reinzuschauen -> lassen wir weg.

        return false;
    }

    public static Process launchForgeBootstrap(
            Path gameDir,
            String mcVersion,
            String forgeProfileId,
            String playerName,
            int ramMb,
            Consumer<String> log
    ) throws LaunchException {

        Path root = gameDir.toAbsolutePath();
        mustExist(root, "GameDir");
        mustExist(root.resolve("assets"), "assets");
        mustExist(root.resolve("libraries"), "libraries");
        mustExist(root.resolve("client.jar"), "client.jar");

        Path forgeJson = root.resolve("versions")
                .resolve(forgeProfileId)
                .resolve(forgeProfileId + ".json");
        mustExist(forgeJson, "Forge Version JSON");

        final NewForgeVersionDiscriminator nfvd;
        try {
            nfvd = new NewForgeVersionDiscriminator(forgeJson);
            if (log != null) log.accept("[BOOT] NFVD aus JSON: forge=" + nfvd.getForgeVersion()
                    + " mc=" + nfvd.getMcVersion()
                    + " mcp=" + nfvd.getMcpVersion()
                    + " group=" + nfvd.getForgeGroup());
        } catch (Exception e) {
            throw new LaunchException("NFVD lesen fehlgeschlagen: " + e.getMessage(), e);
        }

        Path javaExePath = JavaRuntimeManager.ensureJava(mcVersion, log);
        String javaExe = javaExePath.toAbsolutePath().toString();

        // libs sammeln + normalize (wichtig!)
        List<Path> libJars = collectAllLibraryJars(root);
        libJars = normalizeAsm(libJars);
        libJars = normalizeJoptSimple(libJars);
        libJars = normalizeGson(libJars);

        // module-path: alle libs außer kaputte minecraft client jars
        List<Path> modulePathJars = libJars.stream()
                .filter(p -> !isBadForModulePath(p))
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));

        String modulePath = modulePathJars.stream()
                .map(p -> p.toAbsolutePath().toString())
                .collect(Collectors.joining(java.io.File.pathSeparator));

        // classpath: alle libs + client.jar (Forge ist da tolerant)
        Path clientJar = root.resolve("client.jar");
        List<Path> cpJars = new ArrayList<>(libJars);
        cpJars.add(clientJar);

        String classpath = cpJars.stream()
                .map(p -> p.toAbsolutePath().toString())
                .collect(Collectors.joining(java.io.File.pathSeparator));

        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8)).toString();

        // Windows 206 fix: @argfile
        Path argsFile = root.resolve(".modlauncher_jvm_args.txt");
        List<String> argLines = new ArrayList<>();

        argLines.add("-Djava.library.path=.");
        argLines.add("-Dfml.ignoreInvalidMinecraftCertificates=true");
        argLines.add("-Dfml.ignorePatchDiscrepancies=true");

        argLines.add("--add-opens=java.base/java.lang.invoke=ALL-UNNAMED");
        argLines.add("--add-opens=java.base/java.util=ALL-UNNAMED");

        // <<< DAS ist hier Pflicht, sonst fehlen ASM-Module >>>
        argLines.add("--module-path");
        argLines.add(quoteIfNeeded(modulePath));
        argLines.add("--add-modules");
        argLines.add("ALL-MODULE-PATH");

        argLines.add("-Xms512M");
        argLines.add("-Xmx" + ramMb + "M");
        argLines.add("-Dfile.encoding=UTF-8");
        argLines.add("-Dsun.stdout.encoding=UTF-8");
        argLines.add("-Dsun.stderr.encoding=UTF-8");

        argLines.add("-cp");
        argLines.add(quoteIfNeeded(classpath));

        try {
            Files.write(argsFile, argLines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new LaunchException("Konnte args file nicht schreiben: " + e.getMessage(), e);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("@" + argsFile.toAbsolutePath());
        cmd.add("cpw.mods.bootstraplauncher.BootstrapLauncher");

        cmd.add("--username");     cmd.add(playerName);
        cmd.add("--version");      cmd.add(forgeProfileId);
        cmd.add("--gameDir");      cmd.add(root.toString());
        cmd.add("--assetsDir");    cmd.add(root.resolve("assets").toString());
        cmd.add("--assetIndex");   cmd.add(forgeProfileId);

        cmd.add("--uuid");         cmd.add(uuid);
        cmd.add("--accessToken");  cmd.add("0");
        cmd.add("--userType");     cmd.add("mojang");
        cmd.add("--versionType");  cmd.add("release");

        cmd.add("--launchTarget"); cmd.add("forgeclient");

        cmd.add("--fml.forgeVersion"); cmd.add(nfvd.getForgeVersion());
        cmd.add("--fml.mcVersion");    cmd.add(nfvd.getMcVersion());
        cmd.add("--fml.forgeGroup");   cmd.add(nfvd.getForgeGroup());
        cmd.add("--fml.mcpVersion");   cmd.add(nfvd.getMcpVersion());

        if (log != null) {
            log.accept("[BOOT] Starte Forge via BootstrapLauncher...");
            log.accept("[BOOT] java=" + javaExe);
            log.accept("[BOOT] gameDir=" + root);
            log.accept("[BOOT] forgeProfileId=" + forgeProfileId);
            log.accept("[BOOT] cpJars=" + cpJars.size());
            log.accept("[BOOT] moduleJars=" + modulePathJars.size());
            log.accept("[BOOT] argsFile=" + argsFile.toAbsolutePath());
            log.accept("[BOOT] CMD: " + String.join(" ", cmd));
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(root.toFile());
        pb.redirectErrorStream(true);

        final Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new LaunchException("Konnte Minecraft-Prozess nicht starten: " + e.getMessage(), e);
        }

        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (log != null) log.accept("[MC] " + line);
                }
            } catch (Exception e) {
                if (log != null) log.accept("[MC] Log-Reader Fehler: " + e.getMessage());
            }
        }, "mc-stdout");
        reader.setDaemon(true);
        reader.start();

        Thread waiter = new Thread(() -> {
            try {
                int code = p.waitFor();
                if (log != null) log.accept("[MC] Prozess beendet. ExitCode=" + code);
            } catch (InterruptedException ignored) {}
        }, "mc-waiter");
        waiter.setDaemon(true);
        waiter.start();

        return p;
    }
}
