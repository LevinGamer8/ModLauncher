package de.levingamer8.modlauncher.ui;

import atlantafx.base.theme.PrimerDark;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        FXMLLoader loader = new FXMLLoader(App.class.getResource("/de/levingamer8/modlauncher/ui/app.fxml"));
        Scene scene = new Scene(loader.load(), 980, 640);

        // dein Custom Theme
        scene.getStylesheets().add(
                App.class.getResource("/de/levingamer8/modlauncher/ui/theme.css").toExternalForm()
        );

        stage.setTitle("ModLauncher");
        stage.setScene(scene);
        stage.show();
    }
}
