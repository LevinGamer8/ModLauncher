package de.levingamer8.modlauncher.ui;

import de.levingamer8.modlauncher.core.PackUpdater;
import de.levingamer8.modlauncher.core.FileUtil;
import de.levingamer8.modlauncher.core.ProfileStore;
import de.levingamer8.modlauncher.core.ProfileStore.Profile;
import de.levingamer8.modlauncher.mc.MinecraftInstaller;
import de.levingamer8.modlauncher.mc.MinecraftLauncherService;



import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.awt.Desktop;
import java.nio.file.Path;
import java.util.Optional;

import de.levingamer8.modlauncher.core.ProfileStore.JoinMode;


public class Controller {

    @FXML private TextField manifestUrlField;
    @FXML private ComboBox<Profile> profileCombo;
    @FXML private Button updateButton;
    @FXML private Button openFolderButton;
    @FXML private ProgressBar progressBar;
    @FXML private TextArea logArea;
    @FXML private Label statusLabel;
    @FXML private TextField playerNameField;
    @FXML private Button playButton;


    private final ProfileStore profileStore = new ProfileStore();
    private final PackUpdater updater = new PackUpdater();

    @FXML
    public void initialize() {
        profileCombo.getItems().setAll(profileStore.loadProfiles());
        if (!profileCombo.getItems().isEmpty()) {
            profileCombo.getSelectionModel().select(0);
            manifestUrlField.setText(profileCombo.getValue().manifestUrl());
        }

        profileCombo.valueProperty().addListener((obs, o, n) -> {
            if (n != null) manifestUrlField.setText(n.manifestUrl());
        });

        appendLog("Instanz-Basisordner: " + profileStore.baseDir());
    }

    @FXML
    public void onNewProfile() {
        TextInputDialog nameDialog = new TextInputDialog("projekt-xyz");
        nameDialog.setTitle("Neues Profil");
        nameDialog.setHeaderText("Pack-ID / Profilname");
        nameDialog.setContentText("Name:");

        Optional<String> nameOpt = nameDialog.showAndWait();
        if (nameOpt.isEmpty()) return;

        String name = nameOpt.get().trim();
        if (name.isEmpty()) return;

        TextInputDialog urlDialog = new TextInputDialog(manifestUrlField.getText().trim());
        urlDialog.setTitle("Neues Profil");
        urlDialog.setHeaderText("Manifest URL");
        urlDialog.setContentText("URL:");

        Optional<String> urlOpt = urlDialog.showAndWait();
        if (urlOpt.isEmpty()) return;

        String url = urlOpt.get().trim();
        if (url.isEmpty()) return;

        Profile p = new Profile(name, url, "localhost", 25565, JoinMode.SERVERS_DAT);


        profileStore.saveOrUpdateProfile(p);

        profileCombo.getItems().setAll(profileStore.loadProfiles());
        profileCombo.getSelectionModel().select(p);
        appendLog("Profil angelegt/aktualisiert: " + p.name());
    }

    @FXML
    public void onDeleteProfile() {
        Profile p = profileCombo.getValue();
        if (p == null) return;

        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Profil löschen");
        a.setHeaderText("Wirklich löschen?");
        a.setContentText("Profil: " + p.name());
        Optional<ButtonType> res = a.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return;

        profileStore.deleteProfile(p.name());
        // Instanzordner wirklich löschen
        try {
            FileUtil.deleteRecursive(profileStore.instanceDir(p.name()));
        } catch (Exception ex) {
            appendLog("WARNUNG: Konnte Instanzordner nicht löschen: " + ex.getMessage());
        }
        profileCombo.getItems().setAll(profileStore.loadProfiles());
        if (!profileCombo.getItems().isEmpty()) profileCombo.getSelectionModel().select(0);

        appendLog("Profil gelöscht: " + p.name());
    }

