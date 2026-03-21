package de.levingamer8.modlauncher.core;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Protocol-agnostic fetcher supporting HTTP(S), FTP, FTPS, SFTP, and SMB.
 * <p>
 * URL formats:
 * <ul>
 *   <li>http://host/path  or  https://host/path</li>
 *   <li>ftp://user:pass@host:port/path</li>
 *   <li>ftps://user:pass@host:port/path</li>
 *   <li>sftp://user:pass@host:port/path</li>
 *   <li>smb://user:pass@host/share/path</li>
 * </ul>
 */
public class ProtocolFetcher {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public String getText(String url) throws Exception {
        String scheme = schemeOf(url);
        return switch (scheme) {
            case "http", "https" -> httpGetText(url);
            case "ftp" -> ftpGetText(url, false);
            case "ftps" -> ftpGetText(url, true);
            case "sftp" -> sftpGetText(url);
            case "smb" -> smbGetText(url);
            default -> throw new IOException("Unsupported protocol: " + scheme);
        };
    }

    public void downloadToFile(String url, Path target) throws Exception {
        String scheme = schemeOf(url);
        switch (scheme) {
            case "http", "https" -> httpDownload(url, target);
            case "ftp" -> ftpDownload(url, false, target);
            case "ftps" -> ftpDownload(url, true, target);
            case "sftp" -> sftpDownload(url, target);
            case "smb" -> smbDownload(url, target);
            default -> throw new IOException("Unsupported protocol: " + scheme);
        }
    }

