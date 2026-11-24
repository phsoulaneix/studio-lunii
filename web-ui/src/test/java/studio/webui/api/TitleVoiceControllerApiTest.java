/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.api;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.webui.service.TitleVoiceService;

import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /api/tts} as the editor sees it, with stand-in engines. */
class TitleVoiceControllerApiTest {

    @TempDir
    Path tmp;

    private Vertx vertx;
    private HttpServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private boolean edgeInstalled = true;

    @BeforeEach
    void start() throws Exception {
        TitleVoiceService.CommandRunner runner = (command, env, timeout) -> {
            if (command.contains("-c")) {
                return edgeInstalled ? 0 : 1;
            }
            Files.write(Paths.get(command.get(command.indexOf("--write-media") + 1)),
                    studio.webui.service.TitleVoiceServiceTest.wave(24000, 1));
            return 0;
        };
        TitleVoiceService service = new TitleVoiceService(runner, List.of("python"), null, tmp, Duration.ofSeconds(5));
        vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create().setBodyLimit(1024 * 1024));
        router.mountSubRouter("/api/tts", TitleVoiceController.apiRouter(vertx, service));
        CompletableFuture<HttpServer> started = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1", ar -> {
            if (ar.succeeded()) {
                started.complete(ar.result());
            } else {
                started.completeExceptionally(ar.cause());
            }
        });
        server = started.get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close(ar -> closed.complete(null));
        closed.get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.actualPort() + "/api/tts/speak"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a title is answered as a base64 device WAVE")
    void speak() throws Exception {
        HttpResponse<String> response = post(new JsonObject().put("text", "Le petit chaperon rouge").encode());
        assertEquals(200, response.statusCode());
        JsonObject body = new JsonObject(response.body());
        assertTrue(body.getBoolean("success"));
        assertEquals("edge", body.getString("engine"));
        assertEquals(TitleVoiceService.DEFAULT_VOICE, body.getString("voice"));
        assertEquals("audio/wav", body.getString("mimeType"));
        byte[] wave = Base64.getDecoder().decode(body.getString("data"));
        assertEquals(32000f, AudioSystem.getAudioInputStream(new ByteArrayInputStream(wave)).getFormat().getSampleRate());
        assertTrue(body.getLong("durationMillis") > 1000);
    }

    @Test
    @DisplayName("bad requests are 400 INVALID_REQUEST with a message")
    void invalid() throws Exception {
        for (String body : new String[]{
                "{}", "{\"text\": \"  \"}", "{\"text\": 12}", "[1]", "not json",
                new JsonObject().put("text", "x".repeat(TitleVoiceService.MAX_TEXT_LENGTH + 1)).encode(),
                "{\"text\": \"Bonjour\", \"voice\": \"--rate=+100%\"}"}) {
            HttpResponse<String> response = post(body);
            assertEquals(400, response.statusCode(), body);
            JsonObject json = new JsonObject(response.body());
            assertFalse(json.getBoolean("success"));
            assertEquals("INVALID_REQUEST", json.getString("error"));
            assertFalse(json.getString("message").isBlank());
        }
    }

    @Test
    @DisplayName("no engine installed is 503 TTS_UNAVAILABLE, and /engines says so")
    void unavailable() throws Exception {
        edgeInstalled = false;
        HttpResponse<String> response = post("{\"text\": \"Bonjour\"}");
        assertEquals(503, response.statusCode());
        assertEquals("TTS_UNAVAILABLE", new JsonObject(response.body()).getString("error"));

        JsonObject engines = new JsonObject(http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.actualPort() + "/api/tts/engines")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertFalse(engines.getBoolean("edge"));
        assertFalse(engines.getBoolean("system"));
        assertTrue(engines.getJsonArray("voices").contains(TitleVoiceService.DEFAULT_VOICE));
    }
}
