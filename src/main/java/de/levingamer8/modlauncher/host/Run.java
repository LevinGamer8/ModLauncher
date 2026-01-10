package de.levingamer8.modlauncher.host;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Run {

    public static void main(String[] args) throws Exception {
        Path packRoot = Paths.get("C:\\Users\\Pikac\\Desktop\\pack\\fabric-1.21.11");
        String baseUrl = "http://localhost:8080/pack/fabric-1.21.11/";

        ManifestBuilder.buildManifest(
                "Fabric-1.21.11",
                "Fabric 1.21.11",
                1,
                "1.21.11",
                "fabric",
                "0.18.4", // oder egal wenn dein Client eh latest nimmt
                baseUrl,
                packRoot,
                packRoot.resolve("manifest.json"),
                true // overrides.zip erstellen
        );
    }

}
