/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.model.StoryPack;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole creation with stand-ins for yt-dlp, ffmpeg, SponsorBlock and the voice: no network. */
class YoutubePackServiceTest {

    @TempDir
    Path tmp;

    private Path libraryDir;
    private final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, JsonObject> metadata = new HashMap<>();
    private final Set<String> failing = new HashSet<>();
    private final Set<String> blocking = new HashSet<>();
    private final Map<String, List<double[]>> segments = new HashMap<>();
    private boolean ytDlpInstalled = true;
    private boolean ffmpegInstalled = true;
    private boolean voiceWorks = true;
    private int[] voiceCalls = {0};
    private YoutubePackService.Sleeper sleeper = (ms, cancelled) -> { };

    @BeforeEach
    void setUp() throws IOException {
        libraryDir = Files.createDirectories(tmp.resolve("library"));
    }

    /** Plays yt-dlp and ffmpeg from their arguments. */
    private int fakeRun(List<String> cmd, YoutubePackService.LineSink sink, Duration timeout, java.util.function.BooleanSupplier cancelled) throws IOException {
        commands.add(cmd);
        if (cmd.contains("-c")) {
            sink.line("2026.11.23", false);
            return ytDlpInstalled ? 0 : 1;
        }
        if (cmd.get(0).equals("yt-dlp")) {
            return 1;
        }
        if (cmd.get(0).equals("ffmpeg") && cmd.contains("-version")) {
            return ffmpegInstalled ? 0 : 1;
        }
        String url = cmd.get(cmd.size() - 1);
        if (cmd.contains("-J")) {
            JsonObject info = metadata.get(url);
            if (info == null) {
                sink.line("ERROR: [youtube] xxxxxxxxxxx: Video unavailable", true);
                sink.line("null", false);
                return 1;
            }
            sink.line(info.encode(), false);
            return 0;
        }
        if (cmd.contains("--write-thumbnail")) {
            String id = url.substring(url.indexOf("v=") + 2);
            if (blocking.contains(id)) {
                sink.line("ERROR: [youtube] " + id + ": Sign in to confirm you\u2019re not a bot", true);
                return 1;
            }
            if (failing.contains(id)) {
                sink.line("ERROR: [youtube] " + id + ": Private video", true);
                return 1;
            }
            Path out = Paths.get(cmd.get(cmd.indexOf("-o") + 1)).getParent();
            sink.line("[download]  50.0% of 1.00MiB", false);
            Files.write(out.resolve("media.webm"), ("audio " + id).getBytes(StandardCharsets.UTF_8));
            ImageIO.write(YoutubePackBuilderTest.picture(640, 360, Color.BLUE), "jpg", out.resolve("media.jpg").toFile());
            sink.line("[download] 100.0% of 1.00MiB", false);
            return 0;
        }
        if (cmd.get(0).equals("ffmpeg")) {
            String in = cmd.get(cmd.indexOf("-i") + 1);
            Files.write(Paths.get(url), ("mp3 of " + Files.readString(Paths.get(in))).getBytes(StandardCharsets.UTF_8));
            return 0;
        }
        throw new IOException("unexpected command " + cmd);
    }

