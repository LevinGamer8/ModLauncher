package de.levingamer8.modlauncher.ui;

import de.levingamer8.modlauncher.auth.MicrosoftMinecraftAuth;
import de.levingamer8.modlauncher.core.*;
import de.levingamer8.modlauncher.auth.MicrosoftSessionStore;
import de.levingamer8.modlauncher.core.ManifestModels;
import de.levingamer8.modlauncher.core.PackUpdater;
import de.levingamer8.modlauncher.core.ProfileStore;
import de.levingamer8.modlauncher.core.ProfileStore.Profile;
import de.levingamer8.modlauncher.core.LoaderType;
import de.levingamer8.modlauncher.host.*;
import de.levingamer8.modlauncher.host.modrinth.ModrinthClient;
import de.levingamer8.modlauncher.host.modrinth.SearchHit;
import de.levingamer8.modlauncher.host.modrinth.Version;
import de.levingamer8.modlauncher.mc.MinecraftLauncherService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.nio.file.Path;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import de.levingamer8.modlauncher.mc.PlaytimeStore;
import de.levingamer8.modlauncher.update.UpdateController;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;
import javafx.stage.FileChooser;


import de.levingamer8.modlauncher.ui.dialogs.LauncherSettings;

import javafx.scene.control.SplitPane;
import javafx.scene.control.TitledPane;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.*;
import java.awt.Desktop;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Controller {

    @FXML private BorderPane root;

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
    @FXML private Button settingsButton;
    @FXML private Label versionLabel;
    @FXML private TextArea changelogArea;
    @FXML private Label serverStatusLabel;
    @FXML private Label serverDetailsLabel;
    @FXML private Label packInfoLabel;
    @FXML private SplitPane mainSplit;
    @FXML private TitledPane logPane;
    @FXML private ImageView skinView;
    @FXML private Label accountNameLabel;


    // Playtime Labels (FXML muss fx:id="instancePlaytimeLabel" und fx:id="globalPlaytimeLabel" haben)
    @FXML private Label instancePlaytimeLabel;
    @FXML private Label globalPlaytimeLabel;

    @FXML private Label serverPlayersLabel;
    @FXML private Label serverPingLabel;

    private PlaytimeStore globalPlaytimeStore;
    private PlaytimeStore instancePlaytimeStore;

    private final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();
    private Timeline logFlushTimeline;

    private static final int LOG_FLUSH_MAX_LINES = 500;
    private static final int LOG_MAX_CHARS = 300_000;

    private final ProfileStore profileStore = new ProfileStore();
    private final PackUpdater updater = new PackUpdater();

    private volatile MicrosoftMinecraftAuth.MinecraftSession mcSession;
    private MicrosoftSessionStore msStore;

    private UpdateController launcherUpdater;
    private Dialog<Void> loginDialog;
    private boolean uiBusy = false;

    private record NewProfileData(String name, String manifestUrl) {}

    private record VersionsPointer(String version, String manifestUrl) {}


    private final java.util.concurrent.ConcurrentHashMap<String, Image> iconCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.net.http.HttpClient iconHttp = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();


    private final ScheduledExecutorService serverPollExec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "server-poll");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> serverPollTask;


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
            @Override public Profile fromString(String s) { return null; }
        });

        reloadProfilesAndSelect(null);

        profileCombo.valueProperty().addListener((obs, oldV, newV) -> {
            Profile currentProfile = newV;   // WICHTIG: Profil-State setzen!

            refreshProfileDependentUi();
            refreshPlaytimeUi();
            restartServerPolling();
        });



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

        if (versionLabel != null && !versionLabel.getText().equals("vdev")) {
            Platform.runLater(() -> launcherUpdater.checkForUpdates(false));
        }

        setStatus("Bereit", "pillOk");

        if (changelogArea != null) changelogArea.setText("- Noch kein Changelog geladen.\n");
        if (serverStatusLabel != null) serverStatusLabel.getStyleClass().setAll("pillError");
        if (serverDetailsLabel != null) serverDetailsLabel.setText("Noch kein Check implementiert.");
        if (packInfoLabel != null) packInfoLabel.setText("Manifest laden -> dann hier Infos anzeigen.");

        if (logArea != null) logArea.setEditable(false);

        if (mainSplit != null && logPane != null) {
            Runnable apply = () -> {
                if (logPane.isExpanded()) mainSplit.setDividerPositions(0.60);
                else mainSplit.setDividerPositions(0.97);
            };

            Platform.runLater(() -> Platform.runLater(apply));

            logPane.expandedProperty().addListener((obs, oldV, expanded) ->
                    Platform.runLater(() -> Platform.runLater(apply))
            );
        }

        refreshProfileDependentUi();
        refreshPlaytimeUi();
    }

    @FXML
    public void onLauncherUpdate() {
        launcherUpdater.checkForUpdates(true);
    }

    @FXML
    public void onOpenSettings() {
        // Simple dialog (no extra FXML), stores values in Preferences via LauncherSettings
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Settings");
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // --- Language ---
        ComboBox<Locale> langBox = new ComboBox<>();
        langBox.getItems().addAll(
                null,               // system
                Locale.GERMAN,
                Locale.ENGLISH
        );
        langBox.setConverter(new StringConverter<>() {
            @Override public String toString(Locale l) {
                if (l == null) return "System (Default)";
                if (l.getLanguage().equals("de")) return "Deutsch";
                if (l.getLanguage().equals("en")) return "English";
                return l.getDisplayName();
            }
            @Override public Locale fromString(String s) { return null; }
        });

        Locale current = LauncherSettings.getLocale();
        if (current != null && current.getLanguage().equals("en")) langBox.setValue(Locale.ENGLISH);
        else if (current != null && current.getLanguage().equals("de")) langBox.setValue(Locale.GERMAN);
        else langBox.setValue(null);

        // --- RAM ---
        Spinner<Integer> ramSpinner = new Spinner<>(512, 65536, LauncherSettings.getRamMb(), 512);
        ramSpinner.setEditable(true);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(14));
        grid.addRow(0, new Label("Sprache:"), langBox);
        grid.addRow(1, new Label("RAM (MB):"), ramSpinner);
        dlg.getDialogPane().setContent(grid);

        dlg.initModality(Modality.APPLICATION_MODAL);
        if (root != null && root.getScene() != null) {
            Stage owner = (Stage) root.getScene().getWindow();
            dlg.initOwner(owner);
        }

        Button okBtn = (Button) dlg.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.setText("Speichern");
        Button cancelBtn = (Button) dlg.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelBtn.setText("Abbrechen");

        dlg.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;

            // Save
            Locale selected = langBox.getValue();
            LauncherSettings.setLocale(selected == null ? Locale.getDefault() : selected);
            LauncherSettings.setRamMb(ramSpinner.getValue());

            // Apply Locale for next loads
            Locale.setDefault(LauncherSettings.getLocale());

            // IMPORTANT: Current UI won't magically retranslate.
            // If you want live switching, you must reload the scene with a new ResourceBundle.
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setTitle("Hinweis");
            a.setHeaderText("Einstellungen gespeichert");
            a.setContentText("Sprache wird erst nach einem Neustart vollständig übernommen. RAM gilt sofort für den nächsten Start.");
            a.initOwner(root != null && root.getScene() != null ? root.getScene().getWindow() : null);
            a.showAndWait();
        });
    }

    // -------------------- Playtime UI (FIXED PATHS) --------------------

    /**
     * Liest Playtime aus denselben Dateien, die MinecraftLauncherService schreibt:
     * - Instanz: instanceRuntimeDir/playtime.properties
     * - Global:  sharedRoot/playtime_total.properties
     */
    private void refreshPlaytimeUi() {
        Platform.runLater(() -> {
            Path globalFile = profileStore.sharedRoot().resolve("playtime_total.properties");
            globalPlaytimeStore = new PlaytimeStore(globalFile);

            Profile p = (profileCombo != null) ? profileCombo.getValue() : null;

            if (p == null) {
                if (instancePlaytimeLabel != null) instancePlaytimeLabel.setText("-");
                if (globalPlaytimeLabel != null) globalPlaytimeLabel.setText("Gesamt: " + globalPlaytimeStore.getTotalPretty());
                return;
            }

            Path instFile = profileStore.instanceRuntimeDir(p.name()).resolve("playtime.properties");
            instancePlaytimeStore = new PlaytimeStore(instFile);

            if (instancePlaytimeLabel != null) instancePlaytimeLabel.setText(instancePlaytimeStore.getTotalPretty());
            if (globalPlaytimeLabel != null) globalPlaytimeLabel.setText("Gesamt: " + globalPlaytimeStore.getTotalPretty());
        });
    }


    // -------------------- Logging --------------------

    private void startLogFlusher() {
        if (logFlushTimeline != null) return;

        logFlushTimeline = new Timeline(new KeyFrame(Duration.millis(100), e -> flushLogQueue()));
        logFlushTimeline.setCycleCount(Animation.INDEFINITE);
        logFlushTimeline.play();
    }

    private void appendLog(String s) {
        if (s == null) return;
        logQueue.add(s);
    }

    private void clearLog() {
        logQueue.clear();
        if (logArea != null) logArea.clear();
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

        int len = logArea.getLength();
        if (len > LOG_MAX_CHARS) {
            logArea.deleteText(0, len - LOG_MAX_CHARS);
        }
    }

    // -------------------- Profile CRUD --------------------

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

        if (selected != null) profileCombo.getSelectionModel().select(selected);
        else {
            profileCombo.getSelectionModel().clearSelection();
            profileCombo.setValue(null);
        }

        refreshProfileDependentUi();
        refreshPlaytimeUi();
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

        Button testBtn = new Button("Testen");
        ProgressIndicator pi = new ProgressIndicator();
        pi.setVisible(false);
        pi.setMaxSize(18, 18);

        Label testStatus = new Label();
        testStatus.setMinHeight(18);
        HBox testRow = new HBox(10, testBtn, pi, testStatus);

        int r = 0;
        gp.addRow(r++, new Label("Name:"), name);
        gp.addRow(r++, new Label("Manifest URL:"), url);
        gp.addRow(r++, new Label(""), testRow);
        gp.addRow(r++, new Label("Server Host:"), host);
        gp.addRow(r++, new Label("Server Port:"), port);
        gp.addRow(r++, new Label("Join Mode:"), joinMode);

        ColumnConstraints c1 = new ColumnConstraints();
        c1.setMinWidth(110);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        gp.getColumnConstraints().setAll(c1, c2);

        dialog.getDialogPane().setContent(gp);

        Node saveNode = dialog.getDialogPane().lookupButton(saveBtn);
        saveNode.setDisable(true);

        Runnable invalidateTest = () -> {
            testStatus.setText("Bitte testen.");
            testStatus.setUserData(null);
        };

        Runnable updateSaveEnabled = () -> {
            boolean nameOk = !name.getText().trim().isEmpty();
            boolean urlOk = !url.getText().trim().isEmpty();
            boolean testOk = "OK".equals(testStatus.getUserData());

            boolean portOk;
            try {
                int v = Integer.parseInt(port.getText().trim());
                portOk = (v >= 1 && v <= 65535);
            } catch (Exception e) {
                portOk = false;
            }

            saveNode.setDisable(!(nameOk && urlOk && portOk && testOk));
        };

        invalidateTest.run();
        updateSaveEnabled.run();

        name.textProperty().addListener((obs, o, n) -> { invalidateTest.run(); updateSaveEnabled.run(); });
        url.textProperty().addListener((obs, o, n) -> { invalidateTest.run(); updateSaveEnabled.run(); });
        port.textProperty().addListener((obs, o, n) -> updateSaveEnabled.run());

        testBtn.setOnAction(e -> {
            String nm = name.getText().trim();
            String u = url.getText().trim();

            if (nm.isEmpty() || u.isEmpty()) {
                testStatus.setText("Name und URL ausfüllen.");
                testStatus.setUserData(null);
                updateSaveEnabled.run();
                return;
            }

            pi.setVisible(true);
            testBtn.setDisable(true);
            testStatus.setText("Teste…");
            testStatus.setUserData(null);
            updateSaveEnabled.run();

            Task<Void> t = new Task<>() {
                @Override protected Void call() throws Exception {
                    fetchManifest(u);
                    return null;
                }
            };

            t.setOnSucceeded(ev -> {
                pi.setVisible(false);
                testBtn.setDisable(false);
                testStatus.setText("OK ✅");
                testStatus.setUserData("OK");
                updateSaveEnabled.run();
            });

            t.setOnFailed(ev -> {
                pi.setVisible(false);
                testBtn.setDisable(false);
                Throwable ex = t.getException();
                testStatus.setText("Fehler: " + (ex == null ? "unknown" : ex.getMessage()));
                testStatus.setUserData(null);
                updateSaveEnabled.run();
            });

            Thread th = new Thread(t, "manifest-test-edit");
            th.setDaemon(true);
            th.start();
        });

        dialog.setResultConverter(bt -> {
            if (bt != saveBtn) return null;

            String newName = name.getText().trim();
            String newUrl = url.getText().trim();
            String newHost = host.getText().trim();

            int newPort;
            try {
                newPort = Integer.parseInt(port.getText().trim());
            } catch (Exception e) {
                return null;
            }

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

        var existing = profileStore.loadProfiles().stream().map(ProfileStore.Profile::name)
                .map(String::toLowerCase).toList();
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
        Dialog<NewProfileData> d = new Dialog<>();
        d.setTitle("Neue Instanz");
        d.setHeaderText(null);

        ButtonType createBtn = new ButtonType("Erstellen", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(createBtn, ButtonType.CANCEL);

        TextField nameField = new TextField();
        nameField.setPromptText("Instanzname");

        TextField urlField = new TextField();
        urlField.setPromptText("Manifest-URL (oder latest.json)");

        Label status = new Label();
        status.setMinHeight(18);

        ProgressIndicator pi = new ProgressIndicator();
        pi.setVisible(false);
        pi.setMaxSize(18, 18);

        Button testBtn = new Button("Testen");
        HBox testRow = new HBox(10, testBtn, pi, status);

        VBox root = new VBox(10,
                new Label("Name:"),
                nameField,
                new Label("Manifest URL:"),
                urlField,
                testRow
        );
        root.setPadding(new Insets(12));
        d.getDialogPane().setContent(root);

        Node createNode = d.getDialogPane().lookupButton(createBtn);
        createNode.setDisable(true);

        Runnable updateCreateEnabled = () -> {
            boolean ok = !nameField.getText().trim().isEmpty()
                    && !urlField.getText().trim().isEmpty()
                    && "OK".equals(status.getUserData());
            createNode.setDisable(!ok);
        };

        Runnable invalidateTest = () -> {
            status.setText("Bitte testen.");
            status.setUserData(null);
            updateCreateEnabled.run();
        };

        nameField.textProperty().addListener((o, a, b) -> invalidateTest.run());
        urlField.textProperty().addListener((o, a, b) -> invalidateTest.run());

        testBtn.setOnAction(ev -> {
            String name = nameField.getText().trim();
            String url = urlField.getText().trim();

            if (name.isEmpty() || url.isEmpty()) {
                status.setText("Name und URL ausfüllen.");
                status.setUserData(null);
                updateCreateEnabled.run();
                return;
            }

            pi.setVisible(true);
            testBtn.setDisable(true);
            status.setText("Teste…");
            status.setUserData(null);
            updateCreateEnabled.run();

            Task<Void> t = new Task<>() {
                @Override protected Void call() throws Exception {
                    var m = fetchManifest(url);
                    if (m.minecraftVersion() == null || m.minecraftVersion().isBlank())
                        throw new IllegalStateException("minecraftVersion fehlt im Manifest");
                    if (m.loader() == null || m.loader().type() == null || m.loader().type().isBlank())
                        throw new IllegalStateException("loader.type fehlt im Manifest");
                    return null;
                }
            };

            t.setOnSucceeded(e2 -> {
                pi.setVisible(false);
                testBtn.setDisable(false);
                status.setText("OK ✅");
                status.setUserData("OK");
                updateCreateEnabled.run();
            });

            t.setOnFailed(e2 -> {
                pi.setVisible(false);
                testBtn.setDisable(false);
                Throwable ex = t.getException();
                status.setText("Fehler: " + (ex == null ? "unknown" : ex.getMessage()));
                status.setUserData(null);
                updateCreateEnabled.run();
            });

            Thread th = new Thread(t, "manifest-test");
            th.setDaemon(true);
            th.start();
        });

        invalidateTest.run();

        d.setResultConverter(bt -> {
            if (bt != createBtn) return null;
            return new NewProfileData(nameField.getText().trim(), urlField.getText().trim());
        });

        var res = d.showAndWait().orElse(null);
        if (res == null) return;

        var p = new ProfileStore.Profile(
                res.name(),
                res.manifestUrl(),
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

    // -------------------- Update / Play --------------------

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
            statusLabel.setText("Bereit");
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
        if (!requireLoginOrPopup()) return;

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

        Profile finalP = p;

        setUiBusy(true);
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        appendLog("Play gestartet: Manifest laden, Loader installieren, dann starten...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateMessage("Manifest laden...");
                ManifestModels.Manifest manifest = fetchManifest(finalP.manifestUrl());

                // Changelog optional laden
                String changelog = "Kein Changelog definiert.";
                String clUrl = resolveUrl(finalP.manifestUrl(), manifest.changelogUrl());
                if (!clUrl.isBlank()) {
                    try {
                        changelog = loadTextFromUrl(clUrl);
                    } catch (Exception ex) {
                        changelog = "Changelog konnte nicht geladen werden:\n" + ex.getMessage() + "\nURL: " + clUrl;
                    }
                }

                final String finalChangelog = changelog;
                Platform.runLater(() -> {
                    if (changelogArea != null) changelogArea.setText(finalChangelog);
                });

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
                                manifest.minecraftVersion(),
                                loaderType,
                                loaderVer,
                                LauncherSettings.getRamMb()
                        ),
                        auth,
                        msg -> appendLog(msg)
                );

                Platform.runLater(() -> {
                    setStatus("Beendet", "pillOk");

                    // sofort
                    refreshPlaytimeUi();

                    // delayed (wichtig!)
                    Timeline t = new Timeline(new KeyFrame(Duration.millis(400), ev -> refreshPlaytimeUi()));
                    t.setCycleCount(1);
                    t.play();
                });

                updateMessage("MC beendet.");
                return null;
            }
        };

        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            statusLabel.textProperty().unbind();
            statusLabel.setText("Bereit");
            setUiBusy(false);
            progressBar.setProgress(1);
            refreshPlaytimeUi();
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
            refreshPlaytimeUi();
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

    // -------------------- UI Busy / Enable states --------------------

    private void setUiBusy(boolean busy) {
        this.uiBusy = busy;

        Node[] nodes = { loginButton, profileCombo, launcherUpdateButton };
        for (Node n : nodes) if (n != null) n.setDisable(busy);

        if (progressBar != null) {
            progressBar.setVisible(busy);
            if (!busy) progressBar.setProgress(0);
        }

        if (busy) {
            setStatus(
                    statusLabel.getText() == null || statusLabel.getText().isBlank() ? "Loading..." : statusLabel.getText(),
                    "pillBusy"
            );
        }

        refreshProfileDependentUi();
    }

    private void refreshProfileDependentUi() {
        boolean hasProfile = profileCombo != null && profileCombo.getValue() != null;
        boolean loggedIn = isLoggedIn();

        if (openFolderButton != null) openFolderButton.setDisable(uiBusy || !hasProfile);
        if (updateButton != null) updateButton.setDisable(uiBusy || !hasProfile);
        if (playButton != null) playButton.setDisable(uiBusy || !hasProfile || !loggedIn);
    }

    // -------------------- Login --------------------

    private void tryLoadSavedMicrosoftSession() {
        var s = msStore.loadOrNull();
        if (s == null) {
            setLoginStatus("Nicht eingeloggt");
            return;
        }

        long now = Instant.now().getEpochSecond();
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
            onLoginClicked();
        }
        return false;
    }

    @FXML
    private void onLoginClicked() {
        setLoginStatus("Starte Login...");

        Task<MicrosoftMinecraftAuth.MinecraftSession> task = new Task<>() {
            @Override
            protected MicrosoftMinecraftAuth.MinecraftSession call() throws Exception {
                MicrosoftMinecraftAuth auth = new MicrosoftMinecraftAuth();
                var dc = auth.startDeviceCode();

                Platform.runLater(() -> {
                    appendLog("[LOGIN] Öffne: " + dc.verificationUri());
                    appendLog("[LOGIN] Code:  " + dc.userCode());

                    copyToClipboard(dc.userCode());
                    setLoginStatus("Code kopiert: " + dc.userCode());

                    try {
                        Desktop.getDesktop().browse(java.net.URI.create(dc.verificationUri()));
                    } catch (Exception ignored) {}

                    showDeviceCodeDialog(dc.userCode(), dc.verificationUri());
                });

                return auth.loginWithDeviceCode(dc);
            }
        };

        task.setOnSucceeded(e -> {
            mcSession = task.getValue();
            refreshProfileDependentUi();

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

    private void updateAccountUi() {
        Platform.runLater(() -> {
            boolean loggedIn = isLoggedIn();

            String name = loggedIn ? mcSession.playerName() : "Nicht eingeloggt";
            if (accountNameLabel != null) accountNameLabel.setText(name);

            if (skinView != null) {
                String headUrl = loggedIn
                        ? "https://minotar.net/helm/" + mcSession.playerName() + "/64.png"
                        : "https://minotar.net/helm/Steve/64.png";
                skinView.setImage(new Image(headUrl, true));
            }

            if (loginButton != null) {
                loginButton.setText(loggedIn ? "Logout" : "Login (Microsoft)");
            }

            // WICHTIG: Buttons neu bewerten -> Start wird sofort klickbar
            refreshProfileDependentUi();
        });
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

        Button copyBtn = (Button) d.getDialogPane().lookupButton(copyBtnType);
        Button openBtn = (Button) d.getDialogPane().lookupButton(openBtnType);

        copyBtn.addEventFilter(ActionEvent.ACTION, e -> {
            copyToClipboard(code);
            appendLog("[LOGIN] Code kopiert: " + code);
            e.consume();
        });

        openBtn.addEventFilter(ActionEvent.ACTION, e -> {
            try {
                Desktop.getDesktop().browse(java.net.URI.create(verificationUrl));
            } catch (Exception ignored) {}
            e.consume();
        });

        loginDialog = d;
        d.setOnHidden(e -> {
            if (loginDialog == d) loginDialog = null;
        });
        d.show();
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

        Timeline t = new Timeline(new KeyFrame(Duration.seconds(2), e -> a.close()));
        t.setCycleCount(1);
        t.play();
    }

    // -------------------- Misc actions --------------------

    @FXML
    public void onOpenMainFolder() {
        try {
            Path mainDir = Path.of(System.getProperty("user.home"), "AppData", "Roaming", ".modlauncher");

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
    private void onCopyLog() {
        var cb = new ClipboardContent();
        cb.putString(logArea != null ? logArea.getText() : "");
        Clipboard.getSystemClipboard().setContent(cb);
    }

    @FXML
    private void onClearLog() {
        clearLog();
    }

    // -------------------- Status / version / errors --------------------

    private void setStatus(String text, String pillStyle) {
        Platform.runLater(() -> {
            if (statusLabel == null) return;
            statusLabel.textProperty().unbind();
            statusLabel.setText(text);
            statusLabel.getStyleClass().removeAll("pillOk", "pillBusy", "pillError");
            statusLabel.getStyleClass().add(pillStyle);
        });
    }

    private String detectVersion() {
        String v = getClass().getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? "dev" : v;
    }

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Fehler");
        a.setHeaderText("Aktion fehlgeschlagen");
        a.setContentText(msg);
        a.showAndWait();
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

    private void copyToClipboard(String text) {
        ClipboardContent c = new ClipboardContent();
        c.putString(text);
        Clipboard.getSystemClipboard().setContent(c);
    }

    // -------------------- Manifest / Changelog helpers --------------------

    private ManifestModels.Manifest fetchManifest(String url) throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                .build();

        String u = url.trim();

        // 1) latest.json -> manifestUrl
        if (u.endsWith("latest.json")) {
            var req1 = java.net.http.HttpRequest.newBuilder(java.net.URI.create(u)).GET().build();
            var resp1 = client.send(req1, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp1.statusCode() != 200) throw new RuntimeException("latest HTTP " + resp1.statusCode());

            LatestPointer latest = om.readValue(resp1.body(), LatestPointer.class);
            u = latest.manifestUrl();
        }

        // 2) project.json -> versions.json -> latest manifestUrl
        if (u.endsWith("project.json")) {
            // project.json laden (optional – du brauchst es hier nicht zwingend zum Update)
            var reqP = java.net.http.HttpRequest.newBuilder(java.net.URI.create(u)).GET().build();
            var respP = client.send(reqP, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (respP.statusCode() != 200) throw new RuntimeException("project HTTP " + respP.statusCode());

            // versions.json URL (neben project.json)
            String versionsUrl = java.net.URI.create(u).resolve("versions.json").toString();

            var reqV = java.net.http.HttpRequest.newBuilder(java.net.URI.create(versionsUrl)).GET().build();
            var respV = client.send(reqV, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (respV.statusCode() != 200) throw new RuntimeException("versions HTTP " + respV.statusCode());

            VersionsIndex vi = om.readValue(respV.body(), VersionsIndex.class);
            String manifestUrl = vi.latestManifestUrl();
            if (manifestUrl == null || manifestUrl.isBlank())
                throw new IllegalStateException("versions.json hat keine manifestUrl");

            u = manifestUrl;
        }

        // 3) versions.json direkt -> latest manifestUrl
        if (u.endsWith("versions.json")) {
            var reqV = java.net.http.HttpRequest.newBuilder(java.net.URI.create(u)).GET().build();
            var respV = client.send(reqV, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (respV.statusCode() != 200) throw new RuntimeException("versions HTTP " + respV.statusCode());

            VersionsIndex vi = om.readValue(respV.body(), VersionsIndex.class);
            String manifestUrl = vi.latestManifestUrl();
            if (manifestUrl == null || manifestUrl.isBlank())
                throw new IllegalStateException("versions.json hat keine manifestUrl");

            u = manifestUrl;
        }

        // 4) manifest laden
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(u)).GET().build();
        var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("Manifest HTTP " + resp.statusCode());

        return om.readValue(resp.body(), ManifestModels.Manifest.class);
    }



    private String loadTextFromUrl(String url) throws Exception {
        var client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                .build();

        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .GET()
                .build();

        var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Changelog HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private static String resolveUrl(String base, String maybeRelative) {
        if (maybeRelative == null) return "";
        String s = maybeRelative.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        return java.net.URI.create(base).resolve(s).toString();
    }

    // -------------------- Host Mode (AUTO MC + AUTO Loader Versions) --------------------

    @FXML
    public void onHostMode() {

        ChoiceDialog<String> mode = new ChoiceDialog<>("Neues Pack erstellen",
                "Neues Pack erstellen", "Bestehendes Pack bearbeiten");
        mode.setTitle("Host Mode");
        mode.setHeaderText(null);
        mode.setContentText("Was willst du machen?");

        String choice = mode.showAndWait().orElse(null);
        if (choice == null) return;

        boolean editMode = choice.equals("Bestehendes Pack bearbeiten");

        if (editMode) {
            openExistingHostProject();   // <<< NEU
            return;
        }


        Dialog<CreateHostProjectRequest> d = new Dialog<>();
        d.setTitle("Projekt hosten");
        d.setHeaderText(null);

        ButtonType createBtn = new ButtonType("Erstellen", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(createBtn, ButtonType.CANCEL);

        GridPane gp = new GridPane();
        gp.setHgap(10);
        gp.setVgap(10);
        gp.setPadding(new Insets(12));

        TextField projectId = new TextField("testpack");
        TextField name = new TextField("Test Pack");

        // MC Version: editable ComboBox + async Release-Versionen
        ComboBox<String> mcVersion = new ComboBox<>();
        mcVersion.setEditable(true);
        mcVersion.getEditor().setText("1.20.1");
        mcVersion.setPrefWidth(220);

        ComboBox<de.levingamer8.modlauncher.core.LoaderType> loader = new ComboBox<>();
        loader.getItems().setAll(de.levingamer8.modlauncher.core.LoaderType.values());
        loader.getSelectionModel().select(de.levingamer8.modlauncher.core.LoaderType.FABRIC);

        // Loader Version: editable ComboBox + auto passend
        ComboBox<String> loaderVersion = new ComboBox<>();
        loaderVersion.setEditable(true);
        loaderVersion.setPromptText("z.B. 0.15.11 / 47.3.0");
        loaderVersion.setPrefWidth(220);

        TextField baseUrl = new TextField("https://mc.local/testpack/");
        TextField initialVersion = new TextField("1.0.0");

        TextField outFolder = new TextField(profileStore.baseDir().resolve("host-projects").toString());
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

        // MC Release-Versionen laden (nicht blocken)
        Task<List<String>> mcLoad = new Task<>() {
            @Override protected List<String> call() {
                return resolveMinecraftReleaseVersions();
            }
        };
        mcLoad.setOnSucceeded(ev -> {
            List<String> list = mcLoad.getValue();
            if (list != null && !list.isEmpty()) {
                String keep = mcVersion.getEditor().getText();
                mcVersion.getItems().setAll(list);
                if (keep != null && !keep.isBlank()) mcVersion.getEditor().setText(keep);
            }
        });
        mcLoad.setOnFailed(ev -> {
            if (mcVersion.getItems().isEmpty()) {
                mcVersion.getItems().setAll(List.of("1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.1"));
            }
        });
        Thread th = new Thread(mcLoad, "mc-versions");
        th.setDaemon(true);
        th.start();

        Runnable refreshLoaderVersions = () -> {
            de.levingamer8.modlauncher.core.LoaderType lt = loader.getValue();
            boolean vanilla = (lt == de.levingamer8.modlauncher.core.LoaderType.VANILLA);

            loaderVersion.setDisable(vanilla);
            loaderVersion.setOpacity(vanilla ? 0.55 : 1.0);

            if (vanilla) {
                loaderVersion.getItems().clear();
                loaderVersion.getEditor().setText("");
                return;
            }

            String mc = mcVersion.getEditor().getText() == null ? "" : mcVersion.getEditor().getText().trim();
            if (mc.isEmpty()) return;

            List<String> versions = resolveKnownLoaderVersions(mc, lt);
            loaderVersion.getItems().setAll(versions);

            String current = loaderVersion.getEditor().getText() == null ? "" : loaderVersion.getEditor().getText().trim();
            if (current.isEmpty() || (!versions.isEmpty() && versions.stream().noneMatch(v -> v.equalsIgnoreCase(current)))) {
                if (!versions.isEmpty()) loaderVersion.getEditor().setText(versions.get(0));
            }
        };

        loader.valueProperty().addListener((obs, o, n) -> refreshLoaderVersions.run());
        mcVersion.getEditor().textProperty().addListener((obs, o, n) -> refreshLoaderVersions.run());

        // initial
        refreshLoaderVersions.run();

        Node createNode = d.getDialogPane().lookupButton(createBtn);
        createNode.disableProperty().bind(
                projectId.textProperty().isEmpty()
                        .or(name.textProperty().isEmpty())
                        .or(mcVersion.getEditor().textProperty().isEmpty())
                        .or(baseUrl.textProperty().isEmpty())
                        .or(outFolder.textProperty().isEmpty())
                        .or(initialVersion.textProperty().isEmpty())
        );

        d.setResultConverter(bt -> {
            if (bt != createBtn) return null;
            return new CreateHostProjectRequest(
                    projectId.getText().trim().toLowerCase(Locale.ROOT),
                    name.getText().trim(),
                    mcVersion.getEditor().getText().trim(),
                    loader.getValue(),
                    loaderVersion.getEditor().getText().trim(),
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
            appendLog("[HOST] project: " + paths.projectJson());
            appendLog("[HOST] manifest: " + paths.manifestJson());

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(paths.projectRoot().toFile());
            }
        } catch (Exception ex) {
            showError("Host-Projekt konnte nicht erstellt werden:\n" + ex.getMessage());
        }
    }

    private void openExistingHostProject() {
        // project.json auswählen
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("project.json auswählen");
        fc.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("project.json", "project.json")
        );

        java.io.File f = fc.showOpenDialog(profileCombo.getScene().getWindow());
        if (f == null) return;

        try {
            var om = new com.fasterxml.jackson.databind.ObjectMapper();

            // 1) project.json lesen
            HostProjectConfig cfg = om.readValue(f, HostProjectConfig.class);

            java.nio.file.Path projectJson = f.toPath();
            java.nio.file.Path root = projectJson.getParent();

            // 2) versions.json lesen (liegt neben project.json)
            java.nio.file.Path versionsJson = root.resolve("versions.json");
            if (!java.nio.file.Files.exists(versionsJson)) {
                showError("versions.json fehlt neben project.json:\n" + versionsJson);
                return;
            }

            VersionsIndex idx = om.readValue(versionsJson.toFile(), VersionsIndex.class);

            String latest = idx.latestVersion();
            String latestManifestUrl = idx.latestManifestUrl();

            if (latest == null || latest.isBlank() || latestManifestUrl == null || latestManifestUrl.isBlank()) {
                showError("versions.json ist kaputt: keine latest Version / manifestUrl gefunden.");
                return;
            }

            // Optional: Eintrag zur Log-Ausgabe finden
            VersionsIndex.VersionEntry ve = null;
            if (idx.versions() != null) {
                for (var e : idx.versions()) {
                    if (e != null && latest.equalsIgnoreCase(e.version())) {
                        ve = e;
                        break;
                    }
                }
            }

            // 3) modsDir ableiten: <root>/versions/<version>/files/mods
            java.nio.file.Path modsDir = root.resolve("versions")
                    .resolve(latest)
                    .resolve("files")
                    .resolve("mods");

            java.nio.file.Files.createDirectories(modsDir);

            // 4) Modrinth-Dialog öffnen
            LoaderType lt = LoaderType.fromString(cfg.loader().type()); // cfg.loader() muss {type,version} liefern
            openModrinthSearchAndAdd(cfg.mcVersion(), lt, modsDir);

            appendLog("[HOST] Projekt geladen: " + cfg.projectId());
            appendLog("[HOST] Latest: " + latest + " -> " + (ve != null ? ve.manifestUrl() : latestManifestUrl));

            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(root.toFile());
            }
        } catch (Exception ex) {
            showError("Konnte Projekt nicht laden:\n" + ex.getMessage());
        }
    }



    public void openModrinthSearchAndAdd(String mcVersion, LoaderType loaderType, Path modsDir) {
        if (loaderType == LoaderType.VANILLA) {
            showError("Modrinth Search: Vanilla hat keine Mods im Sinne von Forge/Fabric.");
            return;
        }

        String modrinthLoader = LoaderType.toString(loaderType);
        ModrinthClient api = new ModrinthClient();

        final int pageSize = 50;
        final java.util.concurrent.atomic.AtomicInteger offset = new java.util.concurrent.atomic.AtomicInteger(0);

        Dialog<Void> dialog = new Dialog<>();
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Modrinth: Mods hinzufügen");
        dialog.setHeaderText(null);

        ButtonType closeBtn = new ButtonType("Schließen", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeBtn);

        TextField query = new TextField();
        query.setPromptText("Mod suchen (z.B. sodium, jei, iris...)");

        Label ctx = new Label("MC: " + mcVersion + " | Loader: " + loaderType);

        Button searchBtn = new Button("Suchen");
        searchBtn.setDefaultButton(true);

        Button prevBtn = new Button("<");
        Button nextBtn = new Button(">");
        prevBtn.setDisable(true);
        nextBtn.setDisable(true);

        ProgressIndicator progress = new ProgressIndicator();
        progress.setVisible(false);
        progress.setMaxSize(18, 18);

        Label status = new Label();
        status.setMinHeight(18);

        Label pageInfo = new Label();
        pageInfo.setMinHeight(18);

        ListView<SearchHit> list = new ListView<>();
        list.setCellFactory(lv -> new ListCell<>() {

            private final ImageView icon = new ImageView();
            private final Label title = new Label();
            private final Label meta = new Label();
            private final Label desc = new Label();

            private final VBox textBox = new VBox(2, title, meta, desc);
            private final HBox row = new HBox(10, icon, textBox);
            private final VBox root = new VBox(8, row);

            private String expectedIconUrl;

            {
                icon.setFitWidth(28);
                icon.setFitHeight(28);
                icon.setPreserveRatio(true);
                icon.setSmooth(true);

                title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
                meta.setStyle("-fx-opacity: 0.75; -fx-font-size: 11px;");
                desc.setStyle("-fx-opacity: 0.9; -fx-font-size: 12px;");
                desc.setWrapText(true);

                HBox.setHgrow(textBox, Priority.ALWAYS);
                textBox.prefWidthProperty().bind(lv.widthProperty().subtract(90));

                root.setPadding(new Insets(8, 10, 8, 10));
                root.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 10;");

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

                setGraphic(root);
            }
        });

        Button addBtn = new Button("Add to Pack");
        Button genBtn = new Button("Generate Manifest");
        genBtn.setDisable(false);
        addBtn.setDisable(true);

        list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> addBtn.setDisable(n == null));
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) addBtn.fire();
        });

        HBox top = new HBox(10, query, searchBtn, progress, prevBtn, nextBtn);
        HBox.setHgrow(query, Priority.ALWAYS);

        HBox bottom = new HBox(10, status, new Region(), pageInfo);
        HBox.setHgrow(bottom.getChildren().get(1), Priority.ALWAYS);

        VBox root = new VBox(10, ctx, top, list, new HBox(10, addBtn, genBtn), bottom);
        root.setPadding(new Insets(12));
        VBox.setVgrow(list, Priority.ALWAYS);

        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().setPrefSize(900, 650);
        dialog.setResizable(true);

        java.util.function.IntConsumer doSearch = (off) -> {
            String q = query.getText() == null ? "" : query.getText().trim();
            if (q.isEmpty()) {
                status.setText("Bitte Suchbegriff eingeben.");
                return;
            }

            progress.setVisible(true);
            searchBtn.setDisable(true);
            addBtn.setDisable(true);
            prevBtn.setDisable(true);
            nextBtn.setDisable(true);
            status.setText("Suche…");
            pageInfo.setText("");

            Task<de.levingamer8.modlauncher.host.modrinth.SearchResponse> t = new Task<>() {
                @Override
                protected de.levingamer8.modlauncher.host.modrinth.SearchResponse call() throws Exception {
                    return api.searchModsPage(q, modrinthLoader, mcVersion, pageSize, off);
                }
            };

            t.setOnSucceeded(ev -> {
                var resp = t.getValue();
                var hits = resp.hits();

                list.getItems().setAll(hits);
                if (!hits.isEmpty()) list.scrollTo(0);

                progress.setVisible(false);
                searchBtn.setDisable(false);

                int total = resp.total_hits();
                int curOff = resp.offset();
                int lim = resp.limit();

                int page = (lim <= 0) ? 1 : (curOff / lim) + 1;
                int pages = (lim <= 0) ? 1 : (int) Math.ceil(total / (double) lim);

                status.setText(hits.isEmpty() ? "Keine Treffer." : ("Treffer: " + hits.size()));
                pageInfo.setText("Seite " + page + "/" + pages + " • " + total + " gesamt");

                prevBtn.setDisable(curOff <= 0);
                nextBtn.setDisable(curOff + lim >= total);

                offset.set(curOff);
            });

            t.setOnFailed(ev -> {
                Throwable ex = t.getException();
                progress.setVisible(false);
                searchBtn.setDisable(false);
                status.setText("Fehler bei Suche.");
                showError("Modrinth Suche fehlgeschlagen:\n" + (ex == null ? "unknown" : ex.getMessage()));
            });

            Thread th2 = new Thread(t, "modrinth-search");
            th2.setDaemon(true);
            th2.start();
        };

        searchBtn.setOnAction(e -> doSearch.accept(0));
        query.setOnAction(e -> doSearch.accept(0));

        prevBtn.setOnAction(e -> doSearch.accept(Math.max(0, offset.get() - pageSize)));
        nextBtn.setOnAction(e -> doSearch.accept(offset.get() + pageSize));

        Path filesDir = modsDir.getParent();
        Path manifestPath = filesDir.getParent().resolve("manifest.json");

        genBtn.setOnAction(e -> {
            progress.setVisible(true);
            searchBtn.setDisable(true);
            addBtn.setDisable(true);
            genBtn.setDisable(true);
            status.setText("Generiere Manifest...");

            Task<Void> gen = new Task<>() {
                @Override protected Void call() throws Exception {
                    new HostManifestGenerator().generate(manifestPath, filesDir);
                    return null;
                }
            };

            gen.setOnSucceeded(ev -> {
                progress.setVisible(false);
                searchBtn.setDisable(false);
                addBtn.setDisable(false);
                genBtn.setDisable(false);
                status.setText("Manifest aktualisiert.");
                appendLog("[HOST] Manifest aktualisiert: " + manifestPath);
            });

            gen.setOnFailed(ev -> {
                progress.setVisible(false);
                searchBtn.setDisable(false);
                addBtn.setDisable(false);
                genBtn.setDisable(false);
                status.setText("Manifest-Fehler");
                Throwable ex = gen.getException();
                showError("Manifest Generierung fehlgeschlagen:\n" + (ex == null ? "unknown" : ex.getMessage()));
            });

            Thread th2 = new Thread(gen, "host-manifest-gen");
            th2.setDaemon(true);
            th2.start();
        });

        addBtn.setOnAction(e -> {
            SearchHit sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;

            progress.setVisible(true);
            searchBtn.setDisable(true);
            addBtn.setDisable(true);
            status.setText("Downloade & füge hinzu: " + sel.title());

            Task<Path> t = new Task<>() {
                @Override
                protected Path call() throws Exception {
                    Version v = api.getBestVersion(sel.project_id(), modrinthLoader, mcVersion);
                    return api.downloadPrimaryJar(v, modsDir);
                }
            };

            t.setOnSucceeded(ev -> {
                Path jar = t.getValue();
                appendLog("[HOST] Mod hinzugefügt: " + jar.getFileName());

                Task<Void> gen = new Task<>() {
                    @Override protected Void call() throws Exception {
                        new HostManifestGenerator().generate(manifestPath, filesDir);
                        return null;
                    }
                };

                gen.setOnSucceeded(ev2 -> {
                    progress.setVisible(false);
                    searchBtn.setDisable(false);
                    status.setText("Hinzugefügt + Manifest updated: " + jar.getFileName());
                    appendLog("[HOST] Manifest aktualisiert: " + manifestPath);
                });

                gen.setOnFailed(ev2 -> {
                    progress.setVisible(false);
                    searchBtn.setDisable(false);
                    status.setText("Mod hinzugefügt, aber Manifest-Fehler");
                    Throwable ex = gen.getException();
                    showError("Mod hinzugefügt (" + jar.getFileName() + "), aber Manifest-Update fehlgeschlagen:\n"
                            + (ex == null ? "unknown" : ex.getMessage()));
                });

                Thread th3 = new Thread(gen, "host-manifest-gen");
                th3.setDaemon(true);
                th3.start();
            });

            t.setOnFailed(ev -> {
                Throwable ex = t.getException();
                progress.setVisible(false);
                searchBtn.setDisable(false);
                status.setText("Fehler beim Download.");
                showError("Mod hinzufügen fehlgeschlagen:\n" + (ex == null ? "unknown" : ex.getMessage()));
            });

            Thread th2 = new Thread(t, "modrinth-add");
            th2.setDaemon(true);
            th2.start();
        });

        dialog.showAndWait();
    }

    private static String formatDownloads(long n) {
        if (n < 1_000) return Long.toString(n);
        double val;
        String suffix;
        if (n < 1_000_000) { val = n / 1_000.0; suffix = "K"; }
        else if (n < 1_000_000_000) { val = n / 1_000_000.0; suffix = "M"; }
        else { val = n / 1_000_000_000.0; suffix = "B"; }

        String s = (val >= 10) ? String.format(Locale.US, "%.0f", val)
                : String.format(Locale.US, "%.1f", val);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + suffix;
    }

    private void loadIconAsync(String url, ImageView target, java.util.function.Supplier<String> currentUrl) {
        if (url == null || url.isBlank()) {
            target.setImage(null);
            return;
        }

        Image cached = iconCache.get(url);
        if (cached != null) {
            target.setImage(cached);
            return;
        }

        target.setImage(null);

        Task<Image> t = new Task<>() {
            @Override protected Image call() throws Exception {
                var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .header("User-Agent", "ModLauncher/1.0 (host-mode)")
                        .GET().build();
                var res = iconHttp.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) return null;
                return new Image(new java.io.ByteArrayInputStream(res.body()));
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

        Thread th2 = new Thread(t, "modrinth-icon");
        th2.setDaemon(true);
        th2.start();
    }



    private void restartServerPolling() {
        // Task stoppen
        if (serverPollTask != null) {
            serverPollTask.cancel(true);
            serverPollTask = null;
        }

        Profile p = (profileCombo != null) ? profileCombo.getValue() : null;
        if (p == null) {
            setServerUiUnknown("Kein Profil ausgewählt.");
            return;
        }

        String host = (p.serverHost() == null) ? "" : p.serverHost().trim();
        int port = p.serverPort();

        if (host.isEmpty()) {
            setServerUiUnknown("Kein Server-Host gesetzt (Profil bearbeiten).");
            return;
        }
        if (port <= 0 || port > 65535) port = 25565;

        int finalPort1 = port;
        serverPollExec.execute(() -> pollServerOnce(host, finalPort1));

        //TODO: whats is performance? ping every 30 seconds
        final String finalHost = host;
        final int finalPort = port;
        serverPollTask = serverPollExec.scheduleAtFixedRate(
                () -> pollServerOnce(finalHost, finalPort),
                30, 30, TimeUnit.SECONDS
        );
    }

    private void pollServerOnce(String host, int port) {
        // Niemals Netzwerk im JavaFX Thread machen
        if (Platform.isFxApplicationThread()) {
            serverPollExec.execute(() -> pollServerOnce(host, port));
            return;
        }
        // Timeout klein halten, sonst fühlt sich UI "laggy" an
        MinecraftServerPing.Result r = MinecraftServerPing.ping(host, port, 1500);

        Platform.runLater(() -> {
            if (r.online()) {
                // Status
                if (serverStatusLabel != null) {
                    serverStatusLabel.setText("Online");
                    serverStatusLabel.getStyleClass().removeAll("pillOk", "pillBusy", "pillError");
                    serverStatusLabel.getStyleClass().add("pillOk");
                }

                if (serverPlayersLabel != null) {
                    serverPlayersLabel.setText(r.playersOnline() + "/" + r.playersMax());
                }
                if (serverPingLabel != null) {
                    serverPingLabel.setText(r.pingMs() + " ms");
                }

                if (serverDetailsLabel != null) {
                    String v = (r.versionName() == null || r.versionName().isBlank()) ? "" : (" • " + r.versionName());
                    serverDetailsLabel.setText(host + ":" + port + v);
                }
            } else {
                // Offline
                if (serverStatusLabel != null) {
                    serverStatusLabel.setText("Offline");
                    serverStatusLabel.getStyleClass().removeAll("pillOk", "pillBusy", "pillError");
                    serverStatusLabel.getStyleClass().add("pillError");
                }

                if (serverPlayersLabel != null) serverPlayersLabel.setText("-");
                if (serverPingLabel != null) serverPingLabel.setText("-");

                if (serverDetailsLabel != null) {
                    serverDetailsLabel.setText(host + ":" + port + " (keine Antwort)");
                }
            }
        });
    }

    private void setServerUiUnknown(String msg) {
        Platform.runLater(() -> {
            if (serverStatusLabel != null) {
                serverStatusLabel.setText("Unbekannt");
                serverStatusLabel.getStyleClass().removeAll("pillOk", "pillBusy", "pillError");
                serverStatusLabel.getStyleClass().add("pillError");
            }
            if (serverPlayersLabel != null) serverPlayersLabel.setText("-");
            if (serverPingLabel != null) serverPingLabel.setText("-");
            if (serverDetailsLabel != null) serverDetailsLabel.setText(msg);
        });
    }

    // -------------------- Host Mode helper: MC + Loader versions (NO extra libs needed) --------------------

    private static List<String> resolveMinecraftReleaseVersions() {
        // Mojang/Piston Meta version manifest (release only)
        String url = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
        try {
            String json = httpGet(url);

            java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"type\\\"\\s*:\\s*\\\"release\\\""
            );
            java.util.regex.Matcher m = p.matcher(json);
            while (m.find()) {
                out.add(m.group(1));
                if (out.size() >= 120) break;
            }
            return new java.util.ArrayList<>(out);
        } catch (Exception e) {
            return List.of("1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.1");
        }
    }

    private static List<String> resolveKnownLoaderVersions(String mcVersion, de.levingamer8.modlauncher.core.LoaderType lt) {
        try {
            return switch (lt) {
                case FABRIC -> resolveFabricLoaderVersions(mcVersion);
                case QUILT -> resolveQuiltLoaderVersions(mcVersion);
                case FORGE -> resolveForgePromotedVersions(mcVersion);
                case NEOFORGE -> List.of(); // erstmal leer, sonst trägt man schnell Müll ein
                case VANILLA -> List.of();
            };
        } catch (Exception ignored) {
        }

        // offline fallback
        return switch (lt) {
            case FABRIC -> List.of("0.15.11", "0.15.10", "0.15.9");
            case FORGE -> mcVersion.equals("1.20.1")
                    ? List.of("47.3.0", "47.2.0", "47.1.0")
                    : List.of();
            case QUILT -> List.of("0.26.0");
            default -> List.of();
        };
    }

    private static List<String> resolveFabricLoaderVersions(String mcVersion) throws Exception {
        String url = "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion;
        String json = httpGet(url);

        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\\"loader\\\"\\s*:\\s*\\{[^}]*?\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                java.util.regex.Pattern.DOTALL
        );
        java.util.regex.Matcher m = p.matcher(json);
        while (m.find()) {
            out.add(m.group(1));
            if (out.size() >= 30) break;
        }
        return new java.util.ArrayList<>(out);
    }

    private static List<String> resolveQuiltLoaderVersions(String mcVersion) throws Exception {
        String url = "https://meta.quiltmc.org/v3/versions/loader/" + mcVersion;
        String json = httpGet(url);

        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\\"loader_version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
        );
        java.util.regex.Matcher m = p.matcher(json);
        while (m.find()) {
            out.add(m.group(1));
            if (out.size() >= 30) break;
        }
        return new java.util.ArrayList<>(out);
    }

    private static List<String> resolveForgePromotedVersions(String mcVersion) throws Exception {
        String url = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
        String json = httpGet(url);

        String recommended = extractJsonValueForKey(json, mcVersion + "-recommended");
        String latest = extractJsonValueForKey(json, mcVersion + "-latest");

        if (recommended != null && latest != null && !recommended.equals(latest)) return List.of(recommended, latest);
        if (recommended != null) return List.of(recommended);
        if (latest != null) return List.of(latest);
        return List.of();
    }

    private static String extractJsonValueForKey(String json, String key) {
        String needle = "\"" + key + "\":";
        int i = json.indexOf(needle);
        if (i < 0) return null;

        int start = json.indexOf('"', i + needle.length());
        if (start < 0) return null;

        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;

        return json.substring(start + 1, end).trim();
    }

    private static String httpGet(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(4))
                .build();

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(8))
                .header("User-Agent", "ModLauncher/1.0")
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }


    private static int compareVersions(String a, String b) {
        int[] pa = parseVersion(a);
        int[] pb = parseVersion(b);
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int va = i < pa.length ? pa[i] : 0;
            int vb = i < pb.length ? pb[i] : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return a.compareToIgnoreCase(b);
    }

    private static int[] parseVersion(String s) {
        if (s == null) return new int[]{0};
        String[] parts = s.trim().split("[^0-9]+"); // alles was kein digit ist trennt
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.isBlank()) continue;
            try { out.add(Integer.parseInt(p)); } catch (Exception ignored) {}
        }
        if (out.isEmpty()) return new int[]{0};
        int[] arr = new int[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }



}
