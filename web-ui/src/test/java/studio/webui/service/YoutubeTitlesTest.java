/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Same results as Lunii Pack's title functions on the same inputs. */
class YoutubeTitlesTest {

    @Test
    void cleansNoiseFromTitles() {
        assertEquals("Le petit chaperon rouge", YoutubeTitles.clean("Le petit chaperon rouge [HD] #conte", null));
        assertEquals("Le loup et l'agneau", YoutubeTitles.clean("\ud83d\udc3a Le loup et l'agneau (Vid\u00e9o officielle)", null));
        assertEquals("Les Dalton - Le toutou", YoutubeTitles.clean("Les Dalton | Le toutou | Dessin anim\u00e9", null));
        assertEquals("Les Dalton - Le toutou", YoutubeTitles.clean("Les Dalton - Le toutou (S01E05) - Episode complet", null));
        assertEquals("La plage", YoutubeTitles.clean("La plage - Contes du soir", "Contes du soir"));
        assertEquals("Histoire du soir", YoutubeTitles.clean("HISTOIRE DU SOIR", null));
        assertEquals("\u00c9pisode 3 - Le d\u00e9part", YoutubeTitles.clean("\u00c9pisode 3 | Le d\u00e9part", null));
    }

    @Test
    void keepsTheTitleWhenNothingWouldBeLeft() {
        assertEquals("[HD]", YoutubeTitles.clean("[HD]", null));
    }

    @Test
    void removesTheSeriesPrefix() {
        assertEquals(List.of("Le toutou", "La ru\u00e9e vers l'or", "Le magot"),
                YoutubeTitles.removeCommonPrefix(List.of("Les Dalton - Le toutou", "Les Daltons - La ru\u00e9e vers l'or", "Les Dalton - Le magot")));
        assertEquals(List.of("Seul titre"), YoutubeTitles.removeCommonPrefix(List.of("Seul titre")));
        // Nothing shared: unchanged
        assertEquals(List.of("Un", "Deux"), YoutubeTitles.removeCommonPrefix(List.of("Un", "Deux")));
    }

    @Test
    void readsSeasonsAndEpisodes() {
        assertArrayEquals(new int[]{1, 5}, YoutubeTitles.seasonEpisode("Les Dalton S01E05 Le toutou"));
        assertArrayEquals(new int[]{2, 3}, YoutubeTitles.seasonEpisode("Saison 2 - \u00c9pisode 3"));
        assertArrayEquals(new int[]{3, 12}, YoutubeTitles.seasonEpisode("Titre 3x12"));
        assertNull(YoutubeTitles.seasonEpisode("Pas de num\u00e9ro"));

        YoutubeTitles.SeasonPlan plan = YoutubeTitles.seasonPlan(List.of("S02E01 a", "S01E02 b", "S01E01 c", "autre"));
        assertEquals(List.of(2, 1, 0, 3), plan.order);
        assertEquals(Arrays.asList("Saison 2", "Saison 1", "Saison 1", null), plan.groups);
        assertNull(YoutubeTitles.seasonPlan(List.of("S01E01 a", "b", "c")));
    }

    @Test
    void numbersDuplicates() {
        assertEquals(List.of("A", "B", "a 2", "A 3"), YoutubeTitles.numberDuplicates(List.of("A", "B", "a", "A")));
    }

    @Test
    void oneMenuWhenTheStoriesFit() {
        List<YoutubeTitles.Menu> menus = YoutubeTitles.menus(Arrays.asList("x", "y", null), 10);
        assertEquals(1, menus.size());
        assertNull(menus.get(0).name);
        assertEquals(List.of(0, 1, 2), menus.get(0).stories);
    }

    @Test
    void subMenusPerGroupInBalancedSlices() {
        String[] groups = new String[14];
        Arrays.fill(groups, 0, 12, "Saison 1");
        groups[12] = null;
        groups[13] = null;
        List<YoutubeTitles.Menu> menus = YoutubeTitles.menus(Arrays.asList(groups), 10);
        assertEquals(3, menus.size());
        assertEquals("Saison 1, 1 \u00e0 6", menus.get(0).name);
        assertEquals(6, menus.get(0).stories.size());
        assertEquals("Saison 1, 7 \u00e0 12", menus.get(1).name);
        assertEquals("Autres histoires", menus.get(2).name);
        assertEquals(List.of(12, 13), menus.get(2).stories);

        String[] one = new String[11];
        Arrays.fill(one, "Playlist");
        List<YoutubeTitles.Menu> sliced = YoutubeTitles.menus(Arrays.asList(one), 10);
        assertEquals("\u00c9pisodes 1 \u00e0 6", sliced.get(0).name);
        assertEquals("\u00c9pisodes 7 \u00e0 11", sliced.get(1).name);
        // 0: never sub-menus
        assertEquals(1, YoutubeTitles.menus(Arrays.asList(one), 0).size());
    }

    @Test
    void fileNamesAndSlugs() {
        assertEquals("Contes - soir- 1", YoutubeTitles.fileName("Contes / soir: 1"));
        assertEquals("pack", YoutubeTitles.fileName(" .. "));
        // Windows reserved names, whatever the case or the extension
        assertEquals("Nul_", YoutubeTitles.fileName("Nul"));
        assertEquals("con_", YoutubeTitles.fileName("con"));
        assertEquals("COM1_.ok", YoutubeTitles.fileName("COM1.ok"));
        assertEquals("Nulle part", YoutubeTitles.fileName("Nulle part"));
        assertEquals("les-histoires-d-ete", YoutubeTitles.slug("Les histoires d'\u00e9t\u00e9 !"));
        assertEquals("pack", YoutubeTitles.slug("\u2728"));
    }
}
