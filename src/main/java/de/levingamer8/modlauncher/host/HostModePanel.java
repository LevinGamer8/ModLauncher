package de.levingamer8.modlauncher.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.levingamer8.modlauncher.core.LoaderType;
import de.levingamer8.modlauncher.core.ManifestModels;
import de.levingamer8.modlauncher.core.ProfileStore;
import de.levingamer8.modlauncher.host.modrinth.ModrinthClient;
import de.levingamer8.modlauncher.host.modrinth.SearchHit;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Eigenständiges Host-Mode-Management-Panel.
 * Zeigt gespeicherte Projekte, Details, Mods, Versioning.
 */
public class HostModePanel {

    private final Window owner;
    private final ProfileStore profileStore;
    private final Consumer<String> logger;
    private final HostProjectRegistry registry;
    private final ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Dialog<Void> dialog;
    private ListView<HostProjectRegistry.Entry> projectList;
    private VBox detailsPane;
    private Label statusLabel;

    // For Modrinth icon loading
    private final ConcurrentHashMap<String, Image> iconCache = new ConcurrentHashMap<>();
    private final java.net.http.HttpClient iconHttp = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();

    public HostModePanel(Window owner, ProfileStore profileStore, Consumer<String> logger) {
        this.owner = owner;
        this.profileStore = profileStore;
        this.logger = logger;
        this.registry = new HostProjectRegistry(profileStore.baseDir());
    }

    public void show() {
        dialog = new Dialog<>();
        dialog.setTitle("Host Mode");
        dialog.initModality(Modality.WINDOW_MODAL);
        if (owner != null) dialog.initOwner(owner);
        dialog.setResizable(true);

        dialog.getDialogPane().getButtonTypes().add(
                new ButtonType("Schließen", ButtonBar.ButtonData.CANCEL_CLOSE)
        );

        // Main layout: left project list, right details
        SplitPane split = new SplitPane();
        split.setDividerPositions(0.30);

        VBox leftPane = buildLeftPane();
        detailsPane = buildEmptyDetails();

        split.getItems().addAll(leftPane, detailsPane);
        SplitPane.setResizableWithParent(leftPane, false);

        // Status bar
        statusLabel = new Label("Bereit");
        statusLabel.getStyleClass().add("mutedSmall");
        statusLabel.setPadding(new Insets(8, 14, 8, 14));

        VBox root = new VBox(split, statusLabel);
        VBox.setVgrow(split, Priority.ALWAYS);
        root.getStyleClass().add("hostRoot");

        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().setPrefSize(1050, 680);
        dialog.getDialogPane().setMinWidth(800);

        // Apply theme
        String css = getClass().getResource("/de/levingamer8/modlauncher/ui/theme.css").toExternalForm();
        dialog.getDialogPane().getStylesheets().add(css);
        dialog.getDialogPane().getStyleClass().add("root");

        refreshProjectList();
        dialog.showAndWait();
    }

    // ======================== LEFT PANE ========================

