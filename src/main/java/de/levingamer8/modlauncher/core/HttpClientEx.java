package de.levingamer8.modlauncher.core;

import java.nio.file.Path;

/**
 * Multi-protocol fetcher. Despite the name (kept for backwards compatibility),
 * supports HTTP, HTTPS, FTP, FTPS, SFTP, and SMB.
 */
public class HttpClientEx {

    private final ProtocolFetcher fetcher = new ProtocolFetcher();

    public String getText(String url) throws Exception {
        return fetcher.getText(url);
    }

    public void downloadToFile(String url, Path targetTmp) throws Exception {
        fetcher.downloadToFile(url, targetTmp);
    }
}
