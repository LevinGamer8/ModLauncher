package de.levingamer8.modlauncher.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ProjectHostService {

    public record Selection(boolean mods, boolean config, boolean serversDat, boolean optionsTxt,
                            boolean kubejs, boolean defaultconfigs, boolean shaderpacks, boolean resourcepacks) {}

    public interface Log { void log(String s); }
    public interface Progress { void progress(long done, long total); }

    public static Path build(
            Path instanceDir,
            HostManifest manifest,
            Selection sel,
            Path outDir,
            Log log,
            Progress prog
    ) throws Exception {

        // Clean outDir
        if (Files.exists(outDir)) deleteRecursive(outDir);
        Files.createDirectories(outDir);

        Path filesDir = outDir.resolve("files");
        Path overridesDir = outDir.resolve("overrides");
        Files.createDirectories(filesDir);
        Files.createDirectories(overridesDir);

        log.log("[HOST] Instance: " + instanceDir);
        log.log("[HOST] Out: " + outDir);

        // Copy selected folders/files
        if (sel.mods) copyIfExists(instanceDir.resolve("mods"), filesDir.resolve("mods"), log);
        if (sel.shaderpacks) copyIfExists(instanceDir.resolve("shaderpacks"), filesDir.resolve("shaderpacks"), log);
        if (sel.resourcepacks) copyIfExists(instanceDir.resolve("resourcepacks"), filesDir.resolve("resourcepacks"), log);

        if (sel.config) copyIfExists(instanceDir.resolve("config"), overridesDir.resolve("config"), log);
        if (sel.defaultconfigs) copyIfExists(instanceDir.resolve("defaultconfigs"), overridesDir.resolve("defaultconfigs"), log);
        if (sel.kubejs) copyIfExists(instanceDir.resolve("kubejs"), overridesDir.resolve("kubejs"), log);
        if (sel.optionsTxt) copyFileIfExists(instanceDir.resolve("options.txt"), overridesDir.resolve("options.txt"), log);
        if (sel.serversDat) copyFileIfExists(instanceDir.resolve("servers.dat"), overridesDir.resolve("servers.dat"), log);

        // overrides.zip
        Path overridesZip = outDir.resolve("overrides.zip");
        zipFolder(overridesDir, overridesZip);
        manifest.overrides.size = Files.size(overridesZip);
        manifest.overrides.sha256 = sha256(overridesZip);

        // Fill manifest.files by scanning files/
        manifest.files.clear();
        List<Path> allFiles = new ArrayList<>();
        if (Files.exists(filesDir)) {
            try (var w = Files.walk(filesDir)) {
                w.filter(Files::isRegularFile).forEach(allFiles::add);
            }
        }

        long total = allFiles.size();
        long done = 0;

        for (Path f : allFiles) {
            String rel = filesDir.relativize(f).toString().replace("\\", "/");
            HostManifest.FileEntry e = new HostManifest.FileEntry();
            e.path = rel;
            e.size = Files.size(f);
            e.sha256 = sha256(f);
            manifest.files.add(e);

            done++;
            prog.progress(done, total);
        }

        // Write manifest.json
        Path manifestFile = outDir.resolve("manifest.json");
        var om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        om.writeValue(manifestFile.toFile(), manifest);

        log.log("[HOST] Build fertig. files=" + manifest.files.size() + " overrides.zip=" + manifest.overrides.size + " bytes");
        return outDir;
    }

    public static void uploadPutPathQuery(
            String uploadUrl,
            String projectId,
            Path builtDir,
            String bearerToken,
            Log log,
            Progress prog
    ) throws Exception {
        // Upload everything:
        // <projectId>/manifest.json
        // <projectId>/overrides.zip
        // <projectId>/files/**

        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

        List<Path> toUpload = new ArrayList<>();
        toUpload.add(builtDir.resolve("manifest.json"));
        toUpload.add(builtDir.resolve("overrides.zip"));

        Path filesDir = builtDir.resolve("files");
        if (Files.exists(filesDir)) {
            try (var w = Files.walk(filesDir)) {
                w.filter(Files::isRegularFile).forEach(toUpload::add);
            }
        }

        long total = toUpload.size();
        long done = 0;

        for (Path f : toUpload) {
            String rel;
            if (f.endsWith("manifest.json")) rel = projectId + "/manifest.json";
            else if (f.endsWith("overrides.zip")) rel = projectId + "/overrides.zip";
            else rel = projectId + "/files/" + filesDir.relativize(f).toString().replace("\\", "/");

            putFile(client, uploadUrl, rel, f, bearerToken);
            done++;
            prog.progress(done, total);
            log.log("[UPLOAD] OK: " + rel);
        }
    }

    private static void putFile(HttpClient client, String baseUrl, String relPath, Path file, String token) throws Exception {
        String url = baseUrl + "?path=" + URLEncoder.encode(relPath, StandardCharsets.UTF_8);
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofFile(file))
                .header("Content-Type", "application/octet-stream");

        if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token);

        HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        int sc = resp.statusCode();
        if (sc < 200 || sc >= 300) {
            throw new RuntimeException("Upload failed " + sc + " for " + relPath + " | " + resp.body());
        }
    }

    private static void copyIfExists(Path src, Path dst, Log log) throws IOException {
        if (!Files.exists(src)) return;
        log.log("[COPY] " + src.getFileName() + " -> " + dst);
        copyRecursively(src, dst);
    }

    private static void copyFileIfExists(Path src, Path dst, Log log) throws IOException {
        if (!Files.exists(src) || Files.isDirectory(src)) return;
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        log.log("[COPY] " + src.getFileName() + " -> " + dst);
    }

    private static void copyRecursively(Path src, Path dst) throws IOException {
        if (Files.isDirectory(src)) {
            Files.walk(src).forEach(p -> {
                try {
                    Path rel = src.relativize(p);
                    Path out = dst.resolve(rel);
                    if (Files.isDirectory(p)) Files.createDirectories(out);
                    else {
                        Files.createDirectories(out.getParent());
                        Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        } else {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void zipFolder(Path folder, Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            if (!Files.exists(folder)) return;
            Files.walk(folder).forEach(p -> {
                try {
                    if (Files.isDirectory(p)) return;
                    Path rel = folder.relativize(p);
                    ZipEntry entry = new ZipEntry(rel.toString().replace("\\", "/"));
                    zos.putNextEntry(entry);
                    Files.copy(p, zos);
                    zos.closeEntry();
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream din = new DigestInputStream(in, md)) {
            byte[] buf = new byte[8192];
            while (din.read(buf) != -1) {}
        }
        byte[] hash = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var w = Files.walk(p)) {
            w.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
    }
}
