package de.levingamer8.modlauncher.core;

public enum LoaderType {
    VANILLA, FABRIC, FORGE;

    public static LoaderType fromString(String s) {
        if (s == null) return VANILLA;
        return switch (s.trim().toLowerCase()) {
            case "fabric" -> FABRIC;
            case "forge"  -> FORGE;
            default -> VANILLA;
        };
    }
}
