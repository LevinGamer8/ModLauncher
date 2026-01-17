package de.levingamer8.modlauncher.core;

public enum LoaderType {
    VANILLA, FABRIC, FORGE, QUILT, NEOFORGE;

    public static LoaderType fromString(String s) {
        if (s == null) return VANILLA;
        return switch (s.trim().toLowerCase()) {
            case "fabric" -> FABRIC;
            case "forge"  -> FORGE;
            case "neoforge"  -> FORGE;
            case "quilt"  -> FORGE;
            default -> VANILLA;
        };
    }

    public static String toString(LoaderType loader) {
        return switch (loader) {
            case FABRIC -> "fabric";
            case FORGE -> "forge";
            case NEOFORGE -> "neoforge";
            case QUILT ->  "quilt";
            case VANILLA -> throw new IllegalArgumentException("Vanilla not supported for Modrinth mod search");
        };
    }
}