    private YoutubePackService service() {
        YoutubePackService s = new YoutubePackService(this::fakeRun, List.of("python"),
                id -> segments.getOrDefault(id, List.of()),
                (text, engine, voice) -> {
                    voiceCalls[0]++;
                    if (!voiceWorks) {
                        throw new TitleVoiceException(TitleVoiceException.Code.TTS_UNAVAILABLE, "aucune voix");
                    }
                    try {
                        return TitleVoiceServiceTest.wave(32000, 1);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                },
                new YoutubePackService.Library() {
                    @Override
                    public Path directory() {
                        return libraryDir;
                    }

                    @Override
                    public boolean add(String name, Path file) {
                        try {
                            Files.move(file, libraryDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                            return true;
                        } catch (IOException e) {
                            return false;
                        }
                    }
                },
                tmp.resolve("work"), (ms, cancelled) -> sleeper.pause(ms, cancelled), List.of());
        s.pauseMinMillis = 1;
        s.pauseMaxMillis = 2;
        return s;
    }

    private static JsonObject entry(String id, String title, int duration) {
        return new JsonObject().put("id", id).put("title", title).put("duration", duration).put("channel", "Dalton TV");
    }

    private void playlist(String url, String title, JsonObject... entries) {
        metadata.put(url, new JsonObject().put("_type", "playlist").put("title", title).put("entries", new JsonArray(List.of((Object[]) entries))));
    }

    private static YoutubePackJob await(YoutubePackJob job) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (job.isRunning() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertFalse(job.isRunning(), "job still running: " + job.toJson().encodePrettily());
        return job;
    }

    private static YoutubePackRequest request(JsonObject body) {
        return YoutubePackRequest.fromJson(body);
    }

    private static final String PLAYLIST = "https://www.youtube.com/playlist?list=PL123";

    @Test
    void aPlaylistBecomesAPackInTheLibrary() throws Exception {
        playlist(PLAYLIST, "Les Dalton",
                entry("aaaaaaaaaa1", "Les Dalton - Le toutou (S01E02) | Dalton TV", 600),
                entry("aaaaaaaaaa2", "Les Dalton - Le magot (S01E01) [HD]", 610),
                entry("aaaaaaaaaa3", "Les Dalton - Ma Dalton (S02E01)", 620),
                entry("aaaaaaaaaa4", "Les Dalton - Bande-annonce", 20));
        segments.put("aaaaaaaaaa2", List.of(new double[]{0, 12.5}, new double[]{590, 610}));
        YoutubePackService service = service();
        YoutubePackJob job = await(service.start(request(new JsonObject()
                .put("urls", PLAYLIST + "\n").put("minDuration", 60).put("pauses", false))));

        JsonObject state = job.toJson();
        assertEquals("DONE", state.getString("state"), state.encodePrettily());
        JsonObject result = state.getJsonObject("result");
        assertEquals("Les Dalton.zip", result.getString("path"));
        assertEquals("Les Dalton", result.getString("title"));
        assertEquals(3, result.getInteger("stories"));
        assertEquals(1.0, state.getDouble("progress"));

        Path pack = libraryDir.resolve("Les Dalton.zip");
        StoryPack read;
        try (InputStream in = Files.newInputStream(pack)) {
            read = new ArchiveStoryPackReader().read(in);
        }
        assertEquals(7, read.getStageNodes().size());
        JsonObject story;
        try (ZipFile z = new ZipFile(pack.toFile()); InputStream in = z.getInputStream(z.getEntry("story.json"))) {
            story = new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        // Cleaned, prefix removed, sorted by season and episode; the short video is filtered out
        List<String> titles = story.getJsonArray("stageNodes").stream().map(o -> ((JsonObject) o).getString("name"))
                .filter(n -> !n.startsWith("Histoire - ") && !n.equals("Couverture")).collect(Collectors.toList());
        assertEquals(List.of("Le magot", "Le toutou", "Ma Dalton"), titles);

        // SponsorBlock: credits cut out of the second video only
        List<List<String>> conversions = commands.stream().filter(c -> c.get(0).equals("ffmpeg") && c.contains("-i")).collect(Collectors.toList());
        assertEquals(3, conversions.size());
        String filters = conversions.get(0).get(conversions.get(0).indexOf("-af") + 1);
        assertEquals("aselect='between(t,12.500,590.000)',asetpts=N/SR/TB,loudnorm=I=-16:TP=-1:LRA=11", filters);
        assertEquals("loudnorm=I=-16:TP=-1:LRA=11", conversions.get(1).get(conversions.get(1).indexOf("-af") + 1));
        assertTrue(conversions.get(0).contains("96k"));

        // Arguments: no shell, the link after "--", the language asked for
        List<String> meta = commands.stream().filter(c -> c.contains("-J")).findFirst().get();
        assertEquals(List.of("python", "-I", "-m", "yt_dlp"), meta.subList(0, 4));
        assertEquals("--", meta.get(meta.size() - 2));
        assertTrue(meta.contains("youtube:lang=fr"));
        assertTrue(state.getJsonArray("log").encode().contains("rangement par saison"));

        // A second pack of the same name does not replace the first one
        YoutubePackJob again = await(service.start(request(new JsonObject().put("urls", new JsonArray().add(PLAYLIST)).put("pauses", false))));
        assertEquals("Les Dalton (2).zip", again.toJson().getJsonObject("result").getString("path"));
    }

    @Test
    void videosLinkedOneByOneMakeOnePack() throws Exception {
        metadata.put("https://www.youtube.com/watch?v=bbbbbbbbbb1", entry("bbbbbbbbbb1", "Conte du soir - La lune", 300));
        metadata.put("https://youtu.be/bbbbbbbbbb2", entry("bbbbbbbbbb2", "Conte du soir - Le soleil", 300));
        failing.add("bbbbbbbbbb2");
        List<Long> pauses = new ArrayList<>();
        sleeper = (ms, cancelled) -> pauses.add(ms);
        YoutubePackJob job = await(service().start(request(new JsonObject()
                .put("urls", new JsonArray().add("https://www.youtube.com/watch?v=bbbbbbbbbb1").add("youtu.be/bbbbbbbbbb2"))
                .put("title", "Mes contes").put("sponsorBlock", false).put("bitrate", 64))));
        JsonObject state = job.toJson();
        assertEquals("DONE", state.getString("state"), state.encodePrettily());
        assertEquals(1, state.getJsonObject("result").getInteger("stories"));
        assertEquals(1, state.getJsonObject("result").getInteger("skipped"));
        assertTrue(state.getInteger("warnings") >= 1);
        assertTrue(state.getJsonArray("log").encode().contains("Private video"));
        assertEquals(1, pauses.size());
        assertTrue(Files.isRegularFile(libraryDir.resolve("Mes contes.zip")));
        assertTrue(commands.stream().anyMatch(c -> c.contains("64k")));
    }

    @Test
    void stopsDownloadingWhenYoutubeBlocks() throws Exception {
        playlist(PLAYLIST, "Bloqu\u00e9e", entry("ccccccccccc", "Un", 100), entry("ccccccccccd", "Deux", 100), entry("cccccccccce", "Trois", 100));
        blocking.add("ccccccccccd");
        YoutubePackJob job = await(service().start(request(new JsonObject().put("urls", PLAYLIST).put("pauses", false))));
        JsonObject state = job.toJson();
        assertEquals("DONE", state.getString("state"));
        assertEquals(1, state.getJsonObject("result").getInteger("stories"));
        assertTrue(state.getJsonObject("result").getBoolean("blocked"));
        // The third video was never asked for
        assertFalse(commands.stream().anyMatch(c -> c.get(c.size() - 1).endsWith("cccccccccce")));

        blocking.add("ccccccccccc");
        commands.clear();
        YoutubePackJob failed = await(service().start(request(new JsonObject().put("urls", PLAYLIST).put("pauses", false))));
        assertEquals("FAILED", failed.toJson().getString("state"));
        assertEquals("BLOCKED", failed.toJson().getString("error"));
    }

    @Test
    void nothingToDownload() throws Exception {
        YoutubePackJob job = await(service().start(request(new JsonObject().put("urls", "https://www.youtube.com/watch?v=zzzzzzzzzzz"))));
        assertEquals("NOTHING_DOWNLOADED", job.toJson().getString("error"));
        assertTrue(job.toJson().getJsonArray("log").encode().contains("Video unavailable"));
        try (java.util.stream.Stream<Path> left = Files.list(tmp.resolve("work"))) {
            assertEquals(0, left.count(), "temporary files are removed");
        }
    }

    @Test
    void cancelledBetweenTwoDownloads() throws Exception {
        playlist(PLAYLIST, "Longue", entry("ddddddddddd", "Un", 100), entry("dddddddddde", "Deux", 100));
        YoutubePackService service = service();
        AtomicReference<YoutubePackJob> started = new AtomicReference<>();
        CountDownLatch known = new CountDownLatch(1);
        sleeper = (ms, cancelled) -> {
            known.await(5, TimeUnit.SECONDS);
            service.cancel(started.get().getId());
            assertTrue(cancelled.getAsBoolean());
        };
        started.set(service.start(request(new JsonObject().put("urls", PLAYLIST))));
        known.countDown();
        YoutubePackJob job = await(started.get());
        assertEquals("CANCELLED", job.toJson().getString("state"));
        assertEquals("CANCELLED", job.toJson().getString("error"));
        assertEquals(0, Files.list(libraryDir).count());
    }

    @Test
    void oneCreationAtATime() throws Exception {
        playlist(PLAYLIST, "Longue", entry("eeeeeeeeeee", "Un", 100), entry("eeeeeeeeeef", "Deux", 100));
        CountDownLatch release = new CountDownLatch(1);
        sleeper = (ms, cancelled) -> release.await(10, TimeUnit.SECONDS);
        YoutubePackService service = service();
        YoutubePackJob first = service.start(request(new JsonObject().put("urls", PLAYLIST)));
        YoutubePackException busy = assertThrows(YoutubePackException.class,
                () -> service.start(request(new JsonObject().put("urls", PLAYLIST))));
        assertEquals(YoutubePackException.Code.BUSY, busy.getCode());
        assertEquals(first.getId(), service.current().get().getId());
        release.countDown();
        await(first);
        assertEquals(first, service.job(first.getId()).get());
        assertEquals(YoutubePackException.Code.NOT_FOUND,
                assertThrows(YoutubePackException.class, () -> service.cancel("inconnu")).getCode());
    }

    @Test
    void missingToolsAreReported() {
        ffmpegInstalled = false;
        YoutubePackService service = service();
        YoutubePackException e = assertThrows(YoutubePackException.class,
                () -> service.start(request(new JsonObject().put("urls", PLAYLIST))));
        assertEquals(YoutubePackException.Code.TOOLS_MISSING, e.getCode());
        assertTrue(e.getMessage().contains("ffmpeg"));
        JsonObject tools = service.tools(false).toJson();
        assertFalse(tools.getBoolean("ready"));
        assertTrue(tools.getBoolean("ytDlp"));
        assertEquals("2026.11.23", tools.getString("ytDlpVersion"));

        ytDlpInstalled = false;
        ffmpegInstalled = true;
        JsonObject again = service.tools(true).toJson();
        assertFalse(again.getBoolean("ytDlp"));
        assertTrue(again.getString("message").contains("yt-dlp"));
    }

    @Test
    void packWithoutVoicesWhenNoneWorks() throws Exception {
        voiceWorks = false;
        metadata.put("https://www.youtube.com/watch?v=fffffffffff", entry("fffffffffff", "Seule", 100));
        YoutubePackJob job = await(service().start(request(new JsonObject().put("urls", "fffffffffff"))));
        JsonObject state = job.toJson();
        assertEquals("DONE", state.getString("state"));
        assertEquals("Seule", state.getJsonObject("result").getString("title"));
        assertEquals(2, state.getJsonObject("result").getInteger("silentTitles"));
        assertNotNull(new ArchiveStoryPackReader().readMetadata(libraryDir.resolve("Seule.zip")));
    }

    @Test
    void channelTabsAreNotVideos() throws Exception {
        String channel = "https://www.youtube.com/@Blender";
        metadata.put(channel, new JsonObject().put("_type", "playlist").put("title", "Blender").put("entries", new JsonArray()
                .add(new JsonObject().put("_type", "url").put("ie_key", "YoutubeTab").put("id", "UCSMOQeBJ2RAnuFungnQOxLg").put("title", "Blender - Videos"))));
        YoutubePackJob job = await(service().start(request(new JsonObject().put("urls", channel))));
        assertEquals("NOTHING_DOWNLOADED", job.toJson().getString("error"));
        assertTrue(job.toJson().getJsonArray("log").encode().contains("cha\u00eene"));
        assertFalse(commands.stream().anyMatch(c -> c.contains("--write-thumbnail")));
        assertTrue(YoutubePackService.isVideoEntry(entry("aaaaaaaaaa1", "x", 1)));
    }

    @Test
    void audioKeptWhenOnlyPostProcessingFails() throws Exception {
        metadata.put("https://www.youtube.com/watch?v=ggggggggggg", entry("ggggggggggg", "Miniature cass\u00e9e", 100));
        YoutubePackService.CommandRunner real = this::fakeRun;
        YoutubePackService s = new YoutubePackService((cmd, sink, timeout, cancelled) -> {
            int code = real.run(cmd, sink, timeout, cancelled);
            if (cmd.contains("--write-thumbnail")) {
                sink.line("ERROR: Postprocessing: thumbnail conversion failed", true);
                return 1;
            }
            return code;
        }, List.of("python"), id -> List.of(), (t, e, v) -> null, new YoutubePackService.Library() {
            @Override
            public Path directory() {
                return libraryDir;
            }

            @Override
            public boolean add(String name, Path file) {
                return file.toFile().renameTo(libraryDir.resolve(name).toFile());
            }
        }, tmp.resolve("work"), (ms, c) -> { }, List.of());
        YoutubePackJob job = await(s.start(request(new JsonObject().put("urls", "ggggggggggg"))));
        assertEquals("DONE", job.toJson().getString("state"), job.toJson().encodePrettily());
        assertEquals(1, job.toJson().getJsonObject("result").getInteger("stories"));
    }

    @Test
    void packIdentifierComesFromTheLinks() {
        YoutubePackRequest a = request(new JsonObject().put("urls", "aaaaaaaaaa1\nbbbbbbbbbb1").put("title", "Mes histoires"));
        YoutubePackRequest b = request(new JsonObject().put("urls", "bbbbbbbbbb1\naaaaaaaaaa1"));
        YoutubePackRequest c = request(new JsonObject().put("urls", "ccccccccccc").put("title", "Mes histoires"));
        assertEquals(YoutubePackService.packId(a), YoutubePackService.packId(b));
        assertFalse(YoutubePackService.packId(a).equals(YoutubePackService.packId(c)));
    }

    @Test
    void aFailingVoiceIsNotAskedForEveryTitle() throws Exception {
        voiceWorks = false;
        playlist(PLAYLIST, "Muette", entry("hhhhhhhhhh1", "Un", 100), entry("hhhhhhhhhh2", "Deux", 100),
                entry("hhhhhhhhhh3", "Trois", 100), entry("hhhhhhhhhh4", "Quatre", 100), entry("hhhhhhhhhh5", "Cinq", 100));
        int[] calls = {0};
        voiceCalls = calls;
        YoutubePackJob job = await(service().start(request(new JsonObject().put("urls", PLAYLIST).put("pauses", false))));
        assertEquals("DONE", job.toJson().getString("state"));
        assertEquals(YoutubePackService.MAX_VOICE_FAILURES, calls[0]);
        assertEquals(6, job.toJson().getJsonObject("result").getInteger("silentTitles"));
    }

    @Test
    void keptIntervalsAfterCuts() {
        assertEquals(List.of(), YoutubePackService.keptIntervals(100, List.of()));
        List<double[]> k = YoutubePackService.keptIntervals(100, List.of(new double[]{90, 100}, new double[]{0, 5}, new double[]{40, 50}));
        assertEquals("5.0-40.0,50.0-90.0", k.stream().map(i -> i[0] + "-" + i[1]).collect(Collectors.joining(",")));
        // Everything cut: no cut at all rather than an empty story
        assertEquals(List.of(), YoutubePackService.keptIntervals(10, List.of(new double[]{0, 10})));
    }

    @Test
    void sponsorBlockAnswers() throws IOException {
        List<double[]> s = YoutubePackService.parseSegments("[{\"segment\":[1.5,9],\"actionType\":\"skip\"},{\"segment\":[20,30],\"actionType\":\"mute\"}]");
        assertEquals(1, s.size());
        assertEquals(9.0, s.get(0)[1]);
        assertThrows(IOException.class, () -> YoutubePackService.parseSegments("<html>"));
    }

    @Test
    void blockingMessages() {
        assertTrue(YoutubePackService.isBlocked("ERROR: [youtube] x: Sign in to confirm you're not a bot"));
        assertTrue(YoutubePackService.isBlocked("HTTP Error 429: Too Many Requests"));
        assertFalse(YoutubePackService.isBlocked("ERROR: Private video"));
    }
}
