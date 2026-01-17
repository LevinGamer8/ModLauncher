package de.levingamer8.modlauncher.host;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.levingamer8.modlauncher.core.ManifestModels;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public class HostManifestGenerator {

    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Scannt filesDir rekursiv und schreibt manifest.json neu (files[]).
     * @param manifestPath  versions/<ver>/manifest.json
     * @param filesDir      versions/<ver>/files
     */
    public ManifestModels.Manifest generate(Path manifestPath, Path filesDir) throws IOException {
        if (manifestPath == null) throw new IllegalArgumentException("manifestPath null");
        if (filesDir == null) throw new IllegalArgumentException("filesDir null");
        if (!Files.isDirectory(filesDir)) throw new IllegalArgumentException("filesDir ist kein Ordner: " + filesDir);

        ManifestModels.Manifest manifest = readManifest(manifestPath);

        List<ManifestFileEntry> entries = new ArrayList<>();

        List<ManifestModels.ManifestFile> files = new ArrayList<>();

        try (var stream = Files.walk(filesDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String rel = filesDir.relativize(p).toString().replace('\\', '/');
                    long size = Files.size(p);
                    String sha256 = sha256Hex(p);

                    String url = joinUrl(manifest.baseUrl(), rel); // encoded!
                    var dl = new ManifestModels.Download(url);

                    files.add(new ManifestModels.ManifestFile(
                            rel,
                            sha256,
                            size,
                            "client", // TODO später: client/server/both
                            dl
                    ));
                } catch (Exception e) {
                    throw new RuntimeException("Failed hashing " + p + ": " + e.getMessage(), e);
                }
            });
        }

// deterministisch sortieren
        files.sort(java.util.Comparator.comparing(ManifestModels.ManifestFile::path));


        // deterministisch sortieren
        entries.sort(Comparator.comparing(ManifestFileEntry::path));

        var updated = new ManifestModels.Manifest(
                manifest.packId(),
                manifest.packName(),
                manifest.packVersion(),
                manifest.minecraftVersion(),
                manifest.loader(),
                manifest.baseUrl(),
                files,
                manifest.overrides(),
                manifest.generatedAt()
        );

        writeJsonAtomic(manifestPath, updated);
        return updated;
    }

    private ManifestModels.Manifest readManifest(Path manifestPath) throws IOException {
        if (!Files.exists(manifestPath)) throw new IllegalArgumentException("manifest.json fehlt: " + manifestPath);
        return om.readValue(manifestPath.toFile(), ManifestModels.Manifest.class);
    }

    private String sha256Hex(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1024 * 64];
            int r;
            while ((r = in.read(buf)) >= 0) {
                if (r > 0) md.update(buf, 0, r);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private void writeJsonAtomic(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.createDirectories(target.getParent());
        om.writeValue(tmp.toFile(), value);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String joinUrl(String baseUrl, String relPath) {
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        String rel  = relPath.replace('\\', '/');

        String[] parts = rel.split("/");
        StringBuilder sb = new StringBuilder(base);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(encodePathSegment(parts[i]));
        }
        return sb.toString();
    }

    private static String encodePathSegment(String s) {
        // URLEncoder ist für query/form, aber für path-segmente ok wenn wir + -> %20 fixen
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }



}
