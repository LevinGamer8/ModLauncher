package de.levingamer8.modlauncher.core;

import java.util.Objects;

public class AuthService {

    public static final class Session {
        public final String accessToken;
        public final String username;
        public final String uuid;

        public Session(String accessToken, String username, String uuid) {
            this.accessToken = Objects.requireNonNull(accessToken);
            this.username = Objects.requireNonNull(username);
            this.uuid = Objects.requireNonNull(uuid);
        }
    }

    /**
     * TODO: Hier kommt der Microsoft Login rein.
     * Ich kapsel es absichtlich so, damit der Rest deines Launchers nicht an OpenAuth hängt.
     */
    public Session loginMicrosoft() throws Exception {
        // NOTE:
        // OpenAuth-API kann je nach Version unterschiedlich heißen.
        // Wenn IntelliJ hier Fehler wirft, paste die Fehlermeldung, dann passe ich es exakt an.

        // Beispiel-Flow (Platzhalter):
        // MicrosoftAuthenticator authenticator = new MicrosoftAuthenticator();
        // MicrosoftAuthResult result = authenticator.loginWithWebView();  (oder DeviceCode Flow)
        // return new Session(result.getAccessToken(), result.getProfile().getName(), result.getProfile().getId());

        throw new UnsupportedOperationException("Microsoft Login noch nicht verdrahtet (API je nach OpenAuth-Version).");
    }
}
