package de.levingamer8.modlauncher.host;

import java.util.ArrayList;
import java.util.List;

public class HostManifest {
    public int schemaVersion = 1;

    public String projectId;
    public String name;
    public String version;

    public Minecraft minecraft = new Minecraft();
    public String filesBaseUrl;     // wo Clients später downloaden
    public String overridesUrl;     // filesBaseUrl abgeleitet oder extra

    public List<FileEntry> files = new ArrayList<>();
    public Overrides overrides = new Overrides();

    public static class Minecraft {
        public String version;
        public String loader;
        public String loaderVersion;
    }

    public static class FileEntry {
        public String path;   // relativ innerhalb files/
        public String sha256;
        public long size;
    }

    public static class Overrides {
        public String sha256;
        public long size;
    }
}
