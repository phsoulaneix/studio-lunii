/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Bringing any image the browser can decode to the format a pack expects.
 *
 * Stage images (stage, cover and menu option nodes) are shown on the device's 320x240 screen. The
 * archive reader accepts them as BMP, PNG or JPEG (by file extension), and the conversion to the
 * device's formats turns PNG and JPEG into 24-bit BMP, then into 4-bit RLE BMP for the FS format,
 * which refuses anything but 320x240. It never resizes. So an image is kept as it is only when it
 * is already one of those three types at exactly 320x240 (and, for a BMP, in a depth the
 * conversion understands); anything else is redrawn on a canvas, letterboxed on black so that its
 * proportions are kept, and re-encoded as PNG (or JPEG when the original was a JPEG).
 *
 * The pack thumbnail is written as `thumbnail.png`, so it must be a PNG; it is redrawn when it is
 * not one, or when it is larger than THUMBNAIL_MAX_SIZE on a side, keeping its proportions.
 *
 * The decisions are pure functions; the canvas work is injected so it can be replaced in tests
 * (jsdom has no canvas).
 */

export const ASSET_WIDTH = 320;
export const ASSET_HEIGHT = 240;
export const ASSET_TYPES = ['image/bmp', 'image/jpeg', 'image/png'];
export const THUMBNAIL_TYPE = 'image/png';
export const THUMBNAIL_MAX_SIZE = 512;
export const JPEG_QUALITY = 0.92;

export const NOT_AN_IMAGE = 'NOT_AN_IMAGE';

export class NotAnImageError extends Error {
    constructor(message) {
        super(message || 'Not an image');
        this.name = 'NotAnImageError';
        this.code = NOT_AN_IMAGE;
    }
}

const IMAGE_EXTENSIONS = ['bmp', 'jpg', 'jpeg', 'jfif', 'png', 'gif', 'webp', 'svg', 'ico', 'avif', 'apng', 'tif', 'tiff'];

/**
 * Whether a file is worth trying to decode as an image: an image MIME type, or no type at all with
 * an image extension (some systems give no type for WebP or BMP files).
 */
export function looksLikeImage(file) {
    if (!file) {
        return false;
    }
    if (file.type) {
        return file.type.startsWith('image/');
    }
    let name = (file.name || '').toLowerCase();
    let dot = name.lastIndexOf('.');
    return dot !== -1 && IMAGE_EXTENSIONS.indexOf(name.substring(dot + 1)) !== -1;
}

/** MIME type of a data URL (`data:image/png;base64,...` → `image/png`), or '' when there is none. */
export function mimeTypeOfDataUrl(dataUrl) {
    let match = /^data:([^;,]*)[;,]/.exec(dataUrl || '');
    return match ? match[1].toLowerCase() : '';
}

/**
 * Reads the few fields of a BMP header that matter here, from a base64 data URL.
 *
 * @returns {{width: number, height: number, bitsPerPixel: number, compression: number}|null}
 *          null when the data is not a BMP or is too short.
 */
export function readBmpHeader(dataUrl) {
    let comma = (dataUrl || '').indexOf(',');
    if (comma === -1) {
        return null;
    }
    // 54 bytes of header are 72 base64 characters
    let bytes;
    try {
        bytes = atob(dataUrl.substring(comma + 1, comma + 1 + 72));
    } catch (e) {
        return null;
    }
    if (bytes.length < 34 || bytes.charCodeAt(0) !== 0x42 || bytes.charCodeAt(1) !== 0x4D) {
        return null;
    }
    let u16 = (o) => bytes.charCodeAt(o) | (bytes.charCodeAt(o + 1) << 8);
    let i32 = (o) => u16(o) | (u16(o + 2) << 16);
    return {
        width: i32(18),
        height: i32(22),
        bitsPerPixel: u16(28),
        compression: i32(30)
    };
}

/** A BMP the device conversion handles as is: 24-bit uncompressed, or 4-bit RLE, bottom-up. */
function isSupportedBmp(bmpHeader) {
    if (!bmpHeader || bmpHeader.height <= 0) {
        return false;
    }
    return (bmpHeader.bitsPerPixel === 24 && bmpHeader.compression === 0)
        || (bmpHeader.bitsPerPixel === 4 && bmpHeader.compression === 2);
}

/**
 * Why a stage image must be converted, or null when it can be used as is.
 *
 * @param {{type: string, width: number, height: number, bmpHeader?: object}} image
 * @returns {null|'type'|'dimensions'|'bmpDepth'}
 */
export function assetConversionReason({type, width, height, bmpHeader}) {
    if (ASSET_TYPES.indexOf(type) === -1) {
        return 'type';
    }
    if (width !== ASSET_WIDTH || height !== ASSET_HEIGHT) {
        return 'dimensions';
    }
    if (type === 'image/bmp' && !isSupportedBmp(bmpHeader)) {
        return 'bmpDepth';
    }
    return null;
}

/** Why a thumbnail must be converted, or null when it can be used as is. */
export function thumbnailConversionReason({type, width, height}) {
    if (type !== THUMBNAIL_TYPE) {
        return 'type';
    }
    if (width > THUMBNAIL_MAX_SIZE || height > THUMBNAIL_MAX_SIZE) {
        return 'dimensions';
    }
    return null;
}

/**
 * Where to draw a source image inside a target box so that it fits whole, centred, with its
 * proportions kept (bands on the two other sides).
 *
 * @returns {{x: number, y: number, width: number, height: number}} integers, inside the box
 */
