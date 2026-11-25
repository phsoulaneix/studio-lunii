/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Titles of YouTube videos made fit to be read aloud and shown on the device, as Lunii Pack does
 * ({@code nettoyer_titre}, {@code retirer_prefixe_commun}, {@code plan_saisons},
 * {@code dedoublonner_titres}, {@code former_groupes}).
 *
 * <p>Pure functions, no I/O.
 */
public final class YoutubeTitles {

    private YoutubeTitles() {
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final String PARASITES = "officiel(?:le)?|official|video|vid\u00e9o|clip|lyrics?|paroles|audio|"
            + "hd|fhd|4k|1080p|720p|full|complets?|entiers?|int\u00e9gral(?:e)?|version\\s+longue|"
            + "livre\\s+audio|histoire\\s+pour\\s+enfants?|conte\\s+pour\\s+enfants?";

    private static final Pattern HASHTAG = Pattern.compile("#\\w+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]|\u3010[^\u3011]*\u3011");
    private static final Pattern PARENTHESES = Pattern.compile("\\((?:[^)]*\\b(?:" + PARASITES + ")\\b[^)]*)\\)", FLAGS);
    private static final Pattern SEASON_EPISODE_TAG = Pattern.compile("\\(?\\b[Ss]\\d{1,2}\\s*[Ee]\\d{1,3}\\b\\)?", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern FULL_EPISODE = Pattern.compile(
            "\\s*[-\u2013\u2014_|]?\\s*full\\s+episodes?(?:\\s+in)?(?:\\s+(?:hd|4k|english|french))?\\s*$", FLAGS);
    private static final Pattern TRAILING_EPISODE = Pattern.compile(
            "\\s+(?:l['\u2019])?(?:\u00e9|e)pisodes?(?!\\s*n?\u00b0?\\s*\\d)(?:\\s+(?:entiers?|complets?|int[\u00e9e]grale?s?))?"
                    + "(?:\\s+en)?(?:\\s+(?:hd|fhd|4k|vf|fran\u00e7ais))?\\s*$", FLAGS);
    private static final Pattern NUMBERED_EPISODE = Pattern.compile("(?:\u00e9|e)pisode\\s*n?\u00b0?\\s*\\d+", FLAGS);
    private static final Pattern PARASITE_WORDS = Pattern.compile("\\b(?:" + PARASITES + "|\u00e9pisodes?|episodes?|dessins?\\s+anim\u00e9s?|"
            + "cartoons?|compilation|nouveaux?|nouvelles?|en\\s+fran\u00e7ais|"
            + "\\d+\\s*h(?:eures?)?|\\d+\\s*min(?:utes)?|en|in|vf)\\b", FLAGS);
    private static final Pattern NOT_LETTERS = Pattern.compile("[\\W\\d_]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SEPARATORS = Pattern.compile("\\s*[|\uff5c\u00a6\u2502\u2503\u2223\ufe31\u23ae]\\s*|\\s+[-\u2013\u2014_]+\\s+");
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final String STRIP_CHARS = " -\u2013\u2014_:\u00b7.,";

    /** Cleans a raw YouTube title; {@code author} is the channel name, removed from the title, or null. */
    public static String clean(String title, String author) {
        if (title == null) {
            return "";
        }
        String t = Normalizer.normalize(title, Normalizer.Form.NFC);
        // Emojis and pictograms
        StringBuilder sb = new StringBuilder(t.length());
        t.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (type != Character.OTHER_SYMBOL && type != Character.SURROGATE && type != Character.PRIVATE_USE
                    && cp != 0x200d && cp != 0xfe0f) {
                sb.appendCodePoint(cp);
            }
        });
        t = sb.toString();
        t = HASHTAG.matcher(t).replaceAll(" ");
        // Brackets: always noise; parentheses: only with noise words
        t = BRACKETS.matcher(t).replaceAll(" ");
        t = PARENTHESES.matcher(t).replaceAll(" ");
        // Season and episode numbers: "(S01E05)", "S1E5", "S01 E05"
        t = SEASON_EPISODE_TAG.matcher(t).replaceAll(" ");
        t = FULL_EPISODE.matcher(t).replaceAll("");
        t = TRAILING_EPISODE.matcher(t).replaceAll("");

        // Segments separated by "|" or spaced dashes: those that are the channel name or pure
        // noise are dropped, the others kept (often "Series | Episode")
        List<String> segments = new ArrayList<>();
        for (String s : SEPARATORS.split(t, -1)) {
            String seg = s.trim();
            segments.add(seg.length() > 4 && isUpper(seg) ? capitalize(seg) : seg);
        }
        List<String> kept = segments.stream().filter(s -> !isParasite(s, author)).collect(Collectors.toList());
        t = kept.isEmpty() ? (segments.isEmpty() ? "" : segments.get(0)) : String.join(" - ", kept);
        if (author != null && !author.isBlank()) {
            String a = Pattern.quote(author.trim());
            t = Pattern.compile("\\s*[-\u2013\u2014:]\\s*" + a + "\\s*$", FLAGS).matcher(t).replaceAll("");
            t = Pattern.compile("^\\s*" + a + "\\s*[-\u2013\u2014:]\\s*", FLAGS).matcher(t).replaceAll("");
        }
        t = strip(SPACES.matcher(t).replaceAll(" "), STRIP_CHARS);
        if (t.length() > 4 && isUpper(t)) {
            t = capitalize(t);
        }
        return t.isEmpty() ? title.trim() : t;
    }

    private static boolean isParasite(String segment, String author) {
        String s = strip(segment, STRIP_CHARS);
        if (s.isEmpty()) {
            return true;
        }
        if (NUMBERED_EPISODE.matcher(s).find()) {
            return false;
        }
        if (author != null && s.equalsIgnoreCase(author.trim())) {
            return true;
        }
        String rest = PARASITE_WORDS.matcher(s).replaceAll("");
        return NOT_LETTERS.matcher(rest).replaceAll("").isEmpty();
    }

    /** Python's {@code str.isupper()}: at least one cased character, all of them upper case. */
    static boolean isUpper(String s) {
        boolean cased = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLowerCase(cp)) {
                return false;
            }
            if (Character.isUpperCase(cp) || Character.isTitleCase(cp)) {
                cased = true;
            }
            i += Character.charCount(cp);
        }
        return cased;
    }

    /** Python's {@code str.capitalize()}: first character upper case, the rest lower case. */
    static String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        int first = s.codePointAt(0);
        int n = Character.charCount(first);
        return new String(Character.toChars(Character.toUpperCase(first))) + s.substring(n).toLowerCase(Locale.ROOT);
    }

    private static String upperFirst(String s) {
        if (s.isEmpty()) {
            return s;
        }
        int first = s.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first))) + s.substring(Character.charCount(first));
    }

    static String strip(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    /** Removes the series prefix shared by most titles of a batch ("Les Dalton - \u2026"). */
    public static List<String> removeCommonPrefix(List<String> input) {
        if (input.size() < 2) {
            return new ArrayList<>(input);
        }
        List<String> titles = new ArrayList<>(input);
        // "Series - " present on most titles: removed where it is ("Les Daltons" = "Les Dalton")
        List<String> heads = new ArrayList<>();
        for (String t : titles) {
            if (t.contains(" - ")) {
                heads.add(headKey(t.substring(0, t.indexOf(" - "))));
            }
        }
        if (!heads.isEmpty()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            heads.forEach(h -> counts.merge(h, 1, Integer::sum));
            Map.Entry<String, Integer> best = counts.entrySet().stream().max(Map.Entry.comparingByValue()).get();
            if (best.getValue() >= 2 && best.getValue() * 2 >= titles.size()) {
                for (int k = 0; k < titles.size(); k++) {
                    String t = titles.get(k);
                    int sep = t.indexOf(" - ");
                    if (sep >= 0) {
                        String after = t.substring(sep + 3).trim();
                        if (headKey(t.substring(0, sep)).equals(best.getKey()) && after.length() >= 3) {
                            titles.set(k, upperFirst(after));
                        }
                    }
                }
            }
        }
        String prefix = titles.get(0);
        for (String t : titles.subList(1, titles.size())) {
            while (!prefix.isEmpty() && !t.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
        }
        int sep = -1;
        for (char c : "-\u2013\u2014:|\u00b7".toCharArray()) {
            sep = Math.max(sep, prefix.lastIndexOf(c));
        }
        if (sep > 2) {
            List<String> rests = new ArrayList<>();
            for (String t : titles) {
                rests.add(strip(t.substring(sep + 1), " -\u2013\u2014:|\u00b7"));
            }
            if (rests.stream().allMatch(r -> r.length() >= 3)) {
                return rests.stream().map(YoutubeTitles::upperFirst).collect(Collectors.toList());
            }
        }
        return titles;
    }

    private static String headKey(String head) {
        String k = head.toLowerCase(Locale.ROOT).trim();
        while (k.endsWith("s")) {
            k = k.substring(0, k.length() - 1);
        }
        return k;
    }

    // -- seasons: "S01E05", "Saison 2 - \u00c9pisode 5", "2x05"

    private static final List<Pattern> SEASON_PATTERNS = List.of(
            Pattern.compile("(?<![A-Za-z0-9])S\\s*0*(\\d{1,2})\\s*[-_. ]?\\s*(?:E|EP|\u00c9p|Ep)\\.?\\s*0*(\\d{1,3})(?!\\d)", FLAGS),
            Pattern.compile("saison\\s*0*(\\d{1,2})\\W{0,6}(?:\u00e9|e)p(?:isode)?\\.?\\s*n?\u00b0?\\s*0*(\\d{1,3})(?!\\d)", FLAGS),
            Pattern.compile("(?<!\\d)(\\d{1,2})x(\\d{2,3})(?!\\d)"));

    /** Season and episode numbers of a raw title, or null. */
    public static int[] seasonEpisode(String title) {
        for (Pattern p : SEASON_PATTERNS) {
            Matcher m = p.matcher(title == null ? "" : title);
            if (m.find()) {
                return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
            }
        }
        return null;
    }

    /** A season plan: the new order of the titles and the group ("Saison N", or null) of each. */
    public static final class SeasonPlan {
        public final List<Integer> order;
        public final List<String> groups;

        SeasonPlan(List<Integer> order, List<String> groups) {
            this.order = order;
            this.groups = groups;
        }
    }

    /** When most raw titles carry season/episode numbers: sorted by (season, episode), grouped by season. Else null. */
    public static SeasonPlan seasonPlan(List<String> rawTitles) {
        List<int[]> se = rawTitles.stream().map(YoutubeTitles::seasonEpisode).collect(Collectors.toList());
        long numbered = se.stream().filter(x -> x != null).count();
        if (numbered < 2 || numbered * 2 < se.size()) {
            return null;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < se.size(); i++) {
            if (se.get(i) != null) {
                order.add(i);
            }
        }
        order.sort(Comparator.<Integer>comparingInt(i -> se.get(i)[0]).thenComparingInt(i -> se.get(i)[1]).thenComparingInt(i -> i));
        for (int i = 0; i < se.size(); i++) {
            if (se.get(i) == null) {
                order.add(i);
            }
        }
        List<String> groups = se.stream().map(x -> x == null ? null : "Saison " + x[0]).collect(Collectors.toList());
        return new SeasonPlan(order, groups);
    }

    /** Numbers the titles that remain identical ("Titre", "Titre 2"\u2026), ignoring case. */
    public static List<String> numberDuplicates(List<String> titles) {
        Map<String, Integer> seen = new HashMap<>();
        List<String> res = new ArrayList<>();
        for (String t : titles) {
            int n = seen.merge(t.toLowerCase(Locale.ROOT), 1, Integer::sum);
            res.add(n > 1 ? t + " " + n : t);
        }
        return res;
    }

    // -- menus

    /** A menu of the pack: a name (null for the single root menu) and the indexes of its stories. */
    public static final class Menu {
        public final String name;
        public final List<Integer> stories;
        /** The group this menu was cut from, for a slice of a large group. */
        public final String base;

        Menu(String name, List<Integer> stories, String base) {
            this.name = name;
            this.stories = stories;
            this.base = base;
        }
    }

    public static final String OTHER_STORIES = "Autres histoires";

    /**
     * One unnamed menu when the stories fit in {@code menuSize} entries; otherwise one sub-menu per
     * group, in order of first appearance, large groups cut into balanced slices.
     */
    public static List<Menu> menus(List<String> groups, int menuSize) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            all.add(i);
        }
        if (menuSize <= 0 || groups.size() <= menuSize) {
            return List.of(new Menu(null, all, null));
        }
        LinkedHashMap<String, List<Integer>> byName = new LinkedHashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            String name = groups.get(i) == null || groups.get(i).isBlank() ? OTHER_STORIES : groups.get(i);
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
        }
        boolean single = byName.size() == 1;
        List<Menu> res = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> g : byName.entrySet()) {
            List<Integer> eps = g.getValue();
            if (eps.size() <= menuSize) {
                res.add(new Menu(g.getKey(), eps, g.getKey()));
                continue;
            }
            // Balanced slices: 12 stories, menus of 10 -> 6 + 6 (not 10 + 2)
            int n = eps.size();
            int count = (n + menuSize - 1) / menuSize;
            int k = 0;
            for (int s = 0; s < count; s++) {
                int len = n / count + (s < n % count ? 1 : 0);
                String bounds = len > 1 ? (k + 1) + " \u00e0 " + (k + len) : String.valueOf(k + 1);
                res.add(new Menu(single ? "\u00c9pisodes " + bounds : g.getKey() + ", " + bounds,
                        new ArrayList<>(eps.subList(k, k + len)), g.getKey()));
                k += len;
            }
        }
        return res;
    }

    private static final Pattern RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])");

    /** A file name for the pack, without characters or names Windows refuses ("Nul", "Con"\u2026). */
    public static String fileName(String title) {
        String n = title == null ? "" : title.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "-");
        n = strip(n.trim(), " .");
        if (n.length() > 80) {
            n = strip(n.substring(0, 80), " .");
        }
        if (n.isEmpty()) {
            return "pack";
        }
        // Reserved whatever the extension: "nul.zip" is the null device too
        int dot = n.contains(".") ? n.indexOf('.') : n.length();
        String base = n.substring(0, dot).trim();
        return RESERVED.matcher(base).matches() ? base + "_" + n.substring(dot) : n;
    }

    /** An ASCII slug of the title, for stable node identifiers. */
    public static String slug(String title) {
        String t = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKD).replaceAll("[^\\p{ASCII}]", "");
        t = t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        t = strip(t, "-");
        return t.isEmpty() ? "pack" : t;
    }
}
