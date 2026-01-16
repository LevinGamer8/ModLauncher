package de.levingamer8.modlauncher.host;

import java.nio.file.Path;

enum TargetArea { FILES, OVERRIDES }

public class MappingEntry {
    public Path source;          // Datei oder Ordner
    public TargetArea area;      // FILES oder OVERRIDES
    public String targetRelPath; // z.B. "mods/" oder "config/"
    public boolean enabled = true;
}
