package de.levingamer8.modlauncher;

import java.io.*;
import java.net.*;
import java.nio.file.*;

public class MavenWrapperDownloader {
    private static final String WRAPPER_URL =
            "https://repo.maven.apache.org/maven2/io/takari/maven-wrapper/0.5.6/maven-wrapper-0.5.6.jar";

    public static void main(String[] args) throws Exception {
        Path jarPath = Paths.get(".mvn/wrapper/maven-wrapper.jar");
        if (Files.exists(jarPath)) return;

        Files.createDirectories(jarPath.getParent());
        try (InputStream in = new URL(WRAPPER_URL).openStream()) {
            Files.copy(in, jarPath);
        }
    }
}
