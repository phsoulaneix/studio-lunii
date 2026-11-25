/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Makes a pack from YouTube links, in the background, as Lunii Pack does: yt-dlp downloads the
 * audio and the thumbnails, SponsorBlock tells which credits and self-promotion to cut, ffmpeg
 * converts to mono MP3 with a normalised volume, {@link TitleVoiceService} speaks the titles, and
 * the pack is added to the library.
 *
 * <p>One creation at a time: YouTube blocks bursts of downloads, and two would compete for it.
 * Every external program is started from an argument list (never through a shell), with a time
 * limit, and is killed when the creation is cancelled.
 */
public class YoutubePackService {

    private static final Logger LOGGER = LoggerFactory.getLogger(YoutubePackService.class);

    public static final String YTDLP_PROP = "studio.youtube.ytdlp";
    public static final String FFMPEG_PROP = "studio.youtube.ffmpeg";
    public static final String LANG_PROP = "studio.youtube.lang";
    public static final String PAUSE_MIN_PROP = "studio.youtube.pauseMinSeconds";
    public static final String PAUSE_MAX_PROP = "studio.youtube.pauseMaxSeconds";

    /** Same categories as Lunii Pack. */
    static final List<String> SPONSORBLOCK_CATEGORIES = List.of("sponsor", "selfpromo", "interaction", "intro", "outro", "preview");

    private static final List<String> BLOCKED_WORDS = List.of("pas un robot", "not a bot", "sign in to confirm",
            "connectez-vous pour confirmer", "http error 429", "too many requests");
    private static final Pattern DOWNLOAD_PROGRESS = Pattern.compile("\\[download\\]\\s+(\\d+(?:\\.\\d+)?)%");
    private static final Set<String> NOT_AUDIO = Set.of("jpg", "jpeg", "png", "webp", "part", "ytdl", "json", "tmp", "temp");
    private static final int MAX_SPOKEN = TitleVoiceService.MAX_TEXT_LENGTH;
    private static final int KEPT_JOBS = 10;
    /** Consecutive voice failures after which the remaining titles are left silent. */
    static final int MAX_VOICE_FAILURES = 3;

    // -- seams, replaced in tests

    /** Receives the output of a command, one line at a time. */
    public interface LineSink {
        void line(String line, boolean stderr);
    }

    /** Runs a command; throws {@link YoutubePackException} CANCELLED when {@code cancelled} turns true. */
    public interface CommandRunner {
        int run(List<String> command, LineSink sink, Duration timeout, BooleanSupplier cancelled) throws IOException, InterruptedException;
    }

    /** Segments [start, end] (seconds) to cut from a video. */
    public interface SponsorBlock {
        List<double[]> segments(String videoId) throws IOException, InterruptedException;
    }

    /** A spoken text (WAVE); throws {@link TitleVoiceException} when no voice could speak it. */
    public interface Speaker {
        byte[] speak(String text, String engine, String voice);
    }

    /** Waits between two downloads. */
    public interface Sleeper {
        void pause(long millis, BooleanSupplier cancelled) throws InterruptedException;
    }

    /** Where the finished pack goes. */
    public interface Library {
        Path directory();

        boolean add(String name, Path file);
    }

    /** The external programs, as found. */
    public static final class Tools {
        /** Command prefix that runs yt-dlp, or null. */
        public final List<String> ytDlp;
        public final String ytDlpVersion;
        public final String ffmpeg;
        /** {@code name:path} of the JavaScript runtimes yt-dlp may use against YouTube's protections. */
        public final List<String> jsRuntimes;

        Tools(List<String> ytDlp, String ytDlpVersion, String ffmpeg, List<String> jsRuntimes) {
            this.ytDlp = ytDlp;
            this.ytDlpVersion = ytDlpVersion;
            this.ffmpeg = ffmpeg;
            this.jsRuntimes = jsRuntimes;
        }

        public boolean ready() {
            return ytDlp != null && ffmpeg != null;
        }

        /** What is missing, in words, or null. */
        public String problem() {
            if (ready()) {
                return null;
            }
            List<String> missing = new ArrayList<>();
            if (ytDlp == null) {
                missing.add("yt-dlp (install\u00e9 avec Lunii Pack, ou \u00ab pip install yt-dlp \u00bb ; indiquez son Python avec "
                        + TitleVoiceService.PYTHON_ENV + " ou -D" + TitleVoiceService.PYTHON_PROP + ")");
            }
            if (ffmpeg == null) {
                missing.add("ffmpeg (https://ffmpeg.org, dans le PATH ou avec -D" + FFMPEG_PROP + "=\u2026)");
            }
            return "Outils manquants : " + String.join(" ; ", missing);
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("ready", ready())
                    .put("ytDlp", ytDlp != null)
                    .put("ytDlpVersion", ytDlpVersion)
                    .put("ffmpeg", ffmpeg != null)
                    .put("jsRuntime", !jsRuntimes.isEmpty())
                    .put("message", problem());
        }
    }

