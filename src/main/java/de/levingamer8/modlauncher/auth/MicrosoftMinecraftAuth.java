package de.levingamer8.modlauncher.auth;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

public final class MicrosoftMinecraftAuth {

    // In vielen Implementierungen wird dieses Client-ID genutzt (Nintendo Switch),
    // weil es in der Praxis sehr robust funktioniert.
    // Quelle (Open Source Implementierung): :contentReference[oaicite:1]{index=1}
    private static final String CLIENT_ID = "00000000441cc96b";
    private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    private final HttpClient http;
    private final Gson gson = new GsonBuilder().create();

    public MicrosoftMinecraftAuth() {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // Ergebnis, das du direkt in deinen Launcher mappen kannst
    public record MinecraftSession(
            String playerName,
            String uuid,              // UUID ohne Dashes (Minecraft-typisch) oder mit, wie du willst
            String minecraftAccessToken,
            String userType,          // "msa"
            long expiresAtEpochSec,   // wann minecraftAccessToken abläuft
            String xuid               // optional, kann null sein
    ) {}

    public record DeviceCode(
            String userCode,
            String deviceCode,
            String verificationUri,
            int intervalSec,
            int expiresInSec
    ) {}

    /**
     * 1) Device Code holen. Zeig dem User verificationUri + userCode an.
     */
    public DeviceCode startDeviceCode() throws IOException, InterruptedException {
        String body = form(Map.of(
                "scope", SCOPE,
                "client_id", CLIENT_ID,
                "response_type", "device_code"
        ));

        JsonObject j = postForm("https://login.live.com/oauth20_connect.srf", body);

        return new DeviceCode(
                j.get("user_code").getAsString(),
                j.get("device_code").getAsString(),
                j.get("verification_uri").getAsString(),
                j.get("interval").getAsInt(),
                j.get("expires_in").getAsInt()
        );
    }

    /**
     * 2) Polling bis der User eingeloggt ist.
     * Gibt Microsoft Access Token zurück (und Refresh Token, falls du cachen willst).
     */
    private JsonObject waitForMicrosoftToken(DeviceCode code) throws IOException, InterruptedException {
        long deadline = Instant.now().getEpochSecond() + code.expiresInSec();

        while (Instant.now().getEpochSecond() < deadline) {
            Thread.sleep(code.intervalSec() * 1000L);

            String body = form(Map.of(
                    "client_id", CLIENT_ID,
                    "device_code", code.deviceCode(),
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code"
            ));

            // Laut Implementierung wird auch mal mit Query ?client_id=... gepostet – beides klappt.
            // Wir machen es clean ohne Query.
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://login.live.com/oauth20_token.srf"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject j = JsonParser.parseString(resp.body()).getAsJsonObject();

            // Wenn success: enthält access_token
            if (j.has("access_token")) return j;

            // Typische Errors: authorization_pending, slow_down, expired_token, access_denied
            // Bei pending: weiter pollen.
            if (j.has("error")) {
                String err = j.get("error").getAsString();
                if ("authorization_pending".equals(err)) continue;
                if ("slow_down".equals(err)) {
                    Thread.sleep(2000L);
                    continue;
                }
                throw new IOException("Microsoft Login fehlgeschlagen: " + j);
            }
        }

        throw new IOException("Microsoft Login Timeout (Device Code abgelaufen).");
    }

    /**
     * Kompletter Login: Device Code anzeigen lassen -> token -> xbox live -> xsts -> minecraftVersion token -> profile
     */
    public MinecraftSession loginWithDeviceCode(DeviceCode code) throws IOException, InterruptedException {
        JsonObject msToken = waitForMicrosoftToken(code);
        String msAccessToken = msToken.get("access_token").getAsString();

        // 3) Xbox Live auth (RPS Ticket)
        JsonObject xblRes = postJson("https://user.auth.xboxlive.com/user/authenticate",
                """
                {
                  "Properties": {
                    "AuthMethod": "RPS",
                    "SiteName": "user.auth.xboxlive.com",
                    "RpsTicket": "%s"
                  },
                  "RelyingParty": "http://auth.xboxlive.com",
                  "TokenType": "JWT"
                }
                """.formatted(escapeJson(msAccessToken))
        );

        String xblToken = xblRes.get("Token").getAsString();
        String userHash = xblRes.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui")
                .get(0).getAsJsonObject()
                .get("uhs").getAsString();

        // 4) XSTS for Minecraft
        JsonObject xstsRes = postJson("https://xsts.auth.xboxlive.com/xsts/authorize",
                """
                {
                  "Properties": {
                    "SandboxId": "RETAIL",
                    "UserTokens": ["%s"]
                  },
                  "RelyingParty": "rp://api.minecraftservices.com/",
                  "TokenType": "JWT"
                }
                """.formatted(escapeJson(xblToken))
        );

        String xstsToken = xstsRes.get("Token").getAsString();

        // 5) Minecraft login_with_xbox
        JsonObject mcAuth = postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
                """
                { "identityToken": "XBL3.0 x=%s;%s" }
                """.formatted(escapeJson(userHash), escapeJson(xstsToken))
        );

        String mcAccessToken = mcAuth.get("access_token").getAsString();
        long expiresAt = Instant.now().getEpochSecond() + mcAuth.get("expires_in").getAsLong();

        // 6) Minecraft Profile
        JsonObject profile = getJson("https://api.minecraftservices.com/minecraft/profile", mcAccessToken);

        String name = profile.get("name").getAsString();
        String id = profile.get("id").getAsString(); // UUID ohne Dashes

        // XUID bekommst du optional aus anderen Xbox Endpoints; für Start reicht null.
        return new MinecraftSession(name, id, mcAccessToken, "msa", expiresAt, null);
    }

    // ---------------- helpers ----------------

    private JsonObject postForm(String url, String body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + " POST " + url + " => " + resp.body());
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private JsonObject postJson(String url, String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-xbl-contract-version", "1")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + " POST " + url + " => " + resp.body());
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private JsonObject getJson(String url, String bearerToken) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + " GET " + url + " => " + resp.body());
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private static String form(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (var e : kv.entrySet()) {
            if (!first) sb.append("&");
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
