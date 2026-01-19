package de.levingamer8.modlauncher.service;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.util.Duration;

import java.util.concurrent.ConcurrentLinkedQueue;

public class LogService {

    private static final int MAX_CHARS = 30_000;

    private final TextArea area;
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private final Timeline timeline;

    public LogService(TextArea area) {
        this.area = area;
        this.timeline = new Timeline(
                new KeyFrame(Duration.millis(100), e -> flush())
        );
        timeline.setCycleCount(Timeline.INDEFINITE);
    }

    public void start() {
        timeline.play();
    }

    public void stop() {
        timeline.stop();
    }

    public void info(String msg) {
        queue.add(msg + "\n");
    }

    private void flush() {
        if (queue.isEmpty())
            return;

        Platform.runLater(() -> {
            StringBuilder sb = new StringBuilder();
            while (!queue.isEmpty())
                sb.append(queue.poll());

            area.appendText(sb.toString());

            if (area.getText().length() > MAX_CHARS) {
                area.setText(
                        area.getText().substring(area.getText().length() - MAX_CHARS)
                );
            }
        });
    }
}
