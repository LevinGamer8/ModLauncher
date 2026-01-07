package de.levingamer8.modlauncher.core;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileUtil {

    public static void ensureParent(Path p) throws IOException {
        Path parent = p.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1024 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) md.update(buf, 0, r);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void atomicReplace(Path tmp, Path target) throws IOException {
        ensureParent(target);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void unzipSafe(Path zipFile, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        Path normDest = destDir.toAbsolutePath().normalize();

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;

                Path out = normDest.resolve(e.getName()).normalize();
                if (!out.startsWith(normDest)) {
                    throw new SecurityException("Zip Slip detected: " + e.getName());
                }

                ensureParent(out);
                try (OutputStream os = Files.newOutputStream(out,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    zis.transferTo(os);
                }
            }
        }
    }


    public static void deleteRecursive(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        // delete children first
        try (var s = Files.walk(root)) {
            s.sorted((a,b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }
}
