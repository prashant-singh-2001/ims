package com.furnitureims.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * FR-BAK-07/08: OneDrive access via Microsoft's OAuth 2.0 authorization code + PKCE flow,
 * scoped to {@code Files.ReadWrite.AppFolder} - the Graph equivalent of {@link
 * GoogleDriveService}'s {@code drive.file} grant, so this app can only ever see its own
 * app folder ({@code /me/drive/special/approot}), never the rest of the owner's OneDrive.
 * <p>
 * Unlike Google, desktop apps are a Microsoft "public client": no client secret exists to
 * protect, so a single Azure app registration owned by this project ships inside the app
 * ({@link #DEFAULT_CLIENT_ID}) and every shop owner just signs into their own Microsoft
 * account - this is what lets OneDrive skip the per-shop Cloud-Console-style setup Google
 * requires. {@code settings.onedrive_client_id} (see {@link SettingsService#oneDriveClientId})
 * can override it for anyone who registers their own app instead.
 * <p>
 * Built entirely on the JDK ({@link HttpClient}, {@link HttpServer}, {@link MessageDigest})
 * plus Gson (already on the compile classpath transitively via {@code google-http-client-gson},
 * pulled in for {@link GoogleDriveService}) - deliberately not MSAL4J or the Graph SDK, which
 * would pull Nimbus JOSE+JWT, Kiota and Reactor into the packaged runtime for four REST calls
 * this class makes directly.
 * <p>
 * The refresh token is the only thing persisted (via DPAPI, see {@link
 * SettingsService#setOneDriveRefreshToken}); every access token is exchanged fresh from it
 * per operation, exactly as {@link GoogleDriveService} rebuilds its client each call.
 */
@Service
public class OneDriveService implements CloudBackupProvider {

    // TODO(project owner): replace with the real Azure app registration client ID (App
    // registrations > New registration > "Mobile and desktop applications" > redirect URI
    // http://localhost > allow public client flows > API permissions: Files.ReadWrite.AppFolder
    // + offline_access, delegated) before OneDrive support can be used.
    private static final String DEFAULT_CLIENT_ID = "00000000-0000-0000-0000-000000000000";

    private static final String AUTHORIZE_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/token";
    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final String SCOPES = "Files.ReadWrite.AppFolder offline_access";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final SettingsService settingsService;
    private final AuditLogService auditLogService;

    public OneDriveService(SettingsService settingsService, AuditLogService auditLogService) {
        this.settingsService = settingsService;
        this.auditLogService = auditLogService;
    }

    @Override
    public String id() {
        return "ONEDRIVE";
    }

    @Override
    public String displayName() {
        return "OneDrive";
    }

    @Override
    public boolean isConnected() {
        return settingsService.isOneDriveConnected();
    }

    /** Opens the browser once for consent and stores the resulting refresh token. Blocks the
     *  calling thread until the owner finishes in the browser (or the flow fails/times out) -
     *  the UI runs this off the JavaFX Application Thread. */
    @Override
    public void connect() throws IOException, InterruptedException {
        String clientId = effectiveClientId();
        String codeVerifier = randomUrlSafeToken(64);
        String codeChallenge = sha256Base64Url(codeVerifier);
        String state = randomUrlSafeToken(16);

        CompletableFuture<String> authorizationCode = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handleLoopbackCallback(exchange, state, authorizationCode));
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            String redirectUri = "http://localhost:" + port + "/";

            String authorizeUrl = AUTHORIZE_URL
                    + "?client_id=" + urlEncode(clientId)
                    + "&response_type=code"
                    + "&redirect_uri=" + urlEncode(redirectUri)
                    + "&response_mode=query"
                    + "&scope=" + urlEncode(SCOPES)
                    + "&state=" + urlEncode(state)
                    + "&code_challenge=" + urlEncode(codeChallenge)
                    + "&code_challenge_method=S256";
            Desktop.getDesktop().browse(URI.create(authorizeUrl));

            String code;
            try {
                code = authorizationCode.get(5, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                throw new IllegalStateException("Timed out waiting for Microsoft sign-in - try Connect again.");
            } catch (ExecutionException e) {
                throw new IllegalStateException(
                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), e);
            }

            String form = "client_id=" + urlEncode(clientId)
                    + "&scope=" + urlEncode(SCOPES)
                    + "&code=" + urlEncode(code)
                    + "&redirect_uri=" + urlEncode(redirectUri)
                    + "&grant_type=authorization_code"
                    + "&code_verifier=" + urlEncode(codeVerifier);
            JsonObject token = postForm(TOKEN_URL, form);
            if (!token.has("refresh_token")) {
                throw new IllegalStateException("Microsoft did not return a long-lived grant - "
                        + describeError(token));
            }
            settingsService.setOneDriveRefreshToken(token.get("refresh_token").getAsString());
            auditLogService.record("SETTING_CHANGED", "ONEDRIVE", null, "Connected to OneDrive");
        } finally {
            server.stop(0);
        }
    }

    @Override
    public void disconnect() {
        settingsService.clearOneDriveRefreshToken();
        auditLogService.record("SETTING_CHANGED", "ONEDRIVE", null, "Disconnected from OneDrive");
    }

    @Override
    public UploadedFile upload(Path localFile, String remoteName) throws IOException, InterruptedException {
        String accessToken = freshAccessToken();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GRAPH_BASE + "/me/drive/special/approot:/"
                        + urlEncodePathSegment(remoteName) + ":/content"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofFile(localFile))
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject body = requireSuccess(response, "upload");
        return new UploadedFile(body.get("id").getAsString(), body.get("name").getAsString(),
                body.has("size") ? body.get("size").getAsLong() : 0L);
    }

    @Override
    public void download(String fileId, Path targetFile) throws IOException, InterruptedException {
        String accessToken = freshAccessToken();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GRAPH_BASE + "/me/drive/items/" + urlEncodePathSegment(fileId) + "/content"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<Path> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(targetFile));
        if (response.statusCode() >= 400) {
            throw new IOException(
                    "Could not download the archive from OneDrive (HTTP " + response.statusCode() + ")");
        }
    }

    @Override
    public void delete(String fileId) throws IOException, InterruptedException {
        String accessToken = freshAccessToken();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GRAPH_BASE + "/me/drive/items/" + urlEncodePathSegment(fileId)))
                .header("Authorization", "Bearer " + accessToken)
                .DELETE()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400 && response.statusCode() != 404) {
            throw new IOException("Could not delete the archive from OneDrive (HTTP " + response.statusCode() + ")");
        }
    }

    /** Exchanges the stored refresh token for a fresh access token. Microsoft may rotate the
     *  refresh token on any exchange, so a returned one is persisted immediately - otherwise
     *  behaves exactly like {@link GoogleDriveService#upload}'s per-call credential rebuild:
     *  nothing long-lived is cached in this class. */
    private String freshAccessToken() throws IOException, InterruptedException {
        String clientId = effectiveClientId();
        String refreshToken = settingsService.oneDriveRefreshToken().orElseThrow(() ->
                new IllegalStateException("OneDrive is not connected - connect it under Settings > Backup."));

        String form = "client_id=" + urlEncode(clientId)
                + "&scope=" + urlEncode(SCOPES)
                + "&refresh_token=" + urlEncode(refreshToken)
                + "&grant_type=refresh_token";
        JsonObject token = postForm(TOKEN_URL, form);
        if (!token.has("access_token")) {
            throw new IllegalStateException(
                    "OneDrive access has expired or was revoked - reconnect it under Settings > Backup.");
        }
        if (token.has("refresh_token")) {
            settingsService.setOneDriveRefreshToken(token.get("refresh_token").getAsString());
        }
        return token.get("access_token").getAsString();
    }

    private String effectiveClientId() {
        return settingsService.oneDriveClientId(DEFAULT_CLIENT_ID);
    }

    private static JsonObject postForm(String url, String formBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (response.statusCode() >= 400 && !json.has("error")) {
            throw new IOException("Microsoft identity platform returned HTTP " + response.statusCode());
        }
        return json;
    }

    private static JsonObject requireSuccess(HttpResponse<String> response, String operation) throws IOException {
        if (response.statusCode() >= 400) {
            throw new IOException("OneDrive " + operation + " failed (HTTP " + response.statusCode() + "): "
                    + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String describeError(JsonObject token) {
        if (token.has("error_description")) {
            return token.get("error_description").getAsString();
        }
        return token.has("error") ? token.get("error").getAsString() : "unknown error";
    }

    /** Handles the loopback redirect Microsoft sends the system browser back to, extracts
     *  {@code code}, and shows a plain confirmation page - mirrors what Google's {@code
     *  LocalServerReceiver} does for the same purpose. */
    private static void handleLoopbackCallback(HttpExchange exchange, String expectedState,
                                                CompletableFuture<String> authorizationCode) throws IOException {
        try {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String html;
            if (params.containsKey("error")) {
                authorizationCode.completeExceptionally(new IllegalStateException(
                        params.getOrDefault("error_description", params.get("error"))));
                html = "<html><body>Sign-in was not completed. You may close this window.</body></html>";
            } else if (!expectedState.equals(params.get("state"))) {
                authorizationCode.completeExceptionally(
                        new IllegalStateException("OneDrive sign-in state did not match - try Connect again."));
                html = "<html><body>Sign-in could not be verified. You may close this window.</body></html>";
            } else {
                authorizationCode.complete(params.get("code"));
                html = "<html><body>Signed in. You may close this window and return to the app.</body></html>";
            }
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> parseQuery(String query) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            params.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String randomUrlSafeToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Base64Url(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Graph's {@code approot:/{name}:/content} path syntax needs the archive name encoded as
     *  a path segment (spaces as {@code %20}), not as a query parameter (spaces as {@code +}). */
    private static String urlEncodePathSegment(String value) {
        return urlEncode(value).replace("+", "%20");
    }
}
