/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.api;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import studio.core.v1.Constants;
import studio.driver.StoryTellerException;
import studio.webui.service.ApiErrors;
import studio.webui.service.LibraryService;
import studio.webui.service.ProvenanceVerdict;
import studio.webui.service.PurgeCandidate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class LibraryController {

    private static final Logger LOGGER = LoggerFactory.getLogger(LibraryController.class);
    
    public static Router apiRouter(Vertx vertx, LibraryService libraryService) {
        Router router = Router.router(vertx);

        // Local library device metadata
        router.get("/infos").blockingHandler(ctx -> {
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(Json.encode(libraryService.libraryInfos()));
        });

        // Local library packs list
        router.get("/packs").blockingHandler(ctx -> {
            JsonArray libraryPacks = libraryService.packs();
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(Json.encode(libraryPacks));
        });

        // Local library pack download
        router.post("/download").blockingHandler(ctx -> {
            String uuid = ctx.getBodyAsJson().getString("uuid");
            String packPath = ctx.getBodyAsJson().getString("path");
            libraryService.getRawPackFile(packPath)
                    .ifPresentOrElse(
                            file -> ctx.response()
                                    .putHeader("Content-Length", "" + file.length())
                                    .sendFile(file.getAbsolutePath()),
                            () -> {
                                LOGGER.error("Failed to download pack from library");
                                ctx.fail(500);
                            }
                    );
        });

        // One entry of an archive pack of the library (story.json, thumbnail.png, assets/...). The
        // editor opens a library pack entry by entry, rather than downloading the whole archive and
        // unzipping it in the browser. Unordered: the browser asks for several entries at once, and
        // an ordered blocking handler would serve them one after the other.
        router.post("/entry").blockingHandler(ctx -> {
            String packPath = ctx.getBodyAsJson().getString("path");
            String entryName = ctx.getBodyAsJson().getString("entry");
            Optional<byte[]> entry;
            try {
                entry = libraryService.readArchivePackEntry(packPath, entryName);
            } catch (IOException e) {
                LOGGER.error("Failed to read entry " + entryName + " of library pack " + packPath, e);
                ApiErrors.respond(ctx, new StoryTellerException("Failed to read pack entry", e));
                return;
            }
            entry.ifPresentOrElse(
                    bytes -> ctx.response()
                            .putHeader("content-type", "application/octet-stream")
                            // The same entry name may hold other content once the pack is saved again
                            .putHeader("Cache-Control", "no-store")
                            .end(Buffer.buffer(bytes)),
                    () -> ctx.response().setStatusCode(404).end()
            );
        }, false);

        // Local library pack upload
        router.post("/upload").blockingHandler(ctx -> {
            String uuid = ctx.request().getFormAttribute("uuid");
            String packPath = ctx.request().getFormAttribute("path");
            boolean added = libraryService.addPackFile(packPath, ctx.fileUploads().iterator().next().uploadedFileName());
            if (added) {
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("success", true)));
            } else {
                LOGGER.error("Pack was not added to library");
                ctx.fail(500);
            }
        });

        // Local library pack conversion
        router.post("/convert").blockingHandler(ctx -> {
            String uuid = ctx.getBodyAsJson().getString("uuid");
            String packPath = ctx.getBodyAsJson().getString("path");
            Boolean allowEnriched = ctx.getBodyAsJson().getBoolean("allowEnriched", false);
            String format = ctx.getBodyAsJson().getString("format");
            // The conversion runs right here, on the worker thread the blocking handler already
            // provides. It used to be handed to a new java.util.Timer per request, after a one
            // second delay: each Timer kept a thread alive for the rest of the session, and an
            // exception thrown by the conversion killed that thread before the answer was sent, so
            // the request never completed and the client waited until its own timeout.
            Optional<Path> converted;
            String failureMessage;
            try {
                if (Constants.PACK_FORMAT_RAW.equalsIgnoreCase(format)) {
                    failureMessage = "Failed to read or convert pack to raw format";
                    converted = libraryService.addConvertedRawPackFile(packPath, allowEnriched);
                } else if (Constants.PACK_FORMAT_FS.equalsIgnoreCase(format)) {
                    failureMessage = "Failed to read or convert pack to folder format";
                    converted = libraryService.addConvertedFsPackFile(packPath, allowEnriched);
                } else if (Constants.PACK_FORMAT_ARCHIVE.equalsIgnoreCase(format)) {
                    failureMessage = "Failed to read or convert pack to archive format";
                    converted = libraryService.addConvertedArchivePackFile(packPath);
                } else {
                    ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Unknown pack format: " + format));
                    return;
                }
            } catch (IllegalArgumentException e) {
                // A path outside the library: a malformed request, refused as such.
                LOGGER.error("Refused a conversion request", e);
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, e.getMessage(), e));
                return;
            } catch (RuntimeException e) {
                LOGGER.error("Failed to read or convert pack", e);
                ApiErrors.respond(ctx, e);
                return;
            }
            if (converted.isPresent()) {
                // Return path to converted file within library
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject()
                                .put("success", true)
                                .put("path", converted.get().toString())
                        ));
            } else {
                LOGGER.error("Failed to read or convert pack");
                ApiErrors.respond(ctx, new StoryTellerException(failureMessage));
            }
        });

        // Whether a converted pack still corresponds to the source it was made from. Only ever
        // asked when a pack is dropped on a device, never while listing: it reads both artefacts.
        // The answer is a verdict and nothing else — no digest, no record, no path — because the
        // client has no use for them and no business deciding this for itself.
        router.post("/verify-conversion").blockingHandler(ctx -> {
            String sourcePath = ctx.getBodyAsJson().getString("sourcePath");
            String convertedPath = ctx.getBodyAsJson().getString("convertedPath");
            try {
                ProvenanceVerdict verdict = libraryService.verifyConversion(sourcePath, convertedPath);
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("verdict", verdict.name())));
            } catch (IllegalArgumentException e) {
                // Naming something outside the library is a malformed request, not an uncertainty.
                // Answering UNKNOWN here would quietly accept a question that should be refused.
                LOGGER.error("Refused a conversion verification request", e);
                ctx.fail(400, e);
            }
        });

        // Library entries that serve no purpose any more (see LibraryService.purgeCandidates).
        // Read-only. Reads artefacts in full when provenance or duplicates have to be established.
        router.get("/purge/candidates").blockingHandler(ctx -> {
            try {
                List<PurgeCandidate> candidates = libraryService.purgeCandidates();
                JsonArray list = new JsonArray();
                long total = 0;
                for (PurgeCandidate candidate : candidates) {
                    list.add(candidate.toJson());
                    total += Math.max(candidate.getSize(), 0);
                }
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("candidates", list).put("totalSize", total)));
            } catch (RuntimeException e) {
                LOGGER.error("Failed to list purge candidates", e);
                ApiErrors.respond(ctx, e);
            }
        });

        // Remove purge candidates, named explicitly: {"paths": ["<library entry>", ...]}. Each one is
        // checked again against the candidates as they are now; anything else is left alone.
        router.post("/purge").blockingHandler(ctx -> {
            JsonObject body = DeviceController.jsonBody(ctx);
            if (body == null) {
                return;
            }
            JsonArray paths = body.getValue("paths") instanceof JsonArray ? body.getJsonArray("paths") : null;
            if (paths == null || paths.isEmpty()) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "'paths' must list at least one library entry"));
                return;
            }
            List<String> names = new ArrayList<>();
            for (Object o : paths) {
                if (!(o instanceof String)) {
                    ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "'paths' must only contain library entry names"));
                    return;
                }
                names.add((String) o);
            }
            try {
                List<LibraryService.PurgeResult> results = libraryService.purge(names);
                JsonArray list = new JsonArray();
                long freed = 0;
                for (LibraryService.PurgeResult result : results) {
                    list.add(result.toJson());
                    freed += result.getSize();
                }
                boolean all = results.stream().allMatch(LibraryService.PurgeResult::isRemoved);
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("success", all).put("freed", freed).put("results", list)));
            } catch (IllegalArgumentException e) {
                // A name outside the library: refused before anything is removed.
                LOGGER.error("Refused a purge request", e);
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, e.getMessage(), e));
            } catch (RuntimeException e) {
                LOGGER.error("Failed to purge the library", e);
                ApiErrors.respond(ctx, e);
            }
        });

        // Remove pack from device
        router.post("/remove").blockingHandler(ctx -> {
            String packPath = ctx.getBodyAsJson().getString("path");
            boolean removed = libraryService.deletePack(packPath);
            if (removed) {
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("success", true)));
            } else {
                LOGGER.error("Pack was not removed from library");
                ctx.fail(500);
            }
        });

        return router;
    }
}
