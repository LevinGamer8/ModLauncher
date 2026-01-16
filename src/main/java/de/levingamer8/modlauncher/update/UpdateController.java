package de.levingamer8.modlauncher.update;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

public final class UpdateController {

    private final UpdateService svc;

    public UpdateController(String owner, String repo) {
        this.svc = new UpdateService(owner, repo);
    }

    public void checkForUpdates(boolean manual) {
        new Thread(() -> {
            try {
                String current = UpdateService.currentVersionOrZero();
                UpdateInfo latest = svc.fetchLatest();

                if (latest.msiUrl() == null) {
                    if (manual) info("Update", "Kein MSI-Asset im latest Release gefunden.");
                    return;
                }

                if (Versions.compare(latest.version(), current) <= 0) {
                    if (manual) info("Update", "Du bist aktuell: " + current);
                    return;
                }

                Platform.runLater(() -> {
                    Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                            "Update verfügbar: " + current + " → " + latest.version() + "\nJetzt installieren?",
                            ButtonType.YES, ButtonType.NO);
                    a.setHeaderText("ModLauncher Update");
                    a.showAndWait().ifPresent(bt -> {
                        if (bt == ButtonType.YES) {
                            new Thread(() -> {
                                try {
                                    var msi = svc.downloadToTemp(latest.msiUrl(), "ModLauncher-" + latest.version() + ".msi");
                                    MsiInstaller.installAndRestart(msi);
                                } catch (Exception ex) {
                                    Platform.runLater(() -> info("Update", "Download/Install fehlgeschlagen: " + ex.getMessage()));
                                    return;
                                }
                                Platform.exit();
                                System.exit(0);
                            }).start();
                        }
                    });
                });

            } catch (Exception e) {
                if (manual) Platform.runLater(() -> info("Update", "Update-Check fehlgeschlagen: " + e.getMessage()));
            }
        }, "update-check").start();
    }

    private static void info(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.showAndWait();
    }
}