    private VBox buildLeftPane() {
        Label title = new Label("Meine Projekte");
        title.getStyleClass().add("sectionTitle");
        title.setPadding(new Insets(0, 0, 8, 0));

        projectList = new ListView<>();
        projectList.getStyleClass().add("hostProjectList");
        projectList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(HostProjectRegistry.Entry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                VBox box = new VBox(2);
                Label name = new Label(item.name());
                name.setStyle("-fx-font-weight: bold; -fx-text-fill: #eef7ff;");
                Label meta = new Label(item.mcVersion() + " • " + item.loader());
                meta.setStyle("-fx-text-fill: rgba(238,247,255,0.6); -fx-font-size: 11px;");
                box.getChildren().addAll(name, meta);
                box.setPadding(new Insets(6, 10, 6, 10));
                setGraphic(box);
            }
        });

        projectList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                showProjectDetails(selected);
            } else {
                replaceDetails(buildEmptyDetails());
            }
        });

        VBox.setVgrow(projectList, Priority.ALWAYS);

        Button newBtn = new Button("+ Neues Projekt");
        newBtn.getStyleClass().addAll("primary");
        newBtn.setMaxWidth(Double.MAX_VALUE);
        newBtn.setOnAction(e -> onCreateNewProject());

        Button importBtn = new Button("Projekt importieren");
        importBtn.getStyleClass().addAll("secondary");
        importBtn.setMaxWidth(Double.MAX_VALUE);
        importBtn.setOnAction(e -> onImportProject());

        VBox left = new VBox(10, title, projectList, newBtn, importBtn);
        left.setPadding(new Insets(14));
        left.setMinWidth(220);
        return left;
    }

    // ======================== DETAILS PANE ========================

    private VBox buildEmptyDetails() {
        Label hint = new Label("Wähle ein Projekt aus oder erstelle ein neues.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        VBox box = new VBox(hint);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));
        return box;
    }

    private void showProjectDetails(HostProjectRegistry.Entry entry) {
        registry.touch(entry.projectId());

        Path root = Path.of(entry.projectRoot());

        // Header
        Label nameLabel = new Label(entry.name());
        nameLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: 900; -fx-text-fill: #eef7ff;");

        Label idLabel = new Label("ID: " + entry.projectId());
        idLabel.getStyleClass().add("mutedSmall");

        String loaderText = entry.loader();
        if (entry.loaderVersion() != null && !entry.loaderVersion().isBlank()) {
            loaderText += " " + entry.loaderVersion();
        }
        Label mcLabel = new Label("MC " + entry.mcVersion() + "  •  " + loaderText);
        mcLabel.setStyle("-fx-text-fill: #00ffff; -fx-font-size: 13px;");

        Label pathLabel = new Label(entry.projectRoot());
        pathLabel.getStyleClass().add("mutedSmall");
        pathLabel.setWrapText(true);

        // Read versions.json for version info
        String latestVersionTmp = "?";
        List<VersionsIndex.VersionEntry> versions = List.of();
        try {
            Path versionsJson = root.resolve("versions.json");
            if (Files.exists(versionsJson)) {
                VersionsIndex idx = om.readValue(versionsJson.toFile(), VersionsIndex.class);
                if (idx.latestVersion() != null) latestVersionTmp = idx.latestVersion();
                if (idx.versions() != null) versions = idx.versions();
            }
        } catch (Exception ignored) {}
        final String latestVersion = latestVersionTmp;

        Label versionLabel = new Label("Version: " + latestVersion + "  (latest)");
        versionLabel.setStyle("-fx-text-fill: #9ffcff; -fx-font-weight: bold;");

        VBox headerBox = new VBox(4, nameLabel, idLabel, mcLabel, versionLabel, pathLabel);
        headerBox.getStyleClass().add("card");
        headerBox.setPadding(new Insets(16));

        // === Actions ===
        Label actionsTitle = new Label("Aktionen");
        actionsTitle.getStyleClass().add("sectionTitle");

        Button addModsBtn = new Button("Mods hinzuf\u00fcgen");
        addModsBtn.getStyleClass().addAll("secondary");
        addModsBtn.setOnAction(e -> onAddMods(entry));

        Button openDirBtn = new Button("Ordner \u00f6ffnen");
        openDirBtn.getStyleClass().addAll("ghost");
        openDirBtn.setOnAction(e -> {
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(root.toFile());
            } catch (Exception ex) {
                setStatus("Fehler: " + ex.getMessage());
            }
        });

        Button genManifestBtn = new Button("Manifest generieren");
        genManifestBtn.getStyleClass().addAll("ghost");
        genManifestBtn.setOnAction(e -> onGenerateManifest(entry, latestVersion));

        Button removeBtn = new Button("Aus Liste entfernen");
        removeBtn.getStyleClass().addAll("ghostDanger");
        removeBtn.setOnAction(e -> {
            registry.remove(entry.projectId());
            refreshProjectList();
            replaceDetails(buildEmptyDetails());
            setStatus("Projekt aus Liste entfernt: " + entry.name());
        });

        Button editBtn = new Button("Einstellungen");
        editBtn.getStyleClass().addAll("secondary");
        editBtn.setOnAction(e -> onEditProject(entry));

        javafx.scene.layout.FlowPane actionsRow = new javafx.scene.layout.FlowPane(8, 8, addModsBtn, openDirBtn, genManifestBtn, editBtn);
        actionsRow.setAlignment(Pos.CENTER_LEFT);

        // === Version Management ===
        Label releaseTitle = new Label("Neues Release");
        releaseTitle.getStyleClass().add("sectionTitle");

        Label releaseHint = new Label(
                "Erstellt eine Kopie der aktuellen Version, bumpt die Versionsnummer "
                        + "und aktualisiert versions.json. Danach kannst du Mods ändern und das Manifest neu generieren.");
        releaseHint.getStyleClass().add("mutedSmall");
        releaseHint.setWrapText(true);

        String finalLatest = latestVersion;
        Semver currentSemver = Semver.parse(latestVersion);

        Button patchBtn = new Button("Patch  →  " + currentSemver.bumpPatch());
        patchBtn.getStyleClass().addAll("secondary");
        patchBtn.setOnAction(e -> onNewRelease(entry, "patch"));

        Button minorBtn = new Button("Minor  →  " + currentSemver.bumpMinor());
        minorBtn.getStyleClass().addAll("secondary");
        minorBtn.setOnAction(e -> onNewRelease(entry, "minor"));

        Button majorBtn = new Button("Major  →  " + currentSemver.bumpMajor());
        majorBtn.getStyleClass().addAll("ghost");
        majorBtn.setOnAction(e -> onNewRelease(entry, "major"));

        javafx.scene.layout.FlowPane releaseRow = new javafx.scene.layout.FlowPane(8, 8, patchBtn, minorBtn, majorBtn);
        releaseRow.setAlignment(Pos.CENTER_LEFT);

        // === Version History ===
        Label historyTitle = new Label("Versionen");
        historyTitle.getStyleClass().add("sectionTitle");

        VBox historyBox = new VBox(4);
        historyBox.getStyleClass().add("card");
        historyBox.setPadding(new Insets(10));

        if (versions.isEmpty()) {
            historyBox.getChildren().add(new Label("Keine Versionen gefunden."));
        } else {
            // Newest first
            var sorted = new java.util.ArrayList<>(versions);
            sorted.sort((a, b) -> Semver.parse(b.version()).compareTo(Semver.parse(a.version())));
            for (var v : sorted) {
                boolean isLatest = v.version().equals(finalLatest);
                HBox row = new HBox(8);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(4, 8, 4, 8));

                Label verLabel = new Label(v.version());
                verLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #eef7ff;");

                if (isLatest) {
                    Label badge = new Label("latest");
                    badge.getStyleClass().add("pillOk");
                    badge.setStyle(badge.getStyle() + "-fx-font-size: 10px; -fx-padding: 2 6 2 6;");
                    row.getChildren().addAll(verLabel, badge);
                } else {
                    row.getChildren().add(verLabel);
                }

                if (v.manifestUrl() != null) {
                    Label urlLabel = new Label(v.manifestUrl());
                    urlLabel.setStyle("-fx-text-fill: rgba(238,247,255,0.4); -fx-font-size: 10px;");
                    urlLabel.setMaxWidth(Double.MAX_VALUE);
                    urlLabel.setEllipsisString("…");
                    row.getChildren().add(urlLabel);
                }

                historyBox.getChildren().add(row);
            }
        }

        ScrollPane historyScroll = new ScrollPane(historyBox);
        historyScroll.setFitToWidth(true);
        historyScroll.setPrefHeight(160);
        historyScroll.setStyle("-fx-background-color: transparent;");

        // === Assemble ===
        VBox details = new VBox(16,
                headerBox,
                actionsTitle, actionsRow,
                new Separator(),
                releaseTitle, releaseHint, releaseRow,
                new Separator(),
                historyTitle, historyScroll,
                new Region(), // spacer
                removeBtn
        );
        details.setPadding(new Insets(14));
        VBox.setVgrow(historyScroll, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(details);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");

        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        replaceDetails(wrapper);
    }

    private void replaceDetails(VBox newContent) {
        if (dialog == null) return;
        Node content = dialog.getDialogPane().getContent();
        if (content instanceof VBox vbox) {
            Node first = vbox.getChildren().get(0);
            if (first instanceof SplitPane split && split.getItems().size() >= 2) {
                split.getItems().set(1, newContent);
            }
        }
    }

    // ======================== ACTIONS ========================

    private void onCreateNewProject() {
        Dialog<CreateHostProjectRequest> d = new Dialog<>();
        d.setTitle("Neues Host-Projekt erstellen");
        d.initModality(Modality.WINDOW_MODAL);
        d.initOwner(dialog.getDialogPane().getScene().getWindow());

        // Apply theme
        String css = getClass().getResource("/de/levingamer8/modlauncher/ui/theme.css").toExternalForm();
        d.getDialogPane().getStylesheets().add(css);
        d.getDialogPane().getStyleClass().add("root");

        ButtonType createBtn = new ButtonType("Erstellen", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(createBtn, ButtonType.CANCEL);

        // Step 1: Basic info
        Label step1 = new Label("1. Grundeinstellungen");
        step1.getStyleClass().add("sectionTitle");

        TextField projectId = new TextField("mein-pack");
        projectId.setPromptText("z.B. mein-server-pack");
        addTooltip(projectId, "Eindeutige ID für das Projekt (klein, keine Leerzeichen)");

        TextField name = new TextField("Mein Pack");
        name.setPromptText("Anzeigename");
        addTooltip(name, "Der Name, den Spieler sehen");

        ComboBox<String> mcVersion = new ComboBox<>();
        mcVersion.setEditable(true);
        mcVersion.getEditor().setText("1.20.1");
        addTooltip(mcVersion, "Die Minecraft-Version, für die das Pack ist");

        // Async MC versions
        loadMcVersionsAsync(mcVersion);

        ComboBox<LoaderType> loader = new ComboBox<>();
        loader.getItems().setAll(LoaderType.values());
        loader.getSelectionModel().select(LoaderType.FABRIC);
        addTooltip(loader, "Mod-Loader: Fabric, Forge, Quilt, NeoForge oder Vanilla");

        ComboBox<String> loaderVersion = new ComboBox<>();
        loaderVersion.setEditable(true);
        loaderVersion.setPromptText("z.B. 0.15.11");
        addTooltip(loaderVersion, "Version des Mod-Loaders");

        // Auto-refresh loader versions
        Runnable refreshLoader = () -> {
            LoaderType lt = loader.getValue();
            boolean vanilla = lt == LoaderType.VANILLA;
            loaderVersion.setDisable(vanilla);
            if (vanilla) { loaderVersion.getItems().clear(); loaderVersion.getEditor().setText(""); return; }
            String mc = mcVersion.getEditor().getText();
            if (mc == null || mc.isBlank()) return;
            loadLoaderVersionsAsync(mc, lt, loaderVersion);
        };
        loader.valueProperty().addListener((o, a, b) -> refreshLoader.run());
        mcVersion.getEditor().textProperty().addListener((o, a, b) -> refreshLoader.run());

        // Step 2: Hosting
        Label step2 = new Label("2. Hosting-Einstellungen");
        step2.getStyleClass().add("sectionTitle");

        Label baseUrlHint = new Label(
                "Die Base URL ist die Web-Adresse, unter der dein Pack erreichbar sein wird. "
                        + "Wenn du z.B. die Dateien nach https://example.com/mein-pack/ hochlädst, "
                        + "dann ist das deine Base URL.");
        baseUrlHint.getStyleClass().add("mutedSmall");
        baseUrlHint.setWrapText(true);

        TextField baseUrl = new TextField("https://example.com/mein-pack/");
        baseUrl.setPromptText("https://dein-server.de/pack/");
        addTooltip(baseUrl, "Die URL, unter der das Pack gehostet wird");

        TextField initialVersion = new TextField("1.0.0");
        initialVersion.setPromptText("1.0.0");
        addTooltip(initialVersion, "Die erste Versionsnummer (Semver: major.minor.patch)");

        // Step 3: Output
        Label step3 = new Label("3. Ausgabe-Ordner");
        step3.getStyleClass().add("sectionTitle");

        Label outHint = new Label(
                "Hier werden die Projektdateien lokal gespeichert. "
                        + "Diesen Ordner lädst du dann auf deinen Webserver hoch.");
        outHint.getStyleClass().add("mutedSmall");
        outHint.setWrapText(true);

        TextField outFolder = new TextField(
                profileStore.baseDir().resolve("host-projects").toString()
        );
        Button browse = new Button("…");
        browse.getStyleClass().add("ghost");
        browse.setOnAction(e -> {
            DirectoryChooser ch = new DirectoryChooser();
            ch.setTitle("Ausgabe-Ordner wählen");
            java.io.File sel = ch.showDialog(d.getDialogPane().getScene().getWindow());
            if (sel != null) outFolder.setText(sel.getAbsolutePath());
        });
        HBox outRow = new HBox(8, outFolder, browse);
        HBox.setHgrow(outFolder, Priority.ALWAYS);

        // Layout
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(14));

        ColumnConstraints c1 = new ColumnConstraints(130);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c1, c2);

        int r = 0;
        grid.add(step1, 0, r++, 2, 1);
        grid.addRow(r++, new Label("Projekt-ID:"), projectId);
        grid.addRow(r++, new Label("Name:"), name);
        grid.addRow(r++, new Label("MC Version:"), mcVersion);
        grid.addRow(r++, new Label("Loader:"), loader);
        grid.addRow(r++, new Label("Loader Version:"), loaderVersion);
        grid.add(new Separator(), 0, r++, 2, 1);
        grid.add(step2, 0, r++, 2, 1);
        grid.add(baseUrlHint, 0, r++, 2, 1);
        grid.addRow(r++, new Label("Base URL:"), baseUrl);
        grid.addRow(r++, new Label("Version:"), initialVersion);
        grid.add(new Separator(), 0, r++, 2, 1);
        grid.add(step3, 0, r++, 2, 1);
        grid.add(outHint, 0, r++, 2, 1);
        grid.addRow(r++, new Label("Ordner:"), outRow);

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");

        d.getDialogPane().setContent(scroll);
        d.getDialogPane().setPrefWidth(580);

        // Initial loader refresh
        Platform.runLater(refreshLoader);

        // Validation
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
                    Path.of(outFolder.getText().trim()).resolve(projectId.getText().trim().toLowerCase(Locale.ROOT))
            );
        });

        var req = d.showAndWait().orElse(null);
        if (req == null) return;

        try {
            var creator = new HostProjectCreator();
            var paths = creator.create(req);

            // Register project
            registry.register(new HostProjectRegistry.Entry(
                    req.projectId(),
                    req.name(),
                    paths.projectRoot().toString(),
                    req.baseUrl(),
                    req.mcVersion(),
                    req.loader() != null ? req.loader().name() : "VANILLA",
                    req.loaderVersion(),
                    Instant.now().toString()
            ));

            refreshProjectList();
            selectProject(req.projectId());

            setStatus("Projekt erstellt: " + req.name());
            logger.accept("[HOST] Projekt erstellt: " + paths.projectRoot());

            // Open Modrinth search for mods
            if (req.loader() != LoaderType.VANILLA) {
                onAddMods(registry.loadAll().stream()
                        .filter(e -> e.projectId().equals(req.projectId()))
                        .findFirst().orElse(null));
            }
        } catch (Exception ex) {
            showError("Projekt konnte nicht erstellt werden:\n" + ex.getMessage());
        }
    }

    private void onImportProject() {
        FileChooser fc = new FileChooser();
        fc.setTitle("project.json auswählen");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("project.json", "project.json")
        );

        java.io.File f = fc.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
        if (f == null) return;

        try {
            Path projectJson = f.toPath();
            Path root = projectJson.getParent();

            // Try both record types
            ProjectJson pj = om.readValue(f, ProjectJson.class);

            String loaderType = pj.loader() != null ? pj.loader() : "VANILLA";
            String loaderVer = pj.loaderVersion() != null ? pj.loaderVersion() : "";

            // Read base URL from versions.json if possible
            String baseUrl = "";
            try {
                Path versionsJson = root.resolve("versions.json");
                if (Files.exists(versionsJson)) {
                    VersionsIndex idx = om.readValue(versionsJson.toFile(), VersionsIndex.class);
                    String mUrl = idx.latestManifestUrl();
                    if (mUrl != null && mUrl.contains("/versions/")) {
                        baseUrl = mUrl.substring(0, mUrl.indexOf("/versions/") + 1);
                    }
                }
            } catch (Exception ignored) {}

            registry.register(new HostProjectRegistry.Entry(
                    pj.projectId(),
                    pj.projectName(),
                    root.toString(),
                    baseUrl,
                    pj.mcVersion(),
                    loaderType,
                    loaderVer,
                    Instant.now().toString()
            ));

            refreshProjectList();
            selectProject(pj.projectId());
            setStatus("Projekt importiert: " + pj.projectName());
        } catch (Exception ex) {
            showError("Konnte Projekt nicht importieren:\n" + ex.getMessage());
        }
    }

    private void onEditProject(HostProjectRegistry.Entry entry) {
        if (entry == null) return;

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Projekt-Einstellungen: " + entry.name());
        d.initModality(Modality.WINDOW_MODAL);
        d.initOwner(dialog.getDialogPane().getScene().getWindow());

        String css = getClass().getResource("/de/levingamer8/modlauncher/ui/theme.css").toExternalForm();
        d.getDialogPane().getStylesheets().add(css);
        d.getDialogPane().getStyleClass().add("root");

        ButtonType saveBtn = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        // Read current project.json
        Path root = Path.of(entry.projectRoot());
        Path projectJsonPath = root.resolve("project.json");

        ProjectJson currentPj = null;
        try {
            if (Files.exists(projectJsonPath)) {
                currentPj = om.readValue(projectJsonPath.toFile(), ProjectJson.class);
            }
        } catch (Exception ignored) {}

        // === Fields ===
        TextField nameField = new TextField(entry.name());
        nameField.setPromptText("Pack-Name");
        addTooltip(nameField, "Der Anzeigename für Spieler");

        // MC Version
        ComboBox<String> mcVersionBox = new ComboBox<>();
        mcVersionBox.setEditable(true);
        mcVersionBox.getEditor().setText(entry.mcVersion() != null ? entry.mcVersion() : "");
        addTooltip(mcVersionBox, "Minecraft-Version für dieses Pack");
        loadMcVersionsAsync(mcVersionBox);

        // Loader Type
        ComboBox<LoaderType> loaderBox = new ComboBox<>();
        loaderBox.getItems().setAll(LoaderType.values());
        try {
            loaderBox.getSelectionModel().select(LoaderType.fromString(entry.loader()));
        } catch (Exception ex) {
            loaderBox.getSelectionModel().select(LoaderType.VANILLA);
        }
        addTooltip(loaderBox, "Mod-Loader Typ");

        // Loader Version — auto-refreshes when MC or Loader changes
        ComboBox<String> loaderVersionBox = new ComboBox<>();
        loaderVersionBox.setEditable(true);
        loaderVersionBox.getEditor().setText(entry.loaderVersion() != null ? entry.loaderVersion() : "");
        loaderVersionBox.setPromptText("wird automatisch geladen…");
        addTooltip(loaderVersionBox, "Version des Mod-Loaders (wird automatisch passend zur MC-Version geladen)");

        Runnable refreshLoader = () -> {
            LoaderType lt = loaderBox.getValue();
            boolean vanilla = lt == null || lt == LoaderType.VANILLA;
            loaderVersionBox.setDisable(vanilla);
            loaderVersionBox.setOpacity(vanilla ? 0.5 : 1.0);
            if (vanilla) {
                loaderVersionBox.getItems().clear();
                loaderVersionBox.getEditor().setText("");
                return;
            }
            String mc = mcVersionBox.getEditor().getText();
            if (mc == null || mc.isBlank()) return;
            loadLoaderVersionsAsync(mc, lt, loaderVersionBox);
        };
        loaderBox.valueProperty().addListener((o, a, b) -> refreshLoader.run());
        mcVersionBox.getEditor().textProperty().addListener((o, a, b) -> refreshLoader.run());

        // Base URL
        TextField baseUrlField = new TextField(entry.baseUrl() != null ? entry.baseUrl() : "");
        baseUrlField.setPromptText("https://example.com/mein-pack/");
        addTooltip(baseUrlField, "Die URL, unter der das Pack gehostet wird. Ändert die URLs in allen Manifesten.");

        // Server settings (from project.json)
        TextField serverIpField = new TextField(currentPj != null ? safe(currentPj.serverIP()) : "");
        serverIpField.setPromptText("z.B. mc.example.com");
        addTooltip(serverIpField, "Server-IP, die automatisch in servers.dat eingetragen wird");

        TextField serverPortField = new TextField(
                currentPj != null ? safe(currentPj.serverPort()) : "25565");
        serverPortField.setPromptText("25565");
        addTooltip(serverPortField, "Server-Port (Standard: 25565)");

        CheckBox onlySelectedBox = new CheckBox("Nur diesen Server in servers.dat");
        onlySelectedBox.setSelected(currentPj != null && currentPj.onlySelectedServer());
        onlySelectedBox.setStyle("-fx-text-fill: #eef7ff;");
        addTooltip(onlySelectedBox, "Wenn aktiv, wird servers.dat so überschrieben, dass NUR dieser Server drin steht");

        CheckBox allowClientModsBox = new CheckBox("Client-Mods erlauben");
        allowClientModsBox.setSelected(currentPj == null || currentPj.allowClientMods());
        allowClientModsBox.setStyle("-fx-text-fill: #eef7ff;");
        addTooltip(allowClientModsBox, "Ob Spieler eigene Client-side Mods hinzufügen dürfen");

        // === Layout ===
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(14));

        ColumnConstraints c1 = new ColumnConstraints(130);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c1, c2);

        Label generalTitle = new Label("Allgemein");
        generalTitle.getStyleClass().add("sectionTitle");
        Label hostingTitle = new Label("Hosting");
        hostingTitle.getStyleClass().add("sectionTitle");
        Label serverTitle = new Label("Server-Einstellungen");
        serverTitle.getStyleClass().add("sectionTitle");

        int r = 0;
        grid.add(generalTitle, 0, r++, 2, 1);
        grid.addRow(r++, new Label("Name:"), nameField);
        grid.addRow(r++, new Label("MC Version:"), mcVersionBox);
        grid.addRow(r++, new Label("Loader:"), loaderBox);
        grid.addRow(r++, new Label("Loader Version:"), loaderVersionBox);
        grid.add(new Separator(), 0, r++, 2, 1);
        grid.add(hostingTitle, 0, r++, 2, 1);
        grid.addRow(r++, new Label("Base URL:"), baseUrlField);
        grid.add(new Separator(), 0, r++, 2, 1);
        grid.add(serverTitle, 0, r++, 2, 1);
        grid.addRow(r++, new Label("Server IP:"), serverIpField);
        grid.addRow(r++, new Label("Server Port:"), serverPortField);
        grid.add(onlySelectedBox, 1, r++);
        grid.add(allowClientModsBox, 1, r++);

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");

        d.getDialogPane().setContent(scroll);
        d.getDialogPane().setPrefWidth(550);

        // Initial loader version refresh (delayed so combo is rendered)
        Platform.runLater(refreshLoader);

        // === Show & Save ===
        final ProjectJson prevPj = currentPj;

        var result = d.showAndWait().orElse(ButtonType.CANCEL);
        if (result != saveBtn) return;

        // Collect new values
        String newName = nameField.getText().trim();
        String newMc = mcVersionBox.getEditor().getText().trim();
        LoaderType newLt = loaderBox.getValue();
        String newLoaderStr = newLt != null ? newLt.name() : "VANILLA";
        String newLoaderVer = loaderVersionBox.getEditor().getText().trim();
        String newBaseUrl = baseUrlField.getText().trim();
        String newServerIp = serverIpField.getText().trim();
        String newServerPort = serverPortField.getText().trim();
        boolean newOnlySelected = onlySelectedBox.isSelected();
        boolean newAllowClient = allowClientModsBox.isSelected();

        try {
            // 1) Update project.json
            ProjectJson newPj = new ProjectJson(
                    entry.projectId(),
                    newName,
                    newMc,
                    newLoaderStr,
                    newLoaderVer,
                    newServerIp,
                    newServerPort.isEmpty() ? "25565" : newServerPort,
                    newAllowClient,
                    newOnlySelected
            );
            om.writeValue(projectJsonPath.toFile(), newPj);

            // 2) Update all manifest.json files with new loader, mcVersion, baseUrl
            updateManifestsAfterEdit(root, newName, newMc, newLoaderStr, newLoaderVer, newBaseUrl);

            // 3) Update registry
            registry.register(new HostProjectRegistry.Entry(
                    entry.projectId(),
                    newName,
                    entry.projectRoot(),
                    newBaseUrl,
                    newMc,
                    newLoaderStr,
                    newLoaderVer,
                    Instant.now().toString()
            ));

            refreshProjectList();
            selectProject(entry.projectId());
            setStatus("Einstellungen gespeichert: " + newName);
            logger.accept("[HOST] Einstellungen aktualisiert: " + newName);

        } catch (Exception ex) {
            showError("Einstellungen konnten nicht gespeichert werden:\n" + ex.getMessage());
        }
    }

    /**
     * Updates all manifest.json files in all version directories
     * with the new loader, mcVersion, and baseUrl.
     */
    private void updateManifestsAfterEdit(Path projectRoot, String packName, String mcVersion,
                                          String loaderType, String loaderVersion, String baseUrl) throws Exception {
        Path versionsDir = projectRoot.resolve("versions");
        if (!Files.isDirectory(versionsDir)) return;

        try (var dirs = Files.list(versionsDir)) {
            for (Path versionDir : dirs.filter(Files::isDirectory).toList()) {
                Path manifestPath = versionDir.resolve("manifest.json");
                if (!Files.exists(manifestPath)) continue;

                try {
                    ManifestModels.Manifest m = om.readValue(manifestPath.toFile(), ManifestModels.Manifest.class);

                    String ver = versionDir.getFileName().toString();
                    String filesBaseUrl = ensureSlash(baseUrl) + "versions/" + ver + "/files/";

                    ManifestModels.Loader loader = new ManifestModels.Loader(loaderType, loaderVersion);

                    ManifestModels.Manifest updated = new ManifestModels.Manifest(
                            m.packId(),
                            packName,
                            m.packVersion(),
                            mcVersion,
                            loader,
                            filesBaseUrl,
                            m.files(),
                            m.overrides(),
                            Instant.now().toString(),
                            m.changelogUrl()
                    );

                    om.writeValue(manifestPath.toFile(), updated);

                    // Re-generate file hashes with new baseUrl
                    Path filesDir = versionDir.resolve("files");
                    if (Files.isDirectory(filesDir)) {
                        new HostManifestGenerator().generate(manifestPath, filesDir);
                    }
                } catch (Exception ex) {
                    logger.accept("[HOST] Warnung: Manifest in " + versionDir.getFileName() + " nicht aktualisiert: " + ex.getMessage());
                }
            }
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private void onAddMods(HostProjectRegistry.Entry entry) {
        if (entry == null) return;

        Path root = Path.of(entry.projectRoot());
        String latestVersion = getLatestVersion(root);
        if (latestVersion == null) {
            showError("Keine Version gefunden in versions.json");
            return;
        }

        Path modsDir = root.resolve("versions").resolve(latestVersion).resolve("files").resolve("mods");
        try { Files.createDirectories(modsDir); } catch (Exception ignored) {}

        LoaderType lt = LoaderType.fromString(entry.loader());
        if (lt == LoaderType.VANILLA) {
            showError("Vanilla hat keine Mods.");
            return;
        }

        Path filesDir = modsDir.getParent();
        Path manifestPath = filesDir.getParent().resolve("manifest.json");

        openModrinthSearch(entry.mcVersion(), lt, modsDir, manifestPath, filesDir);
    }

    private void onGenerateManifest(HostProjectRegistry.Entry entry, String latestVersion) {
        Path root = Path.of(entry.projectRoot());
        String ver = latestVersion.equals("?") ? getLatestVersion(root) : latestVersion;
        if (ver == null) {
            showError("Keine Version gefunden.");
            return;
        }

        Path filesDir = root.resolve("versions").resolve(ver).resolve("files");
        Path manifestPath = root.resolve("versions").resolve(ver).resolve("manifest.json");

        setStatus("Generiere Manifest...");

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                new HostManifestGenerator().generate(manifestPath, filesDir);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setStatus("Manifest aktualisiert: " + ver);
            logger.accept("[HOST] Manifest aktualisiert: " + manifestPath);
        });
        task.setOnFailed(e -> {
            setStatus("Manifest-Fehler");
            showError("Manifest-Generierung fehlgeschlagen:\n"
                    + (task.getException() != null ? task.getException().getMessage() : "Unbekannt"));
        });

        Thread t = new Thread(task, "host-manifest-gen");
        t.setDaemon(true);
        t.start();
    }

    private void onNewRelease(HostProjectRegistry.Entry entry, String bumpType) {
        Path root = Path.of(entry.projectRoot());

        setStatus("Erstelle neues Release (" + bumpType + ")...");

        Task<HostProjectPaths> task = new Task<>() {
            @Override protected HostProjectPaths call() throws Exception {
                HostReleaseManager mgr = new HostReleaseManager();
                String baseUrl = entry.baseUrl();
                if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://example.com/pack/";

                Path versionsJson = root.resolve("versions.json");
                VersionsIndex idx = om.readValue(versionsJson.toFile(), VersionsIndex.class);
                String oldVer = idx.latestVersion();
                if (oldVer == null) throw new IllegalStateException("Keine aktuelle Version gefunden");

                Semver sv = Semver.parse(oldVer);
                String newVer = switch (bumpType) {
                    case "patch" -> sv.bumpPatch().toString();
                    case "minor" -> sv.bumpMinor().toString();
                    case "major" -> sv.bumpMajor().toString();
                    default -> sv.bumpPatch().toString();
                };

                return mgr.createRelease(root, baseUrl, oldVer, newVer);
            }
        };

        task.setOnSucceeded(e -> {
            HostProjectPaths paths = task.getValue();
            String newVer = paths.versionRoot().getFileName().toString();
            setStatus("Release erstellt: " + newVer);
            logger.accept("[HOST] Neues Release: " + newVer + " in " + paths.versionRoot());

            // Changelog-Editor anzeigen
            showChangelogEditor(paths.versionRoot().resolve("changelog.txt"), newVer);

            refreshProjectList();
            selectProject(entry.projectId());
        });

        task.setOnFailed(e -> {
            setStatus("Release-Fehler");
            Throwable ex = task.getException();
            showError("Release fehlgeschlagen:\n" + (ex != null ? ex.getMessage() : "Unbekannt"));
        });

        Thread t = new Thread(task, "host-release");
        t.setDaemon(true);
        t.start();
    }

    private void showChangelogEditor(Path changelogPath, String version) {
        Dialog<String> d = new Dialog<>();
        d.setTitle("Changelog bearbeiten - v" + version);
        d.setHeaderText("Automatisch generierter Changelog. Du kannst eigenen Text hinzuf\u00fcgen:");
        d.setResizable(true);

        ButtonType saveBtn = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        String currentText = "";
        try {
            if (Files.exists(changelogPath)) currentText = Files.readString(changelogPath);
        } catch (Exception ignored) {}

        TextArea area = new TextArea(currentText);
        area.setWrapText(true);
        area.setPrefRowCount(18);
        area.setPrefColumnCount(50);

        VBox box = new VBox(8, area);
        box.setPadding(new Insets(10));
        d.getDialogPane().setContent(box);
        d.getDialogPane().setPrefWidth(600);

        String css = getClass().getResource("/de/levingamer8/modlauncher/ui/theme.css").toExternalForm();
        d.getDialogPane().getStylesheets().add(css);
        d.getDialogPane().getStyleClass().add("root");

        d.setResultConverter(bt -> bt == saveBtn ? area.getText() : null);

        d.showAndWait().ifPresent(text -> {
            try {
                Files.writeString(changelogPath, text);
                setStatus("Changelog gespeichert: v" + version);
                logger.accept("[HOST] Changelog gespeichert: " + changelogPath);
            } catch (Exception ex) {
                showError("Changelog konnte nicht gespeichert werden:\n" + ex.getMessage());
            }
        });
    }

    // ======================== MODRINTH SEARCH ========================

    private void openModrinthSearch(String mcVersion, LoaderType loaderType,
                                    Path modsDir, Path manifestPath, Path filesDir) {
        String modrinthLoader = LoaderType.toString(loaderType);
        ModrinthClient api = new ModrinthClient();

        final int pageSize = 50;
        final java.util.concurrent.atomic.AtomicInteger offsetVal = new java.util.concurrent.atomic.AtomicInteger(0);

        Dialog<Void> modDialog = new Dialog<>();
        modDialog.initModality(Modality.WINDOW_MODAL);
        modDialog.initOwner(dialog.getDialogPane().getScene().getWindow());
        modDialog.setTitle("Mods hinzufügen");

        String css = getClass().getResource("/de/levingamer8/modlauncher/ui/theme.css").toExternalForm();
        modDialog.getDialogPane().getStylesheets().add(css);
        modDialog.getDialogPane().getStyleClass().add("root");

        modDialog.getDialogPane().getButtonTypes().add(
                new ButtonType("Fertig", ButtonBar.ButtonData.CANCEL_CLOSE)
        );

        Label ctx = new Label("MC " + mcVersion + "  •  " + loaderType);
        ctx.setStyle("-fx-text-fill: #00ffff; -fx-font-weight: bold;");

        TextField query = new TextField();
        query.setPromptText("Mod suchen (z.B. sodium, jei, iris...)");

        Button searchBtn = new Button("Suchen");
        searchBtn.getStyleClass().add("primary");

        Button prevBtn = new Button("<");
        prevBtn.getStyleClass().add("ghost");
        prevBtn.setDisable(true);
        Button nextBtn = new Button(">");
        nextBtn.getStyleClass().add("ghost");
        nextBtn.setDisable(true);

        ProgressIndicator progress = new ProgressIndicator();
        progress.setVisible(false);
        progress.setMaxSize(18, 18);

        Label modStatus = new Label();
        modStatus.getStyleClass().add("mutedSmall");
        Label pageInfo = new Label();
        pageInfo.getStyleClass().add("mutedSmall");

        ListView<SearchHit> list = new ListView<>();
        list.setPrefHeight(400);
        list.setCellFactory(lv -> new ListCell<>() {
            private final Label title = new Label();
            private final Label meta = new Label();
            private final Label desc = new Label();
            private final VBox textBox = new VBox(2, title, meta, desc);
            private final HBox row = new HBox(10, textBox);

            {
                title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #eef7ff;");
                meta.setStyle("-fx-text-fill: rgba(238,247,255,0.6); -fx-font-size: 11px;");
                desc.setStyle("-fx-text-fill: rgba(238,247,255,0.8); -fx-font-size: 12px;");
                desc.setWrapText(true);
                HBox.setHgrow(textBox, Priority.ALWAYS);
                row.setPadding(new Insets(8, 10, 8, 10));
            }

            @Override
            protected void updateItem(SearchHit item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                title.setText(item.title());
                String author = (item.author() == null || item.author().isBlank()) ? "?" : item.author();
                meta.setText(author + " • " + formatDownloads(item.downloads()) + " downloads");
                desc.setText(item.description() == null ? "" : item.description());
                setGraphic(row);
            }
        });

        Button addBtn = new Button("Zum Pack hinzuf\u00fcgen");
        addBtn.getStyleClass().addAll("primary");
        addBtn.setDisable(true);

        Button genBtn = new Button("Manifest generieren");
        genBtn.getStyleClass().addAll("secondary");

        list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> addBtn.setDisable(n == null));
        list.setOnMouseClicked(e -> { if (e.getClickCount() == 2) addBtn.fire(); });

        // Search logic
        java.util.function.IntConsumer doSearch = (off) -> {
            String q = query.getText() == null ? "" : query.getText().trim();
            if (q.isEmpty()) { modStatus.setText("Suchbegriff eingeben."); return; }

            progress.setVisible(true);
            searchBtn.setDisable(true);
            modStatus.setText("Suche…");

            Task<de.levingamer8.modlauncher.host.modrinth.SearchResponse> t = new Task<>() {
                @Override protected de.levingamer8.modlauncher.host.modrinth.SearchResponse call() throws Exception {
                    return api.searchModsPage(q, modrinthLoader, mcVersion, pageSize, off);
                }
            };
            t.setOnSucceeded(ev -> {
                var resp = t.getValue();
                list.getItems().setAll(resp.hits());
                if (!resp.hits().isEmpty()) list.scrollTo(0);
                progress.setVisible(false);
                searchBtn.setDisable(false);
                int total = resp.total_hits();
                int curOff = resp.offset();
                int lim = resp.limit();
                int page = (lim <= 0) ? 1 : (curOff / lim) + 1;
                int pages = (lim <= 0) ? 1 : (int) Math.ceil(total / (double) lim);
                modStatus.setText(resp.hits().isEmpty() ? "Keine Treffer." : resp.hits().size() + " Treffer");
                pageInfo.setText("Seite " + page + "/" + pages + " • " + total + " gesamt");
                prevBtn.setDisable(curOff <= 0);
                nextBtn.setDisable(curOff + lim >= total);
                offsetVal.set(curOff);
            });
            t.setOnFailed(ev -> {
                progress.setVisible(false);
                searchBtn.setDisable(false);
                modStatus.setText("Suche fehlgeschlagen.");
            });
            Thread th = new Thread(t, "modrinth-search");
            th.setDaemon(true);
            th.start();
        };

        searchBtn.setOnAction(e -> doSearch.accept(0));
        query.setOnAction(e -> doSearch.accept(0));
        prevBtn.setOnAction(e -> doSearch.accept(Math.max(0, offsetVal.get() - pageSize)));
        nextBtn.setOnAction(e -> doSearch.accept(offsetVal.get() + pageSize));

        // Add mod
        addBtn.setOnAction(e -> {
            SearchHit sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;

            progress.setVisible(true);
            addBtn.setDisable(true);
            modStatus.setText("Downloade: " + sel.title() + "…");

            Task<Path> t = new Task<>() {
                @Override protected Path call() throws Exception {
                    var v = api.getBestVersion(sel.project_id(), modrinthLoader, mcVersion);
                    return api.downloadPrimaryJar(v, modsDir);
                }
            };
            t.setOnSucceeded(ev -> {
                Path jar = t.getValue();
                modStatus.setText("Hinzugefügt: " + jar.getFileName());
                logger.accept("[HOST] Mod hinzugefügt: " + jar.getFileName());
                progress.setVisible(false);
                addBtn.setDisable(false);

                // Auto-regenerate manifest
                Task<Void> gen = new Task<>() {
                    @Override protected Void call() throws Exception {
                        new HostManifestGenerator().generate(manifestPath, filesDir);
                        return null;
                    }
                };
                gen.setOnSucceeded(ev2 -> logger.accept("[HOST] Manifest aktualisiert"));
                gen.setOnFailed(ev2 -> modStatus.setText("Manifest-Update fehlgeschlagen"));
                Thread th = new Thread(gen, "host-gen");
                th.setDaemon(true);
                th.start();
            });
            t.setOnFailed(ev -> {
                progress.setVisible(false);
                addBtn.setDisable(false);
                modStatus.setText("Download fehlgeschlagen");
            });
            Thread th = new Thread(t, "modrinth-add");
            th.setDaemon(true);
            th.start();
        });

        genBtn.setOnAction(e -> {
            progress.setVisible(true);
            genBtn.setDisable(true);
            modStatus.setText("Generiere Manifest…");
            Task<Void> gen = new Task<>() {
                @Override protected Void call() throws Exception {
                    new HostManifestGenerator().generate(manifestPath, filesDir);
                    return null;
                }
            };
            gen.setOnSucceeded(ev -> {
                progress.setVisible(false);
                genBtn.setDisable(false);
                modStatus.setText("Manifest aktualisiert.");
            });
            gen.setOnFailed(ev -> {
                progress.setVisible(false);
                genBtn.setDisable(false);
                modStatus.setText("Manifest-Fehler");
            });
            Thread th = new Thread(gen, "host-gen");
            th.setDaemon(true);
            th.start();
        });

        HBox topRow = new HBox(10, query, searchBtn, progress, prevBtn, nextBtn);
        HBox.setHgrow(query, Priority.ALWAYS);

        HBox btnRow = new HBox(10, addBtn, genBtn);
        HBox bottomRow = new HBox(10, modStatus, new Region(), pageInfo);
        HBox.setHgrow(bottomRow.getChildren().get(1), Priority.ALWAYS);

        VBox content = new VBox(10, ctx, topRow, list, btnRow, bottomRow);
        content.setPadding(new Insets(14));
        VBox.setVgrow(list, Priority.ALWAYS);

        modDialog.getDialogPane().setContent(content);
        modDialog.getDialogPane().setPrefSize(850, 600);
        modDialog.setResizable(true);
        modDialog.showAndWait();
    }

    // ======================== HELPERS ========================

    private void refreshProjectList() {
        List<HostProjectRegistry.Entry> entries = registry.loadAll();
        projectList.getItems().setAll(entries);
    }

    private void selectProject(String projectId) {
        for (var e : projectList.getItems()) {
            if (e.projectId().equalsIgnoreCase(projectId)) {
                projectList.getSelectionModel().select(e);
                break;
            }
        }
    }

    private String getLatestVersion(Path root) {
        try {
            Path versionsJson = root.resolve("versions.json");
            if (!Files.exists(versionsJson)) return null;
            VersionsIndex idx = om.readValue(versionsJson.toFile(), VersionsIndex.class);
            return idx.latestVersion();
        } catch (Exception e) {
            return null;
        }
    }


    private void setStatus(String text) {
        Platform.runLater(() -> { if (statusLabel != null) statusLabel.setText(text); });
    }

    private void showError(String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.setTitle("Fehler");
            a.setHeaderText(null);
            a.setContentText(msg);
            a.initOwner(dialog != null ? dialog.getDialogPane().getScene().getWindow() : owner);
            a.showAndWait();
        });
    }

    private static void addTooltip(Node node, String text) {
        Tooltip tp = new Tooltip(text);
        tp.setWrapText(true);
        tp.setMaxWidth(450);
        Tooltip.install(node, tp);
    }

    private static String ensureSlash(String s) {
        return s.endsWith("/") ? s : s + "/";
    }

    private static String formatDownloads(long n) {
        if (n < 1_000) return Long.toString(n);
        if (n < 1_000_000) return String.format(Locale.US, "%.1fK", n / 1_000.0);
        if (n < 1_000_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
        return String.format(Locale.US, "%.1fB", n / 1_000_000_000.0);
    }

    // ======================== ASYNC LOADERS ========================

    private void loadMcVersionsAsync(ComboBox<String> mcVersion) {
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() throws Exception {
                String json = httpGet("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
                var out = new java.util.LinkedHashSet<String>();
                var p = java.util.regex.Pattern.compile(
                        "\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"type\"\\s*:\\s*\"release\"");
                var m = p.matcher(json);
                while (m.find()) { out.add(m.group(1)); if (out.size() >= 120) break; }
                return new java.util.ArrayList<>(out);
            }
        };
        task.setOnSucceeded(e -> {
            var list = task.getValue();
            if (list != null && !list.isEmpty()) {
                String keep = mcVersion.getEditor().getText();
                mcVersion.getItems().setAll(list);
                if (keep != null && !keep.isBlank()) mcVersion.getEditor().setText(keep);
            }
        });
        task.setOnFailed(e -> {
            if (mcVersion.getItems().isEmpty()) {
                mcVersion.getItems().setAll(List.of("1.21.4", "1.21.3", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.1"));
            }
        });
        Thread t = new Thread(task, "mc-versions");
        t.setDaemon(true);
        t.start();
    }

    private void loadLoaderVersionsAsync(String mc, LoaderType lt, ComboBox<String> target) {
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() throws Exception {
                return switch (lt) {
                    case FABRIC -> fetchVersions("https://meta.fabricmc.net/v2/versions/loader/" + mc,
                            "\"loader\"\\s*:\\s*\\{[^}]*?\"version\"\\s*:\\s*\"([^\"]+)\"");
                    case QUILT -> fetchVersions("https://meta.quiltmc.org/v3/versions/loader/" + mc,
                            "\"loader_version\"\\s*:\\s*\"([^\"]+)\"");
                    case FORGE -> fetchForgeVersions(mc);
                    case NEOFORGE -> fetchNeoForgeVersions(mc);
                    default -> List.of();
                };
            }
        };
        task.setOnSucceeded(e -> {
            var list = task.getValue();
            if (list != null && !list.isEmpty()) {
                String keep = target.getEditor().getText();
                target.getItems().setAll(list);
                if (keep == null || keep.isBlank() || list.stream().noneMatch(v -> v.equalsIgnoreCase(keep))) {
                    target.getEditor().setText(list.get(0));
                }
            }
        });
        Thread t = new Thread(task, "loader-versions");
        t.setDaemon(true);
        t.start();
    }

    private static List<String> fetchVersions(String url, String regex) throws Exception {
        String json = httpGet(url);
        var out = new java.util.LinkedHashSet<String>();
        var p = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.DOTALL);
        var m = p.matcher(json);
        while (m.find()) { out.add(m.group(1)); if (out.size() >= 30) break; }
        return new java.util.ArrayList<>(out);
    }

    private static List<String> fetchForgeVersions(String mc) throws Exception {
        String json = httpGet("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json");
        String rec = extractJsonValue(json, mc + "-recommended");
        String lat = extractJsonValue(json, mc + "-latest");
        var out = new java.util.ArrayList<String>();
        if (rec != null) out.add(rec);
        if (lat != null && !lat.equals(rec)) out.add(lat);
        return out;
    }

    private static List<String> fetchNeoForgeVersions(String mc) throws Exception {
        // NeoForge Maven metadata: versions for a given MC version
        // NeoForge versions are like 20.4.x for MC 1.20.4, 21.1.x for MC 1.21.1, etc.
        // The MC version maps to NeoForge major.minor: 1.X.Y -> X.Y
        String[] mcParts = mc.split("\\.");
        String neoPrefix;
        if (mcParts.length >= 3) {
            neoPrefix = mcParts[1] + "." + mcParts[2];
        } else if (mcParts.length == 2) {
            neoPrefix = mcParts[1] + ".0";
        } else {
            return List.of();
        }

        String url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
        String xml = httpGet(url);

        var out = new java.util.ArrayList<String>();
        var p = java.util.regex.Pattern.compile("<version>(" + java.util.regex.Pattern.quote(neoPrefix) + "[^<]*)</version>");
        var m = p.matcher(xml);
        while (m.find()) {
            out.add(m.group(1));
        }
        // Newest first
        java.util.Collections.reverse(out);
        if (out.size() > 20) out.subList(20, out.size()).clear();
        return out;
    }

    private static String extractJsonValue(String json, String key) {
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
        // Used for hardcoded API calls (Mojang, Fabric, NeoForge, etc.) - always HTTP
        return new de.levingamer8.modlauncher.core.ProtocolFetcher().getText(url);
    }
}
