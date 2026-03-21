package de.levingamer8.modlauncher.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Vergleicht zwei Versions-Ordner und generiert einen automatischen Changelog.
 * Erkennt hinzugefügte, entfernte und aktualisierte Mods.
 */
public class ChangelogGenerator {

    /**
     * Vergleicht oldFilesDir und newFilesDir und erzeugt einen lesbaren Changelog.
     * @param oldFilesDir  versions/&lt;oldVer&gt;/files (kann null sein für erste Version)
     * @param newFilesDir  versions/&lt;newVer&gt;/files
     * @param newVersion   z.B. "1.0.4"
     * @return formatierter Changelog-Text
     */
    public static String generate(Path oldFilesDir, Path newFilesDir, String newVersion) throws IOException {
        Map<String, Long> oldFiles = oldFilesDir != null && Files.isDirectory(oldFilesDir)
                ? collectFiles(oldFilesDir) : Map.of();
        Map<String, Long> newFiles = Files.isDirectory(newFilesDir)
                ? collectFiles(newFilesDir) : Map.of();

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> updated = new ArrayList<>();

        // Neue oder geänderte Dateien
        for (var entry : newFiles.entrySet()) {
            String path = entry.getKey();
            if (!oldFiles.containsKey(path)) {
                added.add(prettyName(path));
            } else if (!oldFiles.get(path).equals(entry.getValue())) {
                updated.add(prettyName(path));
            }
        }

        // Entfernte Dateien
        for (String path : oldFiles.keySet()) {
            if (!newFiles.containsKey(path)) {
                removed.add(prettyName(path));
            }
        }

        Collections.sort(added, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(removed, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(updated, String.CASE_INSENSITIVE_ORDER);

        StringBuilder sb = new StringBuilder();
        sb.append("## Version ").append(newVersion).append("\n\n");

        if (added.isEmpty() && removed.isEmpty() && updated.isEmpty()) {
            sb.append("Keine Änderungen an Mods.\n");
        } else {
            if (!added.isEmpty()) {
                sb.append("### Hinzugefügt\n");
                for (String name : added) sb.append("+ ").append(name).append("\n");
                sb.append("\n");
            }
            if (!removed.isEmpty()) {
                sb.append("### Entfernt\n");
                for (String name : removed) sb.append("- ").append(name).append("\n");
                sb.append("\n");
            }
            if (!updated.isEmpty()) {
                sb.append("### Aktualisiert\n");
                for (String name : updated) sb.append("~ ").append(name).append("\n");
                sb.append("\n");
            }
        }

        sb.append("---\n");
        return sb.toString();
    }

    /**
     * Sammelt alle Dateien relativ zum Basisordner mit ihrer Größe als einfacher Vergleichswert.
     */
    private static Map<String, Long> collectFiles(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toMap(
                            p -> dir.relativize(p).toString().replace('\\', '/'),
                            p -> {
                                try { return Files.size(p); }
                                catch (IOException e) { return -1L; }
                            }
                    ));
        }
    }

    /**
     * Macht aus "mods/sodium-0.6.1.jar" -> "Sodium 0.6.1"
     */
    private static String prettyName(String path) {
        String filename = path;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0) filename = path.substring(lastSlash + 1);

        // .jar entfernen
        if (filename.endsWith(".jar")) filename = filename.substring(0, filename.length() - 4);

        // Gängige Mod-Dateinamen parsen: name-version+mc.jar oder name-version.jar
        // Alles nach '+' abschneiden (MC-Version)
        int plus = filename.indexOf('+');
        if (plus > 0) filename = filename.substring(0, plus);

        // Bindestriche und Unterstriche durch Leerzeichen
        filename = filename.replace('-', ' ').replace('_', ' ');

        // Überflüssige Leerzeichen
        filename = filename.replaceAll("\\s+", " ").trim();

        return filename;
    }
}
