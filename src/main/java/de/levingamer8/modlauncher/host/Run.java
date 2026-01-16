package de.levingamer8.modlauncher.host;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Run {

    public static void main(String[] args) throws Exception {
        Path packRoot = Paths.get("Y:\\var\\www\\html\\test.wa");
        String baseUrl = "http://192.168.10.115/test.wa/";

        ManifestBuilder.buildManifest(
                "test.wa",
                "test.wa",
                1,
                "1.20.1",
                "forge",
                "47.4.10", // oder egal wenn dein Client eh latest nimmt
                baseUrl,
                packRoot,
                packRoot.resolve("manifest.json"),
                true // overrides.zip erstellen
        );
    }

}
