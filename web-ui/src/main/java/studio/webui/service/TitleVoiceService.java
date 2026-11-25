/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import studio.core.v1.utils.AudioConversion;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Speaks a node title, for the editor's "generate the voice" button.
 *
 * <p>Two engines, tried in this order when the engine is {@code auto}:
 * <ol>
 *     <li>{@code edge}: the {@code edge-tts} Python package, the neural voices Lunii Pack uses. The
 *     text is sent to Microsoft's online speech service.</li>
 *     <li>{@code system}: the Windows voice (SAPI, {@code System.Speech}), offline, with a French
 *     voice when one is installed.</li>
 * </ol>
 *
 * <p>Every process is started from an argument list, never through a shell, and the text never
 * appears on a command line: it is written to a UTF-8 file whose path is passed instead. Each run
 * has a time limit, after which the process is killed.
 *
 * <p>The answer is always a device-native WAVE (PCM 16 bits, mono, 32 kHz) with a short silence at
 * both ends, so the editor attaches it as it would a dropped file and nothing is converted later.
 */
public class TitleVoiceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TitleVoiceService.class);

    public static final String ENGINE_AUTO = "auto";
    public static final String ENGINE_EDGE = "edge";
    public static final String ENGINE_SYSTEM = "system";

    /** Same default as Lunii Pack. */
    public static final String DEFAULT_VOICE = "fr-FR-DeniseNeural";

    public static final int MAX_TEXT_LENGTH = 500;

    public static final String PYTHON_PROP = "studio.tts.python";
    public static final String PYTHON_ENV = "STUDIO_TTS_PYTHON";
    public static final String ENGINE_PROP = "studio.tts.engine";
    public static final String VOICE_PROP = "studio.tts.voice";
    public static final String TIMEOUT_PROP = "studio.tts.timeoutSeconds";

    /** The voices Lunii Pack offers, in the same order. Any other well-formed edge voice name is accepted. */
    public static final List<String> SUGGESTED_VOICES = List.of(
            "fr-FR-DeniseNeural", "fr-FR-HenriNeural", "fr-FR-EloiseNeural",
            "fr-FR-VivienneMultilingualNeural", "fr-FR-RemyMultilingualNeural");

    private static final Pattern VOICE_NAME = Pattern.compile("[a-z]{2,3}-[A-Z]{2}-[A-Za-z]{2,40}Neural");

    /** Silence added before and after the speech, as the device's audio guidelines recommend. */
    static final int PADDING_MILLIS = 400;

    /**
     * SAPI script. The text and the output path come from environment variables naming files, so
     * nothing the user typed is ever parsed by PowerShell.
     */
    static final String SAPI_SCRIPT = String.join(";",
            "$ErrorActionPreference = 'Stop'",
            "Add-Type -AssemblyName System.Speech",
            "$t = [System.IO.File]::ReadAllText($env:STUDIO_TTS_TEXT, [System.Text.Encoding]::UTF8)",
            "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer",
            "$v = $s.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -like 'fr*' } | Select-Object -First 1",
            "if ($v) { $s.SelectVoice($v.VoiceInfo.Name) }",
            "$f = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(32000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)",
            "$s.SetOutputToWaveFile($env:STUDIO_TTS_OUT, $f)",
            "$s.Speak($t)",
            "$s.Dispose()");

    /** Runs a command. Replaced in tests. */
    public interface CommandRunner {
        /**
         * @return the exit code
         * @throws CommandTimeoutException when the process did not end in time (it has been killed)
         */
        int run(List<String> command, Map<String, String> environment, Duration timeout) throws IOException, InterruptedException;
    }

    public static class CommandTimeoutException extends IOException {
        public CommandTimeoutException(String message) {
            super(message);
        }
    }

    /** The outcome of a synthesis. */
    public static final class Speech {
        public final byte[] wave;
        public final String engine;
        public final String voice;
        /** Why {@code auto} fell back to the system voice, or null. */
        public final String fallbackReason;

        Speech(byte[] wave, String engine, String voice, String fallbackReason) {
            this.wave = wave;
            this.engine = engine;
            this.voice = voice;
            this.fallbackReason = fallbackReason;
        }
    }

    private final CommandRunner runner;
    private final List<String> pythonCandidates;
    private final String powershell;
    private final Path tmpRoot;
    private final Duration timeout;

    private volatile String edgePython;
    private volatile long edgeProbedAt = Long.MIN_VALUE;
    private static final long NEGATIVE_PROBE_TTL_MILLIS = 60_000;

    public TitleVoiceService() {
        this(new ProcessCommandRunner(), defaultPythonCandidates(), defaultPowershell(),
                Paths.get(System.getProperty("studio.tmpdir", System.getProperty("user.home") + "/.studio/tmp/"), "tts"),
                Duration.ofSeconds(Long.getLong(TIMEOUT_PROP, 60)));
    }

    public TitleVoiceService(CommandRunner runner, List<String> pythonCandidates, String powershell, Path tmpRoot, Duration timeout) {
        this.runner = runner;
        this.pythonCandidates = pythonCandidates;
        this.powershell = powershell;
        this.tmpRoot = tmpRoot;
        this.timeout = timeout;
    }

    // -- configuration

    static List<String> defaultPythonCandidates() {
        Set<String> candidates = new LinkedHashSet<>();
        String prop = System.getProperty(PYTHON_PROP);
        if (prop != null && !prop.isBlank()) {
            candidates.add(prop.trim());
        }
        String env = System.getenv(PYTHON_ENV);
        if (env != null && !env.isBlank()) {
            candidates.add(env.trim());
        }
        candidates.add("python");
        candidates.add("python3");
        return new ArrayList<>(candidates);
    }

    /** The Windows PowerShell executable, by absolute path so that PATH cannot substitute another one. */
    static String defaultPowershell() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return null;
        }
        String root = Optional.ofNullable(System.getenv("SystemRoot")).orElse("C:\\Windows");
        File exe = Paths.get(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toFile();
        return exe.isFile() ? exe.getAbsolutePath() : null;
    }

    public static String defaultEngine() {
        String engine = System.getProperty(ENGINE_PROP, ENGINE_AUTO).trim().toLowerCase(Locale.ROOT);
        return isKnownEngine(engine) ? engine : ENGINE_AUTO;
    }

    public static String defaultVoice() {
        String voice = System.getProperty(VOICE_PROP, DEFAULT_VOICE).trim();
        return isValidVoice(voice) ? voice : DEFAULT_VOICE;
    }

    static boolean isKnownEngine(String engine) {
        return ENGINE_AUTO.equals(engine) || ENGINE_EDGE.equals(engine) || ENGINE_SYSTEM.equals(engine);
    }

    public static boolean isValidVoice(String voice) {
        return voice != null && VOICE_NAME.matcher(voice).matches();
    }

    // -- validation

    /**
     * The text as it will be spoken: control characters become spaces, runs of white space one
     * space. Refused when nothing is left or when it is longer than {@link #MAX_TEXT_LENGTH}.
     */
    public static String normalizeText(String text) {
        if (text == null) {
            throw new TitleVoiceException(TitleVoiceException.Code.INVALID_REQUEST, "Le texte \u00e0 prononcer est manquant");
        }
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> sb.appendCodePoint(Character.isISOControl(cp) || Character.isWhitespace(cp) ? ' ' : cp));
        String normalized = sb.toString().replaceAll(" {2,}", " ").trim();
        if (normalized.isEmpty()) {
            throw new TitleVoiceException(TitleVoiceException.Code.INVALID_REQUEST, "Le texte \u00e0 prononcer est vide");
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_TEXT_LENGTH) {
            throw new TitleVoiceException(TitleVoiceException.Code.INVALID_REQUEST,
                    "Le texte \u00e0 prononcer d\u00e9passe " + MAX_TEXT_LENGTH + " caract\u00e8res");
        }
        return normalized;
    }

    // -- engines

    /** The Python interpreter that can import edge_tts, or null. Probed once, a failure re-probed after a minute. */
    synchronized String edgePython() {
        long now = System.currentTimeMillis();
        if (edgePython != null || (edgeProbedAt != Long.MIN_VALUE && now - edgeProbedAt < NEGATIVE_PROBE_TTL_MILLIS)) {
            return edgePython;
        }
        edgeProbedAt = now;
        for (String python : pythonCandidates) {
            try {
                int code = runner.run(List.of(python, "-I", "-c", "import edge_tts"), Map.of(), Duration.ofSeconds(20));
                if (code == 0) {
                    LOGGER.info("edge-tts found with " + python);
                    edgePython = python;
                    return python;
                }
            } catch (IOException e) {
                LOGGER.debug("No usable python at " + python + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        LOGGER.info("edge-tts not available (tried " + pythonCandidates + ")");
        return null;
    }

    public boolean isEdgeAvailable() {
        return edgePython() != null;
    }

    public boolean isSystemAvailable() {
        return powershell != null;
    }

    /**
     * Speaks the text.
     *
     * @param text the title, normalized here
     * @param engine {@code auto}, {@code edge} or {@code system}; null for the configured default
     * @param voice an edge voice name; null for the configured default. Ignored by the system voice.
     */
    public Speech speak(String text, String engine, String voice) {
        String spoken = normalizeText(text);
        String e = engine == null || engine.isBlank() ? defaultEngine() : engine.trim().toLowerCase(Locale.ROOT);
        if (!isKnownEngine(e)) {
            throw new TitleVoiceException(TitleVoiceException.Code.INVALID_REQUEST, "Moteur de voix inconnu : " + engine);
        }
        String v = voice == null || voice.isBlank() ? defaultVoice() : voice.trim();
        if (!isValidVoice(v)) {
            throw new TitleVoiceException(TitleVoiceException.Code.INVALID_REQUEST, "Nom de voix invalide : " + voice);
        }

        String python = ENGINE_SYSTEM.equals(e) ? null : edgePython();
        if (ENGINE_EDGE.equals(e) && python == null) {
            throw new TitleVoiceException(TitleVoiceException.Code.TTS_UNAVAILABLE,
                    "La voix en ligne (edge-tts) n'est pas disponible : installez Python et le paquet edge-tts, "
                            + "ou indiquez l'interpr\u00e9teur avec -D" + PYTHON_PROP + "=\u2026");
        }
        if (ENGINE_SYSTEM.equals(e) && powershell == null) {
            throw new TitleVoiceException(TitleVoiceException.Code.TTS_UNAVAILABLE,
                    "La voix de l'ordinateur (Windows SAPI) n'est pas disponible sur ce syst\u00e8me");
        }
        if (python == null && powershell == null) {
            throw new TitleVoiceException(TitleVoiceException.Code.TTS_UNAVAILABLE,
                    "Aucune voix disponible : installez Python et le paquet edge-tts (voix en ligne), "
                            + "ou utilisez Windows (voix de l'ordinateur)");
        }

        Path work = createWorkDir();
        try {
            Path textFile = work.resolve("text.txt");
            Files.writeString(textFile, spoken, StandardCharsets.UTF_8);
            String fallbackReason = null;
            if (python != null) {
                try {
                    byte[] mp3 = runEdge(python, v, textFile, work.resolve("edge.mp3"));
                    return new Speech(toDeviceWave(mp3), ENGINE_EDGE, v, null);
                } catch (TitleVoiceException ex) {
                    if (!ENGINE_AUTO.equals(e) || powershell == null) {
                        throw ex;
                    }
                    LOGGER.warn("edge-tts failed, falling back to the system voice: " + ex.getMessage());
                    fallbackReason = ex.getMessage();
                }
            }
            byte[] wave = runSystem(textFile, work.resolve("system.wav"));
            return new Speech(toDeviceWave(wave), ENGINE_SYSTEM, null, fallbackReason);
        } catch (IOException ex) {
            throw new TitleVoiceException(TitleVoiceException.Code.TTS_FAILED, "\u00c9chec de la synth\u00e8se vocale : " + ex.getMessage(), ex);
        } finally {
            deleteQuietly(work);
        }
    }

    /** The edge command line. Text and output are files; the voice has been checked against {@link #VOICE_NAME}. */
    static List<String> edgeCommand(String python, String voice, Path textFile, Path out) {
        return List.of(python, "-I", "-m", "edge_tts",
                "--voice", voice,
                "--file", textFile.toString(),
                "--write-media", out.toString());
    }

    static List<String> systemCommand(String powershell) {
        return List.of(powershell, "-NoProfile", "-NonInteractive", "-Command", SAPI_SCRIPT);
    }

    static Map<String, String> systemEnvironment(Path textFile, Path out) {
        Map<String, String> env = new HashMap<>();
        env.put("STUDIO_TTS_TEXT", textFile.toString());
        env.put("STUDIO_TTS_OUT", out.toString());
        return env;
    }

    private byte[] runEdge(String python, String voice, Path textFile, Path out) {
        return runToFile("voix en ligne (edge-tts)", edgeCommand(python, voice, textFile, out), Map.of(), out);
    }

    private byte[] runSystem(Path textFile, Path out) {
        return runToFile("voix de l'ordinateur", systemCommand(powershell), systemEnvironment(textFile, out), out);
    }

    private byte[] runToFile(String label, List<String> command, Map<String, String> env, Path out) {
        try {
            int code = runner.run(command, env, timeout);
            if (code != 0) {
                throw new TitleVoiceException(TitleVoiceException.Code.TTS_FAILED, "La " + label + " a \u00e9chou\u00e9 (code " + code + ")");
            }
            if (!Files.isRegularFile(out) || Files.size(out) == 0) {
                throw new TitleVoiceException(TitleVoiceException.Code.TTS_FAILED, "La " + label + " n'a produit aucun son");
            }
            return Files.readAllBytes(out);
        } catch (CommandTimeoutException e) {
            throw new TitleVoiceException(TitleVoiceException.Code.TTS_FAILED,
                    "La " + label + " n'a pas r\u00e9pondu en " + timeout.getSeconds() + " s", e);
        } catch (IOException e) {
            throw new TitleVoiceException(TitleVoiceException.Code.TTS_FAILED, "La " + label + " n'a pas pu \u00eatre lanc\u00e9e : " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TitleVoiceException(TitleVoiceException.Code.TTS_FAILED, "Synth\u00e8se interrompue", e);
        }
    }

    /** Any audio the JVM can read (MP3 through mp3spi, WAVE) \u2192 PCM 16 bits mono 32 kHz, padded with silence. */
    static byte[] toDeviceWave(byte[] audio) {
        try {
            byte[] wave = AudioConversion.anyToWave(audio);
            AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wave));
            AudioFormat format = in.getFormat();
            byte[] pcm = in.readAllBytes();
            if (pcm.length == 0) {
                throw new TitleVoiceException(TitleVoiceException.Code.TTS_FAILED, "La synth\u00e8se a produit un son vide");
            }
            int frame = Math.max(1, format.getFrameSize());
            int padFrames = Math.round(format.getFrameRate() * PADDING_MILLIS / 1000f);
            byte[] silence = new byte[padFrames * frame];
            ByteArrayOutputStream padded = new ByteArrayOutputStream(pcm.length + 2 * silence.length);
            padded.writeBytes(silence);
            padded.writeBytes(pcm);
            padded.writeBytes(silence);
            byte[] all = padded.toByteArray();
            AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(all), format, all.length / frame);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (TitleVoiceException e) {
            throw e;
        } catch (Exception e) {
            throw new TitleVoiceException(TitleVoiceException.Code.TTS_FAILED, "Le son produit n'a pas pu \u00eatre converti : " + e.getMessage(), e);
        }
    }

    /** Duration of a WAVE in milliseconds. */
    public static long durationMillis(byte[] wave) {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wave))) {
            return Math.round(in.getFrameLength() * 1000.0 / in.getFormat().getFrameRate());
        } catch (Exception e) {
            return 0;
        }
    }

    private Path createWorkDir() {
        try {
            Files.createDirectories(tmpRoot);
            return Files.createTempDirectory(tmpRoot, "tts-");
        } catch (IOException e) {
            throw new TitleVoiceException(TitleVoiceException.Code.TTS_FAILED, "Dossier temporaire inaccessible : " + e.getMessage(), e);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                if (!p.toFile().delete() && p.toFile().exists()) {
                    LOGGER.warn("Could not remove TTS temporary file " + p);
                }
            });
        } catch (IOException e) {
            LOGGER.debug("Could not remove " + dir);
        }
    }

    /** Starts the process directly (no shell), discards its output, kills it on timeout. */
    static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public int run(List<String> command, Map<String, String> environment, Duration timeout) throws IOException, InterruptedException {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().putAll(environment);
            pb.redirectInput(ProcessBuilder.Redirect.from(nullFile()));
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            boolean finished = false;
            try {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    throw new CommandTimeoutException("timeout after " + timeout.getSeconds() + " s");
                }
                return process.exitValue();
            } finally {
                // Timed out, or interrupted while waiting: never leave the process (or what it
                // started) running, and let it release its output files before they are removed.
                if (!finished) {
                    kill(process);
                }
            }
        }

        private static void kill(Process process) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                process.onExit().get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.warn("TTS process did not exit after being killed");
            }
        }

        private static File nullFile() {
            return new File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "NUL" : "/dev/null");
        }
    }
}
