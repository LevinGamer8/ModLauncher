package de.levingamer8.modlauncher.ui;

import de.levingamer8.modlauncher.auth.MicrosoftMinecraftAuth;
import de.levingamer8.modlauncher.core.ManifestModels;
import de.levingamer8.modlauncher.core.PackUpdater;
import de.levingamer8.modlauncher.core.ProfileStore;
import de.levingamer8.modlauncher.core.ProfileStore.Profile;
import de.levingamer8.modlauncher.core.LoaderType;
import de.levingamer8.modlauncher.host.HostManifest;
import de.levingamer8.modlauncher.host.ProjectHostService;
import de.levingamer8.modlauncher.mc.FabricVersionResolver;
import de.levingamer8.modlauncher.mc.ForgeVersionResolver;
import de.levingamer8.modlauncher.mc.MinecraftLauncherService;

import de.levingamer8.modlauncher.auth.MicrosoftSessionStore;
import java.time.Instant;
import java.nio.file.Path;


import de.levingamer8.modlauncher.update.UpdateController;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;

import javafx.geometry.Insets;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TitledPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;




import java.util.concurrent.ConcurrentLinkedQueue;
import java.awt.*;

public class Controller {

    @FXML private ComboBox<Profile> profileCombo;

    @FXML private Button updateButton;
    @FXML private Button openFolderButton;
    @FXML private ProgressBar progressBar;
    @FXML private TextArea logArea;
    @FXML private Label statusLabel;
    @FXML private Button playButton;
    @FXML private Label loginStatusLabel;
    @FXML private Button loginButton;
    @FXML private Button launcherUpdateButton;
    @FXML private Label versionLabel;
    @FXML private TextArea changelogArea;
    @FXML private Label serverStatusLabel;
    @FXML private Label serverDetailsLabel;
    @FXML private Label packInfoLabel;
    @FXML private SplitPane mainSplit;
    @FXML private TitledPane logPane;
    @FXML private ImageView skinView;
    @FXML private Label accountNameLabel;


    private final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();
    private Timeline logFlushTimeline;

    private static final int LOG_FLUSH_MAX_LINES = 500;     // pro Tick max Zeilen
    private static final int LOG_MAX_CHARS = 300_000;       // TextArea Limit


    private final ProfileStore profileStore = new ProfileStore();
    private final PackUpdater updater = new PackUpdater();

    private volatile MicrosoftMinecraftAuth.MinecraftSession mcSession;
    private MicrosoftSessionStore msStore;

    private UpdateController launcherUpdater;
    private Dialog<Void> loginDialog;   // aktuell geöffneter Device-Code Dialog (wenn vorhanden)




