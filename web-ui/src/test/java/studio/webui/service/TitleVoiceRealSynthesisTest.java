/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real synthesis with the engines installed on this machine. Opt-in, since it needs Python with
 * edge-tts and the network, or Windows:
 * {@code mvn test -Dtest=TitleVoiceRealSynthesisTest -Dstudio.tts.realCheck=true -Dstudio.tts.python=…}
 * The WAVE files are left in {@code target/tts-check} to be listened to.
 */
@EnabledIfSystemProperty(named = "studio.tts.realCheck", matches = "true")
class TitleVoiceRealSynthesisTest {

    private static final String TEXT = "Le petit chaperon rouge et le grand m\u00e9chant loup";

    /** Every voice offered in the editor, the ones Lunii Pack offers. */
    @Test
    void edge() throws Exception {
        TitleVoiceService service = new TitleVoiceService();
        for (String voice : TitleVoiceService.SUGGESTED_VOICES) {
            TitleVoiceService.Speech speech = service.speak(TEXT, "edge", voice);
            assertEquals(voice, speech.voice);
            check(speech, "edge", voice);
        }
    }

    @Test
    void system() throws Exception {
        TitleVoiceService.Speech speech = new TitleVoiceService().speak(TEXT, "system", null);
        check(speech, "system", "system");
    }

    private static void check(TitleVoiceService.Speech speech, String engine, String name) throws Exception {
        assertEquals(engine, speech.engine);
        long ms = TitleVoiceService.durationMillis(speech.wave);
        Path out = Paths.get("target", "tts-check", name + ".wav");
        Files.createDirectories(out.getParent());
        Files.write(out, speech.wave);
        System.out.println(engine + ": " + out.toAbsolutePath() + " " + speech.wave.length + " bytes, " + ms + " ms");
        assertTrue(ms > 1500, "speech should last more than the padding: " + ms);
    }
}