    private final CommandRunner runner;
    private final List<String> pythonCandidates;
    private final SponsorBlock sponsorBlock;
    private final Speaker speaker;
    private final Library library;
    private final Path tmpRoot;
    private final Sleeper sleeper;
    private final List<Path> searchDirs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "youtube-pack");
        t.setDaemon(true);
        return t;
    });

    private final LinkedHashMap<String, YoutubePackJob> jobs = new LinkedHashMap<>();
    private Tools tools;
    private long toolsProbedAt;
    private static final long NEGATIVE_PROBE_TTL_MILLIS = 60_000;

    Duration downloadTimeout = Duration.ofMinutes(Long.getLong("studio.youtube.downloadTimeoutMinutes", 60));
    Duration convertTimeout = Duration.ofMinutes(30);
    long pauseMinMillis = Long.getLong(PAUSE_MIN_PROP, 10) * 1000;
    long pauseMaxMillis = Long.getLong(PAUSE_MAX_PROP, 30) * 1000;
    String language = System.getProperty(LANG_PROP, "fr");

    /** The service STUdio runs: real programs, the given voice service and library. */
    public YoutubePackService(TitleVoiceService voices, LibraryService libraryService) {
        this(new ProcessRunner(), TitleVoiceService.defaultPythonCandidates(), new SponsorBlockApi(),
                (text, engine, voice) -> voices.speak(text, engine, voice).wave,
                new Library() {
                    @Override
                    public Path directory() {
                        return Paths.get(libraryService.libraryPath());
                    }

                    @Override
                    public boolean add(String name, Path file) {
                        return libraryService.addPackFile(name, file.toString());
                    }
                },
                Paths.get(System.getProperty(LibraryService.TMP_DIR_PROP, System.getProperty("user.home") + LibraryService.TMP_DIR_PATH), "youtube"),
                YoutubePackService::sleep, defaultSearchDirs());
    }

    public YoutubePackService(CommandRunner runner, List<String> pythonCandidates, SponsorBlock sponsorBlock, Speaker speaker,
                              Library library, Path tmpRoot, Sleeper sleeper, List<Path> searchDirs) {
        this.runner = runner;
        this.pythonCandidates = pythonCandidates;
        this.sponsorBlock = sponsorBlock;
        this.speaker = speaker;
        this.library = library;
        this.tmpRoot = tmpRoot;
        this.sleeper = sleeper;
        this.searchDirs = searchDirs;
    }

    // -- tools

    static List<Path> defaultSearchDirs() {
        List<Path> dirs = new ArrayList<>();
        String path = Optional.ofNullable(System.getenv("PATH")).orElse("");
        for (String d : path.split(File.pathSeparator)) {
            if (!d.isBlank()) {
                try {
                    dirs.add(Paths.get(d.trim()));
                } catch (RuntimeException e) {
                    // not a path
                }
            }
        }
        return dirs;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** The programs, probed once; a missing one is probed again after a minute, or when {@code refresh}. */
    public synchronized Tools tools(boolean refresh) {
        long now = System.currentTimeMillis();
        if (tools != null && !refresh && (tools.ready() || now - toolsProbedAt < NEGATIVE_PROBE_TTL_MILLIS)) {
            return tools;
        }
        toolsProbedAt = now;
        List<String> ytDlp = null;
        String version = null;
        String python = null;
        for (String candidate : pythonCandidates) {
            List<String> out = new ArrayList<>();
            if (probe(List.of(candidate, "-I", "-c", "import yt_dlp.version as v; print(v.__version__)"), out)) {
                ytDlp = List.of(candidate, "-I", "-m", "yt_dlp");
                version = out.isEmpty() ? null : out.get(0).trim();
                python = candidate;
                break;
            }
        }
        if (ytDlp == null) {
            String exe = System.getProperty(YTDLP_PROP, "yt-dlp");
            List<String> out = new ArrayList<>();
            if (probe(List.of(exe, "--version"), out)) {
                ytDlp = List.of(exe);
                version = out.isEmpty() ? null : out.get(0).trim();
            }
        }
        String ffmpeg = System.getProperty(FFMPEG_PROP, "ffmpeg");
        if (!probe(List.of(ffmpeg, "-version"), new ArrayList<>())) {
            ffmpeg = null;
        }
        // yt-dlp only enables Deno by default; Lunii Pack installs Deno beside its Python
        List<Path> dirs = new ArrayList<>();
        if (python != null && Paths.get(python).isAbsolute() && Paths.get(python).getParent() != null) {
            dirs.add(Paths.get(python).getParent());
        }
        dirs.addAll(searchDirs);
        List<String> js = new ArrayList<>();
        for (String name : List.of("deno", "node", "bun")) {
            for (Path d : dirs) {
                Path exe = d.resolve(windows() ? name + ".exe" : name);
                if (Files.isRegularFile(exe)) {
                    js.add(name + ":" + exe.toAbsolutePath());
                    break;
                }
            }
        }
        tools = new Tools(ytDlp, version, ffmpeg, js);
        LOGGER.info("YouTube tools: yt-dlp=" + ytDlp + " (" + version + "), ffmpeg=" + ffmpeg + ", js=" + js);
        return tools;
    }

    private boolean probe(List<String> command, List<String> stdout) {
        try {
            return runner.run(command, (line, err) -> {
                if (!err) {
                    stdout.add(line);
                }
            }, Duration.ofSeconds(30), () -> false) == 0;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Probe failed: " + command + ": " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // -- jobs

    /** Starts a creation; refused while another one is running, or when a program is missing. */
    public synchronized YoutubePackJob start(YoutubePackRequest request) {
        YoutubePackJob running = jobs.values().stream().filter(YoutubePackJob::isRunning).findFirst().orElse(null);
        if (running != null) {
            throw new YoutubePackException(YoutubePackException.Code.BUSY, "Un pack est d\u00e9j\u00e0 en cours de cr\u00e9ation");
        }
        Tools t = tools(false);
        if (!t.ready()) {
            t = tools(true);
        }
        if (!t.ready()) {
            throw new YoutubePackException(YoutubePackException.Code.TOOLS_MISSING, t.problem());
        }
        YoutubePackJob job = new YoutubePackJob();
        jobs.put(job.getId(), job);
        while (jobs.size() > KEPT_JOBS) {
            jobs.remove(jobs.keySet().iterator().next());
        }
        Tools used = t;
        executor.submit(() -> execute(job, request, used));
        return job;
    }

    public synchronized Optional<YoutubePackJob> job(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    /** The running creation, or else the last one. */
    public synchronized Optional<YoutubePackJob> current() {
        YoutubePackJob last = null;
        for (YoutubePackJob j : jobs.values()) {
            if (j.isRunning()) {
                return Optional.of(j);
            }
            last = j;
        }
        return Optional.ofNullable(last);
    }

    public synchronized YoutubePackJob cancel(String id) {
        YoutubePackJob job = jobs.get(id);
        if (job == null) {
            throw new YoutubePackException(YoutubePackException.Code.NOT_FOUND, "Cr\u00e9ation inconnue : " + id);
        }
        if (job.isRunning()) {
            job.requestCancel();
            job.log("Annulation demand\u00e9e\u2026");
        }
        return job;
    }

    // -- the creation

    /** A video to put in the pack. */
    static final class Video {
        final String id;
        final String raw;
        final String author;
        final double duration;
        String title;
        String group;
        Path audio;
        Path thumbnail;
        Path converted;

        Video(String id, String raw, String author, double duration, String group) {
            this.id = id;
            this.raw = raw;
            this.author = author;
            this.duration = duration;
            this.group = group;
            this.title = raw;
        }
    }

    private void checkCancel(YoutubePackJob job) {
        if (job.isCancelRequested()) {
            throw new YoutubePackException(YoutubePackException.Code.CANCELLED, "cr\u00e9ation annul\u00e9e");
        }
    }

    void execute(YoutubePackJob job, YoutubePackRequest req, Tools t) {
        Path work = null;
        JsonObject result = null;
        YoutubePackException failure = null;
        try {
            work = Files.createDirectories(tmpRoot).resolve(job.getId());
            Files.createDirectories(work);

            // 1) What the links hold
            job.step(YoutubePackJob.Step.ANALYSING, 0.01);
            List<List<Video>> blocks = new ArrayList<>();
            String playlistTitle = null;
            int playlists = 0;
            List<Video> singles = new ArrayList<>();
            for (int k = 0; k < req.urls.size(); k++) {
                checkCancel(job);
                String url = req.urls.get(k);
                job.item(k + 1, req.urls.size(), url);
                job.log("Analyse de " + url);
                JsonObject info = metadata(t, url, job);
                if (info == null) {
                    continue;
                }
                JsonArray entries = info.getJsonArray("entries");
                if (entries != null) {
                    playlists++;
                    String group = Optional.ofNullable(info.getString("title")).orElse("Playlist");
                    playlistTitle = group;
                    List<Video> block = new ArrayList<>();
                    int others = 0;
                    for (Object o : entries) {
                        if (o instanceof JsonObject && isVideoEntry((JsonObject) o)) {
                            block.add(video((JsonObject) o, group));
                        } else {
                            others++;
                        }
                    }
                    if (block.isEmpty() && others > 0) {
                        // A channel page lists its tabs (Videos, Shorts\u2026), not videos
                        job.warn("lien non pris en charge (cha\u00eene ou onglet) : " + url
                                + ". Collez le lien d'une playlist ou de vid\u00e9os.");
                        continue;
                    }
                    job.log("  playlist \u00ab " + group + " \u00bb : " + block.size() + " vid\u00e9o(s)");
                    blocks.add(block);
                } else if (info.getString("id") != null) {
                    Video v = video(info, null);
                    job.log("  vid\u00e9o \u00ab " + v.raw + " \u00bb" + (v.duration > 0 ? " (" + hms(v.duration) + ")" : ""));
                    singles.add(v);
                }
            }
            if (!singles.isEmpty()) {
                blocks.add(singles);
            }

            // Duration bounds, duplicates, count limit
            Set<String> seen = new LinkedHashSet<>();
            int total = 0;
            for (List<Video> block : blocks) {
                block.removeIf(v -> {
                    if (req.minDuration > 0 && v.duration > 0 && v.duration < req.minDuration) {
                        job.log("  - ignor\u00e9e, trop courte (" + hms(v.duration) + ") : " + v.raw);
                        return true;
                    }
                    if (req.maxDuration > 0 && v.duration > req.maxDuration) {
                        job.log("  - ignor\u00e9e, trop longue (" + hms(v.duration) + ") : " + v.raw);
                        return true;
                    }
                    return !seen.add(v.id);
                });
            }
            for (List<Video> block : blocks) {
                if (total + block.size() > req.maxVideos) {
                    int keep = Math.max(0, req.maxVideos - total);
                    job.warn((block.size() - keep) + " vid\u00e9o(s) laiss\u00e9e(s) de c\u00f4t\u00e9 : au plus " + req.maxVideos + " par pack");
                    block.subList(keep, block.size()).clear();
                }
                total += block.size();
            }
            blocks.removeIf(List::isEmpty);
            if (total == 0) {
                throw new YoutubePackException(YoutubePackException.Code.NOTHING_DOWNLOADED,
                        "aucune vid\u00e9o \u00e0 t\u00e9l\u00e9charger (lien invalide, vid\u00e9o priv\u00e9e ou filtr\u00e9e par la dur\u00e9e)");
            }

            // Titles: cleaned, series prefix removed, sorted by season
            List<Video> videos = new ArrayList<>();
            for (List<Video> block : blocks) {
                arrangeTitles(block, req, job);
                videos.addAll(block);
            }
            List<String> numbered = YoutubeTitles.numberDuplicates(videos.stream().map(v -> v.title).collect(Collectors.toList()));
            for (int i = 0; i < videos.size(); i++) {
                videos.get(i).title = numbered.get(i);
            }
            String packTitle = req.title != null ? req.title
                    : playlists == 1 && singles.isEmpty() ? playlistTitle
                    : videos.size() == 1 ? videos.get(0).title
                    : "Mes histoires";

            // 2) Downloads
            job.step(YoutubePackJob.Step.DOWNLOADING, 0.05);
            List<Video> downloaded = new ArrayList<>();
            boolean blocked = false;
            for (int i = 0; i < videos.size(); i++) {
                checkCancel(job);
                Video v = videos.get(i);
                if (i > 0 && req.pauses && pauseMaxMillis > 0) {
                    long ms = pauseMinMillis + (pauseMaxMillis > pauseMinMillis
                            ? ThreadLocalRandom.current().nextLong(pauseMaxMillis - pauseMinMillis + 1) : 0);
                    job.item(i + 1, videos.size(), v.title);
                    job.log("Pause de " + Math.round(ms / 1000.0) + " s (YouTube bloque les rafales de t\u00e9l\u00e9chargements)");
                    sleeper.pause(ms, job::isCancelRequested);
                    checkCancel(job);
                }
                job.item(i + 1, videos.size(), v.title);
                job.log("[" + (i + 1) + "/" + videos.size() + "] t\u00e9l\u00e9chargement : " + v.title);
                int index = i;
                try {
                    download(t, v, work.resolve("v" + i), job,
                            frac -> job.progress(0.05 + 0.6 * (index + frac) / videos.size()));
                    downloaded.add(v);
                } catch (BlockedException e) {
                    blocked = true;
                    job.warn("YouTube bloque les t\u00e9l\u00e9chargements (\u00ab confirmez que vous n'\u00eates pas un robot \u00bb) : "
                            + "arr\u00eat, le pack est cr\u00e9\u00e9 avec les vid\u00e9os d\u00e9j\u00e0 r\u00e9cup\u00e9r\u00e9es. R\u00e9essayez dans quelques heures.");
                    break;
                } catch (IOException e) {
                    job.warn("\u00e9chec du t\u00e9l\u00e9chargement, vid\u00e9o ignor\u00e9e : " + v.title + " (" + e.getMessage() + ")");
                }
                job.progress(0.05 + 0.6 * (i + 1) / videos.size());
            }
            if (downloaded.isEmpty()) {
                throw blocked
                        ? new YoutubePackException(YoutubePackException.Code.BLOCKED,
                        "YouTube bloque les t\u00e9l\u00e9chargements (\u00ab confirmez que vous n'\u00eates pas un robot \u00bb) : r\u00e9essayez dans quelques heures")
                        : new YoutubePackException(YoutubePackException.Code.NOTHING_DOWNLOADED, "aucune vid\u00e9o n'a pu \u00eatre t\u00e9l\u00e9charg\u00e9e");
            }

            // 3) Cuts and conversion
            job.step(YoutubePackJob.Step.CONVERTING, 0.65);
            List<Video> converted = new ArrayList<>();
            for (int i = 0; i < downloaded.size(); i++) {
                checkCancel(job);
                Video v = downloaded.get(i);
                job.item(i + 1, downloaded.size(), v.title);
                List<double[]> keep = req.sponsorBlock ? keptIntervals(v, job) : List.of();
                Path out = work.resolve("c" + i + ".mp3");
                try {
                    convert(t, v.audio, out, keep, req.bitrate, job);
                    v.converted = out;
                    converted.add(v);
                    job.log("  [" + (i + 1) + "/" + downloaded.size() + "] converti : " + v.title);
                } catch (IOException e) {
                    job.warn("conversion impossible, vid\u00e9o ignor\u00e9e : " + v.title + " (" + e.getMessage() + ")");
                }
                job.progress(0.65 + 0.2 * (i + 1) / downloaded.size());
            }
            if (converted.isEmpty()) {
                throw new YoutubePackException(YoutubePackException.Code.FAILED, "aucune histoire n'a pu \u00eatre convertie");
            }

            // 4) Voices, images, archive
            job.step(YoutubePackJob.Step.VOICES, 0.85);
            List<YoutubePackBuilder.Story> stories = new ArrayList<>();
            for (Video v : converted) {
                stories.add(new YoutubePackBuilder.Story(v.title, "yt:" + v.id, v.group, v.converted, readImage(v.thumbnail)));
            }
            int expectedVoices = stories.size() + 1 + (int) converted.stream().map(v -> v.group).distinct().count();
            Map<String, byte[]> spoken = new HashMap<>();
            int[] done = {0};
            int[] voiceFailures = {0};
            YoutubePackBuilder.Settings settings = new YoutubePackBuilder.Settings(packTitle, req.description,
                    packId(req), req.menuSize, req.autoplayNext);
            Path packFile = work.resolve("pack.zip");
            YoutubePackBuilder.Result built = YoutubePackBuilder.build(stories, settings, text -> {
                checkCancel(job);
                done[0]++;
                job.item(Math.min(done[0], expectedVoices), expectedVoices, text);
                job.progress(0.85 + 0.12 * Math.min(1.0, (double) done[0] / expectedVoices));
                if (spoken.containsKey(text)) {
                    return spoken.get(text);
                }
                // A voice that keeps failing is not asked again for every title
                if (voiceFailures[0] >= MAX_VOICE_FAILURES) {
                    return null;
                }
                byte[] wave;
                try {
                    wave = speaker.speak(text.length() > MAX_SPOKEN ? text.substring(0, MAX_SPOKEN) : text, req.engine, req.voice);
                    voiceFailures[0] = 0;
                } catch (TitleVoiceException e) {
                    voiceFailures[0]++;
                    job.warn("voix indisponible (" + e.getMessage() + ") : titre sans voix"
                            + (voiceFailures[0] >= MAX_VOICE_FAILURES ? " ; les titres suivants restent sans voix" : ""));
                    wave = null;
                }
                checkCancel(job);
                spoken.put(text, wave);
                return wave;
            }, packFile);
            if (built.silentTitles > 0) {
                job.warn(built.silentTitles + " titre(s) sans voix : silencieux sur la Lunii. Vous pouvez les g\u00e9n\u00e9rer dans l'\u00e9diteur.");
            }

            job.step(YoutubePackJob.Step.WRITING, 0.97);
            checkCancel(job);
            String name = libraryName(packTitle);
            if (!library.add(name, packFile)) {
                throw new YoutubePackException(YoutubePackException.Code.FAILED, "le pack n'a pas pu \u00eatre ajout\u00e9 \u00e0 la biblioth\u00e8que");
            }
            job.log("Pack ajout\u00e9 \u00e0 la biblioth\u00e8que : " + name + " (" + built.stories + " histoire(s))");
            result = new JsonObject()
                    .put("path", name)
                    .put("uuid", built.uuid)
                    .put("title", packTitle)
                    .put("stories", built.stories)
                    .put("skipped", videos.size() - built.stories)
                    .put("silentTitles", built.silentTitles)
                    .put("blocked", blocked);
        } catch (YoutubePackException e) {
            failure = e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = new YoutubePackException(YoutubePackException.Code.CANCELLED, "cr\u00e9ation interrompue");
        } catch (Exception | Error e) {
            // Errors too (out of memory, AWT without fonts\u2026): a job left RUNNING would refuse every later one
            LOGGER.error("Pack creation from YouTube failed", e);
            failure = new YoutubePackException(YoutubePackException.Code.FAILED,
                    e.getMessage() != null ? e.getMessage() : e.toString(), e);
        } finally {
            if (work != null) {
                deleteQuietly(work);
            }
            // Finished only once the temporary files are gone
            if (result != null) {
                job.done(result);
            } else {
                job.fail(failure != null ? failure
                        : new YoutubePackException(YoutubePackException.Code.FAILED, "cr\u00e9ation interrompue"));
            }
        }
    }

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    /** A playlist entry that is a video, not a channel tab or a nested playlist. */
    static boolean isVideoEntry(JsonObject entry) {
        String id = entry.getString("id");
        String type = entry.getString("_type");
        String ie = entry.getString("ie_key");
        return id != null && VIDEO_ID.matcher(id).matches()
                && (type == null || "url".equals(type) || "video".equals(type))
                && (ie == null || "Youtube".equalsIgnoreCase(ie));
    }

    private static Video video(JsonObject info, String group) {
        String raw = Optional.ofNullable(info.getString("title")).filter(s -> !s.isBlank()).orElse(info.getString("id"));
        String author = Optional.ofNullable(info.getString("channel")).orElse(info.getString("uploader"));
        double duration = info.getValue("duration") instanceof Number ? ((Number) info.getValue("duration")).doubleValue() : 0;
        return new Video(info.getString("id"), raw, author, duration, group);
    }

    /** Lunii Pack's title handling for one playlist, or for the videos linked one by one. */
    static void arrangeTitles(List<Video> block, YoutubePackRequest req, YoutubePackJob job) {
        for (Video v : block) {
            v.title = req.cleanTitles ? YoutubeTitles.clean(v.raw, v.author) : v.raw;
        }
        if (req.cleanTitles) {
            List<String> t = YoutubeTitles.removeCommonPrefix(block.stream().map(v -> v.title).collect(Collectors.toList()));
            for (int i = 0; i < block.size(); i++) {
                block.get(i).title = t.get(i);
            }
        }
        if (req.seasons && block.size() >= 2) {
            YoutubeTitles.SeasonPlan plan = YoutubeTitles.seasonPlan(block.stream().map(v -> v.raw).collect(Collectors.toList()));
            if (plan != null) {
                for (int i = 0; i < block.size(); i++) {
                    if (plan.groups.get(i) != null) {
                        block.get(i).group = plan.groups.get(i);
                    }
                }
                List<Video> sorted = plan.order.stream().map(block::get).collect(Collectors.toList());
                block.clear();
                block.addAll(sorted);
                if (job != null) {
                    job.log("  rangement par saison : " + plan.groups.stream().filter(g -> g != null).distinct().count()
                            + " saison(s), \u00e9pisodes tri\u00e9s dans l'ordre");
                }
            }
        }
    }

    /**
     * The pack identifier, from which node uuids derive: the links asked for, so that two packs of
     * the same title stay two packs, and making the same pack again updates it on the device.
     */
    static String packId(YoutubePackRequest req) {
        List<String> urls = new ArrayList<>(req.urls);
        Collections.sort(urls);
        return "yt-" + YoutubePackBuilder.sha1(String.join("\n", urls).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /** A file name in the library that no pack has yet. */
    String libraryName(String title) {
        String base = YoutubeTitles.fileName(title);
        Path dir = library.directory();
        String name = base + ".zip";
        for (int k = 2; dir != null && Files.exists(dir.resolve(name)); k++) {
            name = base + " (" + k + ").zip";
        }
        return name;
    }

    // -- yt-dlp

    private List<String> common(Tools t) {
        List<String> c = new ArrayList<>(t.ytDlp);
        c.addAll(List.of("--ignore-config", "--encoding", "utf-8", "--no-playlist",
                // YouTube translates titles to the browser's language: ask for the wanted one
                "--extractor-args", "youtube:lang=" + language,
                "--add-headers", "Accept-Language:" + language + "," + language + "-FR;q=0.9,en;q=0.5"));
        for (String js : t.jsRuntimes) {
            c.add("--js-runtimes");
            c.add(js);
        }
        return c;
    }

    /** {@code yt-dlp -J --flat-playlist}: one video, or a playlist and its entries. Null when unreadable. */
    JsonObject metadata(Tools t, String url, YoutubePackJob job) throws IOException, InterruptedException {
        List<String> cmd = common(t);
        cmd.addAll(List.of("--flat-playlist", "--ignore-errors", "--no-warnings", "-J", "--", url));
        StringBuilder out = new StringBuilder();
        List<String> errors = new ArrayList<>();
        boolean[] blocked = {false};
        int code = runner.run(cmd, (line, err) -> {
            if (err) {
                errors.add(line);
                blocked[0] |= isBlocked(line);
            } else {
                out.append(line).append('\n');
            }
        }, Duration.ofMinutes(5), job::isCancelRequested);
        if (blocked[0]) {
            throw new YoutubePackException(YoutubePackException.Code.BLOCKED,
                    "YouTube bloque les requ\u00eates (\u00ab confirmez que vous n'\u00eates pas un robot \u00bb) : r\u00e9essayez dans quelques heures");
        }
        String json = out.toString().trim();
        if (json.isEmpty() || "null".equals(json)) {
            job.warn("lien illisible (vid\u00e9o priv\u00e9e, supprim\u00e9e ou lien invalide) : " + url + lastError(errors, code));
            return null;
        }
        try {
            return new JsonObject(json.substring(json.indexOf('{')));
        } catch (DecodeException | StringIndexOutOfBoundsException e) {
            job.warn("r\u00e9ponse de yt-dlp illisible pour " + url + lastError(errors, code));
            return null;
        }
    }

    static boolean isBlocked(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        return BLOCKED_WORDS.stream().anyMatch(l::contains);
    }

    private static String lastError(List<String> errors, int code) {
        for (int i = errors.size() - 1; i >= 0; i--) {
            String e = errors.get(i).replaceAll("\u001b\\[[0-9;]*m", "").trim();
            if (!e.isEmpty()) {
                e = e.replaceFirst("^ERROR:\\s*", "");
                return " (" + (e.length() > 200 ? e.substring(0, 200) + "\u2026" : e) + ")";
            }
        }
        return code != 0 ? " (code " + code + ")" : "";
    }

    static final class BlockedException extends IOException {
        BlockedException() {
            super("blocked by YouTube");
        }
    }

    interface Progress {
        void fraction(double f);
    }

    /** Downloads the best audio and the thumbnail (as JPEG) of a video into {@code dir}. */
    void download(Tools t, Video v, Path dir, YoutubePackJob job, Progress progress) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        List<String> cmd = common(t);
        cmd.addAll(List.of("--newline", "--no-mtime", "--sleep-requests", "1",
                "-f", "bestaudio/best",
                "-o", dir.resolve("media.%(ext)s").toString(),
                "--write-thumbnail", "--convert-thumbnails", "jpg"));
        if (Paths.get(t.ffmpeg).isAbsolute()) {
            // A bare "ffmpeg" is found on the PATH by yt-dlp itself; a relative location would not be
            cmd.addAll(List.of("--ffmpeg-location", t.ffmpeg));
        }
        cmd.addAll(List.of("--", "https://www.youtube.com/watch?v=" + v.id));
        List<String> errors = new ArrayList<>();
        boolean[] blocked = {false};
        int code = runner.run(cmd, (line, err) -> {
            Matcher m = DOWNLOAD_PROGRESS.matcher(line);
            if (m.find()) {
                progress.fraction(Math.min(1, Double.parseDouble(m.group(1)) / 100));
            } else if (err) {
                errors.add(line);
            }
            blocked[0] |= isBlocked(line);
        }, downloadTimeout, job::isCancelRequested);
        if (blocked[0]) {
            throw new BlockedException();
        }
        Path audio;
        try (Stream<Path> files = Files.list(dir)) {
            audio = files.filter(p -> p.getFileName().toString().startsWith("media."))
                    .filter(p -> !NOT_AUDIO.contains(extension(p)))
                    .max(Comparator.comparingLong(p -> p.toFile().length()))
                    .orElse(null);
        }
        if (audio == null) {
            String why = lastError(errors, code);
            throw new IOException(why.isEmpty() ? "aucun fichier audio" : why.substring(2, why.length() - 1));
        }
        if (code != 0) {
            // The audio is complete (partial downloads stay ".part"): only a post-processing step,
            // usually the thumbnail conversion, failed
            job.log("  ! yt-dlp a signal\u00e9 une erreur apr\u00e8s le t\u00e9l\u00e9chargement" + lastError(errors, code)
                    + " : l'audio est gard\u00e9");
        }
        v.audio = audio;
        Path thumb = dir.resolve("media.jpg");
        v.thumbnail = Files.isRegularFile(thumb) ? thumb : null;
    }

    private static String extension(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static BufferedImage readImage(Path p) {
        if (p == null) {
            return null;
        }
        try {
            return ImageIO.read(p.toFile());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Unreadable thumbnail " + p + ": " + e.getMessage());
            return null;
        }
    }

    // -- cuts and conversion

    /** The parts of the video to keep once SponsorBlock's segments are removed; empty means all of it. */
    List<double[]> keptIntervals(Video v, YoutubePackJob job) throws InterruptedException {
        List<double[]> cut;
        try {
            cut = sponsorBlock.segments(v.id);
        } catch (IOException e) {
            job.log("  SponsorBlock injoignable (" + e.getMessage() + ") : pas de coupe pour " + v.title);
            return List.of();
        }
        List<double[]> keep = keptIntervals(v.duration, cut);
        if (!keep.isEmpty()) {
            double end = v.duration > 0 ? v.duration : keep.get(keep.size() - 1)[1];
            double removed = end - keep.stream().mapToDouble(k -> Math.min(k[1], end) - k[0]).sum();
            if (removed > 1) {
                job.log("  " + v.title + " : " + hms(removed) + " retir\u00e9es (g\u00e9n\u00e9riques, autopromo)");
            }
        }
        return keep;
    }

    /** Port of Lunii Pack's {@code calculer_coupes}, without manual bounds. */
    static List<double[]> keptIntervals(double duration, List<double[]> cut) {
        if (cut == null || cut.isEmpty()) {
            return List.of();
        }
        double end = duration > 0 ? duration : 1e7;
        List<double[]> keep = new ArrayList<>();
        keep.add(new double[]{0, end});
        List<double[]> sorted = new ArrayList<>(cut);
        sorted.sort(Comparator.comparingDouble(s -> s[0]));
        for (double[] s : sorted) {
            List<double[]> next = new ArrayList<>();
            for (double[] k : keep) {
                if (s[1] <= k[0] || s[0] >= k[1]) {
                    next.add(k);
                } else {
                    if (s[0] > k[0]) {
                        next.add(new double[]{k[0], s[0]});
                    }
                    if (s[1] < k[1]) {
                        next.add(new double[]{s[1], k[1]});
                    }
                }
            }
            keep = next;
        }
        keep.removeIf(k -> k[1] - k[0] <= 0.5);
        // Nothing left: the cuts are ignored rather than an empty story
        return keep;
    }

    static List<String> convertCommand(String ffmpeg, Path in, Path out, List<double[]> keep, int bitrate) {
        List<String> filters = new ArrayList<>();
        if (!keep.isEmpty()) {
            String expr = keep.stream().map(k -> String.format(Locale.ROOT, "between(t,%.3f,%.3f)", k[0], k[1]))
                    .collect(Collectors.joining("+"));
            filters.add("aselect='" + expr + "'");
            filters.add("asetpts=N/SR/TB");
        }
        filters.add("loudnorm=I=-16:TP=-1:LRA=11");
        return List.of(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y", "-i", in.toString(),
                "-vn", "-map_metadata", "-1", "-af", String.join(",", filters),
                "-ac", "1", "-ar", "44100", "-c:a", "libmp3lame", "-b:a", bitrate + "k", out.toString());
    }

    /** Mono MP3 at 44.1 kHz, normalised volume, no metadata, as Lunii Pack makes them. */
    void convert(Tools t, Path in, Path out, List<double[]> keep, int bitrate, YoutubePackJob job) throws IOException, InterruptedException {
        List<String> errors = new ArrayList<>();
        int code = runner.run(convertCommand(t.ffmpeg, in, out, keep, bitrate), (line, err) -> errors.add(line),
                convertTimeout, job::isCancelRequested);
        if (code != 0 || !Files.isRegularFile(out) || Files.size(out) == 0) {
            String why = lastError(errors, code);
            throw new IOException("fichier audio illisible" + why);
        }
    }

    // -- helpers

    static String hms(double seconds) {
        long s = Math.round(seconds);
        long h = s / 3600;
        long m = (s / 60) % 60;
        return h > 0 ? String.format("%d h %02d", h, m) : String.format("%d min %02d", m, s % 60);
    }

    private static void sleep(long millis, BooleanSupplier cancelled) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end && !cancelled.getAsBoolean()) {
            Thread.sleep(Math.min(200, Math.max(1, end - System.currentTimeMillis())));
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                if (!p.toFile().delete() && p.toFile().exists()) {
                    LOGGER.warn("Could not remove temporary file " + p);
                }
            });
        } catch (IOException e) {
            LOGGER.debug("Could not remove " + dir);
        }
    }

    /** SponsorBlock's public API; 404 means no known segment. */
    static final class SponsorBlockApi implements SponsorBlock {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        @Override
        public List<double[]> segments(String videoId) throws IOException, InterruptedException {
            String categories = new JsonArray(SPONSORBLOCK_CATEGORIES).encode();
            URI uri = URI.create("https://sponsor.ajay.app/api/skipSegments?videoID=" + URLEncoder.encode(videoId, StandardCharsets.UTF_8)
                    + "&categories=" + URLEncoder.encode(categories, StandardCharsets.UTF_8));
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "studio-lunii").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                return List.of();
            }
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode());
            }
            return parseSegments(resp.body());
        }
    }

    static List<double[]> parseSegments(String body) throws IOException {
        List<double[]> res = new ArrayList<>();
        try {
            for (Object o : new JsonArray(body)) {
                if (!(o instanceof JsonObject)) {
                    continue;
                }
                JsonObject s = (JsonObject) o;
                JsonArray seg = s.getJsonArray("segment");
                if (!"skip".equals(s.getString("actionType", "skip")) || seg == null || seg.size() < 2) {
                    continue;
                }
                res.add(new double[]{((Number) seg.getValue(0)).doubleValue(), ((Number) seg.getValue(1)).doubleValue()});
            }
        } catch (DecodeException | ClassCastException e) {
            throw new IOException("r\u00e9ponse illisible");
        }
        return res;
    }

    /** Starts the program directly (no shell), reads its output as UTF-8, kills it on timeout or cancel. */
    static final class ProcessRunner implements CommandRunner {
        @Override
        public int run(List<String> command, LineSink sink, Duration timeout, BooleanSupplier cancelled) throws IOException, InterruptedException {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectInput(ProcessBuilder.Redirect.from(new File(windows() ? "NUL" : "/dev/null")));
            // A Python started without -I (a standalone yt-dlp) writes UTF-8 too
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process process = pb.start();
            Thread out = pump(process.getInputStream(), false, sink);
            Thread err = pump(process.getErrorStream(), true, sink);
            long deadline = System.nanoTime() + timeout.toNanos();
            boolean finished = false;
            try {
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) {
                        throw new YoutubePackException(YoutubePackException.Code.CANCELLED, "cr\u00e9ation annul\u00e9e");
                    }
                    if (System.nanoTime() > deadline) {
                        throw new TitleVoiceService.CommandTimeoutException("d\u00e9lai d\u00e9pass\u00e9 (" + timeout.toMinutes() + " min)");
                    }
                }
                finished = true;
                out.join(5000);
                err.join(5000);
                return process.exitValue();
            } finally {
                if (!finished) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    try {
                        process.onExit().get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        LOGGER.warn("Process did not exit after being killed: " + command.get(0));
                    }
                }
            }
        }

        private static Thread pump(InputStream in, boolean stderr, LineSink sink) {
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        try {
                            sink.line(line, stderr);
                        } catch (RuntimeException e) {
                            LOGGER.debug("Output line handler failed: " + e.getMessage());
                        }
                    }
                } catch (IOException e) {
                    // the process ended or was killed
                }
            }, "youtube-pack-output");
            t.setDaemon(true);
            t.start();
            return t;
        }
    }
}
