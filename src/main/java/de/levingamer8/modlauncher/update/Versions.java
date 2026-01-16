package de.levingamer8.modlauncher.update;

public final class Versions {
    private Versions() {}

    public static int compare(String a, String b) {
        a = stripV(a);
        b = stripV(b);
        String[] A = a.split("\\.");
        String[] B = b.split("\\.");
        int n = Math.max(A.length, B.length);
        for (int i = 0; i < n; i++) {
            int ai = i < A.length ? parse(A[i]) : 0;
            int bi = i < B.length ? parse(B[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static String stripV(String s) { return s != null && s.startsWith("v") ? s.substring(1) : s; }
    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); } catch (Exception e) { return 0; }
    }
}