export function fitRect(srcWidth, srcHeight, boxWidth, boxHeight) {
    if (!(srcWidth > 0) || !(srcHeight > 0)) {
        return {x: 0, y: 0, width: boxWidth, height: boxHeight};
    }
    let scale = Math.min(boxWidth / srcWidth, boxHeight / srcHeight);
    let width = Math.min(boxWidth, Math.max(1, Math.round(srcWidth * scale)));
    let height = Math.min(boxHeight, Math.max(1, Math.round(srcHeight * scale)));
    return {
        x: Math.floor((boxWidth - width) / 2),
        y: Math.floor((boxHeight - height) / 2),
        width,
        height
    };
}

/** Size of a thumbnail after conversion: the source scaled down (never up) to fit the maximum. */
export function thumbnailSize(srcWidth, srcHeight) {
    let scale = Math.min(1, THUMBNAIL_MAX_SIZE / srcWidth, THUMBNAIL_MAX_SIZE / srcHeight);
    return {
        width: Math.max(1, Math.round(srcWidth * scale)),
        height: Math.max(1, Math.round(srcHeight * scale))
    };
}

/** Output type for a converted stage image: JPEG stays JPEG (already lossy), anything else PNG. */
export function assetOutputType(sourceType) {
    return sourceType === 'image/jpeg' ? 'image/jpeg' : 'image/png';
}

// --- Browser plumbing (replaced in tests) ---

export function readFileAsDataUrl(file) {
    return new Promise((resolve, reject) => {
        let reader = new FileReader();
        reader.addEventListener('load', () => resolve(reader.result), false);
        reader.addEventListener('error', () => reject(new NotAnImageError('File could not be read')), false);
        reader.readAsDataURL(file);
    });
}

export function decodeImage(dataUrl) {
    return new Promise((resolve, reject) => {
        let image = new Image();
        image.onload = () => {
            let width = image.naturalWidth || image.width;
            let height = image.naturalHeight || image.height;
            if (!width || !height) {
                reject(new NotAnImageError('Image has no dimensions'));
            } else {
                resolve({image, width, height});
            }
        };
        image.onerror = () => reject(new NotAnImageError('Image could not be decoded'));
        image.src = dataUrl;
    });
}

/**
 * Draws an image on a canvas of the given size and encodes it. Stage images are drawn on black: the
 * device has no transparency, and the server-side conversion also flattens on black. A null
 * background keeps transparency (thumbnails).
 */
export function renderImage(image, canvasWidth, canvasHeight, rect, outputType, background = '#000000') {
    let canvas = document.createElement('canvas');
    canvas.width = canvasWidth;
    canvas.height = canvasHeight;
    let ctx = canvas.getContext('2d');
    if (background) {
        ctx.fillStyle = background;
        ctx.fillRect(0, 0, canvasWidth, canvasHeight);
    }
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(image, rect.x, rect.y, rect.width, rect.height);
    return outputType === 'image/jpeg' ? canvas.toDataURL(outputType, JPEG_QUALITY) : canvas.toDataURL(outputType);
}

const browser = {readFileAsDataUrl, decodeImage, renderImage};

async function load(file, deps) {
    if (!looksLikeImage(file)) {
        throw new NotAnImageError('Not an image file: ' + (file && file.type));
    }
    let dataUrl = await deps.readFileAsDataUrl(file);
    let decoded = await deps.decodeImage(dataUrl);
    // Trust the bytes' declared type over the file's: the data URL is what ends up in the pack
    let type = mimeTypeOfDataUrl(dataUrl) || file.type;
    return {dataUrl, type, ...decoded};
}

/**
 * Prepares a file for use as a stage image (stage, cover or menu option node).
 *
 * @returns {Promise<{dataUrl: string, converted: boolean, reason: string|null, original: {type, width, height}}>}
 * @throws NotAnImageError when the file is not an image the browser can decode
 */
export async function prepareAssetImage(file, deps = browser) {
    let {dataUrl, type, image, width, height} = await load(file, deps);
    let bmpHeader = type === 'image/bmp' ? readBmpHeader(dataUrl) : null;
    let reason = assetConversionReason({type, width, height, bmpHeader});
    let original = {type, width, height};
    if (!reason) {
        return {dataUrl, converted: false, reason, original};
    }
    let rect = fitRect(width, height, ASSET_WIDTH, ASSET_HEIGHT);
    let converted = deps.renderImage(image, ASSET_WIDTH, ASSET_HEIGHT, rect, assetOutputType(type));
    return {dataUrl: converted, converted: true, reason, original};
}

/**
 * Prepares a file for use as the pack thumbnail.
 *
 * @returns {Promise<{dataUrl: string, converted: boolean, reason: string|null, original: {type, width, height}}>}
 * @throws NotAnImageError when the file is not an image the browser can decode
 */
export async function prepareThumbnail(file, deps = browser) {
    let {dataUrl, type, image, width, height} = await load(file, deps);
    let reason = thumbnailConversionReason({type, width, height});
    let original = {type, width, height};
    if (!reason) {
        return {dataUrl, converted: false, reason, original};
    }
    let size = thumbnailSize(width, height);
    let rect = {x: 0, y: 0, width: size.width, height: size.height};
    let converted = deps.renderImage(image, size.width, size.height, rect, THUMBNAIL_TYPE, null);
    return {dataUrl: converted, converted: true, reason, original};
}