    @FXML
    public void onUpdate() {
        Profile p = profileCombo.getValue();
        String manifestUrl = manifestUrlField.getText().trim();
        if (manifestUrl.isEmpty()) {
            showError("Manifest URL fehlt.");
            return;
        }
        if (p == null) {
            // Auto-Profil, wenn nichts ausgewählt
            p = new Profile("default", manifestUrl, "localhost", 25565, JoinMode.SERVERS_DAT);

            profileStore.saveOrUpdateProfile(p);
            profileCombo.getItems().setAll(profileStore.loadProfiles());
            profileCombo.getSelectionModel().select(p);
        } else {
            // URL im Profil aktualisieren
            p = new Profile(p.name(), manifestUrl, p.serverHost(), p.serverPort(), p.joinMode());

            profileStore.saveOrUpdateProfile(p);
        }

        Profile finalP = p;
        setUiBusy(true);
        clearLog();
        appendLog("Update gestartet: " + manifestUrl);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                updater.update(finalP, profileStore, (msg) -> {
                    updateMessage(msg);
                    Platform.runLater(() -> appendLog(msg));
                }, (done, total) -> {
                    double prog = total <= 0 ? -1 : (double) done / (double) total;
                    updateProgress(done, total);
                    Platform.runLater(() -> progressBar.setProgress(prog < 0 ? ProgressIndicator.INDETERMINATE_PROGRESS : prog));
                });
                return null;
            }
        };

        statusLabel.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> {
            statusLabel.textProperty().unbind();
            statusLabel.setText("Fertig");
            appendLog("Update fertig.");
            setUiBusy(false);
            progressBar.setProgress(1);
        });
        task.setOnFailed(e -> {
            statusLabel.textProperty().unbind();
            statusLabel.setText("Fehler");
            Throwable ex = task.getException();
            appendLog("FEHLER: " + (ex != null ? ex.getMessage() : "unbekannt"));
            setUiBusy(false);
            progressBar.setProgress(0);
            if (ex != null) ex.printStackTrace();
            showError(ex != null ? ex.getMessage() : "Unbekannter Fehler");
        });

        Thread t = new Thread(task, "pack-updater");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    public void onPlay() {
        Profile p = profileCombo.getValue();
        if (p == null) {
            showError("Kein Profil ausgewählt.");
            return;
        }

        String name = playerNameField.getText().trim();
        if (name.isEmpty()) name = "Player";

        Path gameDir = profileStore.minecraftDir(p.name());

        setUiBusy(true);
        appendLog("Play: Forge wird geprüft/installed und danach gestartet...");

        String finalName = name;

        appendLog("GameDir (launch): " + gameDir);
        appendLog("InstanceDir: " + profileStore.instanceDir(p.name()));



        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                // 1) Sicherstellen: MC + Forge installiert in die Instance
                MinecraftInstaller.ensureInstalled(gameDir, MinecraftInstaller.DEFAULT, (msg) -> {
                    updateMessage(msg);
                    Platform.runLater(() -> appendLog(msg));
                });

                // 2) Starten (offline name für jetzt)
                Platform.runLater(() -> appendLog("Starte Spiel..."));
                String forgeProfileId = MinecraftInstaller.forgeProfileId(MinecraftInstaller.DEFAULT);

                MinecraftLauncherService.launchForgeBootstrap(
                        gameDir,
                        MinecraftInstaller.DEFAULT.mcVersion(),
                        forgeProfileId,
                        finalName,
                        4096,
                        msg -> Platform.runLater(() -> appendLog(msg))
                );





                return null;
            }
        };

        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            statusLabel.textProperty().unbind();
            statusLabel.setText("Gestartet");
            setUiBusy(false);
        });

        task.setOnFailed(e -> {
            statusLabel.textProperty().unbind();
            statusLabel.setText("Fehler");
            Throwable ex = task.getException();
            appendLog("FEHLER: " + (ex != null ? ex.getMessage() : "unbekannt"));
            setUiBusy(false);
            if (ex != null) ex.printStackTrace();
            showError(ex != null ? ex.getMessage() : "Unbekannter Fehler");
        });

        Thread t = new Thread(task, "mc-play");
        t.setDaemon(true);
        t.start();
    }


    @FXML
    public void onOpenFolder() {
        Profile p = profileCombo.getValue();
        if (p == null) {
            showError("Kein Profil ausgewählt.");
            return;
        }
        Path instance = profileStore.instanceDir(p.name());
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(instance.toFile());
            } else {
                showError("Desktop-Open nicht unterstützt. Ordner: " + instance);
            }
        } catch (Exception ex) {
            showError("Konnte Ordner nicht öffnen: " + ex.getMessage());
        }
    }

    private void setUiBusy(boolean busy) {
        updateButton.setDisable(busy);
        openFolderButton.setDisable(busy);
        profileCombo.setDisable(busy);
        manifestUrlField.setDisable(busy);
        playButton.setDisable(busy);
    }

    private void appendLog(String s) {
        logArea.appendText(s + "\n");
    }

    private void clearLog() {
        logArea.clear();
    }

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Fehler");
        a.setHeaderText("Aktion fehlgeschlagen");
        a.setContentText(msg);
        a.showAndWait();
    }
}
