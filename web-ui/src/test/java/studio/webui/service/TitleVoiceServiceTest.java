/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TitleVoiceServiceTest {

    @TempDir
    Path tmp;

    /** Records every command and plays the part of python / powershell. */
    static class FakeRunner implements TitleVoiceService.CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        final List<Map<String, String>> environments = new ArrayList<>();
        int probeExit = 0;
        int edgeExit = 0;
        int systemExit = 0;
        boolean edgeTimesOut = false;

        @Override
        public int run(List<String> command, Map<String, String> env, Duration timeout) throws IOException {
            commands.add(command);
            environments.add(env);
            if (command.contains("-c")) {
                return probeExit;
            }
            if (command.contains("edge_tts")) {
                if (edgeTimesOut) {
                    throw new TitleVoiceService.CommandTimeoutException("timeout");
                }
                if (edgeExit == 0) {
                    Files.write(Paths.get(command.get(command.indexOf("--write-media") + 1)), wave(22050, 2));
                }
                return edgeExit;
            }
            if (systemExit == 0) {
                Files.write(Paths.get(env.get("STUDIO_TTS_OUT")), wave(32000, 1));
            }
            return systemExit;
        }
    }

    public static byte[] wave(float rate, int channels) throws IOException {
        AudioFormat format = new AudioFormat(rate, 16, channels, true, false);
        int frames = (int) (rate / 2);
        byte[] pcm = new byte[frames * format.getFrameSize()];
        for (int i = 0; i < frames; i++) {
            short s = (short) (Math.sin(i * 2 * Math.PI * 440 / rate) * 8000);
            for (int c = 0; c < channels; c++) {
                int o = (i * channels + c) * 2;
                pcm[o] = (byte) s;
                pcm[o + 1] = (byte) (s >> 8);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), format, frames), AudioFileFormat.Type.WAVE, out);
        return out.toByteArray();
    }

    private TitleVoiceService service(FakeRunner runner, boolean withPowershell) {
        return new TitleVoiceService(runner, List.of("python-test"), withPowershell ? "C:\\ps.exe" : null, tmp, Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("empty, blank, missing or too long text is refused before anything runs")
    void validation() {
        FakeRunner runner = new FakeRunner();
        TitleVoiceService s = service(runner, true);
        for (String text : new String[]{null, "", "   ", "\n\t\u0000", "a".repeat(TitleVoiceService.MAX_TEXT_LENGTH + 1)}) {
            TitleVoiceException e = assertThrows(TitleVoiceException.class, () -> s.speak(text, null, null));
            assertEquals(TitleVoiceException.Code.INVALID_REQUEST, e.getCode());
        }
        assertEquals(TitleVoiceException.Code.INVALID_REQUEST,
                assertThrows(TitleVoiceException.class, () -> s.speak("Bonjour", "espeak", null)).getCode());
        assertEquals(TitleVoiceException.Code.INVALID_REQUEST,
                assertThrows(TitleVoiceException.class, () -> s.speak("Bonjour", "edge", "fr-FR-Denise\" & calc")).getCode());
        assertTrue(runner.commands.isEmpty());
        assertEquals("a b c", TitleVoiceService.normalizeText("  a\r\n b\u0007\tc "));
        assertEquals(TitleVoiceService.MAX_TEXT_LENGTH,
                TitleVoiceService.normalizeText("\u00e9".repeat(TitleVoiceService.MAX_TEXT_LENGTH)).length());
    }

    @Test
    @DisplayName("edge-tts runs without a shell, the text only in a file, and gives a device WAVE")
    void edge() throws Exception {
        FakeRunner runner = new FakeRunner();
        String title = "Le loup & l'agneau \"vite\" ; $(rm) | %PATH%";
        TitleVoiceService.Speech speech = service(runner, true).speak(title, "auto", "fr-FR-HenriNeural");
        assertEquals("edge", speech.engine);
        assertEquals("fr-FR-HenriNeural", speech.voice);
        assertNull(speech.fallbackReason);

        List<String> cmd = runner.commands.get(1);
        assertEquals("python-test", cmd.get(0));
        assertEquals(List.of("-I", "-m", "edge_tts", "--voice", "fr-FR-HenriNeural", "--file"), cmd.subList(1, 7));
        for (List<String> c : runner.commands) {
            assertFalse(c.stream().anyMatch(a -> a.contains("loup")), "the text must not be an argument");
            assertFalse(c.get(0).toLowerCase().matches(".*(cmd|sh|bash)(\\.exe)?"));
        }

        AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(speech.wave));
        assertEquals(32000f, in.getFormat().getSampleRate());
        assertEquals(1, in.getFormat().getChannels());
        assertEquals(16, in.getFormat().getSampleSizeInBits());
        // 0.5 s of speech plus 2 \u00d7 400 ms of silence
        assertEquals(1300, TitleVoiceService.durationMillis(speech.wave), 20);
        try (var left = Files.list(tmp)) {
            assertEquals(0, left.count(), "the work folder is removed");
        }
    }

    @Test
    @DisplayName("no engine at all is a typed TTS_UNAVAILABLE, so is an explicit engine that is missing")
    void unavailable() {
        FakeRunner runner = new FakeRunner();
        runner.probeExit = 1;
        TitleVoiceException none = assertThrows(TitleVoiceException.class, () -> service(runner, false).speak("Bonjour", null, null));
        assertEquals(TitleVoiceException.Code.TTS_UNAVAILABLE, none.getCode());
        assertEquals(503, none.getCode().status);

        FakeRunner r2 = new FakeRunner();
        r2.probeExit = 1;
        assertEquals(TitleVoiceException.Code.TTS_UNAVAILABLE,
                assertThrows(TitleVoiceException.class, () -> service(r2, true).speak("Bonjour", "edge", null)).getCode());
        assertEquals(TitleVoiceException.Code.TTS_UNAVAILABLE,
                assertThrows(TitleVoiceException.class, () -> service(new FakeRunner(), false).speak("Bonjour", "system", null)).getCode());
    }

    @Test
    @DisplayName("auto falls back to the system voice when edge-tts fails; the system voice gets its text through a file")
    void fallback() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.edgeExit = 1;
        TitleVoiceService.Speech speech = service(runner, true).speak("Bonjour", "auto", null);
        assertEquals("system", speech.engine);
        assertTrue(speech.fallbackReason.contains("edge-tts"));
        List<String> ps = runner.commands.get(runner.commands.size() - 1);
        assertEquals(List.of("C:\\ps.exe", "-NoProfile", "-NonInteractive", "-Command", TitleVoiceService.SAPI_SCRIPT), ps);
        assertFalse(TitleVoiceService.SAPI_SCRIPT.contains("Bonjour"));
        assertTrue(runner.environments.get(runner.environments.size() - 1).get("STUDIO_TTS_TEXT").endsWith("text.txt"));
        assertTrue(TitleVoiceService.durationMillis(speech.wave) > 1000);

        // Asked for edge explicitly: no fallback, a typed failure
        FakeRunner r2 = new FakeRunner();
        r2.edgeExit = 1;
        assertEquals(TitleVoiceException.Code.TTS_FAILED,
                assertThrows(TitleVoiceException.class, () -> service(r2, true).speak("Bonjour", "edge", null)).getCode());
    }

    @Test
    @DisplayName("a timeout is TTS_FAILED, and an engine that writes nothing too")
    void failures() {
        FakeRunner runner = new FakeRunner();
        runner.edgeTimesOut = true;
        TitleVoiceException e = assertThrows(TitleVoiceException.class, () -> service(runner, false).speak("Bonjour", null, null));
        assertEquals(TitleVoiceException.Code.TTS_FAILED, e.getCode());
        assertTrue(e.getMessage().contains("pas r\u00e9pondu"));

        FakeRunner silent = new FakeRunner() {
            @Override
            public int run(List<String> command, Map<String, String> env, Duration timeout) {
                return 0;
            }
        };
        assertEquals(TitleVoiceException.Code.TTS_FAILED,
                assertThrows(TitleVoiceException.class, () -> service(silent, false).speak("Bonjour", null, null)).getCode());
    }

    @Test
    @DisplayName("the real runner kills a process that outlives its time limit")
    void realRunnerTimeout() {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        TitleVoiceService.CommandRunner real = new TitleVoiceService.ProcessCommandRunner();
        // `java` with no class prints usage and exits quickly: exit code, no timeout
        assertThrows(TitleVoiceService.CommandTimeoutException.class, () ->
                real.run(List.of(java, "-cp", System.getProperty("java.class.path"), Sleeper.class.getName()), Map.of(), Duration.ofMillis(300)));
    }

    public static class Sleeper {
        public static void main(String[] args) throws Exception {
            Thread.sleep(30_000);
        }
    }
}
