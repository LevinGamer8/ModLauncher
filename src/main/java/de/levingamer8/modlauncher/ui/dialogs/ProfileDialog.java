package de.levingamer8.modlauncher.ui.dialogs;

import de.levingamer8.modlauncher.core.ProfileStore;
import de.levingamer8.modlauncher.service.ManifestService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import java.util.Objects;

public class ProfileDialog {

    private final ManifestService manifestService;

    public ProfileDialog(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    public ProfileDialogResult showCreate() {
        ProfileStore.Profile base = new ProfileStore.Profile(
                "",
                "",
                "",
                25565,
                ProfileStore.JoinMode.SERVERS_DAT
        );
        return showInternal("Neue Instanz", "Erstellen", base, null);
    }

    public ProfileDialogResult showEdit(ProfileStore.Profile existing) {
        return showInternal("Profil bearbeiten", "Speichern", existing, existing.name());
    }

    private ProfileDialogResult showInternal(String title, String okText, ProfileStore.Profile initial, String oldNameOrNull) {
        Dialog<ProfileDialogResult> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(null);

        ButtonType okBtn = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, ButtonType.CANCEL);

        // fields
        TextField name = new TextField(nullToEmpty(initial.name()));
        TextField url = new TextField(nullToEmpty(initial.manifestUrl()));
        TextField host = new TextField(nullToEmpty(initial.serverHost()));
        TextField port = new TextField(String.valueOf(initial.serverPort()));

        ComboBox<ProfileStore.JoinMode> joinMode = new ComboBox<>();
        joinMode.getItems().setAll(ProfileStore.JoinMode.values());
        joinMode.getSelectionModel().select(initial.joinMode() == null ? ProfileStore.JoinMode.SERVERS_DAT : initial.joinMode());
        joinMode.setConverter(new StringConverter<>() {
            @Override public String toString(ProfileStore.JoinMode jm) { return jm == null ? "" : jm.name(); }
            @Override public ProfileStore.JoinMode fromString(String s) { return null; }
        });

        // Test UI
        Button testBtn = new Button("Testen");
        ProgressIndicator pi = new ProgressIndicator();
        pi.setVisible(false);
        pi.setMaxSize(18, 18);

        Label testStatus = new Label();
        testStatus.setMinHeight(18);

        HBox testRow = new HBox(10, testBtn, pi, testStatus);

        GridPane gp = new GridPane();
        gp.setHgap(10);
        gp.setVgap(10);
        gp.setPadding(new Insets(12));

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

        Node okNode = dialog.getDialogPane().lookupButton(okBtn);
        okNode.setDisable(true);

        Runnable invalidateTest = () -> {
            testStatus.setText("Bitte testen.");
            testStatus.setUserData(null); // null = nicht OK
        };

        Runnable updateOkEnabled = () -> {
            boolean nameOk = !name.getText().trim().isEmpty();
            boolean urlOk = !url.getText().trim().isEmpty();
            boolean testOk = "OK".equals(testStatus.getUserData());

            boolean portOk;
            try {
                int v = Integer.parseInt(port.getText().trim());
                portOk = v >= 1 && v <= 65535;
            } catch (Exception e) {
                portOk = false;
            }

            okNode.setDisable(!(nameOk && urlOk && portOk && testOk));
        };

        // du wolltest: auch beim Edit erst testen müssen
        invalidateTest.run();
        updateOkEnabled.run();

        // Änderungen -> Test wieder ungültig
        name.textProperty().addListener((obs, o, n) -> { invalidateTest.run(); updateOkEnabled.run(); });
        url.textProperty().addListener((obs, o, n) -> { invalidateTest.run(); updateOkEnabled.run(); });
        port.textProperty().addListener((obs, o, n) -> updateOkEnabled.run());

        testBtn.setOnAction(e -> {
            String nm = name.getText().trim();
            String u = url.getText().trim();

            if (nm.isEmpty() || u.isEmpty()) {
                testStatus.setText("Name und URL ausfüllen.");
                testStatus.setUserData(null);
                updateOkEnabled.run();
                return;
            }

            pi.setVisible(true);
            testBtn.setDisable(true);
            testStatus.setText("Teste…");
            testStatus.setUserData(null);
            updateOkEnabled.run();

            Task<Void> t = new Task<>() {
                @Override protected Void call() throws Exception {
                    manifestService.loadAndValidate(u);
                    return null;
                }
            };

            t.setOnSucceeded(ev -> {
                pi.setVisible(false);
                testBtn.setDisable(false);
                testStatus.setText("OK ✅");
                testStatus.setUserData("OK");
                updateOkEnabled.run();
            });

            t.setOnFailed(ev -> {
                pi.setVisible(false);
                testBtn.setDisable(false);
                Throwable ex = t.getException();
                testStatus.setText("Fehler: " + (ex == null ? "unknown" : ex.getMessage()));
                testStatus.setUserData(null);
                updateOkEnabled.run();
            });

            Thread th = new Thread(t, "manifest-test");
            th.setDaemon(true);
            th.start();
        });

        dialog.setResultConverter(bt -> {
            if (!Objects.equals(bt, okBtn)) return null;

            int p;
            try {
                p = Integer.parseInt(port.getText().trim());
            } catch (Exception ex) {
                return null;
            }

            ProfileStore.Profile prof = new ProfileStore.Profile(
                    name.getText().trim(),
                    url.getText().trim(),
                    host.getText().trim(),
                    p,
                    joinMode.getValue()
            );

            return new ProfileDialogResult(prof, oldNameOrNull);
        });

        return dialog.showAndWait().orElse(null);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
