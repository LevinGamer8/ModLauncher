package de.levingamer8.modlauncher.ui;

import de.levingamer8.modlauncher.auth.MicrosoftMinecraftAuth;
import de.levingamer8.modlauncher.core.ManifestModels;
import de.levingamer8.modlauncher.core.PackUpdater;
import de.levingamer8.modlauncher.core.ProfileStore;
import de.levingamer8.modlauncher.core.ProfileStore.Profile;
import de.levingamer8.modlauncher.core.LoaderType;
import de.levingamer8.modlauncher.host.CreateHostProjectRequest;
import de.levingamer8.modlauncher.host.HostProjectCreator;
import de.levingamer8.modlauncher.host.modrinth.ModrinthClient;
import de.levingamer8.modlauncher.host.modrinth.SearchHit;
import de.levingamer8.modlauncher.host.modrinth.Version;
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
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.util.Duration;
import javafx.util.StringConverter;

import javafx.geometry.Insets;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TitledPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;


import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.awt.Desktop;

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

    private final java.util.concurrent.ConcurrentHashMap<String, javafx.scene.image.Image> iconCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.net.http.HttpClient iconHttp = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();


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


    @FXML
    public void onHostMode() {
        // Dialog
        Dialog<CreateHostProjectRequest> d = new Dialog<>();
        d.setTitle("Projekt hosten");
        d.setHeaderText(null);

        ButtonType createBtn = new ButtonType("Erstellen", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(createBtn, ButtonType.CANCEL);

        GridPane gp = new GridPane();
        gp.setHgap(10);
        gp.setVgap(10);
        gp.setPadding(new javafx.geometry.Insets(12));

        TextField projectId = new TextField("testpack");
        TextField name = new TextField("Test Pack");
        TextField mcVersion = new TextField("1.20.1");

        ComboBox<de.levingamer8.modlauncher.core.LoaderType> loader = new ComboBox<>();
        loader.getItems().setAll(de.levingamer8.modlauncher.core.LoaderType.values());
        loader.getSelectionModel().select(de.levingamer8.modlauncher.core.LoaderType.FABRIC);

        TextField loaderVersion = new TextField("0.15.11");
        TextField baseUrl = new TextField("https://mc.local/testpack/");
        TextField initialVersion = new TextField("1.0.0");

        TextField outFolder = new TextField(
                profileStore.baseDir().resolve("host-projects").toString()
        );
        Button browse = new Button("…");
        browse.setOnAction(e -> {
            DirectoryChooser ch = new DirectoryChooser();
            ch.setTitle("Output Ordner wählen");
            java.io.File sel = ch.showDialog(d.getDialogPane().getScene().getWindow());
            if (sel != null) outFolder.setText(sel.getAbsolutePath());
        });

        int r = 0;
        gp.addRow(r++, new Label("Project ID:"), projectId);
        gp.addRow(r++, new Label("Name:"), name);
        gp.addRow(r++, new Label("MC Version:"), mcVersion);
        gp.addRow(r++, new Label("Loader:"), loader);
        gp.addRow(r++, new Label("Loader Version:"), loaderVersion);
        gp.addRow(r++, new Label("Base URL:"), baseUrl);
        gp.addRow(r++, new Label("Initial Version:"), initialVersion);

        HBox outRow = new HBox(8, outFolder, browse);
        HBox.setHgrow(outFolder, Priority.ALWAYS);
        gp.addRow(r++, new Label("Output Folder:"), outRow);

        d.getDialogPane().setContent(gp);

        // LoaderVersion nur bei VANILLA deaktivieren
        loader.valueProperty().addListener((obs, o, n) -> {
            boolean vanilla = (n == de.levingamer8.modlauncher.core.LoaderType.VANILLA);
            loaderVersion.setDisable(vanilla);
            if (vanilla) loaderVersion.setText("");
        });

        Node createNode = d.getDialogPane().lookupButton(createBtn);
        createNode.disableProperty().bind(
                projectId.textProperty().isEmpty()
                        .or(name.textProperty().isEmpty())
                        .or(mcVersion.textProperty().isEmpty())
                        .or(baseUrl.textProperty().isEmpty())
                        .or(outFolder.textProperty().isEmpty())
                        .or(initialVersion.textProperty().isEmpty())
        );

        d.setResultConverter(bt -> {
            if (bt != createBtn) return null;
            return new CreateHostProjectRequest(
                    projectId.getText().trim().toLowerCase(),
                    name.getText().trim(),
                    mcVersion.getText().trim(),
                    loader.getValue(),
                    loaderVersion.getText().trim(),
                    initialVersion.getText().trim(),
                    baseUrl.getText().trim(),
                    Path.of(outFolder.getText().trim())
            );
        });

        var req = d.showAndWait().orElse(null);
        if (req == null) return;

        try {
            var creator = new HostProjectCreator();
            var paths = creator.create(req);
            Path modsDir = paths.filesDir().resolve("mods");
            openModrinthSearchAndAdd(req.mcVersion(), req.loader(), modsDir);

            appendLog("[HOST] Projekt erstellt: " + paths.projectRoot());
            appendLog("[HOST] latest: " + paths.latestJson());
            appendLog("[HOST] manifest: " + paths.manifestJson());

            // Ordner öffnen
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(paths.projectRoot().toFile());
            }
        } catch (Exception ex) {
            showError("Host-Projekt konnte nicht erstellt werden:\n" + ex.getMessage());
        }
    }

    public void openModrinthSearchAndAdd(
            String mcVersion,
            LoaderType loaderType,
            Path modsDir
    ) {
        if (loaderType == LoaderType.VANILLA) {
            showError("Modrinth Search: Vanilla hat keine Mods im Sinne von Forge/Fabric.");
            return;
        }

        String modrinthLoader = LoaderType.toString(loaderType);
        ModrinthClient api = new ModrinthClient();

        Dialog<Void> dialog = new Dialog<>();
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Modrinth: Mods hinzufügen");
        dialog.setHeaderText(null);

        ButtonType closeBtn = new ButtonType("Schließen", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeBtn);

        // --- UI Controls ---
        TextField query = new TextField();
        query.setPromptText("Mod suchen (z.B. sodium, jei, iris...)");

        Label ctx = new Label("MC: " + mcVersion + " | Loader: " + loaderType);

        Button searchBtn = new Button("Suchen");
        searchBtn.setDefaultButton(true);

        ProgressIndicator progress = new ProgressIndicator();
        progress.setVisible(false);
        progress.setMaxSize(18, 18);

        Label status = new Label();
        status.setMinHeight(18);

        ListView<SearchHit> list = new ListView<>();
        list.setCellFactory(lv -> new ListCell<>() {
            private String expectedIconUrl;

            private final ImageView icon = new ImageView();
            private final Label title = new Label();
            private final Label meta  = new Label();
            private final Label desc  = new Label();

            private final VBox textBox = new VBox(2, title, meta, desc);
            private final HBox row = new HBox(10, icon, textBox);

            private final Separator sep = new Separator();
            private final VBox root = new VBox(8, row, sep);

            {
                // icon
                icon.setFitWidth(64);
                icon.setFitHeight(64);
                icon.setPreserveRatio(true);
                icon.setSmooth(true);

                // text styles (ohne CSS geht auch direkt)
                title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
                meta.setStyle("-fx-opacity: 0.75; -fx-font-size: 11px;");
                desc.setStyle("-fx-opacity: 0.9; -fx-font-size: 12px;");
                desc.setWrapText(true);

                // layout
                row.setFillHeight(true);
                HBox.setHgrow(textBox, Priority.ALWAYS);

                // damit WrapText wirklich funktioniert:
                textBox.prefWidthProperty().bind(lv.widthProperty().subtract(70));

                // Separator etwas dezenter
                sep.setOpacity(0.35);
                sep.setVisible(false);
                sep.setManaged(false);

                this.hoverProperty().addListener((obs, o, n) -> {
                    root.setStyle(n
                            ? "-fx-background-color: rgba(255,255,255,0.06); -fx-background-radius: 10;"
                            : "-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 10;");
                });

            }

            @Override
            protected void updateItem(SearchHit item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }

                title.setText(item.title());

                String author = (item.author() == null || item.author().isBlank()) ? "?" : item.author();
                meta.setText(author + " • " + formatDownloads(item.downloads()) + " downloads • " + item.slug());

                desc.setText(item.description() == null ? "" : item.description());

                expectedIconUrl = item.icon_url();
                loadIconAsync(expectedIconUrl, icon, () -> expectedIconUrl);


                boolean isLast = (getIndex() == lv.getItems().size() - 1);
                sep.setVisible(!isLast);
                sep.setManaged(!isLast);

                setGraphic(root);
            }
        });



        Button addBtn = new Button("Add to Pack");
        addBtn.setDisable(true);

        // enable add button when selection exists
        list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> addBtn.setDisable(n == null));
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) addBtn.fire();
        });


        // Layout
        HBox top = new HBox(10, query, searchBtn, progress);
        HBox.setHgrow(query, Priority.ALWAYS);

        VBox root = new VBox(10,
                ctx,
                top,
                list,
                new HBox(10, addBtn),
                status
        );
        root.setPadding(new Insets(8, 10, 8, 10));
        root.setStyle("""
             -fx-background-color: rgba(255,255,255,0.03);
             -fx-background-radius: 10;
        """);
        VBox.setVgrow(list, Priority.ALWAYS);

        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().setPrefSize(720, 520);
        dialog.setResizable(true);

        // --- Actions ---
        Runnable doSearch = () -> {
            String q = query.getText() == null ? "" : query.getText().trim();
            if (q.isEmpty()) {
                status.setText("Bitte Suchbegriff eingeben.");
                return;
            }

            progress.setVisible(true);
            searchBtn.setDisable(true);
            addBtn.setDisable(true);
            status.setText("Suche…");

            Task<java.util.List<SearchHit>> t = new Task<>() {
                @Override protected java.util.List<SearchHit> call() throws Exception {
                    return api.searchModsPage(q, modrinthLoader, mcVersion, 50, 0).hits();
                }
            };

            t.setOnSucceeded(ev -> {
                List<SearchHit> hits = t.getValue();
                list.getItems().setAll(hits);
                status.setText(hits.isEmpty() ? "Keine Treffer." : ("Treffer: " + hits.size()));
                progress.setVisible(false);
                searchBtn.setDisable(false);
            });

            t.setOnFailed(ev -> {
                Throwable ex = t.getException();
                progress.setVisible(false);
                searchBtn.setDisable(false);
                status.setText("Fehler bei Suche.");
                showError("Modrinth Suche fehlgeschlagen:\n" + (ex == null ? "unknown" : ex.getMessage()));
            });

            Thread th = new Thread(t, "modrinth-search");
            th.setDaemon(true);
            th.start();
        };

        searchBtn.setOnAction(e -> doSearch.run());
        query.setOnAction(e -> doSearch.run()); // Enter in TextField

        addBtn.setOnAction(e -> {
            SearchHit sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;

            progress.setVisible(true);
            searchBtn.setDisable(true);
            addBtn.setDisable(true);
            status.setText("Downloade & füge hinzu: " + sel.title());

            Task<Path> t = new Task<>() {
                @Override protected Path call() throws Exception {
                    // best compatible version for loader+mc
                    Version v = api.getBestVersion(sel.project_id(), modrinthLoader, mcVersion);
                    // download jar into mods folder
                    return api.downloadPrimaryJar(v, modsDir);
                }
            };

            t.setOnSucceeded(ev -> {
                Path jar = t.getValue();
                progress.setVisible(false);
                searchBtn.setDisable(false);
                status.setText("Hinzugefügt: " + jar.getFileName());
                appendLog("[HOST] Mod hinzugefügt: " + jar.getFileName() + " -> " + jar);

                // optional: auto-refresh manifest later, not here
            });

            t.setOnFailed(ev -> {
                Throwable ex = t.getException();
                progress.setVisible(false);
                searchBtn.setDisable(false);
                status.setText("Fehler beim Download.");
                showError("Mod hinzufügen fehlgeschlagen:\n" + (ex == null ? "unknown" : ex.getMessage()));
            });

            Thread th = new Thread(t, "modrinth-add");
            th.setDaemon(true);
            th.start();
        });

        // show dialog
        dialog.showAndWait();
    }

    private static String formatDownloads(long n) {
        if (n < 1_000) return Long.toString(n);
        double val;
        String suffix;
        if (n < 1_000_000) { val = n / 1_000.0; suffix = "K"; }
        else if (n < 1_000_000_000) { val = n / 1_000_000.0; suffix = "M"; }
        else { val = n / 1_000_000_000.0; suffix = "B"; }

        String s = (val >= 10) ? String.format(java.util.Locale.US, "%.0f", val)
                : String.format(java.util.Locale.US, "%.1f", val);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + suffix;
    }

    private void loadIconAsync(String url, javafx.scene.image.ImageView target, java.util.function.Supplier<String> currentUrl) {
        if (url == null || url.isBlank()) {
            target.setImage(null);
            return;
        }

        javafx.scene.image.Image cached = iconCache.get(url);
        if (cached != null) {
            target.setImage(cached);
            return;
        }

        target.setImage(null);

        javafx.concurrent.Task<javafx.scene.image.Image> t = new javafx.concurrent.Task<>() {
            @Override protected javafx.scene.image.Image call() throws Exception {
                var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .header("User-Agent", "ModLauncher/1.0 (host-mode)")
                        .GET().build();
                var res = iconHttp.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) return null;
                return new javafx.scene.image.Image(new java.io.ByteArrayInputStream(res.body()));
            }
        };

        t.setOnSucceeded(e -> {
            var img = t.getValue();
            if (img == null) return;
            iconCache.put(url, img);
            if (url.equals(currentUrl.get())) {
                target.setImage(img);
            }
        });

        Thread th = new Thread(t, "modrinth-icon");
        th.setDaemon(true);
        th.start();
    }

}
