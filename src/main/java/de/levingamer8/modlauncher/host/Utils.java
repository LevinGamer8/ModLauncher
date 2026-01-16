package de.levingamer8.modlauncher.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import java.util.List;

public class Utils {

    public static String sha256(Path file) throws Exception {
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

    public static void copyRecursively(Path src, Path dst) throws IOException {
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

    public static void zipFolder(Path folder, Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
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

    public static Path buildProject(
            Manifest manifest,
            List<MappingEntry> entries,
            Path buildRoot // z.B. Paths.get("build").resolve(manifest.projectId)
    ) throws Exception {

        Path projectDir = buildRoot;
        Path filesDir = projectDir.resolve("files");
        Path overridesDir = projectDir.resolve("overrides");
        Files.createDirectories(filesDir);
        Files.createDirectories(overridesDir);

        // 1) Copy nach files/ und overrides/
        for (MappingEntry e : entries) {
            if (!e.enabled) continue;

            Path targetBase = (e.area == TargetArea.FILES) ? filesDir : overridesDir;
            Path target = targetBase.resolve(e.targetRelPath);

            // Wenn Quelle Datei ist und targetRelPath wie "mods/" endet, packe Datei in diesen Ordner
            if (!Files.isDirectory(e.source) && (e.targetRelPath.endsWith("/") || e.targetRelPath.endsWith("\\"))) {
                target = target.resolve(e.source.getFileName().toString());
            }

            copyRecursively(e.source, target);
        }

        // 2) overrides.zip
        Path overridesZip = projectDir.resolve("overrides.zip");
        zipFolder(overridesDir, overridesZip);

        // 3) Manifest: files[] auflisten (alles in files/)
        manifest.files.clear();
        Files.walk(filesDir).forEach(p -> {
            try {
                if (Files.isDirectory(p)) return;
                String rel = filesDir.relativize(p).toString().replace("\\", "/");
                Manifest.ManifestFile mf = new Manifest.ManifestFile();
                mf.path = rel;
                mf.size = Files.size(p);
                mf.sha256 = sha256(p);
                manifest.files.add(mf);
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });

        // 4) overrides Hash/Size
        manifest.overrides = new Manifest.Overrides();
        manifest.overrides.size = Files.size(overridesZip);
        manifest.overrides.sha256 = sha256(overridesZip);

        // 5) manifest.json schreiben
        Path manifestFile = projectDir.resolve("manifest.json");
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        om.writeValue(manifestFile.toFile(), manifest);

        return projectDir;
    }


    public static void httpPutFile(String uploadUrl, String relPath, Path file, String bearerToken) throws Exception {
        String url = uploadUrl + "?path=" + URLEncoder.encode(relPath.replace("\\","/"), StandardCharsets.UTF_8);

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofFile(file))
                .header("Content-Type", "application/octet-stream");

        if (bearerToken != null && !bearerToken.isBlank()) {
            b.header("Authorization", "Bearer " + bearerToken);
        }

        HttpResponse<String> resp = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("Upload failed " + resp.statusCode() + " - " + resp.body());
        }
    }

    public static void uploadBuiltProject(String uploadUrl, String projectId, Path projectDir, String bearerToken) throws Exception {
        // files/
        Path filesDir = projectDir.resolve("files");
        Files.walk(filesDir).forEach(p -> {
            try {
                if (Files.isDirectory(p)) return;
                String rel = filesDir.relativize(p).toString().replace("\\","/");
                httpPutFile(uploadUrl, projectId + "/files/" + rel, p, bearerToken);
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        // overrides.zip + manifest.json (Root)
        httpPutFile(uploadUrl, projectId + "/overrides.zip", projectDir.resolve("overrides.zip"), bearerToken);
        httpPutFile(uploadUrl, projectId + "/manifest.json", projectDir.resolve("manifest.json"), bearerToken);
    }





}
