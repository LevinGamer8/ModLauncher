package de.levingamer8.modlauncher.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class ManifestGenerator {

    private final ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public HostManifest generateAndWrite(Path manifestJsonPath) throws IOException {
        HostManifest current = om.readValue(manifestJsonPath.toFile(), HostManifest.class);

        // files dir ist: <manifestDir>/files
        Path versionDir = manifestJsonPath.getParent();
        Path filesDir = versionDir.resolve("files");
        if (!Files.isDirectory(filesDir)) {
            throw new IllegalStateException("files dir missing: " + filesDir);
        }

        List<ManifestFileEntry> entries = new ArrayList<>();

        try (var walk = Files.walk(filesDir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String rel = filesDir.relativize(p).toString().replace('\\', '/');
                    long size = Files.size(p);
                    String sha = sha256Hex(p);
                    entries.add(new ManifestFileEntry(rel, sha, size));
                } catch (Exception e) {
                    throw new RuntimeException("Failed hashing: " + p, e);
                }
            });
        }

        HostManifest updated = new HostManifest(
                current.projectId(),
                current.name(),
                current.mcVersion(),
                current.loader(),
                current.loaderVersion(),
                current.version(),
                Instant.now().toString(),
                current.baseUrl(),
                entries
        );

        writeJsonAtomic(manifestJsonPath, updated);
        return updated;
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1024 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) md.update(buf, 0, r);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private void writeJsonAtomic(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        om.writeValue(tmp.toFile(), value);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
