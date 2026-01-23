package de.levingamer8.modlauncher.ui.dialogs;

import de.levingamer8.modlauncher.ui.App;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.io.IOException;
import java.util.ResourceBundle;

public final class I18n {
    private static final String BUNDLE_BASE = "i18n.messages";

    private I18n() {}

    public static ResourceBundle getBundle() {
        return ResourceBundle.getBundle(BUNDLE_BASE, LauncherSettings.getLocale());
    }

    public static void reload(Scene scene) {
        if (scene == null) return;
        try {
            FXMLLoader loader = new FXMLLoader(
                    App.class.getResource("/de/levingamer8/modlauncher/ui/app.fxml"),
                    getBundle()
            );
            Parent root = loader.load();
            scene.setRoot(root);
        } catch (IOException e) {
            throw new RuntimeException("Failed to reload UI", e);
        }
    }
}
