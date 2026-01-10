package de.levingamer8.modlauncher.runtime;

import fr.theshark34.openlauncherlib.LaunchException;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class JavaRuntimeManager {

    private JavaRuntimeManager() {}

    /** Root: %USERPROFILE%\.modlauncher\java */
    public static Path getJavaRootDir() {
        return Path.of(System.getenv("APPDATA"), ".modlauncher", "java");
    }

    /**
     * Wählt die Java Major-Version abhängig von MC.
     * - <= 1.16.x -> 8
     * - 1.17.x .. 1.20.4 -> 17
     * - >= 1.20.5 -> 21
     */
    public static int requiredJavaMajor(String mcVersion) throws LaunchException {
        int[] v = parseMcVersion(mcVersion);
        int major = v[0], minor = v[1], patch = v[2];

        if (major != 1) throw new LaunchException("Unbekannte MC-Version: " + mcVersion);

        if (minor <= 16) return 8;
        if (minor <= 20) {
            // 1.20.5+ => Java 21
            if (minor == 20 && patch >= 5) return 21;
            return 17;
        }
        // 1.21+ => Java 21
        return 21;
    }

    public static Path ensureJava(String mcVersion, Consumer<String> log) throws LaunchException {
        int javaMajor = requiredJavaMajor(mcVersion);

        Path root = getJavaRootDir();
        Path majorDir = root.resolve(String.valueOf(javaMajor));
        Path javaExe = findJavaExeIn(majorDir);

        if (javaExe != null) {
            if (log != null) log.accept("[JAVA] Nutze vorhandenes Java " + javaMajor + ": " + javaExe);
            return javaExe;
        }

        // Nicht gefunden -> installieren
        try {
            Files.createDirectories(majorDir);
        } catch (IOException e) {
            throw new LaunchException("Kann Java-Ordner nicht erstellen: " + majorDir + " (" + e.getMessage() + ")", e);
        }

        if (log != null) log.accept("[JAVA] Java " + javaMajor + " nicht gefunden -> installiere nach: " + majorDir);

        Path zip = majorDir.resolve("temurin-" + javaMajor + "-windows-x64.zip");
        downloadTemurinZip(javaMajor, zip, log);

        Path extractDir = majorDir.resolve("jdk");
        deleteDirectoryIfExists(extractDir);
        unzip(zip, extractDir);

        // Nach Entpacken suchen
        javaExe = findJavaExeIn(extractDir);
        if (javaExe == null) {
            throw new LaunchException("Java installiert, aber java.exe nicht gefunden in: " + extractDir);
        }

        if (log != null) log.accept("[JAVA] Install ok: " + javaExe);
        return javaExe;
    }

    private static void downloadTemurinZip(int javaMajor, Path targetZip, Consumer<String> log) throws LaunchException {
        // Adoptium API (Temurin) – liefert ZIP (Windows x64) inkl. Redirect
        String url = "https://api.adoptium.net/v3/binary/latest/" + javaMajor
                + "/ga/windows/x64/jdk/hotspot/normal/eclipse";

        if (log != null) log.accept("[JAVA] Download: " + url);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        try {
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new LaunchException("Java Download fehlgeschlagen (HTTP " + resp.statusCode() + "): " + url);
            }

            // Stream -> Datei
            try (InputStream in = resp.body()) {
                Files.copy(in, targetZip, StandardCopyOption.REPLACE_EXISTING);
            }

        } catch (IOException | InterruptedException e) {
            throw new LaunchException("Java Download fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    private static void unzip(Path zipFile, Path outDir) throws LaunchException {
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new LaunchException("Kann Zielordner nicht erstellen: " + outDir, e);
        }

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = outDir.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(outDir)) {
                    throw new LaunchException("ZipSlip erkannt: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(outPath))) {
                        zis.transferTo(os);
                    }
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new LaunchException("Entpacken fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    private static Path findJavaExeIn(Path dir) {
        if (dir == null || !Files.exists(dir)) return null;

        // typischer Pfad: <dir>\jdk\<something>\bin\java.exe
        // wir suchen rekursiv die erste passende.
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("java.exe"))
                    .filter(p -> p.toString().toLowerCase().contains(File.separator + "bin" + File.separator))
                    .findFirst()
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void deleteDirectoryIfExists(Path dir) throws LaunchException {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            throw new LaunchException("Kann Ordner nicht löschen: " + dir + " (" + e.getMessage() + ")", e);
        }
    }

    /** Parse "1.20.1" => [1,20,1] */
    private static int[] parseMcVersion(String mcVersion) throws LaunchException {
        try {
            String[] parts = mcVersion.trim().split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return new int[]{major, minor, patch};
        } catch (Exception e) {
            throw new LaunchException("MC-Version ungültig: " + mcVersion);
        }
    }
}
