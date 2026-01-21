package de.levingamer8.modlauncher.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class MinecraftServerPing {

    public record Result(boolean online, int playersOnline, int playersMax, long pingMs, String versionName) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int DEFAULT_PROTOCOL_VERSION = 760; // z.B. 1.20.1 (nur als Default) //TODO: use the right protocol version id for the pack mc version. https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Protocol_version_numbers

    public static Result ping(String host, int port, int timeoutMs) {
        long start = System.nanoTime();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {

                // 1) Handshake (state = 1 -> status)
                ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
                DataOutputStream handshake = new DataOutputStream(handshakeBytes);

                writeVarInt(handshake, 0x00); // packet id
                writeVarInt(handshake, DEFAULT_PROTOCOL_VERSION);
                writeString(handshake, host);
                handshake.writeShort(port);
                writeVarInt(handshake, 0x01); // next state: status

                writePacket(out, handshakeBytes.toByteArray());

                // 2) Status Request
                ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
                DataOutputStream request = new DataOutputStream(requestBytes);
                writeVarInt(request, 0x00); // packet id
                writePacket(out, requestBytes.toByteArray());

                // 3) Status Response (JSON)
                int packetLength = readVarInt(in);
                byte[] packet = in.readNBytes(packetLength);
                DataInputStream pin = new DataInputStream(new ByteArrayInputStream(packet));

                int packetId = readVarInt(pin);
                if (packetId != 0x00) throw new IOException("Unexpected packetId: " + packetId);

                String json = readString(pin);
                JsonNode root = MAPPER.readTree(json);

                int onlinePlayers = root.path("players").path("online").asInt(0);
                int maxPlayers = root.path("players").path("max").asInt(0);
                String versionName = root.path("version").path("name").asText("");

                // 4) Ping Request (timestamp) -> Pong Response
                long nowMs = System.currentTimeMillis();

                ByteArrayOutputStream pingBytes = new ByteArrayOutputStream();
                DataOutputStream ping = new DataOutputStream(pingBytes);
                writeVarInt(ping, 0x01); // ping packet id
                ping.writeLong(nowMs);
                writePacket(out, pingBytes.toByteArray());

                int pongLength = readVarInt(in);
                byte[] pongPacket = in.readNBytes(pongLength);
                DataInputStream pongIn = new DataInputStream(new ByteArrayInputStream(pongPacket));
                int pongId = readVarInt(pongIn);
                if (pongId != 0x01) throw new IOException("Unexpected pongId: " + pongId);
                long echoed = pongIn.readLong();
                long rttMs = Math.max(0L, System.currentTimeMillis() - echoed);

                return new Result(true, onlinePlayers, maxPlayers, rttMs, versionName);
            }
        } catch (Exception ex) {
            return new Result(false, 0, 0, -1, "");
        } finally {
            long totalMs = (System.nanoTime() - start) / 1_000_000L;
            // Optional: logging totalMs
        }
    }

    private static void writePacket(DataOutputStream out, byte[] payload) throws IOException {
        writeVarInt(out, payload.length);
        out.write(payload);
        out.flush();
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, data.length);
        out.write(data);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > 1_000_000) throw new IOException("String length out of bounds: " + len);
        byte[] data = in.readNBytes(len);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & 0xFFFFFF80) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v & 0x7F);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));

            numRead++;
            if (numRead > 5) throw new IOException("VarInt too big");
        } while ((read & 0x80) != 0);

        return result;
    }
}
