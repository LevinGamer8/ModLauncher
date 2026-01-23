package de.levingamer8.modlauncher.ui;

import atlantafx.base.theme.PrimerDark;
import de.levingamer8.modlauncher.ui.dialogs.I18n;
import de.levingamer8.modlauncher.ui.dialogs.LauncherSettings;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;

public class App extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        Locale.setDefault(LauncherSettings.getLocale());
        ResourceBundle bundle = ResourceBundle.getBundle("i18n.messages", Locale.getDefault());
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        FXMLLoader loader = new FXMLLoader(
                App.class.getResource("/de/levingamer8/modlauncher/ui/app.fxml"),
                bundle
        );

        Scene scene = new Scene(loader.load(), 1400, 900);

        scene.getStylesheets().add(
                Objects.requireNonNull(App.class.getResource("/de/levingamer8/modlauncher/ui/theme.css")).toExternalForm()
        );

        stage.getIcons().add(
                new javafx.scene.image.Image(
                        Objects.requireNonNull(
                                App.class.getResourceAsStream("/icon.ico")
                        )
                )
        );

        stage.setTitle(I18n.getBundle().getString("app.title"));
        stage.setScene(scene);
        stage.setMinWidth(1200);
        stage.setMinHeight(780);
        stage.centerOnScreen();
        stage.show();
    }
}
