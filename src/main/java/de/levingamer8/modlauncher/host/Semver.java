package de.levingamer8.modlauncher.host;

public record Semver(int major, int minor, int patch) implements Comparable<Semver> {

    public static Semver parse(String s) {
        if (s == null || s.isBlank()) return new Semver(0,0,0);
        String[] p = s.trim().split("\\.");
        int ma = p.length > 0 ? Integer.parseInt(p[0]) : 0;
        int mi = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        int pa = p.length > 2 ? Integer.parseInt(p[2]) : 0;
        return new Semver(ma, mi, pa);
    }

    public Semver bumpPatch() { return new Semver(major, minor, patch + 1); }
    public Semver bumpMinor() { return new Semver(major, minor + 1, 0); }
    public Semver bumpMajor() { return new Semver(major + 1, 0, 0); }

    @Override public String toString() { return major + "." + minor + "." + patch; }

    @Override public int compareTo(Semver o) {
        int c = Integer.compare(this.major, o.major);
        if (c != 0) return c;
        c = Integer.compare(this.minor, o.minor);
        if (c != 0) return c;
        return Integer.compare(this.patch, o.patch);
    }

    public int toIntPackVersion() {
        // optional: wenn du packVersion int behalten willst
        return major * 10000 + minor * 100 + patch;
    }
}