    /**
     * Resolves a possibly-relative path against a base URL, respecting the protocol.
     * For HTTP(S), uses URI.resolve(). For other protocols, does path-based resolution.
     */
    public static String resolve(String baseUrl, String maybeRelative) {
        if (maybeRelative == null) return "";
        String s = maybeRelative.trim();
        if (s.isEmpty()) return "";
        // Already absolute
        if (s.contains("://")) return s;
        // For HTTP, use standard URI resolution
        String scheme = schemeOf(baseUrl);
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return URI.create(baseUrl).resolve(s).toString();
        }
        // For other protocols: replace the file part of the base URL
        int lastSlash = baseUrl.lastIndexOf('/');
        if (lastSlash > baseUrl.indexOf("://") + 2) {
            return baseUrl.substring(0, lastSlash + 1) + s;
        }
        return baseUrl + "/" + s;
    }

    /**
     * Checks if the given URL uses a supported protocol scheme.
     */
    public static boolean isSupported(String url) {
        if (url == null || url.isBlank()) return false;
        String scheme = schemeOf(url);
        return switch (scheme) {
            case "http", "https", "ftp", "ftps", "sftp", "smb" -> true;
            default -> false;
        };
    }

    // ========================= HTTP =========================

    private String httpGetText(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    private void httpDownload(String url, Path target) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET().build();
        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        try (var in = resp.body();
             var out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(out);
        }
    }

    // ========================= FTP / FTPS =========================

    private static FTPClient createFtpClient(String url, boolean secure) throws IOException {
        ParsedUrl p = parseUrl(url);
        FTPClient ftp = secure ? new FTPSClient() : new FTPClient();
        ftp.setConnectTimeout(20_000);
        ftp.setDataTimeout(Duration.ofSeconds(30));
        ftp.connect(p.host, p.port > 0 ? p.port : (secure ? 990 : 21));

        String user = p.user != null ? p.user : "anonymous";
        String pass = p.password != null ? p.password : "modlauncher@";
        if (!ftp.login(user, pass)) {
            ftp.disconnect();
            throw new IOException("FTP login failed for " + p.host);
        }
        ftp.enterLocalPassiveMode();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        return ftp;
    }

    private String ftpGetText(String url, boolean secure) throws IOException {
        ParsedUrl p = parseUrl(url);
        FTPClient ftp = createFtpClient(url, secure);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!ftp.retrieveFile(p.path, baos)) {
                throw new IOException("FTP retrieve failed: " + ftp.getReplyString());
            }
            return baos.toString(StandardCharsets.UTF_8);
        } finally {
            ftp.logout();
            ftp.disconnect();
        }
    }

    private void ftpDownload(String url, boolean secure, Path target) throws IOException {
        ParsedUrl p = parseUrl(url);
        FTPClient ftp = createFtpClient(url, secure);
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            if (!ftp.retrieveFile(p.path, out)) {
                throw new IOException("FTP download failed: " + ftp.getReplyString());
            }
        } finally {
            ftp.logout();
            ftp.disconnect();
        }
    }

    // ========================= SFTP =========================

    private String sftpGetText(String url) throws Exception {
        ParsedUrl p = parseUrl(url);
        JSch jsch = new JSch();
        var session = jsch.getSession(
                p.user != null ? p.user : "anonymous",
                p.host,
                p.port > 0 ? p.port : 22
        );
        if (p.password != null) session.setPassword(p.password);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.setTimeout(20_000);
        session.connect();
        try {
            ChannelSftp ch = (ChannelSftp) session.openChannel("sftp");
            ch.connect(10_000);
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ch.get(p.path, baos);
                return baos.toString(StandardCharsets.UTF_8);
            } finally {
                ch.disconnect();
            }
        } finally {
            session.disconnect();
        }
    }

    private void sftpDownload(String url, Path target) throws Exception {
        ParsedUrl p = parseUrl(url);
        JSch jsch = new JSch();
        var session = jsch.getSession(
                p.user != null ? p.user : "anonymous",
                p.host,
                p.port > 0 ? p.port : 22
        );
        if (p.password != null) session.setPassword(p.password);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.setTimeout(20_000);
        session.connect();
        try {
            ChannelSftp ch = (ChannelSftp) session.openChannel("sftp");
            ch.connect(10_000);
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.get(p.path, out);
            } finally {
                ch.disconnect();
            }
        } finally {
            session.disconnect();
        }
    }

    // ========================= SMB (Windows UNC) =========================

    private String smbGetText(String url) throws IOException {
        Path uncPath = smbUrlToUncPath(url);
        return Files.readString(uncPath, StandardCharsets.UTF_8);
    }

    private void smbDownload(String url, Path target) throws IOException {
        Path uncPath = smbUrlToUncPath(url);
        Files.copy(uncPath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Converts smb://host/share/path to \\host\share\path (Windows UNC).
     * Uses Windows' built-in SMB client — handles auth, signing, SMB2/3 automatically.
     */
    private static Path smbUrlToUncPath(String url) throws IOException {
        ParsedUrl p = parseUrl(url);
        if (p.host == null || p.host.isEmpty()) {
            throw new IOException("SMB URL hat keinen Host: " + url);
        }
        if (p.path == null || p.path.equals("/")) {
            throw new IOException("SMB URL braucht mindestens Share + Datei: " + url);
        }
        // URL-Encoding dekodieren (%2B -> +, %20 -> Leerzeichen, etc.)
        String decodedPath = java.net.URLDecoder.decode(p.path, StandardCharsets.UTF_8);
        // \\host\share\path
        String uncPath = "\\\\" + p.host + decodedPath.replace('/', '\\');
        return Path.of(uncPath);
    }

    // ========================= URL Parsing =========================

    static String schemeOf(String url) {
        if (url == null) return "";
        int idx = url.indexOf("://");
        if (idx <= 0) return "";
        return url.substring(0, idx).toLowerCase();
    }

    private record ParsedUrl(String host, int port, String user, String password, String path) {}

    private static ParsedUrl parseUrl(String url) {
        // scheme://[user[:password]@]host[:port]/path
        int schemeEnd = url.indexOf("://");
        String rest = url.substring(schemeEnd + 3);

        String user = null;
        String password = null;

        int atIdx = rest.indexOf('@');
        // Make sure @ is before the first / (part of authority, not path)
        int firstSlash = rest.indexOf('/');
        if (atIdx > 0 && (firstSlash < 0 || atIdx < firstSlash)) {
            String userInfo = rest.substring(0, atIdx);
            rest = rest.substring(atIdx + 1);
            int colonIdx = userInfo.indexOf(':');
            if (colonIdx >= 0) {
                user = decodePercent(userInfo.substring(0, colonIdx));
                password = decodePercent(userInfo.substring(colonIdx + 1));
            } else {
                user = decodePercent(userInfo);
            }
        }

        // host[:port]/path
        firstSlash = rest.indexOf('/');
        String hostPort;
        String path;
        if (firstSlash >= 0) {
            hostPort = rest.substring(0, firstSlash);
            path = rest.substring(firstSlash); // includes leading /
        } else {
            hostPort = rest;
            path = "/";
        }

        String host;
        int port = -1;
        int colonIdx = hostPort.lastIndexOf(':');
        if (colonIdx > 0) {
            try {
                port = Integer.parseInt(hostPort.substring(colonIdx + 1));
                host = hostPort.substring(0, colonIdx);
            } catch (NumberFormatException e) {
                host = hostPort;
            }
        } else {
            host = hostPort;
        }

        return new ParsedUrl(host, port, user, password, path);
    }

    private static String decodePercent(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
