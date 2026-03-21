package de.levingamer8.modlauncher.core;

import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ServersDatUtil {

    private ServersDatUtil() {}

    /**
     * Upsert eines Servers in servers.dat (merge mode).
     * - erstellt Datei, wenn fehlt
     * - aktualisiert vorhandenen Eintrag (match nach ip)
     * - optional pinToTop => an Index 0
     */
    public static void upsertServer(Path minecraftDir, String name, String host, int port, boolean pinToTop) throws IOException {
        CompoundTag root = readOrCreateRoot(minecraftDir);
        ListTag<CompoundTag> servers = getOrCreateServersList(root);

        String ip = host + ":" + port;

        List<CompoundTag> list = toJavaList(servers);

        CompoundTag entry = findByIp(list, ip);
        if (entry == null) {
            entry = new CompoundTag();
            entry.putString("ip", ip);
            entry.putString("name", name);
            entry.putBoolean("hidden", false);
            entry.putBoolean("acceptTextures", true);
            list.add(entry);
        } else {
            entry.putString("ip", ip);
            entry.putString("name", name);
        }

        if (pinToTop) {
            list.remove(entry);
            list.add(0, entry); // FIX: ArrayList hat kein addFirst
        }

        root.put("servers", fromJavaList(list));
        writeRoot(minecraftDir, root);
    }

    /**
     * onlySelectedServer Mode:
     * Überschreibt servers.dat so, dass NUR dieser eine Server drin ist.
     */
    public static void setOnlyServer(Path minecraftDir, String name, String host, int port) throws IOException {
        CompoundTag root = readOrCreateRoot(minecraftDir);

        String ip = host + ":" + port;

        CompoundTag entry = new CompoundTag();
        entry.putString("ip", ip);
        entry.putString("name", name);
        entry.putBoolean("hidden", false);
        entry.putBoolean("acceptTextures", true);

        List<CompoundTag> list = new ArrayList<>();
        list.add(entry);

        root.put("servers", fromJavaList(list));
        writeRoot(minecraftDir, root);
    }

    // -------------------- intern --------------------

    private static CompoundTag readOrCreateRoot(Path minecraftDir) throws IOException {
        Path serversDat = minecraftDir.resolve("servers.dat");
        Files.createDirectories(minecraftDir);

        if (Files.exists(serversDat)) {
            try {
                return (CompoundTag) NBTUtil.read(serversDat.toFile()).getTag();
            } catch (Exception e) {
                // kaputte Datei -> neu anlegen
                return new CompoundTag();
            }
        }
        return new CompoundTag();
    }

    @SuppressWarnings("unchecked")
    private static ListTag<CompoundTag> getOrCreateServersList(CompoundTag root) {
        Tag<?> t = root.get("servers");
        if (t instanceof ListTag<?> lt) {
            try {
                return (ListTag<CompoundTag>) lt;
            } catch (ClassCastException ignored) {
                // fallthrough -> neu
            }
        }
        ListTag<CompoundTag> servers = new ListTag<>(CompoundTag.class);
        root.put("servers", servers); // FIX: sicherstellen, dass es im root hängt
        return servers;
    }

    private static List<CompoundTag> toJavaList(ListTag<CompoundTag> servers) {
        List<CompoundTag> list = new ArrayList<>();
        for (CompoundTag ct : servers) list.add(ct);
        return list;
    }

    private static CompoundTag findByIp(List<CompoundTag> list, String ip) {
        for (CompoundTag ct : list) {
            String existingIp = ct.containsKey("ip") ? ct.getString("ip").orElse(null) : null;
            if (existingIp != null && ip.equalsIgnoreCase(existingIp)) return ct;
        }
        return null;
    }

    private static ListTag<CompoundTag> fromJavaList(List<CompoundTag> list) {
        ListTag<CompoundTag> newServers = new ListTag<>(CompoundTag.class);
        for (CompoundTag ct : list) newServers.add(ct);
        return newServers;
    }

    private static void writeRoot(Path minecraftDir, CompoundTag root) throws IOException {
        Path serversDat = minecraftDir.resolve("servers.dat");
        NBTUtil.write(root, serversDat.toFile());
    }
}
