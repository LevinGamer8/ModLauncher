package de.levingamer8.modlauncher.core;

import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.tag.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class ServersDatUtil {

    /**
     * Schreibt/aktualisiert entries in servers.dat.
     * - erstellt Datei, wenn fehlt
     * - aktualisiert vorhandenen Eintrag (nach ip)
     * - setzt ihn optional an Position 0 (oben in der Liste)
     */
    public static void upsertServer(Path minecraftDir, String name, String host, int port, boolean pinToTop) throws IOException {
        Path serversDat = minecraftDir.resolve("servers.dat");
        Files.createDirectories(minecraftDir);

        CompoundTag root;
        ListTag<CompoundTag> servers;

        if (Files.exists(serversDat)) {
            root = (CompoundTag) NBTUtil.read(serversDat.toFile()).getTag();
            Tag<?> t = root.get("servers");
            servers = (t instanceof ListTag<?> lt) ? (ListTag<CompoundTag>) lt : new ListTag<>(CompoundTag.class);
        } else {
            root = new CompoundTag();
            servers = new ListTag<>(CompoundTag.class);
            root.put("servers", servers);
        }

        String ip = host + ":" + port;

        // existing list to Java list
        List<CompoundTag> list = new ArrayList<>();
        for (CompoundTag ct : servers) list.add(ct);

        CompoundTag entry = null;
        for (CompoundTag ct : list) {
            String existingIp = ct.getString("ip").orElse("");
            if (ip.equalsIgnoreCase(existingIp)) {
                entry = ct;
                break;
            }
        }


        if (entry == null) {
            entry = new CompoundTag();
            entry.putString("ip", ip);
            entry.putString("name", name);
            // optional defaults
            entry.putBoolean("hidden", false);
            entry.putBoolean("acceptTextures", true);
            list.add(entry);
        } else {
            entry.putString("name", name);
        }

        if (pinToTop) {
            list.remove(entry);
            list.addFirst(entry);
        }

        // rebuild servers list
        ListTag<CompoundTag> newServers = new ListTag<>(CompoundTag.class);
        for (CompoundTag ct : list) newServers.add(ct);
        root.put("servers", newServers);

        NBTUtil.write(root, serversDat.toFile());
    }
}
