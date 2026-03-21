package de.levingamer8.modlauncher.ui;

import de.levingamer8.modlauncher.auth.MicrosoftMinecraftAuth;
import de.levingamer8.modlauncher.auth.MicrosoftSessionStore;
import de.levingamer8.modlauncher.core.*;
import de.levingamer8.modlauncher.core.ProfileStore.Profile;
import de.levingamer8.modlauncher.host.*;
import de.levingamer8.modlauncher.mc.MinecraftLauncherService;
import de.levingamer8.modlauncher.mc.PlaytimeStore;
import de.levingamer8.modlauncher.mc.ProcessWatcher;
import de.levingamer8.modlauncher.ui.dialogs.I18n;
import de.levingamer8.modlauncher.ui.dialogs.LauncherSettings;
import de.levingamer8.modlauncher.update.UpdateController;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.awt.Desktop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
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
    @FXML private MenuButton menuButton;
    @FXML private Label versionLabel;
    @FXML private TextArea changelogArea;
    @FXML private Label serverStatusLabel;
    @FXML private Label serverDetailsLabel;
    @FXML private TextArea packInfoArea;
    @FXML private SplitPane mainSplit;
    @FXML private TitledPane logPane;
    @FXML private ImageView skinView;
    @FXML private Label accountNameLabel;
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
            refreshProfileDependentUi();
            refreshPlaytimeUi();
            restartServerPolling();
            loadModpackChangelog();
        });

        // Server-Tab und Modpack-Changelog sofort beim Start laden
        Platform.runLater(() -> {
            restartServerPolling();
            loadModpackChangelog();
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
        if (packInfoArea != null) packInfoArea.setText("Manifest laden -> dann hier Infos anzeigen.");

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
        dlg.getDialogPane().setMinWidth(400);

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

    private static String stackTraceToString(Throwable t) {
        var sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
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

        // Server-Daten aus project.json vorausfüllen wenn Profil leer
        String prefillHost = (p.serverHost() == null) ? "" : p.serverHost();
        int prefillPort = p.serverPort();
        if (prefillHost.isEmpty() && p.manifestUrl() != null && !p.manifestUrl().isBlank()) {
            try {
                ProjectJson project = fetchProject(p.manifestUrl());
                prefillHost = safe(project.serverIP());
                prefillPort = safeProjectPort(project);
            } catch (Exception ignored) {}
        }

        TextField host = new TextField(prefillHost);
        TextField port = new TextField(String.valueOf(prefillPort));

        ComboBox<ProfileStore.JoinMode> joinMode = new ComboBox<>();
        joinMode.getItems().setAll(ProfileStore.JoinMode.values());
        joinMode.getSelectionModel().select(p.joinMode() == null ? ProfileStore.JoinMode.SERVERS_DAT : p.joinMode());

        int currentRam = p.ramMb() > 0 ? p.ramMb() : LauncherSettings.getRamMb();
        Spinner<Integer> ramSpinner = new Spinner<>(512, 65536, currentRam, 512);
        ramSpinner.setEditable(true);
        Label ramHint = new Label("(0 = Default: " + LauncherSettings.getRamMb() + " MB)");
        ramHint.getStyleClass().add("mutedSmall");
        HBox ramRow = new HBox(8, ramSpinner, ramHint);
        ramRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Button testBtn = new Button("Testen");
        ProgressIndicator pi = new ProgressIndicator();
        pi.setVisible(false);
        pi.setMaxSize(18, 18);

        Label testStatus = new Label();
        testStatus.setMinHeight(18);
        testStatus.setWrapText(true);
        testStatus.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(testStatus, Priority.ALWAYS);
        HBox testRow = new HBox(10, testBtn, pi, testStatus);

        int r = 0;
        gp.addRow(r++, new Label("Name:"), name);
        gp.addRow(r++, new Label("Manifest URL:"), url);
        gp.addRow(r++, new Label(""), testRow);
        gp.addRow(r++, new Label("Server Host:"), host);
        gp.addRow(r++, new Label("Server Port:"), port);
        gp.addRow(r++, new Label("Join Mode:"), joinMode);
        gp.addRow(r++, new Label("RAM (MB):"), ramRow);

        ColumnConstraints c1 = new ColumnConstraints();
        c1.setMinWidth(110);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        gp.getColumnConstraints().setAll(c1, c2);

        dialog.getDialogPane().setContent(gp);
        dialog.getDialogPane().setMinWidth(520);

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
                String msg = ex == null ? "unknown" : (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                testStatus.setText("Fehler: " + msg);
                testStatus.setTooltip(new Tooltip(msg));
                testStatus.setUserData(null);
                updateSaveEnabled.run();
                appendLog("FEHLER (Test): " + msg);
                if (ex != null) appendLog(stackTraceToString(ex));
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
                    joinMode.getValue(),
                    ramSpinner.getValue()
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
                p.joinMode(),
                p.ramMb()
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
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);

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
        d.getDialogPane().setMinWidth(520);

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
                String msg = ex == null ? "unknown" : (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                status.setText("Fehler: " + msg);
                status.setTooltip(new Tooltip(msg));
                status.setUserData(null);
                updateCreateEnabled.run();
                appendLog("FEHLER (Test): " + msg);
                if (ex != null) appendLog(stackTraceToString(ex));
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

        // Server-Daten aus project.json laden
        String serverHost = "";
        int serverPort = 25565;
        try {
            ProjectJson project = fetchProject(res.manifestUrl());
            serverHost = safe(project.serverIP());
            serverPort = safeProjectPort(project);
        } catch (Exception ignored) {
            // project.json optional — Profil trotzdem erstellen
        }

        var p = new ProfileStore.Profile(
                res.name(),
                res.manifestUrl(),
                serverHost,
                serverPort,
                ProfileStore.JoinMode.SERVERS_DAT,
                0  // 0 = use global default
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

            if (new ProcessWatcher().isRunning()) {
                statusLabel.setText(I18n.getBundle().getString("game.running"));
            } else {
                statusLabel.setText(I18n.getBundle().getString("game.closed"));
            }

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
            if (ex != null) appendLog(stackTraceToString(ex));
            showError(details);
            updateAccountUi();
            setUiBusy(false);
            progressBar.setProgress(0);
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

                updateMessage("project.json laden...");
                ProjectJson project = fetchProject(finalP.manifestUrl());

                // Pack-Changelog laden
                String packInfo = "Kein Changelog definiert.";
                String clUrl = resolveUrl(finalP.manifestUrl(), manifest.changelogUrl());
                if (!clUrl.isBlank()) {
                    try {
                        packInfo = loadTextFromUrl(clUrl);
                    } catch (Exception ex) {
                        packInfo = "Changelog konnte nicht geladen werden:\n" + ex.getMessage() + "\nURL: " + clUrl;
                    }
                }

                final String packInfoFinal = packInfo;
                Platform.runLater(() -> {
                    if (packInfoArea != null) packInfoArea.setText(packInfoFinal);
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

                int serverPort = safeProjectPort(project);
                String serverHost = safe(project.serverIP());
                String serverName = safe(project.projectName());
                boolean onlySelected = safeProjectOnlySelected(project);
                boolean directJoin = finalP.joinMode() == ProfileStore.JoinMode.DIRECT;

                appendLog("[DEBUG] project.json -> serverIP=" + serverHost
                        + " serverPort=" + serverPort
                        + " onlySelected=" + onlySelected
                        + " serverName=" + serverName
                        + " directJoin=" + directJoin);

                updateMessage("Install/Resolve/Launch...");
                launcher.launch(
                        sharedRoot,
                        gameDir,
                        runtimeDir,
                        new MinecraftLauncherService.LaunchSpec(
                                manifest.minecraftVersion(),
                                loaderType,
                                loaderVer,
                                finalP.effectiveRamMb(),
                                serverHost,
                                serverPort,
                                serverName,
                                true,
                                onlySelected,
                                directJoin
                        ),
                        auth,
                        msg -> appendLog(msg)
                );



                Platform.runLater(() -> {
                    setStatus(I18n.getBundle().getString("game.closed"), "pillOk");

                    refreshPlaytimeUi();

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
            if (ex != null) appendLog(stackTraceToString(ex));
            setUiBusy(false);
            progressBar.setProgress(0);
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

    private static String toProjectUrlFromAny(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";

        // already project.json
        if (u.endsWith("project.json")) return u;

        // latest.json or versions.json next to project.json
        if (u.endsWith("latest.json") || u.endsWith("versions.json")) {
            return de.levingamer8.modlauncher.core.ProtocolFetcher.resolve(u, "project.json");
        }

        // manifest inside versions folder: .../<pack>/versions/<ver>/manifest.json
        int idx = u.indexOf("/versions/");
        if (idx > 0) {
            return u.substring(0, idx) + "/project.json";
        }

        // fallback: same directory as given url
        int lastSlash = u.lastIndexOf('/');
        if (lastSlash > 0) return u.substring(0, lastSlash + 1) + "project.json";
        return u + "/project.json";
    }

    private static int parsePortSafe(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.isEmpty()) return 0;
        try {
            return Integer.parseInt(t);
        } catch (Exception e) {
            return 0;
        }
    }

    // -------------------- UI Busy / Enable states --------------------

    private void setUiBusy(boolean busy) {
        this.uiBusy = busy;

        Node[] nodes = { loginButton, profileCombo, menuButton };
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
            if (ex != null) appendLog(stackTraceToString(ex));
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

    private final de.levingamer8.modlauncher.core.ProtocolFetcher protocolFetcher = new de.levingamer8.modlauncher.core.ProtocolFetcher();

    private ManifestModels.Manifest fetchManifest(String url) throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();

        String u = url.trim();

        // 0) If URL is a base directory (no known file suffix), try project.json
        if (!u.endsWith(".json") && !u.endsWith(".yml") && !u.endsWith(".yaml")) {
            if (!u.endsWith("/")) u += "/";
            u += "project.json";
        }

        // 1) latest.json -> manifestUrl
        if (u.endsWith("latest.json")) {
            String body = protocolFetcher.getText(u);
            LatestPointer latest = om.readValue(body, LatestPointer.class);
            u = latest.manifestUrl();
        }

        // 2) project.json -> versions.json -> latest manifestUrl
        if (u.endsWith("project.json")) {
            protocolFetcher.getText(u); // validate it exists
            String versionsUrl = de.levingamer8.modlauncher.core.ProtocolFetcher.resolve(u, "versions.json");
            String versionsBody = protocolFetcher.getText(versionsUrl);
            VersionsIndex vi = om.readValue(versionsBody, VersionsIndex.class);
            String manifestUrl = vi.latestManifestUrl();
            if (manifestUrl == null || manifestUrl.isBlank())
                throw new IllegalStateException("versions.json hat keine manifestUrl");
            u = manifestUrl;
        }

        // 3) versions.json direkt -> latest manifestUrl
        if (u.endsWith("versions.json")) {
            String versionsBody = protocolFetcher.getText(u);
            VersionsIndex vi = om.readValue(versionsBody, VersionsIndex.class);
            String manifestUrl = vi.latestManifestUrl();
            if (manifestUrl == null || manifestUrl.isBlank())
                throw new IllegalStateException("versions.json hat keine manifestUrl");
            u = manifestUrl;
        }

        // 4) manifest laden
        String manifestBody = protocolFetcher.getText(u);
        return om.readValue(manifestBody, ManifestModels.Manifest.class);
    }



    private String loadTextFromUrl(String url) throws Exception {
        return protocolFetcher.getText(url);
    }

    private static String resolveUrl(String base, String maybeRelative) {
        return de.levingamer8.modlauncher.core.ProtocolFetcher.resolve(base, maybeRelative);
    }

    // -------------------- Modpack Changelog --------------------

    private void loadModpackChangelog() {
        Profile p = (profileCombo != null) ? profileCombo.getValue() : null;
        if (p == null || p.manifestUrl() == null || p.manifestUrl().isBlank()) {
            if (packInfoArea != null) packInfoArea.setText("Kein Profil oder Manifest-URL.");
            return;
        }

        if (packInfoArea != null) packInfoArea.setText("Changelog wird geladen...");

        serverPollExec.execute(() -> {
            try {
                ManifestModels.Manifest manifest = fetchManifest(p.manifestUrl());
                String clUrl = resolveUrl(p.manifestUrl(), manifest.changelogUrl());
                String packInfo;
                if (clUrl != null && !clUrl.isBlank()) {
                    try {
                        packInfo = loadTextFromUrl(clUrl);
                    } catch (Exception ex) {
                        packInfo = "Changelog konnte nicht geladen werden.";
                    }
                } else {
                    packInfo = "Kein Changelog definiert.";
                }
                final String text = packInfo;
                Platform.runLater(() -> {
                    if (packInfoArea != null) packInfoArea.setText(text);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (packInfoArea != null) packInfoArea.setText("Manifest konnte nicht geladen werden.");
                });
            }
        });
    }

    // -------------------- Host Mode --------------------

    @FXML
    public void onHostMode() {
        Window hostOwner = (root != null && root.getScene() != null) ? root.getScene().getWindow() : null;
        HostModePanel panel = new HostModePanel(hostOwner, profileStore, this::appendLog);
        panel.show();
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

        // Wenn Profil keinen Server hat, versuche project.json zu laden
        if (host.isEmpty() && p.manifestUrl() != null && !p.manifestUrl().isBlank()) {
            setServerUiUnknown("Server-Daten werden geladen...");
            serverPollExec.execute(() -> {
                try {
                    ProjectJson project = fetchProject(p.manifestUrl());
                    String projHost = safe(project.serverIP());
                    int projPort = safeProjectPort(project);
                    if (!projHost.isEmpty()) {
                        Platform.runLater(() -> startServerPolling(projHost, projPort));
                    } else {
                        Platform.runLater(() -> setServerUiUnknown("Kein Server in project.json konfiguriert."));
                    }
                } catch (Exception e) {
                    Platform.runLater(() -> setServerUiUnknown("project.json konnte nicht geladen werden."));
                }
            });
            return;
        }

        if (host.isEmpty()) {
            setServerUiUnknown("Kein Server-Host gesetzt (Profil bearbeiten).");
            return;
        }

        startServerPolling(host, port);
    }

    private void startServerPolling(String host, int port) {
        if (port <= 0 || port > 65535) port = 25565;

        int finalPort1 = port;
        serverPollExec.execute(() -> pollServerOnce(host, finalPort1));

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


    private ProjectJson fetchProject(String anyUrl) throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        String u = toProjectUrlFromAny(anyUrl);
        if (u.isBlank()) throw new IllegalArgumentException("URL ist leer");
        String body = protocolFetcher.getText(u);
        return om.readValue(body, ProjectJson.class);
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
        // Used for hardcoded API calls (Mojang, Fabric, etc.) - always HTTP
        var fetcher = new de.levingamer8.modlauncher.core.ProtocolFetcher();
        return fetcher.getText(url);
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


    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private static int safeProjectPort(ProjectJson p) {
        try {
            // falls es int ist
            return Math.max(0, Integer.parseInt(p.serverPort()));
        } catch (Throwable ignored) {
        }
        try {
            // falls String
            String v = (String) p.getClass().getMethod("serverPort").invoke(p);
            int port = Integer.parseInt(v.trim());
            return (port >= 1 && port <= 65535) ? port : 25565;
        } catch (Throwable ignored) {
        }
        return 25565;
    }

    private static boolean safeProjectOnlySelected(ProjectJson p) {
        try {
            return p.onlySelectedServer();
        } catch (Throwable ignored) {
        }
        try {
            Object v = p.getClass().getMethod("onlySelectedServer").invoke(p);
            if (v instanceof Boolean b) return b;
        } catch (Throwable ignored) {
        }
        return false;
    }


}
