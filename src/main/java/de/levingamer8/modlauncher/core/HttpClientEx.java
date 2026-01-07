package de.levingamer8.modlauncher.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class HttpClientEx {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public String getText(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET().build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    public void downloadToFile(String url, java.nio.file.Path targetTmp) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET().build();

        HttpResponse<java.io.InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }

        try (var in = resp.body();
             var out = java.nio.file.Files.newOutputStream(targetTmp,
                     java.nio.file.StandardOpenOption.CREATE,
                     java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(out);
        }
    }
}
