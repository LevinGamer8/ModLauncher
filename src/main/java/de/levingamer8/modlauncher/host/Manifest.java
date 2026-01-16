package de.levingamer8.modlauncher.host;

import java.util.ArrayList;
import java.util.List;

public class Manifest {
    public int schemaVersion = 1;
    public String projectId;
    public String name;
    public String version;
    public Minecraft minecraft;
    public String filesBaseUrl;
    public String overridesUrl;
    public List<ManifestFile> files = new ArrayList<>();
    public Overrides overrides;

    public static class Minecraft {
        public String version;
        public String loader;
        public String loaderVersion;
    }
    public static class ManifestFile {
        public String path;
        public String sha256;
        public long size;
    }
    public static class Overrides {
        public String sha256;
        public long size;
    }
}
