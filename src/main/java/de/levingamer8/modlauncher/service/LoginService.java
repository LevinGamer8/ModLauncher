package de.levingamer8.modlauncher.service;

import de.levingamer8.modlauncher.auth.MicrosoftMinecraftAuth;
import de.levingamer8.modlauncher.auth.MicrosoftSessionStore;
import javafx.concurrent.Task;

import java.time.Instant;
import java.util.function.Consumer;

public class LoginService {

    public record DeviceCodeInfo(String userCode, String verificationUri) {}

    private final MicrosoftSessionStore store;

    public LoginService(MicrosoftSessionStore store) {
        this.store = store;
    }

    /** Lädt gespeicherte Session, prüft Ablauf (expiresAtEpochSec) und gibt nur gültige zurück. */
    public MicrosoftMinecraftAuth.MinecraftSession tryLoadSavedSession() {
        MicrosoftMinecraftAuth.MinecraftSession s = store.loadOrNull();
        if (s == null) return null;

        long now = Instant.now().getEpochSecond();
        if (s.expiresAtEpochSec() <= now) {
            store.clear();
            return null;
        }
        return s;
    }

    public void save(MicrosoftMinecraftAuth.MinecraftSession session) {
        store.save(session);
    }

    public void clear() {
        store.clear();
    }

    public String storeFile() {
        return store.file().toString();
    }

    /** Task: Device-Code holen (Callback) -> Polling Login -> Session zurück */
    public Task<MicrosoftMinecraftAuth.MinecraftSession> createDeviceCodeLoginTask(
            Consumer<DeviceCodeInfo> onDeviceCode
    ) {
        return new Task<>() {
            @Override
            protected MicrosoftMinecraftAuth.MinecraftSession call() throws Exception {
                MicrosoftMinecraftAuth auth = new MicrosoftMinecraftAuth();

                MicrosoftMinecraftAuth.DeviceCode dc = auth.startDeviceCode();
                if (onDeviceCode != null) {
                    onDeviceCode.accept(new DeviceCodeInfo(dc.userCode(), dc.verificationUri()));
                }

                return auth.loginWithDeviceCode(dc);
            }
        };
    }
}
