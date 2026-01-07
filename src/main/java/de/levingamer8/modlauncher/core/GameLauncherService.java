package de.levingamer8.modlauncher.core;

import de.levingamer8.modlauncher.core.ProfileStore.JoinMode;

import java.nio.file.Path;

public class GameLauncherService {

    public record LaunchRequest(
            Path minecraftDir,
            String mcVersion,
            String loaderType,    // "forge" erstmal
            String loaderVersion, // z.B. "47.4.13"
            int memoryMb,
            String serverName,
            String serverHost,
            int serverPort,
            JoinMode joinMode
    ) {}

    public void prepareJoin(LaunchRequest r) throws Exception {
        if (r.joinMode == JoinMode.SERVERS_DAT) {
            ServersDatUtil.upsertServer(
                    r.minecraftDir,
                    r.serverName,
                    r.serverHost,
                    r.serverPort,
                    true
            );
        }
    }

    /**
     * Startet Minecraft.
     *
     * Realistisch machst du das über openlauncherlib (Install MC + Forge + Launch args).
     * Ich lasse hier bewusst den "Engine"-Teil getrennt, sonst wird’s unwartbar.
     */
    public Process launch(LaunchRequest r, AuthService.Session session) throws Exception {
        prepareJoin(r);

        // TODO:
        // - Minecraft + Forge install (falls fehlt)
        // - JVM args + classpath bauen
        // - Prozess starten
        //
        // In der nächsten Antwort gebe ich dir den fertigen openlauncherlib-Block passend zu Forge + deinem Instanz-Root.
        // Dafür brauche ich nur: Welche Forge-Version testest du (z.B. 1.20.1-47.4.13) und wie viel RAM default?

        throw new UnsupportedOperationException("Launch Engine noch nicht verdrahtet.");
    }
}
