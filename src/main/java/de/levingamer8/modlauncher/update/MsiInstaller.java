package de.levingamer8.modlauncher.update;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MsiInstaller {
    private MsiInstaller() {}

    public static void installAndRestart(Path msiPath) throws Exception {
        Path script = Files.createTempFile("modlauncher-update-", ".cmd");

        String launcherExe = "\"C:\\Program Files\\ModLauncher\\ModLauncher.exe\"";

        String content = """
    @echo off
    echo Installing ModLauncher update...
    msiexec /i "%s" /passive /norestart

    echo Waiting for installer...
    timeout /t 3 /nobreak >nul

    echo Restarting ModLauncher...
    start "" %s
    """.formatted(msiPath.toAbsolutePath(), launcherExe);

        Files.writeString(script, content);

        new ProcessBuilder("cmd.exe", "/c", script.toAbsolutePath().toString())
                .inheritIO()
                .start();
    }

}
