package de.levingamer8.modlauncher.host;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Speichert Referenzen auf erstellte/geöffnete Host-Projekte,
 * damit der Launcher sich daran erinnert.
 */
public class HostProjectRegistry {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            String projectId,
            String name,
            String projectRoot,
            String baseUrl,
            String mcVersion,
            String loader,
            String loaderVersion,
            String lastOpened
    ) {
        public Entry withLastOpened(String timestamp) {
            return new Entry(projectId, name, projectRoot, baseUrl, mcVersion, loader, loaderVersion, timestamp);
        }
    }

    private final Path registryFile;
    private final ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public HostProjectRegistry(Path baseDir) {
        this.registryFile = baseDir.resolve("host-registry.json");
    }

    public List<Entry> loadAll() {
        if (!Files.exists(registryFile)) return new ArrayList<>();
        try {
            Entry[] arr = om.readValue(registryFile.toFile(), Entry[].class);
            List<Entry> list = new ArrayList<>(Arrays.asList(arr));
            // neueste zuerst
            list.sort(Comparator.comparing(Entry::lastOpened, Comparator.nullsLast(Comparator.reverseOrder())));
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void register(Entry entry) {
        List<Entry> all = loadAll();
        all.removeIf(e -> e.projectId().equalsIgnoreCase(entry.projectId()));
        all.add(0, entry.withLastOpened(Instant.now().toString()));
        save(all);
    }

    public void remove(String projectId) {
        List<Entry> all = loadAll();
        all.removeIf(e -> e.projectId().equalsIgnoreCase(projectId));
        save(all);
    }

    public void touch(String projectId) {
        List<Entry> all = loadAll();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).projectId().equalsIgnoreCase(projectId)) {
                all.set(i, all.get(i).withLastOpened(Instant.now().toString()));
                break;
            }
        }
        save(all);
    }

    private void save(List<Entry> entries) {
        try {
            Files.createDirectories(registryFile.getParent());
            om.writeValue(registryFile.toFile(), entries);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save host-registry.json: " + e.getMessage(), e);
        }
    }
}
