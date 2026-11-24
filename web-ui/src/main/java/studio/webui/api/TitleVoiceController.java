/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.api;

import io.vertx.core.Vertx;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import studio.webui.service.TitleVoiceException;
import studio.webui.service.TitleVoiceService;

import java.util.Base64;

/** Speech for node titles in the editor: {@code /api/tts}. See API.md. */
public class TitleVoiceController {

    private static final Logger LOGGER = LoggerFactory.getLogger(TitleVoiceController.class);

    public static Router apiRouter(Vertx vertx, TitleVoiceService service) {
        Router router = Router.router(vertx);

        // Which engines can be used, and the default settings
        router.get("/engines").blockingHandler(ctx -> json(ctx, 200, new JsonObject()
                .put("edge", service.isEdgeAvailable())
                .put("system", service.isSystemAvailable())
                .put("defaultEngine", TitleVoiceService.defaultEngine())
                .put("defaultVoice", TitleVoiceService.defaultVoice())
                .put("voices", new JsonArray(TitleVoiceService.SUGGESTED_VOICES))
                .put("maxLength", TitleVoiceService.MAX_TEXT_LENGTH)), false);

        // Speak a title: {"text": "\u2026", "engine": "auto|edge|system", "voice": "fr-FR-DeniseNeural"}
        router.post("/speak").blockingHandler(ctx -> {
            try {
                JsonObject body;
                try {
                    body = ctx.getBodyAsJson();
                } catch (DecodeException | ClassCastException e) {
                    throw new TitleVoiceException(TitleVoiceException.Code.INVALID_REQUEST, "Le corps de la requ\u00eate n'est pas un objet JSON");
                }
                if (body == null) {
                    throw new TitleVoiceException(TitleVoiceException.Code.INVALID_REQUEST, "Le corps de la requ\u00eate est vide");
                }
                Object text = body.getValue("text");
                Object engine = body.getValue("engine");
                Object voice = body.getValue("voice");
                if ((text != null && !(text instanceof String)) || (engine != null && !(engine instanceof String))
                        || (voice != null && !(voice instanceof String))) {
                    throw new TitleVoiceException(TitleVoiceException.Code.INVALID_REQUEST, "text, engine et voice doivent \u00eatre des cha\u00eenes");
                }
                TitleVoiceService.Speech speech = service.speak((String) text, (String) engine, (String) voice);
                JsonObject answer = new JsonObject()
                        .put("success", true)
                        .put("engine", speech.engine)
                        .put("voice", speech.voice)
                        .put("mimeType", "audio/wav")
                        .put("durationMillis", TitleVoiceService.durationMillis(speech.wave))
                        .put("data", Base64.getEncoder().encodeToString(speech.wave));
                if (speech.fallbackReason != null) {
                    answer.put("fallbackReason", speech.fallbackReason);
                }
                json(ctx, 200, answer);
            } catch (TitleVoiceException e) {
                LOGGER.warn("Title speech refused or failed: " + e.getMessage());
                json(ctx, e.getCode().status, new JsonObject()
                        .put("success", false)
                        .put("error", e.getCode().name())
                        .put("message", e.getMessage()));
            }
        }, false);

        return router;
    }

    private static void json(RoutingContext ctx, int status, JsonObject body) {
        if (ctx.response().ended()) {
            return;
        }
        ctx.response()
                .setStatusCode(status)
                .putHeader("content-type", "application/json")
                .end(Json.encode(body));
    }
}
