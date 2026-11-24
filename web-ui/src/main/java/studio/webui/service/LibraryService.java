/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import studio.core.v1.model.StoryPack;
import studio.core.v1.model.metadata.StoryPackMetadata;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;
import studio.core.v1.reader.binary.BinaryStoryPackReader;
import studio.core.v1.reader.fs.FsStoryPackReader;
import studio.core.v1.utils.PackAssetsCompression;
import studio.core.v1.writer.archive.ArchiveStoryPackWriter;
import studio.core.v1.writer.binary.BinaryStoryPackWriter;
import studio.core.v1.writer.fs.FsStoryPackWriter;
import studio.metadata.DatabaseMetadataService;
import studio.metadata.DatabasePackMetadata;
import studio.webui.model.LibraryPack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LibraryService {
    public static final String LOCAL_LIBRARY_PROP = "studio.library";
    public static final String LOCAL_LIBRARY_PATH = "/.studio/library/";
    public static final String TMP_DIR_PROP = "studio.tmpdir";
    public static final String TMP_DIR_PATH = "/.studio/tmp/";
    /** Largest archive entry served to the editor: far above any image or audio asset of a pack. */
    static final int MAX_PACK_ENTRY_SIZE = 512 * 1024 * 1024;

    private final Logger LOGGER = LoggerFactory.getLogger(LibraryService.class);

    private final DatabaseMetadataService databaseMetadataService;

    /*
     * Parsed packs, keyed by path, alongside the file attributes they were read with.
     *
     * Reading a pack means walking its whole archive, so the result is worth keeping. What is not
     * worth keeping is the assumption that a path still holds the same file: this cache used to
     * store the parse alone and hand it back for five minutes regardless of what had happened on
     * disk, which meant a pack overwritten in place kept reporting its previous title, timestamp and
     * everything else until the entry expired. Overwriting in place is not unusual — saving from the
     * editor writes to the file name the pack was opened under, and dropping the same file into the
     * library twice does the same thing.
     *
     * So an entry now carries the size and modification time observed at the moment of the parse,
     * and is reused only while both still match. See readPackFileCached.
     *
     * Since that check is what keeps an entry honest, a time-based expiry adds nothing but a full
     * re-read of every pack every few minutes. Entries are bounded in number instead: one per file
     * of the library, the extra ones being paths since deleted.
     */
    private final Cache<Path, CachedPack> cachedPacks = Caffeine.newBuilder()
            .maximumSize(2_000).build();

    /**
     * A parse, and the observable state of the file it came from.
     *
     * <p>{@code pack} is an {@code Optional} because a failed parse is a result like any other and
     * is cached like any other — but a failed parse of <em>that</em> version of the file. A pack
     * copied into the library and read while the copy was still running fails to parse, and used to
     * stay invisible for five minutes after the copy finished; bound to the attributes it was read
     * with, the failure is forgotten as soon as the file changes.
     */
    private static final class CachedPack {
        private final Optional<LibraryPack> pack;
        private final long size;
        private final FileTime lastModifiedTime;

        private CachedPack(Optional<LibraryPack> pack, BasicFileAttributes attributes) {
            this.pack = pack;
            this.size = attributes.size();
            this.lastModifiedTime = attributes.lastModifiedTime();
        }

        private boolean matches(BasicFileAttributes attributes) {
            return size == attributes.size() && lastModifiedTime.equals(attributes.lastModifiedTime());
        }
    }

    private final ContentDigest contentDigest = new ContentDigest();

    private final ConversionProvenanceStore provenanceStore = new ConversionProvenanceStore();

    public LibraryService(DatabaseMetadataService databaseMetadataService) {
        this.databaseMetadataService = databaseMetadataService;

        // Create the local library folder if needed
        File libraryFolder = new File(libraryPath());
        if (!libraryFolder.exists() || !libraryFolder.isDirectory()) {
            try {
                Files.createDirectories(Paths.get(libraryPath()));
            } catch (IOException e) {
                LOGGER.error("Failed to initialize local library", e);
                throw new IllegalStateException("Failed to initialize local library");
            }
        }

        // Create the temp folder if needed
        File tmpFolder = new File(tmpDirPath());
        if (!tmpFolder.exists() || !tmpFolder.isDirectory()) {
            try {
                Files.createDirectories(Paths.get(tmpDirPath()));
            } catch (IOException e) {
                LOGGER.error("Failed to initialize temp folder", e);
                throw new IllegalStateException("Failed to initialize temp folder");
            }
        }
    }

    public JsonObject libraryInfos() {
        return new JsonObject()
                .put("path", libraryPath());
    }

    public JsonArray packs() {
        // Check that local library folder exists
        File libraryFolder = new File(libraryPath());
        if (!libraryFolder.exists() || !libraryFolder.isDirectory()) {
            return new JsonArray();
        } else {
            // First, refresh unofficial database with metadata from archive packs
            try (Stream<Path> paths = Files.walk(Paths.get(libraryPath()), 1)) {
                paths
                        .filter(Files::isRegularFile)
                        .filter(path -> !isStagingEntry(path))
                        .filter(path -> path.toString().endsWith(".zip"))
                        .map(this::readPackFileCached)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        // Group packs by UUID
                        .collect(Collectors.groupingBy(p -> p.getMetadata().getUuid()))
                        .entrySet()
                        .forEach(entry -> {
                            List<LibraryPack> packs = entry.getValue();
                            packs.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
                            LibraryPack meta = packs.get(0);
                            LOGGER.debug("Refreshing metadata for pack `" + entry.getKey() + "` from file `" + meta.getPath() + "`");
                            // The parse just made (or reused) above: reading the file a second time,
                            // outside the cache, made every listing re-read every archive in full.
                            databaseMetadataService.refreshUnofficialMetadata(
                                    new DatabasePackMetadata(
                                            meta.getMetadata().getUuid(),
                                            meta.getMetadata().getTitle(),
                                            meta.getMetadata().getDescription(),
                                            Optional.ofNullable(meta.getMetadata().getThumbnail()).map(thumb -> "data:image/png;base64," + Base64.getEncoder().encodeToString(thumb)).orElse(null),
                                            false
                                    )
                            );
                        });
            } catch (IOException e) {
                LOGGER.error("Failed to read packs from local library", e);
                throw new RuntimeException(e);
            }

            // List pack files in library folder
            try (Stream<Path> paths = Files.walk(Paths.get(libraryPath()), 1)) {
                return new JsonArray(
                        paths
                                .filter(path -> !path.equals(Paths.get(libraryPath())))
                                .filter(path -> !isStagingEntry(path))
                                .map(this::readPackFileCached)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                // Group packs by UUID
                                .collect(Collectors.groupingBy(p -> p.getMetadata().getUuid()))
                                .entrySet().stream()
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        entry -> new JsonArray(
                                                entry.getValue().stream()
                                                        // Sort packs by timestamp descending
                                                        .sorted((a,b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
                                                        .map(this::getPackMetadata)
                                                        .collect(Collectors.toList())
                                        )
                                ))
                                .entrySet().stream()
                                .map(entry -> new JsonObject().put("uuid", entry.getKey()).put("packs", entry.getValue()))
                                .collect(Collectors.toList())
                );
            } catch (IOException e) {
                LOGGER.error("Failed to read packs from local library", e);
                throw new RuntimeException(e);
            }
        }
    }

    public Optional<File> getRawPackFile(String packPath) {
        // Only ever a direct library entry: whatever this returns is sent straight to the client.
        return libraryEntry(packPath).map(Path::toFile);
    }

    /**
     * One entry of an archive pack of the library, read through the archive's central directory
     * without going over the rest of it: what the editor needs to open a pack, entry by entry.
     *
     * @return the entry's bytes, or empty if the name is not an archive pack of the library, or if
     * the archive holds no file by that entry name
     */
    public Optional<byte[]> readArchivePackEntry(String packPath, String entryName) throws IOException {
        if (packPath == null || !packPath.toLowerCase(Locale.ROOT).endsWith(".zip")
                || entryName == null || entryName.isEmpty()) {
            return Optional.empty();
        }
        Optional<Path> pack = libraryEntry(packPath).filter(Files::isRegularFile);
        if (pack.isEmpty()) {
            return Optional.empty();
        }
        try (ZipFile zip = ZipFile.builder().setPath(pack.get()).get()) {
            ZipArchiveEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                return Optional.empty();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                if (in == null) {
                    throw new IOException("Unsupported archive entry: " + entryName);
                }
                // Bounded, whatever the entry declares: a deflated entry can expand far beyond it
                byte[] bytes = in.readNBytes(MAX_PACK_ENTRY_SIZE + 1);
                if (bytes.length > MAX_PACK_ENTRY_SIZE) {
                    throw new IOException("Archive entry too large: " + entryName);
                }
                return Optional.of(bytes);
            }
        }
    }

    public Optional<Path> addConvertedRawPackFile(String packPath, Boolean allowEnriched) {
        if (packPath.endsWith(".pack")) {
            LOGGER.error("Pack is already in raw format");
            throw new RuntimeException("Pack is already in raw format");
        }
        boolean fromArchive = packPath.endsWith(".zip");
        return convert(packPath, fromArchive ? "archive" : "FS", "raw", ".pack", "raw",
                fromArchive ? archiveReader() : fsReader(),
                this::uncompressAssetsIfNeeded,
                rawWriter(allowEnriched));
    }

    public Optional<Path> addConvertedArchivePackFile(String packPath) {
        if (packPath.endsWith(".zip")) {
            LOGGER.error("Pack is already in archive format");
            throw new RuntimeException("Pack is already in archive format");
        }
        boolean fromRaw = packPath.endsWith(".pack");
        return convert(packPath, fromRaw ? "raw" : "FS", "archive", ".zip", "archive",
                fromRaw ? binaryReader() : fsReader(),
                fromRaw ? this::compressAssets : this::leaveAssetsAlone,
                archiveWriter());
    }

    public Optional<Path> addConvertedFsPackFile(String packPath, Boolean allowEnriched) {
        boolean fromArchive = packPath.endsWith(".zip");
        boolean fromRaw = packPath.endsWith(".pack");
        if (!fromArchive && !fromRaw) {
            LOGGER.error("Pack is already in FS format");
            throw new RuntimeException("Pack is already in FS format");
        }
        return convert(packPath, fromArchive ? "archive" : "raw", "FS", null, "fs",
                fromArchive ? archiveReader() : binaryReader(),
                this::prepareAssetsForFirmware2dot4,
                fsWriter());
    }

    /**
     * The one conversion path, shared by the six conversions the library offers.
     *
     * <p>Each of those was written out in full, and the six copies had drifted: one logged "Pack to
     * transfer is in" where the others logged "Pack is in", and three took the destination UUID from
     * the pack before the asset transform while three took it after. Neither difference changed what
     * the code did — the transforms mutate and return the same instance — but keeping six copies in
     * step by hand is the problem this removes.
     *
     * <p>The order below is the part that matters and the reason this is one method rather than six.
     * The source is observed <em>before</em> anything reads it and again afterwards, and the
     * provenance record is only written if the two observations agree; the conversion writes to a
     * temporary and is moved into the library only once it has succeeded. Those are the properties
     * {@code ConversionProvenanceTest} and {@code ConversionStreamLifecycleTest} hold, and they now
     * hold in one place instead of six.
     *
     * @param extension the suffix of the produced artifact, or {@code null} when the conversion
     *                  produces a folder rather than a file
     */
    private Optional<Path> convert(String packPath, String sourceLabel, String targetLabel,
                                   String extension, String provenanceKind,
                                   PackReader reader, AssetTransform transform, PackWriter writer) {
        Path tmp = null;
        try {
            // Observed before the conversion reads it; observed again afterwards, and
            // recorded only if the two agree. See recordProvenance.
            Optional<String> sourceBefore = sourceIdentity(packPath);

            boolean producesFolder = extension == null;
            tmp = producesFolder ? createTempDirectory(packPath) : createTempFile(packPath, extension);
            LOGGER.info("Pack is in " + sourceLabel + " format. Converting to " + targetLabel
                    + " format and storing in temporary " + (producesFolder ? "folder: " : "file: ")
                    + tmp.toAbsolutePath());

            LOGGER.info("Reading " + sourceLabel + " format pack");
            StoryPack storyPack = reader.read(requireLibraryEntry(packPath));

            StoryPack converted = transform.apply(storyPack);

            LOGGER.info("Writing " + targetLabel + " format pack");
            Path produced = writer.write(converted, tmp);

            String destinationName = requirePackUuid(converted.getUuid()) + ".converted_"
                    + System.currentTimeMillis() + (producesFolder ? "" : extension);
            Path destinationPath = requireLibraryEntry(destinationName);
            LOGGER.info("Moving " + targetLabel + " format pack into local library: " + destinationPath);
            installIntoLibrary(produced, destinationPath);
            recordProvenance(packPath, sourceBefore, destinationPath, provenanceKind);

            return Optional.of(Paths.get(destinationName));
        } catch (Exception e) {
            String message = "Failed to convert " + sourceLabel + " format pack to " + targetLabel + " format";
            LOGGER.error(message, e);
            throw new RuntimeException(message, e);
        } finally {
            // Whatever is left of the temporary (all of it after a failure, the empty folder created
            // for an FS conversion after a success) is removed, never left to pile up.
            deleteQuietly(tmp);
        }
    }

    /**
     * Prefix of the entries a conversion is assembled under inside the library when it cannot be
     * renamed there in one step. They are not packs and the listing skips them.
     */
    static final String STAGING_PREFIX = ".staging-";

    /**
     * Puts a produced artefact (file or folder) at {@code destination}, which must not exist yet.
     *
     * <p>Within one filesystem this is a single rename. Across filesystems (a library configured on
     * another volume than the temporary folder) a folder cannot be moved, and a file would be copied
     * in place where the listing could see it half written. Both are then copied under a hidden
     * staging name in the library and renamed to their final name once complete.
     */
    void installIntoLibrary(Path produced, Path destination) throws IOException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(destination.toString());
        }
        try {
            Files.move(produced, destination, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (AtomicMoveNotSupportedException e) {
            LOGGER.info("Temporary folder and library are on different filesystems, copying: " + destination);
        }
        Path staging = destination.resolveSibling(STAGING_PREFIX + destination.getFileName() + "." + UUID.randomUUID());
        try {
            copyRecursively(produced, staging);
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, destination);
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(staging);
            throw e;
        }
        deleteQuietly(produced);
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(source, target, LinkOption.NOFOLLOW_LINKS);
            return;
        }
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path copy = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(path, copy, LinkOption.NOFOLLOW_LINKS);
                }
            }
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                FileUtils.deleteDirectory(path.toFile());
            } else {
                Files.delete(path);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to remove temporary conversion output: " + path, e);
        }
    }

    /** Entries of the library folder that are work in progress rather than packs. */
    private static boolean isStagingEntry(Path path) {
        // ".part-" is a 1.x pack being extracted from the device (StoryTellerService).
        return path.getFileName() != null && (path.getFileName().toString().startsWith(STAGING_PREFIX)
                || path.getFileName().toString().startsWith(".part-"));
    }

    @FunctionalInterface
    private interface PackReader {
        StoryPack read(Path source) throws Exception;
    }

    @FunctionalInterface
    private interface AssetTransform {
        StoryPack apply(StoryPack pack) throws Exception;
    }

    @FunctionalInterface
    private interface PackWriter {
        /** Writes {@code pack} under {@code target} and returns what must be moved into the library. */
        Path write(StoryPack pack, Path target) throws Exception;
    }

    private static PackReader archiveReader() {
        return source -> {
            try (FileInputStream fis = new FileInputStream(source.toFile())) {
                return new ArchiveStoryPackReader().read(fis);
            }
        };
    }

    private static PackReader binaryReader() {
        return source -> {
            try (FileInputStream fis = new FileInputStream(source.toFile())) {
                return new BinaryStoryPackReader().read(fis);
            }
        };
    }

    private static PackReader fsReader() {
        return source -> new FsStoryPackReader().read(source);
    }

    private StoryPack uncompressAssetsIfNeeded(StoryPack pack) throws Exception {
        if (PackAssetsCompression.hasCompressedAssets(pack)) {
            LOGGER.info("Uncompressing pack assets");
            return PackAssetsCompression.withUncompressedAssets(pack);
        }
        return pack;
    }

    private StoryPack compressAssets(StoryPack pack) throws Exception {
        LOGGER.info("Compressing pack assets");
        return PackAssetsCompression.withCompressedAssets(pack);
    }

    /** An FS source already carries the assets an archive wants, so nothing is done to them. */
    private StoryPack leaveAssetsAlone(StoryPack pack) {
        return pack;
    }

    private StoryPack prepareAssetsForFirmware2dot4(StoryPack pack) throws Exception {
        // Prepare assets (RLE-encoded BMP, audio must already be MP3)
        LOGGER.info("Converting assets if necessary");
        return PackAssetsCompression.withPreparedAssetsFirmware2dot4(pack);
    }

    private static PackWriter rawWriter(Boolean allowEnriched) {
        return (pack, target) -> {
            try (FileOutputStream fos = new FileOutputStream(target.toFile())) {
                new BinaryStoryPackWriter().write(pack, fos, allowEnriched);
            }
            return target;
        };
    }

    private static PackWriter archiveWriter() {
        return (pack, target) -> {
            try (FileOutputStream fos = new FileOutputStream(target.toFile())) {
                new ArchiveStoryPackWriter().write(pack, fos);
            }
            return target;
        };
    }

    /** The FS writer creates its own folder under the temporary directory and returns it. */
    private static PackWriter fsWriter() {
        return (pack, target) -> new FsStoryPackWriter().write(pack, target);
    }

    /**
     * Whether a converted pack can stand in for a source without asking the user.
     *
     * <p>Called when a pack is dropped on a device and never while listing, because it reads both
     * artefacts in full. Both digests are recomputed here, every time. Neither the size nor the
     * modification time is allowed to stand in for that: C7-0b established they do not identify
     * content, which is fine for deciding whether to re-parse a file and not fine at all for
     * deciding to send bytes to a device without telling anyone.
     *
     * @throws IllegalArgumentException if either name does not denote a direct child of the library
     *                                  folder. That is a malformed request, not an uncertainty.
     */
    public ProvenanceVerdict verifyConversion(String sourcePath, String convertedPath) {
        Path source = requireLibraryEntry(sourcePath);
        Path converted = requireLibraryEntry(convertedPath);
        try {
            Optional<ConversionRecord> recorded = provenanceStore.find(converted.getFileName().toString());
            if (!recorded.isPresent()) {
                // No ledger, an unreadable one, or a conversion made before any of this existed.
                // None of those is a statement that the artefacts differ.
                return ProvenanceVerdict.UNKNOWN;
            }
            Optional<String> currentSource = sourceIdentity(sourcePath);
            Optional<String> currentArtifact = identityOf(converted);
            if (!currentSource.isPresent() || !currentArtifact.isPresent()) {
                // Missing, unreadable, or moving while being read. Nothing was established.
                return ProvenanceVerdict.UNKNOWN;
            }
            boolean sameSource = currentSource.get().equals(recorded.get().getSourceSha256());
            boolean sameArtifact = currentArtifact.get().equals(recorded.get().getArtifactSha256());
            return sameSource && sameArtifact ? ProvenanceVerdict.MATCH : ProvenanceVerdict.MISMATCH;
        } catch (RuntimeException e) {
            // An unexpected failure is an absence of knowledge, never a proof.
            LOGGER.error("Failed to verify the provenance of `" + convertedPath + "`", e);
            return ProvenanceVerdict.UNKNOWN;
        }
    }

    /** The identity of a library artefact, read as a file or as a tree according to its name. */
    private Optional<String> identityOf(Path artifact) {
        return kindOf(artifact.getFileName().toString()) == ConversionRecord.Kind.FILE
                ? contentDigest.ofFile(artifact)
                : contentDigest.ofTree(artifact);
    }

    /**
     * Resolves a name the client supplied against the library, refusing anything that is not a
     * direct child of it.
     *
     * <p>Uncertainty about two packs is a verdict; being asked about a file somewhere else on the
     * disk is not a question this is willing to answer. Without this, the two names below would let
     * a caller point the digests at arbitrary paths, and a verification endpoint would become a way
     * to probe the filesystem. The listing only ever offers names from one directory, so requiring
     * exactly that costs nothing legitimate.
     *
     * @throws IllegalArgumentException if the name is empty, or escapes the library folder, or names
     *                                  something nested inside it
     */
    private Path requireLibraryEntry(String name) {
        return libraryEntry(name).orElseThrow(
                () -> new IllegalArgumentException("Not an entry of the local library: " + name));
    }

    /**
     * The single authority turning a name into a path this service is willing to act on.
     *
     * <p>A library entry is <strong>a direct child of the library folder</strong>. That is not a
     * restriction invented here: the listing walks the folder one level deep, the client only ever
     * receives names taken from that listing, and every conversion returns a bare name. Anything
     * else — a nested path, an absolute one, a climb out and back in, {@code .} or {@code ..} — is
     * refused.
     *
     * <p>Refused, never repaired. A caller asking for the wrong thing is told no rather than handed
     * a different thing, because silently rewriting a path is how a caller ends up acting on
     * something it did not name.
     *
     * <p>Containment is measured against the <em>configured</em> root, normalised lexically and not
     * resolved through links. A library that a user has placed behind a symbolic link is a perfectly
     * ordinary setup and stays supported; resolving the root to its real path would break it for no
     * security gain, since the question is whether an <em>entry</em> leaves the library, not where
     * the library itself lives.
     *
     * <p>Which is why lexical containment is not the whole check. An entry that already exists is
     * examined with {@code NOFOLLOW_LINKS} and refused if it is a symbolic link, an NTFS junction or
     * any other special entry: those are lexically direct children while pointing somewhere else
     * entirely, and a recursive delete would follow one straight out of the library. Junctions in
     * particular report {@code isSymbolicLink()} as false — measured in C7-2b — so {@code isOther()}
     * is what catches them.
     *
     * <p>An entry that does not exist yet is allowed: an upload and a conversion both have to name a
     * destination before creating it. The lexical rule still applies to it.
     */
    public Optional<Path> libraryEntry(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        Path libraryRoot = Paths.get(libraryPath()).toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = libraryRoot.resolve(name).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            LOGGER.warn("Not a usable library entry name: " + name);
            return Optional.empty();
        }
        // Windows device names (NUL, CON.zip, COM1…) and names with a trailing dot or space, which
        // Win32 strips and so turns into an alias of another entry. Refused everywhere, so that a
        // library behaves the same on every system.
        String last = resolved.getFileName() == null ? "" : resolved.getFileName().toString();
        if (last.matches("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(\\..*)?") || last.endsWith(".") || last.endsWith(" ")) {
            LOGGER.warn("Refused a library entry name that Windows reserves or rewrites: " + name);
            return Optional.empty();
        }
        if (!libraryRoot.equals(resolved.getParent())) {
            LOGGER.warn("Refused a name that is not a direct entry of the local library: " + name);
            return Optional.empty();
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(resolved, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                LOGGER.warn("Refused a library entry that is a link or a special entry: " + name);
                return Optional.empty();
            }
        } catch (NoSuchFileException absent) {
            // A destination that does not exist yet. The lexical rule above already holds.
        } catch (IOException e) {
            LOGGER.warn("Could not examine a library entry, refusing it: " + name, e);
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    /**
     * The identifier a conversion output is named after, refused unless it really is one.
     *
     * <p>The name of every converted artefact starts with the pack's UUID, and that UUID comes from
     * the pack's own content — from a {@code story.json} for an archive, from a folder name for an
     * fs pack. It is therefore not trusted input, and a value containing a separator would compose a
     * destination path outside the library before anything else had a chance to object.
     *
     * <p>Checked by parsing it as a UUID rather than by looking for dangerous characters. A blacklist
     * only refuses what it was told to think of; this states what is required. And it requires
     * nothing new: {@code BinaryStoryPackWriter} and {@code FsStoryPackWriter} already call
     * {@code UUID.fromString} on these values, as does the device driver in five places. All this
     * does is ask the question before a path is built rather than after.
     */
    private static String requirePackUuid(String uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("A pack UUID is required to name a conversion");
        }
        try {
            UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a usable pack UUID: " + uuid, e);
        }
        return uuid;
    }

    /**
     * The content identity of a library artefact used as a conversion source.
     *
     * <p>Package-private and overridable so a test can make the source change between the two
     * observations that bracket a conversion, deterministically and without timing.
     */
    Optional<String> sourceIdentity(String packPath) {
        Path source = requireLibraryEntry(packPath);
        return kindOf(packPath) == ConversionRecord.Kind.FILE
                ? contentDigest.ofFile(source)
                : contentDigest.ofTree(source);
    }

    /** A library artefact is a file when its name says so, and a tree otherwise. */
    private static ConversionRecord.Kind kindOf(String name) {
        return name.endsWith(".zip") || name.endsWith(".pack")
                ? ConversionRecord.Kind.FILE
                : ConversionRecord.Kind.TREE;
    }

    /**
     * Writes down what this conversion was made from, once the artefact is installed and only if
     * that can be said truthfully.
     *
     * <p>The source is observed twice, before the conversion reads it and again now, and a record is
     * written only if the two observations are identical. Observing once would not do. Observe only
     * before, and a source edited during the conversion is recorded as the one that produced an
     * artefact made from something else. Observe only after, and the opposite: the edited source is
     * recorded, so later it will match, and an artefact that did not come from it is reused without
     * a word — the silent wrong transfer this whole line of work exists to prevent.
     *
     * <p>What the bracket establishes is narrower than it may look, and worth stating plainly: two
     * stable observations of the source, taken before and after the whole conversion, were equal. It
     * is not a filesystem transaction, and it does not prove that the bytes hashed are the bytes the
     * converter consumed — a change followed by an exact restoration inside the window is invisible
     * to it. It does close the two ordinary races: a source edited during the conversion and left
     * edited, and a source edited before the final observation.
     *
     * <p>Nothing here can make a conversion fail. The artefact is installed before this runs, and
     * every way of failing to describe it — an unstable source, an unreadable artefact, a ledger
     * that cannot be written — ends the same way: a log line, no record, and the conversion returned
     * to the caller exactly as it would have been. The artefact simply stays unproven, which is what
     * every artefact in every library is today.
     */
    private void recordProvenance(String packPath, Optional<String> sourceBefore, Path artifact,
                                  String targetFormat) {
        try {
            String artifactName = artifact.getFileName().toString();
            if (!sourceBefore.isPresent()) {
                LOGGER.warn("No provenance for `" + artifactName + "`: the source could not be read"
                        + " before the conversion");
                return;
            }
            Optional<String> sourceAfter = sourceIdentity(packPath);
            if (!sourceAfter.isPresent()) {
                LOGGER.warn("No provenance for `" + artifactName + "`: the source could not be read"
                        + " after the conversion");
                return;
            }
            if (!sourceBefore.get().equals(sourceAfter.get())) {
                LOGGER.warn("No provenance for `" + artifactName + "`: `" + packPath + "` changed"
                        + " while it was being converted, so it cannot be recorded as the source");
                return;
            }
            ConversionRecord.Kind artifactKind = kindOf(artifactName);
            Optional<String> artifactDigest = artifactKind == ConversionRecord.Kind.FILE
                    ? contentDigest.ofFile(artifact)
                    : contentDigest.ofTree(artifact);
            if (!artifactDigest.isPresent()) {
                LOGGER.warn("No provenance for `" + artifactName + "`: the artefact could not be read");
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(artifact, BasicFileAttributes.class);
            ConversionRecord record = new ConversionRecord(
                    Paths.get(packPath).getFileName().toString(), kindOf(packPath), sourceBefore.get(),
                    artifactKind, artifactDigest.get(), attributes.size(),
                    attributes.lastModifiedTime().toMillis(), targetFormat, ApplicationVersion.current());
            if (!provenanceStore.record(artifactName, record)) {
                LOGGER.warn("No provenance for `" + artifactName + "`: the provenance database could"
                        + " not be written. The conversion is unaffected.");
            }
        } catch (IOException | RuntimeException e) {
            // Describing a conversion must never undo one.
            LOGGER.error("Failed to record provenance for a conversion that succeeded", e);
        }
    }

    public boolean addPackFile(String destPath, String uploadedFilePath) {
        try {
            // Copy temporary file to local library
            File src = new File(uploadedFilePath);
            Optional<Path> destination = libraryEntry(destPath);
            if (!destination.isPresent()) {
                // Refused before anything is deleted, moved or created.
                return false;
            }
            File dest = destination.get().toFile();
            if (dest.isDirectory()) {
                // A pack folder of the same name: not something an upload replaces.
                return false;
            }
            // Replaced in one step: deleting first lost the existing pack if the move then failed
            // (full disk, process killed). Across volumes, copied beside it under a staging name.
            try {
                Files.move(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException acrossVolumes) {
                Path staging = dest.toPath().resolveSibling(STAGING_PREFIX + dest.getName() + "." + UUID.randomUUID());
                try {
                    Files.copy(src.toPath(), staging);
                    Files.move(staging, dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(staging);
                }
                Files.deleteIfExists(src.toPath());
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to add pack to local library", e);
            throw new RuntimeException(e);
        }
    }

    public boolean deletePack(String packPath) {
        File libraryFolder = new File(libraryPath());
        if (!libraryFolder.exists() || !libraryFolder.isDirectory()) {
            return false;
        } else {
            try {
                Optional<Path> entry = libraryEntry(packPath);
                if (!entry.isPresent()) {
                    // The log below used to claim this while only checking that the file existed —
                    // which one outside the folder does too. Now the claim is true.
                    LOGGER.error("Cannot remove pack from library because it is not in the folder");
                    return false;
                }
                File packFile = entry.get().toFile();
                if (packFile.exists()) {
                    FileUtils.forceDelete(packFile);
                    return true;
                } else {
                    LOGGER.error("Cannot remove pack from library because it is not in the folder");
                    return false;
                }
            } catch (IOException e) {
                LOGGER.error("Failed to remove pack from library", e);
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ purge of old packs

    /**
     * Names STUdio gives its own conversion outputs: {@code <uuid>.converted_<millis>}, followed by
     * {@code .pack} or {@code .zip} when the output is a file. The same convention as
     * {@code isConversionOutput} in {@code utils/packs.js}, with one more requirement: the UUID in
     * the name must be the one the entry's own metadata declares.
     */
    private static final Pattern CONVERSION_OUTPUT_NAME =
            Pattern.compile("^([0-9a-fA-F-]{36})\\.converted_(\\d+)(\\.pack|\\.zip)?$");

    /** One parsed library entry, as the purge looks at it. */
    private static final class PurgeEntry {
        private final LibraryPack pack;
        private final String name;
        private final String format;
        /** The millis in a conversion output's name, or -1 for anything that is not one. */
        private final long conversionStamp;

        private PurgeEntry(LibraryPack pack) {
            this.pack = pack;
            this.name = pack.getPath().getFileName().toString();
            this.format = pack.getMetadata().getFormat();
            Matcher m = CONVERSION_OUTPUT_NAME.matcher(name);
            this.conversionStamp = m.matches() && m.group(1).equalsIgnoreCase(pack.getMetadata().getUuid())
                    ? parseStamp(m.group(2))
                    : -1;
        }

        private boolean isConversion() {
            return conversionStamp >= 0;
        }

        private static long parseStamp(String digits) {
            try {
                return Long.parseLong(digits);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /** Most recent first: by modification time as the listing sorts, then by name stamp, then name. */
    private static final Comparator<PurgeEntry> MOST_RECENT_FIRST = Comparator
            .comparingLong((PurgeEntry e) -> e.pack.getTimestamp())
            .thenComparingLong(e -> e.conversionStamp)
            .thenComparing(e -> e.name)
            .reversed();

    /**
     * The library entries that serve no purpose any more. Nothing is deleted here.
     *
     * <p>The rule, for the entries of one pack UUID:
     * <ul>
     * <li><b>SUPERSEDED</b> (selected): a conversion output for which another conversion output of the
     * same format is more recent both by modification time — which is what the library listing and
     * the drop on a device use to pick the one they send — and by the timestamp STUdio wrote in its
     * name. When those two orders disagree nothing is proposed: which one is "the old one" is then
     * not something this can state.</li>
     * <li><b>SOURCE_CHANGED</b> (selected): the most recent conversion output of its format, whose
     * provenance record still describes it exactly (its digest is recomputed) and proves it was made
     * from content that no other entry of the pack holds any more, while at least one source (an
     * entry that is not a conversion output) is present to make a fresh one from. A missing record,
     * an unreadable entry or any doubt leaves it alone: unknown provenance is never a reason to
     * purge.</li>
     * <li><b>DUPLICATE_SOURCE</b> (not selected): a source file older than another source of the same
     * format holding exactly the same bytes (same size, then same SHA-256).</li>
     * </ul>
     *
     * <p>Never proposed: the most recent source of each format (the current archive in particular),
     * a source whose content differs from every other (an older version the user may want), the
     * most recent conversion of each format unless its provenance proves it stale, and any entry
     * that would leave its pack with nothing at all. Entries that cannot be parsed are not packs as
     * far as the library is concerned, and are never touched.
     *
     * <p>Establishing SOURCE_CHANGED and DUPLICATE_SOURCE reads artefacts in full, so this is only
     * ever called on demand, never while listing.
     */
    public List<PurgeCandidate> purgeCandidates() {
        List<PurgeEntry> entries;
        Path root = Paths.get(libraryPath());
        if (!Files.isDirectory(root)) {
            return new ArrayList<>();
        }
        try (Stream<Path> paths = Files.walk(root, 1)) {
            entries = paths
                    .filter(path -> !path.equals(root))
                    .filter(path -> !isStagingEntry(path))
                    .map(this::readPackFileCached)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(p -> p.getMetadata().getUuid() != null && p.getMetadata().getFormat() != null)
                    .map(PurgeEntry::new)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.error("Failed to read packs from local library", e);
            throw new RuntimeException(e);
        }

        List<PurgeCandidate> candidates = new ArrayList<>();
        Map<String, List<PurgeEntry>> byUuid = entries.stream()
                .collect(Collectors.groupingBy(e -> e.pack.getMetadata().getUuid().toLowerCase(Locale.ROOT)));
        for (List<PurgeEntry> group : byUuid.values()) {
            candidates.addAll(purgeCandidatesOf(group));
        }
        candidates.sort(Comparator.comparing((PurgeCandidate c) -> c.getTitle() == null ? c.getUuid() : c.getTitle())
                .thenComparing(PurgeCandidate::getPath));
        return candidates;
    }

    private List<PurgeCandidate> purgeCandidatesOf(List<PurgeEntry> group) {
        Map<PurgeEntry, PurgeCandidate.Reason> reasons = new LinkedHashMap<>();
        Map<PurgeEntry, PurgeEntry> keptFor = new HashMap<>();
        Map<PurgeEntry, Optional<String>> digests = new HashMap<>();

        // Conversion outputs, by format
        Map<String, List<PurgeEntry>> conversions = group.stream().filter(PurgeEntry::isConversion)
                .collect(Collectors.groupingBy(e -> e.format));
        List<PurgeEntry> latestConversions = new ArrayList<>();
        for (List<PurgeEntry> sameFormat : conversions.values()) {
            sameFormat.sort(MOST_RECENT_FIRST);
            PurgeEntry latest = sameFormat.get(0);
            latestConversions.add(latest);
            for (PurgeEntry older : sameFormat.subList(1, sameFormat.size())) {
                if (latest.conversionStamp > older.conversionStamp
                        && latest.pack.getTimestamp() > older.pack.getTimestamp()) {
                    reasons.put(older, PurgeCandidate.Reason.SUPERSEDED);
                    keptFor.put(older, latest);
                }
            }
        }

        List<PurgeEntry> sources = group.stream().filter(e -> !e.isConversion()).collect(Collectors.toList());

        // The most recent conversion of each format, when its provenance proves it stale
        if (!sources.isEmpty()) {
            for (PurgeEntry latest : latestConversions) {
                Optional<ConversionRecord> record = provenanceStore.find(latest.name);
                if (!record.isPresent()) {
                    continue;
                }
                Optional<String> artifact = digests.computeIfAbsent(latest, this::digestOf);
                if (!artifact.isPresent() || !artifact.get().equals(record.get().getArtifactSha256())) {
                    // The record does not describe what is on disk now: nothing is proven.
                    continue;
                }
                boolean doubt = false;
                PurgeEntry origin = null;
                // Sources first, most recent first: the usual origin is found after one read. Every
                // other entry counts, superseded ones included — an origin still present anywhere
                // means this conversion is not stale.
                List<PurgeEntry> others = new ArrayList<>(group);
                others.sort(Comparator.comparing(PurgeEntry::isConversion).thenComparing(MOST_RECENT_FIRST));
                for (PurgeEntry other : others) {
                    if (other == latest) {
                        continue;
                    }
                    Optional<String> digest = digests.computeIfAbsent(other, this::digestOf);
                    if (!digest.isPresent()) {
                        doubt = true;
                        break;
                    }
                    if (digest.get().equals(record.get().getSourceSha256())) {
                        origin = other;
                        break;
                    }
                }
                if (!doubt && origin == null) {
                    reasons.put(latest, PurgeCandidate.Reason.SOURCE_CHANGED);
                    sources.stream().sorted(MOST_RECENT_FIRST).findFirst()
                            .ifPresent(source -> keptFor.put(latest, source));
                }
            }
        }

        // Older sources byte-identical to a more recent one of the same format
        Map<String, List<PurgeEntry>> sourcesByFormat = sources.stream().collect(Collectors.groupingBy(e -> e.format));
        for (List<PurgeEntry> sameFormat : sourcesByFormat.values()) {
            sameFormat.sort(MOST_RECENT_FIRST);
            for (int i = 1; i < sameFormat.size(); i++) {
                PurgeEntry older = sameFormat.get(i);
                for (PurgeEntry newer : sameFormat.subList(0, i)) {
                    if (reasons.containsKey(newer) || sizeOf(newer) != sizeOf(older)) {
                        continue;
                    }
                    Optional<String> a = digests.computeIfAbsent(older, this::digestOf);
                    Optional<String> b = digests.computeIfAbsent(newer, this::digestOf);
                    if (a.isPresent() && b.isPresent() && a.get().equals(b.get())) {
                        reasons.put(older, PurgeCandidate.Reason.DUPLICATE_SOURCE);
                        keptFor.put(older, newer);
                        break;
                    }
                }
            }
        }

        // Never the last copy of a pack. The rules above cannot get here; this does not rely on it.
        if (reasons.size() >= group.size()) {
            LOGGER.warn("Purge would leave pack `" + group.get(0).pack.getMetadata().getUuid()
                    + "` with nothing, proposing nothing for it");
            return new ArrayList<>();
        }

        String uuid = group.get(0).pack.getMetadata().getUuid();
        String title = databaseMetadataService.getPackMetadata(uuid)
                .map(DatabasePackMetadata::getTitle)
                .orElse(group.get(0).pack.getMetadata().getTitle());
        return reasons.entrySet().stream()
                .map(e -> new PurgeCandidate(e.getKey().name, uuid, title, e.getKey().format,
                        sizeOf(e.getKey()), e.getKey().pack.getTimestamp(), e.getValue(),
                        Optional.ofNullable(keptFor.get(e.getKey())).map(k -> k.name).orElse(null)))
                .collect(Collectors.toList());
    }

    private Optional<String> digestOf(PurgeEntry entry) {
        return identityOf(entry.pack.getPath());
    }

    /** Bytes on disk: the file's size, or the sum of the regular files of a folder. */
    private static long sizeOf(PurgeEntry entry) {
        Path path = entry.pack.getPath();
        try {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return Files.size(path);
            }
            try (Stream<Path> files = Files.walk(path)) {
                return files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .mapToLong(p -> p.toFile().length())
                        .sum();
            }
        } catch (IOException | UncheckedIOException e) {
            return -1;
        }
    }

    /** What a purge did with one requested name. */
    public static final class PurgeResult {
        private final String path;
        private final boolean removed;
        private final long size;
        private final String error;

        private PurgeResult(String path, boolean removed, long size, String error) {
            this.path = path;
            this.removed = removed;
            this.size = size;
            this.error = error;
        }

        public String getPath() {
            return path;
        }

        public boolean isRemoved() {
            return removed;
        }

        /** Bytes freed, 0 unless removed. */
        public long getSize() {
            return size;
        }

        /** Null when removed; otherwise {@code NOT_A_CANDIDATE} or {@code REMOVAL_FAILED}. */
        public String getError() {
            return error;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject().put("path", path).put("removed", removed).put("size", size);
            if (error != null) {
                json.put("error", error);
            }
            return json;
        }
    }

    /**
     * Removes the named entries, each only if it is still a purge candidate right now.
     *
     * <p>The list the client sends is a selection, not an authority: candidates are recomputed here,
     * from the disk as it is, and a name that is not among them — stale list, entry since replaced,
     * a name made up — is left alone and reported as {@code NOT_A_CANDIDATE}. Removing several
     * candidates together is safe because none of them is what another one is redundant with.
     *
     * @throws IllegalArgumentException before anything is removed, if any name is not a direct entry
     *                                  of the library
     */
    public List<PurgeResult> purge(List<String> paths) {
        for (String name : paths) {
            requireLibraryEntry(name);
        }
        Map<String, PurgeCandidate> current = purgeCandidates().stream()
                .collect(Collectors.toMap(PurgeCandidate::getPath, c -> c, (a, b) -> a));
        List<PurgeResult> results = new ArrayList<>();
        for (String name : new LinkedHashSet<>(paths)) {
            PurgeCandidate candidate = current.get(name);
            if (candidate == null) {
                LOGGER.warn("Not purging `" + name + "`: it is not, or no longer, a purge candidate");
                results.add(new PurgeResult(name, false, 0, "NOT_A_CANDIDATE"));
                continue;
            }
            boolean removed = deletePack(name);
            // The listing is refreshed from the disk; the parse kept for this name goes too.
            cachedPacks.asMap().keySet().removeIf(p -> p.getFileName() != null && p.getFileName().toString().equals(name));
            if (removed) {
                LOGGER.info("Purged `" + name + "` (" + candidate.getReason() + ")");
            }
            results.add(new PurgeResult(name, removed, removed ? Math.max(candidate.getSize(), 0) : 0,
                    removed ? null : "REMOVAL_FAILED"));
        }
        return results;
    }

    public String libraryPath() {
        // Path may be overridden by system property `studio.library`
        return System.getProperty(LOCAL_LIBRARY_PROP, System.getProperty("user.home") + LOCAL_LIBRARY_PATH);
    }

    private String tmpDirPath() {
        // Path may be overridden by system property `studio.tmpdir`
        return System.getProperty(TMP_DIR_PROP, System.getProperty("user.home") + TMP_DIR_PATH);
    }

    private Path createTempFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(Paths.get(tmpDirPath()), prefix, suffix);
    }

    private Path createTempDirectory(String prefix) throws IOException {
        return Files.createTempDirectory(Paths.get(tmpDirPath()), prefix);
    }

    /**
     * The cached parse for a path, if the file there is still the one it was made from.
     *
     * <p>"Still the one" means the same size and the same modification time — one stat, both values,
     * no reading of content. That is a coherence check on fast observable attributes, and its limit
     * is exactly what those attributes cannot see: a replacement of identical size whose modification
     * time has been restored goes unnoticed. Closing that gap would mean reading the file to compare
     * it, which is the work this cache exists to avoid. It is a different question in any case —
     * whether a particular converted pack was produced from a particular source is about the
     * relationship between two files, not about one path at one moment, and nothing here answers it.
     *
     * <p>If the attributes cannot be read at all, the cached entry is not reused and not trusted:
     * whatever {@code readPackFile} makes of the path as it is now becomes the answer.
     */
    private Optional<LibraryPack> readPackFileCached(Path path) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            LOGGER.debug("Cannot read attributes, parsing without the cache: " + path.toString());
            return readPackFile(path);
        }

        CachedPack cached = cachedPacks.getIfPresent(path);
        if (cached != null && cached.matches(attributes)) {
            return cached.pack;
        }

        Optional<LibraryPack> pack = readPackFile(path);
        cachedPacks.put(path, new CachedPack(pack, attributes));
        return pack;
    }

    private Optional<LibraryPack> readPackFile(Path path) {
        LOGGER.debug("Reading pack file: " + path.toString());
        // Handle all file formats
        if (path.toString().endsWith(".zip")) {
            try {
                LOGGER.debug("Reading archive pack metadata.");
                ArchiveStoryPackReader packReader = new ArchiveStoryPackReader();
                // Through the zip central directory: the assets are not read
                StoryPackMetadata meta = packReader.readMetadata(path);
                if (meta != null) {
                    return Optional.of(new LibraryPack(path, Files.getLastModifiedTime(path).toMillis() , meta));
                }
                LOGGER.warn("Failed to read metadata for story pack: " + path.toString());
                return Optional.empty();
            } catch (Exception e) {
                LOGGER.error("Failed to read archive-format pack " + path.toString() + " from local library", e);
                return Optional.empty();
            }
        } else if (path.toString().endsWith(".pack")) {
            try (FileInputStream fis = new FileInputStream(path.toFile())) {
                LOGGER.debug("Reading raw pack metadata.");
                BinaryStoryPackReader packReader = new BinaryStoryPackReader();
                StoryPackMetadata meta = packReader.readMetadata(fis);
                if (meta != null) {
                    int packSectorSize = (int)Math.ceil((double)path.toFile().length() / 512d);
                    meta.setSectorSize(packSectorSize);
                    return Optional.of(new LibraryPack(path, Files.getLastModifiedTime(path).toMillis() , meta));
                }
                LOGGER.warn("Failed to read metadata for story pack: " + path.toString());
                return Optional.empty();
            } catch (Exception e) {
                LOGGER.error("Failed to read raw format pack " + path.toString() + " from local library", e);
                return Optional.empty();
            }
        } else if (Files.isDirectory(path)) {
            try {
                LOGGER.debug("Reading FS pack metadata.");
                FsStoryPackReader packReader = new FsStoryPackReader();
                StoryPackMetadata meta = packReader.readMetadata(path);
                if (meta != null) {
                    int packSectorSize = (int)Math.ceil((double)path.toFile().length() / 512d);
                    meta.setSectorSize(packSectorSize);
                    return Optional.of(new LibraryPack(path, Files.getLastModifiedTime(path).toMillis() , meta));
                }
                LOGGER.warn("Failed to read metadata for story pack: " + path.toString());
                return Optional.empty();
            } catch (Exception e) {
                LOGGER.error("Failed to read FS format pack " + path.toString() + " from local library", e);
                return Optional.empty();
            }
        }

        // Ignore other files
        return Optional.empty();
    }

    private JsonObject getPackMetadata(LibraryPack pack) {
        JsonObject json = new JsonObject()
                .put("format", pack.getMetadata().getFormat())
                .put("uuid", pack.getMetadata().getUuid())
                .put("version", pack.getMetadata().getVersion())
                .put("path", pack.getPath().getFileName().toString())
                .put("timestamp", pack.getTimestamp())
                .put("nightModeAvailable", pack.getMetadata().isNightModeAvailable());
        Optional.ofNullable(pack.getMetadata().getTitle()).ifPresent(title -> json.put("title", title));
        Optional.ofNullable(pack.getMetadata().getDescription()).ifPresent(desc -> json.put("description", desc));
        Optional.ofNullable(pack.getMetadata().getThumbnail()).ifPresent(thumb -> json.put("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(thumb)));
        Optional.ofNullable(pack.getMetadata().getSectorSize()).ifPresent(size -> json.put("sectorSize", size));
        return databaseMetadataService.getPackMetadata(pack.getMetadata().getUuid())
                .map(metadata -> json
                        .put("title", metadata.getTitle())
                        .put("description", metadata.getDescription())
                        .put("image", metadata.getThumbnail())
                        .put("official", metadata.isOfficial())
                )
                .orElse(json);
    }

}
