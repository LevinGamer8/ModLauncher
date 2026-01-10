package de.levingamer8.modlauncher.ui;

import de.levingamer8.modlauncher.auth.MicrosoftMinecraftAuth;
import de.levingamer8.modlauncher.core.FileUtil;
import de.levingamer8.modlauncher.core.ManifestModels;
import de.levingamer8.modlauncher.core.PackUpdater;
import de.levingamer8.modlauncher.core.ProfileStore;
import de.levingamer8.modlauncher.core.ProfileStore.Profile;
import de.levingamer8.modlauncher.core.ProfileStore.JoinMode;
import de.levingamer8.modlauncher.core.LoaderType;
import de.levingamer8.modlauncher.mc.MinecraftLauncherService;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.awt.Desktop;
import java.nio.file.Path;
import java.util.Optional;

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
    @FXML private Label loginStatusLabel;

    private final ProfileStore profileStore = new ProfileStore();
    private final PackUpdater updater = new PackUpdater();

    private volatile MicrosoftMinecraftAuth.MinecraftSession mcSession;


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
        appendLog("Shared-Cache: " + profileStore.sharedRoot());
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
            p = new Profile("default", manifestUrl, "localhost", 25565, JoinMode.SERVERS_DAT);
            profileStore.saveOrUpdateProfile(p);
            profileCombo.getItems().setAll(profileStore.loadProfiles());
            profileCombo.getSelectionModel().select(p);
        } else {
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
                    updateProgress(done, total);
                    double prog = total <= 0 ? -1 : (double) done / (double) total;
                    Platform.runLater(() -> progressBar.setProgress(
                            prog < 0 ? ProgressIndicator.INDETERMINATE_PROGRESS : prog
                    ));
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

        String playerName = playerNameField.getText().trim();
        if (playerName.isEmpty()) playerName = "Player";

        String manifestUrl = manifestUrlField.getText().trim();
        if (manifestUrl.isEmpty()) {
            showError("Manifest URL fehlt.");
            return;
        }

        // Profil URL sicher speichern (falls Feld geändert wurde)
        Profile finalP = new Profile(p.name(), manifestUrl, p.serverHost(), p.serverPort(), p.joinMode());
        profileStore.saveOrUpdateProfile(finalP);

        setUiBusy(true);
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        appendLog("Play gestartet: Manifest laden, Loader installieren, dann starten...");

        String finalName = playerName;

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {

                updateMessage("Manifest laden...");
                ManifestModels.Manifest manifest = fetchManifest(finalP.manifestUrl());

                LoaderType loaderType = LoaderType.fromString(
                        manifest.loader() != null ? manifest.loader().type() : "vanilla"
                );
                String loaderVer = manifest.loader() != null ? manifest.loader().version() : "";

                Path sharedRoot = profileStore.sharedRoot();
                Path gameDir = profileStore.instanceGameDir(finalP.name());
                Path runtimeDir = profileStore.instanceRuntimeDir(finalP.name());

                MinecraftLauncherService launcher = new MinecraftLauncherService();

                MinecraftLauncherService.AuthSession auth;

                if (mcSession != null && mcSession.minecraftAccessToken() != null && !mcSession.minecraftAccessToken().isBlank()) {
                    auth = new MinecraftLauncherService.AuthSession(
                            mcSession.playerName(),
                            mcSession.uuid(),
                            mcSession.minecraftAccessToken(),
                            mcSession.userType() // z.B. "msa"
                    );
                } else {
                    // erstmal: offline fallback (oder du zwingst login)
                    auth = new MinecraftLauncherService.AuthSession(
                            "Player",
                            "00000000000000000000000000000000",
                            "0",
                            "legacy"
                    );
                }



                updateMessage("Install/Resolve/Launch...");
                launcher.launch(
                        sharedRoot,
                        gameDir,
                        runtimeDir,
                        new MinecraftLauncherService.LaunchSpec(
                                manifest.minecraft(),
                                loaderType,
                                loaderVer,
                                4096
                        ),
                        auth,
                        msg -> Platform.runLater(() -> appendLog(msg))
                );

                updateMessage("MC gestartet.");
                return null;
            }
        };

        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            statusLabel.textProperty().unbind();
            statusLabel.setText("Gestartet");
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

    private void setLoginStatus(String text) {
        if (loginStatusLabel == null) return;

        Platform.runLater(() -> loginStatusLabel.setText(text));
    }


    @FXML
    private void onLoginClicked() {
        setLoginStatus("Starte Login...");

        Task<MicrosoftMinecraftAuth.MinecraftSession> task = new Task<>() {
            @Override
            protected MicrosoftMinecraftAuth.MinecraftSession call() throws Exception {
                MicrosoftMinecraftAuth auth = new MicrosoftMinecraftAuth();

                // 1) Device code holen
                var dc = auth.startDeviceCode();

                // 2) User informieren
                Platform.runLater(() -> {
                    appendLog("[LOGIN] Öffne: " + dc.verificationUri());
                    appendLog("[LOGIN] Code:  " + dc.userCode());
                    setLoginStatus("Bitte Code eingeben: " + dc.userCode());
                });

                // Optional: Browser öffnen
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(dc.verificationUri()));
                } catch (Exception ignored) {}

                // 3) Polling bis fertig
                return auth.loginWithDeviceCode(dc);
            }
        };

        task.setOnSucceeded(e -> {
            mcSession = task.getValue();
            setLoginStatus("Eingeloggt als: " + mcSession.playerName());
            appendLog("[LOGIN] OK: " + mcSession.playerName() + " / " + mcSession.uuid());

            // Optional: playerNameField automatisch setzen
            if (playerNameField != null) playerNameField.setText(mcSession.playerName());
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            setLoginStatus("Login fehlgeschlagen");
            appendLog("[LOGIN] ERROR: " + (ex != null ? ex.toString() : "unknown"));
        });

        new Thread(task, "ms-login").start();
    }




    @FXML
    private void onCopyLog() {
        var cb = new javafx.scene.input.ClipboardContent();
        cb.putString(logArea.getText());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cb);
    }

    @FXML
    private void onClearLog() {
        logArea.clear();
    }


    private ManifestModels.Manifest fetchManifest(String url) throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                .build();
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build();
        var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("Manifest HTTP " + resp.statusCode());
        return om.readValue(resp.body(), ManifestModels.Manifest.class);
    }
}
