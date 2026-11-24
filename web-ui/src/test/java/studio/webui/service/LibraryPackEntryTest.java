/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.metadata.DatabaseMetadataService;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The editor opens a library pack entry by entry: story.json first, then each asset, each read
 * through the archive's central directory instead of downloading and unzipping the whole archive in
 * the browser. These tests pin what one entry request may return, and what it may not reach.
 */
class LibraryPackEntryTest {

    private static final byte[] STORY = "{\"title\":\"Entrée\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AUDIO = new byte[200_000];

    @TempDir
    Path root;

    private Path library;
    private LibraryService service;
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        for (int i = 0; i < AUDIO.length; i++) {
            AUDIO[i] = (byte) (i * 31 + i / 7);
        }
        library = root.resolve("library");
        Files.createDirectories(library);
        Files.createDirectories(root.resolve("db"));
        Path work = Files.createTempDirectory("studio-entry-work");
        work.toFile().deleteOnExit();
        setProperty(LibraryService.LOCAL_LIBRARY_PROP, library + File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, work + File.separator);
        setProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP,
                root.resolve("db").resolve("conversions.json").toString());
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP,
                root.resolve("db").resolve("unofficial.json").toString());
        service = new LibraryService(new DatabaseMetadataService(true));
    }

    @AfterEach
    void tearDown() {
        previousProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Test
    @DisplayName("story.json and an asset are read, whether stored or deflated")
    void readsStoredAndDeflatedEntries() throws IOException {
        writePack(library.resolve("pack.zip"));

        assertArrayEquals(STORY, service.readArchivePackEntry("pack.zip", "story.json").orElseThrow());
        assertArrayEquals(AUDIO, service.readArchivePackEntry("pack.zip", "assets/stored.mp3").orElseThrow());
        assertArrayEquals(AUDIO, service.readArchivePackEntry("pack.zip", "assets/deflated.mp3").orElseThrow());
    }

    @Test
    @DisplayName("a missing entry, a directory entry, a missing pack or a non-archive pack yield nothing")
    void absentEntriesYieldNothing() throws IOException {
        writePack(library.resolve("pack.zip"));
        Files.write(library.resolve("pack.pack"), STORY);

        assertFalse(service.readArchivePackEntry("pack.zip", "thumbnail.png").isPresent());
        assertFalse(service.readArchivePackEntry("pack.zip", "assets/").isPresent());
        assertFalse(service.readArchivePackEntry("pack.zip", "").isPresent());
        assertFalse(service.readArchivePackEntry("pack.zip", null).isPresent());
        assertFalse(service.readArchivePackEntry("other.zip", "story.json").isPresent());
        assertFalse(service.readArchivePackEntry("pack.pack", "story.json").isPresent());
    }

    @Test
    @DisplayName("an archive outside the library is not read")
    void archiveOutsideTheLibraryIsNotRead() throws IOException {
        writePack(root.resolve("outside.zip"));

        assertFalse(service.readArchivePackEntry("../outside.zip", "story.json").isPresent());
        assertFalse(service.readArchivePackEntry(root.resolve("outside.zip").toString(), "story.json").isPresent());
    }

    private static void writePack(Path path) throws IOException {
        try (OutputStream out = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("story.json"));
            zip.write(STORY);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/"));
            zip.closeEntry();
            ZipEntry stored = new ZipEntry("assets/stored.mp3");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(AUDIO.length);
            CRC32 crc = new CRC32();
            crc.update(AUDIO);
            stored.setCrc(crc.getValue());
            zip.putNextEntry(stored);
            zip.write(AUDIO);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/deflated.mp3"));
            zip.write(AUDIO);
            zip.closeEntry();
        }
    }

    private void setProperty(String key, String value) {
        previousProperties.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }
}
