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
import studio.webui.service.TitleVoiceService;
import studio.webui.service.YoutubePackException;
import studio.webui.service.YoutubePackJob;
import studio.webui.service.YoutubePackRequest;
import studio.webui.service.YoutubePackService;

/** Packs made from YouTube links: {@code /api/youtube}. See API.md. */
public class YoutubeController {

    private static final Logger LOGGER = LoggerFactory.getLogger(YoutubeController.class);

    public static Router apiRouter(Vertx vertx, YoutubePackService service) {
        Router router = Router.router(vertx);

        // Whether yt-dlp and ffmpeg are there; probing starts processes, hence blocking
        router.get("/tools").blockingHandler(ctx -> {
            boolean refresh = "true".equalsIgnoreCase(ctx.request().getParam("refresh"));
            json(ctx, 200, service.tools(refresh).toJson()
                    .put("voices", new JsonArray(TitleVoiceService.SUGGESTED_VOICES))
                    .put("defaultVoice", TitleVoiceService.defaultVoice())
                    .put("bitrates", new JsonArray().add(48).add(64).add(96).add(128))
                    .put("defaultBitrate", YoutubePackRequest.DEFAULT_BITRATE)
                    .put("defaultMenuSize", YoutubePackRequest.DEFAULT_MENU_SIZE)
                    .put("defaultMaxVideos", YoutubePackRequest.DEFAULT_MAX_VIDEOS)
                    .put("maxLinks", YoutubePackRequest.MAX_LINKS));
        }, false);

        // Start a creation
        router.post("/jobs").blockingHandler(ctx -> handle(ctx, () -> {
            JsonObject body;
            try {
                body = ctx.getBodyAsJson();
            } catch (DecodeException | ClassCastException e) {
                throw new YoutubePackException(YoutubePackException.Code.INVALID_REQUEST, "Le corps de la requ\u00eate n'est pas un objet JSON");
            }
            YoutubePackJob job = service.start(YoutubePackRequest.fromJson(body));
            json(ctx, 202, job.toJson());
        }), false);

        // The running creation, or the last one: the dialog finds it again after being closed.
        // Blocking handlers: the service lock may be held while the tools are probed (processes),
        // which must never stall the event loop.
        router.get("/jobs/current").blockingHandler(ctx -> handle(ctx, () -> json(ctx, 200,
                service.current().orElseThrow(() -> new YoutubePackException(YoutubePackException.Code.NOT_FOUND,
                        "Aucune cr\u00e9ation en cours")).toJson())), false);

        router.get("/jobs/:id").blockingHandler(ctx -> handle(ctx, () -> {
            String id = ctx.pathParam("id");
            json(ctx, 200, service.job(id).orElseThrow(() -> new YoutubePackException(YoutubePackException.Code.NOT_FOUND,
                    "Cr\u00e9ation inconnue : " + id)).toJson());
        }), false);

        router.post("/jobs/:id/cancel").blockingHandler(ctx -> handle(ctx, () -> json(ctx, 200, service.cancel(ctx.pathParam("id")).toJson())), false);

        return router;
    }

    private static void handle(RoutingContext ctx, Runnable action) {
        try {
            action.run();
        } catch (YoutubePackException e) {
            LOGGER.warn("YouTube pack request refused: " + e.getMessage());
            json(ctx, e.getCode().status, new JsonObject()
                    .put("success", false)
                    .put("error", e.getCode().name())
                    .put("message", e.getMessage()));
        }
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
