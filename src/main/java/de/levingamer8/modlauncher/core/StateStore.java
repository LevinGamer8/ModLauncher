package de.levingamer8.modlauncher.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Optional;

public class StateStore {

    private final ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public Optional<Integer> readInstalledPackVersion(Path instanceDir) {
        Path state = instanceDir.resolve(".state/state.json");
        if (!Files.exists(state)) return Optional.empty();
        try {
            var node = om.readTree(state.toFile());
            if (node.has("installedPackVersion")) return Optional.of(node.get("installedPackVersion").asInt());
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    public void writeState(Path instanceDir, String packId, int packVersion) throws IOException {
        Path dir = instanceDir.resolve(".state");
        Files.createDirectories(dir);
        Path state = dir.resolve("state.json");

        ObjectNode node = om.createObjectNode();
        node.put("packId", packId);
        node.put("installedPackVersion", packVersion);
        node.put("installedAt", Instant.now().toString());

        om.writeValue(state.toFile(), node);
    }
}
