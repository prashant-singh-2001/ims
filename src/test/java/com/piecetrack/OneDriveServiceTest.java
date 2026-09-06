package com.piecetrack;

import com.google.gson.JsonObject;
import com.piecetrack.service.OneDriveService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneDriveServiceTest {

    private HttpServer server;
    private String serverUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.start();
        serverUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postForm_throwsIOException_whenResponseIsHtml502() {
        setMockResponse(502, "text/html", "<html><body>502 Bad Gateway</body></html>");

        IOException ex = assertThrows(IOException.class, () -> invokePostForm(serverUrl, "grant_type=test"));

        assertEquals("Microsoft identity platform returned an unreadable (non-JSON) response — HTTP 502", ex.getMessage());
    }

    @Test
    void postForm_throwsIOException_whenResponseIsEmpty500() {
        setMockResponse(500, "text/plain", "");

        IOException ex = assertThrows(IOException.class, () -> invokePostForm(serverUrl, "grant_type=test"));

        assertEquals("Microsoft identity platform returned an unreadable (non-JSON) response — HTTP 500", ex.getMessage());
    }

    @Test
    void postForm_throwsIOException_whenResponseIsInvalidJson200() {
        setMockResponse(200, "application/json", "not a valid json payload");

        IOException ex = assertThrows(IOException.class, () -> invokePostForm(serverUrl, "grant_type=test"));

        assertEquals("Microsoft identity platform returned an unreadable (non-JSON) response — HTTP 200", ex.getMessage());
    }

    @Test
    void postForm_returnsJsonObject_whenResponseIsValidOAuthErrorJson400() throws Exception {
        String errorPayload = "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS700084: The refresh token has expired.\"}";
        setMockResponse(400, "application/json;charset=utf-8", errorPayload);

        JsonObject result = invokePostForm(serverUrl, "grant_type=test");

        assertNotNull(result);
        assertTrue(result.has("error"));
        assertEquals("invalid_grant", result.get("error").getAsString());
        assertEquals("AADSTS700084: The refresh token has expired.", result.get("error_description").getAsString());
    }

    @Test
    void postForm_returnsJsonObject_whenResponseIsValidOAuthSuccessJson200() throws Exception {
        String successPayload = "{\"access_token\":\"fake-access\",\"refresh_token\":\"fake-refresh\"}";
        setMockResponse(200, "application/json;charset=utf-8", successPayload);

        JsonObject result = invokePostForm(serverUrl, "grant_type=test");

        assertNotNull(result);
        assertEquals("fake-access", result.get("access_token").getAsString());
        assertEquals("fake-refresh", result.get("refresh_token").getAsString());
    }

    private void setMockResponse(int statusCode, String contentType, String responseBody) {
        server.createContext("/token", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    /**
     * Follows the established repository pattern (e.g. SceneRouterShellTest, SrsAcceptanceTest)
     * of invoking private methods via reflection rather than expanding production visibility solely for testing.
     */
    private static JsonObject invokePostForm(String url, String formBody) throws Exception {
        Method method = OneDriveService.class.getDeclaredMethod("postForm", String.class, String.class);
        method.setAccessible(true);
        try {
            return (JsonObject) method.invoke(null, url, formBody);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }
}
