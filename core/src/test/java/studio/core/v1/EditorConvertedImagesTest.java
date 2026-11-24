/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.model.metadata.StoryPackMetadata;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;
import studio.core.v1.reader.binary.BinaryStoryPackReader;
import studio.core.v1.reader.fs.FsStoryPackReader;
import studio.core.v1.utils.PackAssetsCompression;
import studio.core.v1.writer.binary.BinaryStoryPackWriter;
import studio.core.v1.writer.fs.FsStoryPackWriter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A pack whose images were converted by the web editor stays valid all the way to the device.
 *
 * <p>The editor converts any image it is given to 320x240 on a browser canvas, re-encoded as PNG
 * (or JPEG when the original was a JPEG), and any thumbnail to PNG ({@code web-ui/javascript/src/utils/images.js}).
 * The fixtures in {@code editor-images/} are real outputs of that code in Chromium: a 1920x1080 PNG
 * and a 3000x4000 JPEG letterboxed into 320x240, and a 2000x1500 JPEG thumbnail scaled to a
 * 512x384 PNG. A canvas writes RGBA PNGs, which is not what the BMP conversion was written for, so
 * this pins that they are read from an archive laid out the way the editor's writer lays it out
 * ({@code writer.js}), and converted to the raw and FS formats, without error and at 320x240.
 */
@DisplayName("A pack with images converted by the editor")
class EditorConvertedImagesTest {

    private static final String FIRST = "11111111-1111-1111-1111-111111111111";
    private static final String SECOND = "22222222-2222-2222-2222-222222222222";

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = EditorConvertedImagesTest.class.getResourceAsStream("/editor-images/" + name)) {
            assertNotNull(in, "fixture " + name);
            return in.readAllBytes();
        }
    }

    private static String stage(String uuid, String image, boolean squareOne) {
        return "{\"uuid\":\"" + uuid + "\",\"type\":\"stage\",\"name\":\"n\"," + (squareOne ? "\"squareOne\":true," : "")
                + "\"position\":{\"x\":0,\"y\":0},\"image\":\"" + image + "\",\"audio\":null,"
                + "\"okTransition\":{\"actionNode\":\"a1\",\"optionIndex\":0},"
                + "\"homeTransition\":null,"
                + "\"controlSettings\":{\"wheel\":true,\"ok\":true,\"home\":true,\"pause\":false,\"autoplay\":false}}";
    }

    /** The archive the editor's writer produces: story.json, assets named by hash, thumbnail.png. */
    private static byte[] editorArchive(byte[] png, byte[] jpeg, byte[] thumbnail) throws IOException {
        String pngName = DigestUtils.sha1Hex(png) + ".png";
        String jpegName = DigestUtils.sha1Hex(jpeg) + ".jpg";
        String story = "{\"format\":\"v1\",\"title\":\"Images converties\",\"version\":1,\"description\":\"\","
                + "\"nightModeAvailable\":false,"
                + "\"stageNodes\":[" + stage(FIRST, pngName, true) + "," + stage(SECOND, jpegName, false) + "],"
                + "\"actionNodes\":[{\"id\":\"a1\",\"name\":\"a\",\"position\":{\"x\":0,\"y\":0},"
                + "\"options\":[\"" + SECOND + "\"]}]}";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            put(zos, "assets/" + pngName, png);
            put(zos, "assets/" + jpegName, jpeg);
            put(zos, "thumbnail.png", thumbnail);
            put(zos, "story.json", story.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static StoryPack readEditorPack() throws IOException {
        byte[] archive = editorArchive(resource("editor-converted-from-png.png"),
                resource("editor-converted-from-jpeg.jpg"), resource("editor-converted-thumbnail.png"));
        return new ArchiveStoryPackReader().read(new ByteArrayInputStream(archive));
    }

    private static void assertDeviceBitmap(byte[] bmp, int bitsPerPixel, String what) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(bmp).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals('B', bmp[0], what + ": BMP signature");
        assertEquals('M', bmp[1], what + ": BMP signature");
        assertEquals(320, header.getInt(18), what + ": width");
        assertEquals(240, header.getInt(22), what + ": height");
        assertEquals(bitsPerPixel, header.getShort(28), what + ": bits per pixel");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bmp));
        assertNotNull(decoded, what + ": decodes");
    }

    @Test
    @DisplayName("are read from the archive with their types, at 320x240")
    void readFromArchive() throws IOException {
        StoryPack pack = readEditorPack();

        assertEquals(2, pack.getStageNodes().size(), "stage nodes");
        StageNode first = pack.getStageNodes().get(0);
        StageNode second = pack.getStageNodes().get(1);
        assertEquals("image/png", first.getImage().getMimeType());
        assertEquals("image/jpeg", second.getImage().getMimeType());
        for (StageNode node : pack.getStageNodes()) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(node.getImage().getRawData()));
            assertNotNull(image, "image of " + node.getUuid() + " decodes");
            assertEquals(320, image.getWidth());
            assertEquals(240, image.getHeight());
        }
    }

    @Test
    @DisplayName("keep the converted thumbnail as the pack's PNG thumbnail")
    void thumbnailIsRead() throws IOException {
        byte[] thumbnail = resource("editor-converted-thumbnail.png");
        byte[] archive = editorArchive(resource("editor-converted-from-png.png"),
                resource("editor-converted-from-jpeg.jpg"), thumbnail);
        StoryPackMetadata metadata = new ArchiveStoryPackReader().readMetadata(new ByteArrayInputStream(archive));

        assertArrayEquals(thumbnail, metadata.getThumbnail(), "thumbnail bytes");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(metadata.getThumbnail()));
        assertEquals(512, image.getWidth());
        assertEquals(384, image.getHeight());
    }

    @Test
    @DisplayName("convert to the raw format's 24-bit bitmaps, written and read back")
    void convertToRaw() throws Exception {
        StoryPack uncompressed = PackAssetsCompression.withUncompressedAssets(readEditorPack());
        for (StageNode node : uncompressed.getStageNodes()) {
            assertEquals("image/bmp", node.getImage().getMimeType());
            assertDeviceBitmap(node.getImage().getRawData(), 24, "raw image of " + node.getUuid());
        }

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        new BinaryStoryPackWriter().write(uncompressed, raw, false);
        StoryPack read = new BinaryStoryPackReader().read(new ByteArrayInputStream(raw.toByteArray()));
        assertEquals(2, read.getStageNodes().size(), "stage nodes read back");
        for (StageNode node : read.getStageNodes()) {
            assertNotNull(ImageIO.read(new ByteArrayInputStream(node.getImage().getRawData())),
                    "raw image read back decodes");
        }
    }

    @Test
    @DisplayName("convert to the FS format's 4-bit RLE bitmaps, written and read back")
    void convertToFs(@TempDir Path folder) throws Exception {
        StoryPack prepared = PackAssetsCompression.withPreparedAssetsFirmware2dot4(readEditorPack());
        for (StageNode node : prepared.getStageNodes()) {
            assertEquals("image/bmp", node.getImage().getMimeType());
            byte[] bmp = node.getImage().getRawData();
            assertDeviceBitmap(bmp, 4, "FS image of " + node.getUuid());
            assertEquals(2, ByteBuffer.wrap(bmp).order(ByteOrder.LITTLE_ENDIAN).getInt(30), "RLE4 compression");
        }

        // The writer refuses anything but 320x240 4-bit RLE bitmaps: reaching the end is the check
        Path written = new FsStoryPackWriter().write(prepared, folder);
        StoryPack read = new FsStoryPackReader().read(written);
        assertEquals(2, read.getStageNodes().size(), "stage nodes read back");
    }
}