    @FXML
    public void initialize() {
        profileCombo.getItems().setAll(profileStore.loadProfiles());
        profileCombo.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(Profile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.name());
            }
        });
        profileCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Profile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.name());
            }
        });
        profileCombo.setConverter(new StringConverter<>() {
            @Override public String toString(Profile p) { return p == null ? "" : p.name(); }
            @Override public Profile fromString(String s) { return null; } // nicht editierbar -> egal
        });

        reloadProfilesAndSelect(null);

        profileCombo.valueProperty().addListener((obs, oldV, newV) -> {
            if (openFolderButton != null) {
                openFolderButton.setDisable(newV == null);
            }
        });
        if (openFolderButton != null) {
            openFolderButton.setDisable(profileCombo.getValue() == null);
        }


        appendLog("Instanz-Basisordner: " + profileStore.baseDir());
        appendLog("Shared-Cache: " + profileStore.sharedRoot());
        startLogFlusher();

        msStore = new MicrosoftSessionStore(profileStore.baseDir().resolve("auth").resolve("microsoft_session.json"));
        tryLoadSavedMicrosoftSession();
        updateAccountUi();


        launcherUpdater = new UpdateController(
                "LevinGamer8",
                "ModLauncher"
        );

        if (versionLabel != null) {
            versionLabel.setText("v" + detectVersion());
        }

        // Auto-Check beim Start (nicht wenn dev version)
        if (!versionLabel.getText().equals("vdev")) {
            Platform.runLater(() -> launcherUpdater.checkForUpdates(false));
        }

        // Default-Status optisch korrekt
        setStatus("Bereit", "pillOk");

        if (changelogArea != null) changelogArea.setText("- Noch kein Changelog geladen.\n");
        if (serverStatusLabel != null) serverStatusLabel.getStyleClass().setAll("pillError");
        if (serverDetailsLabel != null) serverDetailsLabel.setText("Noch kein Check implementiert.");
        if (packInfoLabel != null) packInfoLabel.setText("Manifest laden -> dann hier Infos anzeigen.");


        if (logArea != null) logArea.setEditable(false);

        if (mainSplit != null && logPane != null) {

            Runnable apply = () -> {
                if (logPane.isExpanded()) {
                    mainSplit.setDividerPositions(0.60);
                } else {
                    mainSplit.setDividerPositions(0.97);
                }
            };

            Platform.runLater(() -> Platform.runLater(apply));

            logPane.expandedProperty().addListener((obs, oldV, expanded) ->
                    Platform.runLater(() -> Platform.runLater(apply))
            );
        }

    }

    @FXML
    public void onLauncherUpdate() {
        launcherUpdater.checkForUpdates(true);
    }


    private void startLogFlusher() {
        if (logFlushTimeline != null) return;

        logFlushTimeline = new Timeline(new KeyFrame(Duration.millis(100), e -> flushLogQueue()));
        logFlushTimeline.setCycleCount(Animation.INDEFINITE);
        logFlushTimeline.play();
    }

    private void tryLoadSavedMicrosoftSession() {
        var s = msStore.loadOrNull();
        if (s == null) {
            setLoginStatus("Nicht eingeloggt");
            return;
        }

        long now = Instant.now().getEpochSecond();
        // 60s Puffer
        if (s.expiresAtEpochSec() <= now + 60) {
            msStore.clear();
            setLoginStatus("Nicht eingeloggt (Session abgelaufen)");
            appendLog("[LOGIN] gespeicherte Session abgelaufen -> gelöscht");
            return;
        }

        mcSession = s;
        updateAccountUi();
        setLoginStatus("Eingeloggt als: " + mcSession.playerName());
        appendLog("[LOGIN] Session geladen: " + mcSession.playerName());
    }


    @FXML
    private void onEditProfile() {
        var p = profileCombo.getValue();
        if (p == null) return;

        Dialog<ProfileStore.Profile> dialog = new Dialog<>();
        dialog.setTitle("Profil bearbeiten");
        dialog.setHeaderText(null);

        ButtonType saveBtn = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        // Form
        GridPane gp = new GridPane();
        gp.setHgap(10);
        gp.setVgap(10);
        gp.setPadding(new Insets(12));

        TextField name = new TextField(p.name());
        TextField url = new TextField(p.manifestUrl());
        TextField host = new TextField(p.serverHost() == null ? "" : p.serverHost());
        TextField port = new TextField(String.valueOf(p.serverPort()));
        ComboBox<ProfileStore.JoinMode> joinMode = new ComboBox<>();
        joinMode.getItems().setAll(ProfileStore.JoinMode.values());
        joinMode.getSelectionModel().select(p.joinMode() == null ? ProfileStore.JoinMode.SERVERS_DAT : p.joinMode());

        int r = 0;
        gp.addRow(r++, new Label("Name:"), name);
        gp.addRow(r++, new Label("Manifest URL:"), url);
        gp.addRow(r++, new Label("Server Host:"), host);
        gp.addRow(r++, new Label("Server Port:"), port);
        gp.addRow(r++, new Label("Join Mode:"), joinMode);

        ColumnConstraints c1 = new ColumnConstraints();
        c1.setMinWidth(110);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        gp.getColumnConstraints().setAll(c1, c2);

        dialog.getDialogPane().setContent(gp);

        // simple validation
        Node saveNode = dialog.getDialogPane().lookupButton(saveBtn);
        saveNode.disableProperty().bind(
                name.textProperty().isEmpty().or(url.textProperty().isEmpty())
        );

        dialog.setResultConverter(bt -> {
            if (bt != saveBtn) return null;

            String newName = name.getText().trim();
            String newUrl = url.getText().trim();
            String newHost = host.getText().trim();
            int newPort;

            try {
                newPort = Integer.parseInt(port.getText().trim());
            } catch (Exception e) {
                return null; // wird unten als "kein result" behandelt
            }

            if (newName.isEmpty() || newUrl.isEmpty()) return null;

            return new ProfileStore.Profile(
                    newName,
                    newUrl,
                    newHost.isEmpty() ? "" : newHost,
                    newPort,
                    joinMode.getValue()
            );
        });

        var result = dialog.showAndWait().orElse(null);
        if (result == null) return;

        // IMPORTANT: wenn Name geändert wurde, altes Profil löschen, sonst hast du beide
        if (!p.name().equalsIgnoreCase(result.name())) {
            profileStore.deleteProfile(p.name());
        }
        profileStore.saveOrUpdateProfile(result);

        reloadProfilesAndSelect(result.name());
    }


    @FXML
    private void onDuplicateProfile() {
        var p = profileCombo.getValue();
        if (p == null) return;

        String baseName = p.name() + " Copy";
        String newName = baseName;
        int i = 2;

        // Name eindeutig machen
        var existing = profileStore.loadProfiles().stream().map(ProfileStore.Profile::name).map(String::toLowerCase).toList();
        while (existing.contains(newName.toLowerCase())) {
            newName = baseName + " " + (i++);
        }

        var copy = new ProfileStore.Profile(
                newName,
                p.manifestUrl(),
                p.serverHost(),
                p.serverPort(),
                p.joinMode()
        );

        profileStore.saveOrUpdateProfile(copy);
        reloadProfilesAndSelect(copy.name());
    }

    @FXML
    private void onNewProfile() {
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Neues Profil");
        d.setHeaderText("Profilname eingeben");
        d.setContentText("Name:");

        var nameOpt = d.showAndWait();
        if (nameOpt.isEmpty()) return;

        String name = nameOpt.get().trim();
        if (name.isEmpty()) return;

        // Default-Werte
        var p = new ProfileStore.Profile(
                name,
                "http://localhost:8080/pack/fabric-1.21.11/manifest.json",
                "",
                25565,
                ProfileStore.JoinMode.SERVERS_DAT
        );

        profileStore.saveOrUpdateProfile(p);
        reloadProfilesAndSelect(p.name());
    }

    @FXML
    private void onDeleteProfile() {
        var p = profileCombo.getValue();
        if (p == null) return;

        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Profil löschen");
        a.setHeaderText("Profil wirklich löschen?");
        a.setContentText(p.name());

        var res = a.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return;

        profileStore.deleteProfile(p.name());
        reloadProfilesAndSelect(null);
    }

    private void reloadProfilesAndSelect(String nameToSelectOrNull) {
        var all = profileStore.loadProfiles();
        profileCombo.getItems().setAll(all);

        Profile selected = null;

        if (nameToSelectOrNull != null) {
            for (var p : all) {
                if (p.name().equalsIgnoreCase(nameToSelectOrNull)) {
                    selected = p;
                    break;
                }
            }
        }

        if (selected == null && !all.isEmpty()) selected = all.getFirst();

        if (selected != null) {
            profileCombo.getSelectionModel().select(selected);
        }
    }



    @FXML
    public void onUpdate() {
        Profile p = profileCombo.getValue();
        if (p == null) {
            showError("Kein Profil ausgewählt.");
            return;
        }

        String manifestUrl = (p.manifestUrl() == null) ? "" : p.manifestUrl().trim();
        if (manifestUrl.isEmpty()) {
            showError("Dieses Profil hat keine Manifest URL. Bitte über 'Bearbeiten' setzen.");
            return;
        }

        setUiBusy(true);
        clearLog();
        appendLog("Update gestartet: " + manifestUrl);

        Profile finalP = p;

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                updater.update(finalP, profileStore, (msg) -> {
                    updateMessage(msg);
                    appendLog(msg);
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
            String details = formatException(ex);
            appendLog("FEHLER: " + details);
            showError(details);
            updateAccountUi();
            setUiBusy(false);
            progressBar.setProgress(0);
            if (ex != null) ex.printStackTrace();
        });

        Thread t = new Thread(task, "pack-updater");
        t.setDaemon(true);
        t.start();
    }


    @FXML
    public void onPlay() {

        if (!requireLoginOrPopup()) {
            return;
        }


        Profile p = profileCombo.getValue();
        if (p == null) {
            showError("Kein Profil ausgewählt.");
            return;
        }


        String manifestUrl = (p.manifestUrl() == null) ? "" : p.manifestUrl().trim();
        if (manifestUrl.isEmpty()) {
            showError("Dieses Profil hat keine Manifest URL. Bitte über 'Bearbeiten' setzen.");
            return;
        }

        Profile finalP = p; // keine Feld-URL mehr


        setUiBusy(true);
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        appendLog("Play gestartet: Manifest laden, Loader installieren, dann starten...");


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


                MinecraftLauncherService.AuthSession auth = new MinecraftLauncherService.AuthSession(
                        mcSession.playerName(),
                        mcSession.uuid(),
                        mcSession.minecraftAccessToken(),
                        mcSession.userType()
                );

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
                        msg -> appendLog(msg)
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

    private static String formatException(Throwable t) {
        if (t == null) return "unbekannt";
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) msg = t.getClass().getName();

        Throwable c = t.getCause();
        if (c != null) {
            String cm = c.getMessage();
            if (cm == null || cm.isBlank()) cm = c.getClass().getName();
            msg += " | cause: " + cm;
        }
        return msg;
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
        // Buttons, die während Busy wirklich gesperrt werden sollen
        Node[] nodes = {
                updateButton,
                playButton,
                loginButton,
                profileCombo,
                launcherUpdateButton
        };

        for (Node n : nodes) if (n != null) n.setDisable(busy);

        // Diese zwei sollen nicht immer disabled werden:
        // - openMainFolderButton (immer)
        // - openFolderButton (wenn Profil vorhanden)
        if (openFolderButton != null) {
            openFolderButton.setDisable(profileCombo == null || profileCombo.getValue() == null);
        }

        progressBar.setVisible(busy);
        if (!busy) {
            progressBar.setProgress(0);
        } else {
            setStatus(statusLabel.getText() == null || statusLabel.getText().isBlank() ? "Loading..." : statusLabel.getText(), "pillBusy");
        }
    }




    private void appendLog(String s) {
        // NICHT direkt in die TextArea schreiben -> nur queue
        if (s == null) return;
        logQueue.add(s);
    }

    private void clearLog() {
        logQueue.clear();
        logArea.clear();
    }

    private void flushLogQueue() {
        if (logArea == null) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LOG_FLUSH_MAX_LINES; i++) {
            String line = logQueue.poll();
            if (line == null) break;
            sb.append(line).append('\n');
        }

        if (sb.length() == 0) return;

        logArea.appendText(sb.toString());

        // TextArea begrenzen (sonst RAM wächst endlos)
        int len = logArea.getLength();
        if (len > LOG_MAX_CHARS) {
            logArea.deleteText(0, len - LOG_MAX_CHARS);
        }
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

    private void showDeviceCodeDialog(String code, String verificationUrl) {
        Dialog<Void> d = new Dialog<>();
        d.setTitle("Microsoft Login");
        d.setHeaderText("Code kopiert ✅");

        ButtonType copyBtnType = new ButtonType("Code kopieren", ButtonBar.ButtonData.LEFT);
        ButtonType openBtnType = new ButtonType("Seite öffnen", ButtonBar.ButtonData.LEFT);
        ButtonType closeBtnType = new ButtonType("Schließen", ButtonBar.ButtonData.CANCEL_CLOSE);

        d.getDialogPane().getButtonTypes().setAll(copyBtnType, openBtnType, closeBtnType);

        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        box.getChildren().addAll(
                new Label("1) Browser öffnen"),
                new Label("2) Code einfügen"),
                new Label("3) bei Microsoft einloggen und hierher zurückkehren"),
                new Label("Code: " + code)
        );
        d.getDialogPane().setContent(box);

        // Buttons holen
        Button copyBtn = (Button) d.getDialogPane().lookupButton(copyBtnType);
        Button openBtn = (Button) d.getDialogPane().lookupButton(openBtnType);

        // WICHTIG: Button-Klick darf Dialog NICHT schließen -> Event konsumieren
        copyBtn.addEventFilter(ActionEvent.ACTION, e -> {
            copyToClipboard(code);
            appendLog("[LOGIN] Code kopiert: " + code);
            e.consume();
        });

        openBtn.addEventFilter(ActionEvent.ACTION, e -> {
            try { Desktop.getDesktop().browse(java.net.URI.create(verificationUrl)); } catch (Exception ignored) {}
            e.consume();
        });

        // Nur Schließen/X schließen wirklich
        loginDialog = d;
        d.setOnHidden(e -> {
            if (loginDialog == d) loginDialog = null;
        });
        d.show();

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

                    copyToClipboard(dc.userCode());
                    setLoginStatus("Code kopiert: " + dc.userCode());

                    // Browser optional automatisch öffnen ODER nur Dialogbutton
                    try { Desktop.getDesktop().browse(java.net.URI.create(dc.verificationUri())); } catch (Exception ignored) {}

                    showDeviceCodeDialog(dc.userCode(), dc.verificationUri());
                });

                // 3) Polling bis fertig
                return auth.loginWithDeviceCode(dc);
            }
        };

        task.setOnSucceeded(e -> {
            mcSession = task.getValue();

            setLoginStatus("Eingeloggt als: " + mcSession.playerName());
            appendLog("[LOGIN] OK: " + mcSession.playerName() + " / " + mcSession.uuid());
            msStore.save(mcSession);
            appendLog("[LOGIN] Session gespeichert: " + msStore.file());

            updateAccountUi();
            setStatus("Fertig", "pillOk");

            Platform.runLater(() -> {
                closeLoginDialogIfOpen();
                showWelcomeToast(mcSession.playerName());
            });
        });

        task.setOnFailed(e -> {
            setStatus("Fehler", "pillError");
            Throwable ex = task.getException();
            setLoginStatus("Login fehlgeschlagen");
            appendLog("[LOGIN] ERROR: " + (ex != null ? ex.toString() : "unknown"));
        });

        new Thread(task, "ms-login").start();
    }

    private void setStatus(String text, String pillStyle) {
        Platform.runLater(() -> {
            statusLabel.textProperty().unbind();
            statusLabel.setText(text);
            statusLabel.getStyleClass().removeAll("pillOk", "pillBusy", "pillError");
            statusLabel.getStyleClass().add(pillStyle);
        });
    }

    private String detectVersion() {
        // kommt aus Manifest Implementation-Version (Gradle/Maven setzen, siehe unten)
        String v = getClass().getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? "dev" : v;
    }


    @FXML
    private void onCopyLog() {
        var cb = new javafx.scene.input.ClipboardContent();
        cb.putString(logArea.getText());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cb);
    }

    @FXML
    private void onClearLog() {
        clearLog();
    }

    private boolean isLoggedIn() {
        return mcSession != null
                && mcSession.minecraftAccessToken() != null
                && !mcSession.minecraftAccessToken().isBlank();
    }

    private boolean requireLoginOrPopup() {
        if (isLoggedIn()) return true;

        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Login erforderlich");
        a.setHeaderText("Du musst dich erst einloggen");
        a.setContentText("Bitte melde dich mit Microsoft an, bevor du Minecraft starten kannst.");

        ButtonType loginNow = new ButtonType("Jetzt einloggen", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Abbrechen", ButtonBar.ButtonData.CANCEL_CLOSE);
        a.getButtonTypes().setAll(loginNow, cancel);

        var res = a.showAndWait().orElse(cancel);
        if (res == loginNow) {
            onLoginClicked(); // startet deinen Device-Code Login
        }
        return false;
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

    private void updateAccountUi() {
        Platform.runLater(() -> {
            boolean loggedIn = isLoggedIn();

            String name = loggedIn ? mcSession.playerName() : "Nicht eingeloggt";
            if (accountNameLabel != null) accountNameLabel.setText(name);

            if (skinView != null) {
                String headUrl;
                if (loggedIn) {
                    // 3D/2D Head Render (funktioniert easy)
                    headUrl = "https://minotar.net/helm/" + mcSession.playerName() + "/64.png";
                } else {
                    // Steve fallback
                    headUrl = "https://minotar.net/helm/Steve/64.png";
                }
                skinView.setImage(new Image(headUrl, true)); // backgroundLoading=true
            }

            if (loginButton != null) {
                loginButton.setText(loggedIn ? "Logout" : "Login (Microsoft)");
            }
            if (playButton != null) playButton.setDisable(!loggedIn);

        });
    }


    @FXML
    public void onOpenMainFolder() {
        try {
            Path mainDir = Path.of(
                    System.getProperty("user.home"),
                    "AppData", "Roaming", ".modlauncher"
            );

            if (!mainDir.toFile().exists()) {
                showError("Main-Ordner existiert nicht:\n" + mainDir);
                return;
            }

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(mainDir.toFile());
            } else {
                showError("Desktop-Open wird auf diesem System nicht unterstützt.");
            }
        } catch (Exception e) {
            showError("Konnte Main-Ordner nicht öffnen:\n" + e.getMessage());
        }
    }

    @FXML
    private void onAccountButton() {
        if (isLoggedIn()) {
            doLogout();
        } else {
            onLoginClicked();
        }
    }

    private void doLogout() {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Logout");
        a.setHeaderText("Wirklich ausloggen?");
        a.setContentText("Die gespeicherte Session wird gelöscht.");

        var res = a.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return;

        mcSession = null;
        if (msStore != null) msStore.clear();

        setLoginStatus("Nicht eingeloggt");
        appendLog("[LOGIN] Logout: Session gelöscht");
        setStatus("Bereit", "pillOk");
        updateAccountUi();
    }

    private void copyToClipboard(String text) {
        ClipboardContent c = new ClipboardContent();
        c.putString(text);
        Clipboard.getSystemClipboard().setContent(c);
    }


    private void closeLoginDialogIfOpen() {
        if (loginDialog != null) {
            try { loginDialog.close(); } catch (Exception ignored) {}
            loginDialog = null;
        }
    }

    private void showWelcomeToast(String name) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Eingeloggt");
        a.setHeaderText("Willkommen, " + name + "!");
        a.setContentText("Du bist nun erfolgreich eingeloggt.");
        a.getButtonTypes().setAll(ButtonType.OK);
        a.show();

        // Auto-close nach 2 Sekunden
        Timeline t = new Timeline(new KeyFrame(Duration.seconds(2), e -> a.close()));
        t.setCycleCount(1);
        t.play();
    }


    private static class HostDialogResult {
        String projectId, name, version;
        String mcVersion, loader, loaderVersion;
        String filesBaseUrl;
        String uploadUrl;
        String bearerToken;
        boolean doUpload;

        ProjectHostService.Selection selection;
    }

    private HostDialogResult showHostDialog(Profile p) {
        Dialog<HostDialogResult> d = new Dialog<>();
        d.setTitle("Projekt hosten");
        d.setHeaderText("Wähle, was aus der Instanz hochgeladen werden soll.");

        ButtonType ok = new ButtonType("Build", ButtonBar.ButtonData.OK_DONE);
        ButtonType buildUpload = new ButtonType("Build + Upload", ButtonBar.ButtonData.APPLY);
        d.getDialogPane().getButtonTypes().addAll(ok, buildUpload, ButtonType.CANCEL);

        GridPane gp = new GridPane();
        gp.setHgap(10); gp.setVgap(10); gp.setPadding(new Insets(12));

        TextField projectId = new TextField(p.name().toLowerCase().replace(" ", "-"));
        TextField name = new TextField(p.name());
        TextField version = new TextField("1.0.0");

        TextField mcVersion = new TextField("1.20.1");

        // Loader als Auswahl, damit es keine Tippfehler gibt
        ComboBox<String> loader = new ComboBox<>();
        loader.getItems().setAll("fabric", "forge");
        loader.getSelectionModel().select("fabric");

        TextField loaderVersion = new TextField("");

        TextField filesBaseUrl = new TextField("https://server.tld/projects/" + projectId.getText() + "/files/");
        TextField uploadUrl = new TextField("https://server.tld/api/upload");
        PasswordField token = new PasswordField();

        CheckBox cbMods = new CheckBox("mods/ (files)"); cbMods.setSelected(true);
        CheckBox cbConfig = new CheckBox("config/ (overrides)"); cbConfig.setSelected(true);
        CheckBox cbServersDat = new CheckBox("servers.dat (overrides)"); cbServersDat.setSelected(false);
        CheckBox cbOptions = new CheckBox("options.txt (overrides)"); cbOptions.setSelected(false);
        CheckBox cbKubejs = new CheckBox("kubejs/ (overrides)"); cbKubejs.setSelected(false);
        CheckBox cbDefaultConfigs = new CheckBox("defaultconfigs/ (overrides)"); cbDefaultConfigs.setSelected(false);
        CheckBox cbShaderpacks = new CheckBox("shaderpacks/ (files)"); cbShaderpacks.setSelected(false);
        CheckBox cbResourcepacks = new CheckBox("resourcepacks/ (files)"); cbResourcepacks.setSelected(false);





        // nach dem Erstellen von mcVersionField, loaderBox, loaderVersionField:
        wireLoaderAutoSuggest(mcVersion, loader, loaderVersion);


        int r = 0;
        gp.addRow(r++, new Label("Project ID:"), projectId);
        gp.addRow(r++, new Label("Name:"), name);
        gp.addRow(r++, new Label("Version:"), version);

        gp.addRow(r++, new Label("MC Version:"), mcVersion);
        gp.addRow(r++, new Label("Loader:"), loader);
        gp.addRow(r++, new Label("Loader Version:"), loaderVersion);

        gp.addRow(r++, new Label("filesBaseUrl:"), filesBaseUrl);
        gp.addRow(r++, new Label("uploadUrl:"), uploadUrl);
        gp.addRow(r++, new Label("Bearer Token:"), token);

        VBox box = new VBox(6, cbMods, cbConfig, cbServersDat, cbOptions, cbKubejs, cbDefaultConfigs, cbShaderpacks, cbResourcepacks);
        box.setPadding(new Insets(6,0,0,0));
        gp.addRow(r++, new Label("Auswahl:"), box);

        ColumnConstraints c1 = new ColumnConstraints(); c1.setMinWidth(120);
        ColumnConstraints c2 = new ColumnConstraints(); c2.setHgrow(Priority.ALWAYS);
        gp.getColumnConstraints().setAll(c1, c2);

        d.getDialogPane().setContent(gp);

        Node okBtn = d.getDialogPane().lookupButton(ok);
        okBtn.disableProperty().bind(projectId.textProperty().isEmpty().or(filesBaseUrl.textProperty().isEmpty()));

        d.setResultConverter(bt -> {
            if (bt == ButtonType.CANCEL) return null;

            HostDialogResult res = new HostDialogResult();
            res.projectId = projectId.getText().trim();
            res.name = name.getText().trim();
            res.version = version.getText().trim();

            res.mcVersion = mcVersion.getText().trim();
            res.loader = loader.getValue() == null ? "" : loader.getValue().trim();
            res.loaderVersion = loaderVersion.getText().trim();

            res.filesBaseUrl = filesBaseUrl.getText().trim();
            res.uploadUrl = uploadUrl.getText().trim();
            res.bearerToken = token.getText();

            res.doUpload = (bt == buildUpload);

            res.selection = new ProjectHostService.Selection(
                    cbMods.isSelected(),
                    cbConfig.isSelected(),
                    cbServersDat.isSelected(),
                    cbOptions.isSelected(),
                    cbKubejs.isSelected(),
                    cbDefaultConfigs.isSelected(),
                    cbShaderpacks.isSelected(),
                    cbResourcepacks.isSelected()
            );

            return res;
        });

        return d.showAndWait().orElse(null);
    }

    @FXML
    public void onProjectHost() {

        // 1) Instanz explizit auswählen
        Profile p = pickInstanceToHost();
        if (p == null) return;

        Path instanceDir = profileStore.instanceDir(p.name());
        if (!instanceDir.toFile().exists()) {
            showError("Instanz existiert nicht: " + instanceDir);
            return;
        }

        // 2) Host-Dialog
        HostDialogResult cfg = showHostDialog(p);
        if (cfg == null) return;

        setUiBusy(true);
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        appendLog("[HOST] Start für Instanz: " + p.name());

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateMessage("Build...");

                HostManifest m = new HostManifest();
                m.projectId = cfg.projectId;
                m.name = cfg.name;
                m.version = cfg.version;
                m.minecraft.version = cfg.mcVersion;
                m.minecraft.loader = cfg.loader;
                m.minecraft.loaderVersion = cfg.loaderVersion;

                m.filesBaseUrl = cfg.filesBaseUrl;
                m.overridesUrl = cfg.filesBaseUrl.replace("/files/", "/overrides.zip");

                Path outDir = profileStore.baseDir().resolve("host_builds").resolve(cfg.projectId);

                ProjectHostService.build(
                        instanceDir,
                        m,
                        cfg.selection,
                        outDir,
                        (msg) -> appendLog(msg),
                        (done, total) -> Platform.runLater(() -> {
                            if (total <= 0) progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                            else progressBar.setProgress((double) done / (double) total);
                        })
                );

                if (cfg.doUpload) {
                    updateMessage("Upload...");
                    Platform.runLater(() -> progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS));

                    ProjectHostService.uploadPutPathQuery(
                            cfg.uploadUrl,
                            cfg.projectId,
                            outDir,
                            cfg.bearerToken,
                            (msg) -> appendLog(msg),
                            (done, total) -> Platform.runLater(() -> {
                                if (total <= 0) progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                                else progressBar.setProgress((double) done / (double) total);
                            })
                    );
                }

                updateMessage("Fertig");
                return null;
            }
        };

        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            statusLabel.textProperty().unbind();
            setStatus("Fertig", "pillOk");
            appendLog("[HOST] Done.");
            setUiBusy(false);
            progressBar.setProgress(1);
        });

        task.setOnFailed(e -> {
            statusLabel.textProperty().unbind();
            setStatus("Fehler", "pillError");
            Throwable ex = task.getException();
            appendLog("[HOST] ERROR: " + (ex != null ? formatException(ex) : "unknown"));
            setUiBusy(false);
            progressBar.setProgress(0);
            showError(ex != null ? ex.getMessage() : "Unbekannter Fehler");
            if (ex != null) ex.printStackTrace();
        });

        Thread t = new Thread(task, "project-host");
        t.setDaemon(true);
        t.start();
    }


    private Profile pickInstanceToHost() {
        var profiles = profileStore.loadProfiles();
        if (profiles == null || profiles.isEmpty()) {
            showError("Keine Instanzen/Profiles vorhanden.");
            return null;
        }

        Dialog<Profile> d = new Dialog<>();
        d.setTitle("Instanz auswählen");
        d.setHeaderText("Welche Instanz möchtest du hosten?");

        ButtonType ok = new ButtonType("Weiter", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        ComboBox<Profile> cb = new ComboBox<>();
        cb.getItems().setAll(profiles);

        // Anzeige Name
        cb.setCellFactory(x -> new ListCell<>() {
            @Override protected void updateItem(Profile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.name());
            }
        });
        cb.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(Profile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.name());
            }
        });

        // Vorauswahl: aktuell gewähltes Profil, sonst erstes
        Profile current = profileCombo != null ? profileCombo.getValue() : null;
        if (current != null) cb.getSelectionModel().select(current);
        else cb.getSelectionModel().selectFirst();

        VBox box = new VBox(10, new Label("Instanz:"), cb);
        box.setPadding(new Insets(12));
        d.getDialogPane().setContent(box);

        Node okBtn = d.getDialogPane().lookupButton(ok);
        okBtn.setDisable(cb.getValue() == null);
        cb.valueProperty().addListener((o, a, b) -> okBtn.setDisable(b == null));

        d.setResultConverter(bt -> bt == ok ? cb.getValue() : null);

        return d.showAndWait().orElse(null);
    }


    private void wireLoaderAutoSuggest(
            TextField mcVersionField,
            ComboBox<String> loaderBox,
            TextField loaderVersionField
    ) {
        // merkt sich, ob wir das Feld automatisch gesetzt haben
        final boolean[] autoSet = { false };

        Runnable trigger = () -> {
            String l = loaderBox.getValue();
            String mc = mcVersionField.getText() == null ? "" : mcVersionField.getText().trim();
            if (l == null || mc.isEmpty()) return;

            // NICHT überschreiben, wenn User schon was eingetragen hat (und es nicht von uns kam)
            String current = loaderVersionField.getText() == null ? "" : loaderVersionField.getText().trim();
            if (!current.isEmpty() && !autoSet[0]) return;

            Task<String> t = new Task<>() {
                @Override protected String call() throws Exception {
                    if ("forge".equalsIgnoreCase(l)) {
                        return ForgeVersionResolver.resolveRecommendedOrLatest(mc);
                    }
                    if ("fabric".equalsIgnoreCase(l)) {
                        return FabricVersionResolver.resolveLatestStable(mc);
                    }
                    return "";
                }
            };

            t.setOnSucceeded(e -> {
                String v = t.getValue();
                if (v == null || v.isBlank()) {
                    appendLog("[LOADER] Keine Version gefunden für " + l + " / MC " + mc);
                    return;
                }
                loaderVersionField.setText(v);
                autoSet[0] = true;
                appendLog("[LOADER] Auto: " + l + " " + mc + " -> " + v);
            });

            t.setOnFailed(e -> {
                Throwable ex = t.getException();
                appendLog("[LOADER] Resolve failed: " + (ex != null ? ex.getMessage() : "unknown"));
            });

            Thread th = new Thread(t, "loader-resolve");
            th.setDaemon(true);
            th.start();
        };

        // Wenn User manuell tippt, ist es nicht mehr "auto"
        loaderVersionField.textProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            // wenn User ändert (nicht via setText aus trigger) kannst du schwer erkennen,
            // aber pragmatisch: sobald er fokussiert ist und tippt -> autoSet=false
            // -> das machen wir über focus listener:
        });

        loaderVersionField.focusedProperty().addListener((obs, was, is) -> {
            if (is) autoSet[0] = false; // User übernimmt Kontrolle
        });

        // Trigger bei Änderungen
        mcVersionField.textProperty().addListener((o,a,b) -> trigger.run());
        loaderBox.valueProperty().addListener((o,a,b) -> trigger.run());

        // 1x initial
        Platform.runLater(trigger);
    }




}
