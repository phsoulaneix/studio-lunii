/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * When an image given to the editor is converted, and to what.
 *
 * jsdom has no canvas, so the decoding and drawing are replaced by fakes: what is tested is the
 * decision (keep or convert), the geometry (where the picture lands on the 320x240 screen) and the
 * output type, not the pixels.
 */

import {
    ASSET_WIDTH, ASSET_HEIGHT, THUMBNAIL_MAX_SIZE, NOT_AN_IMAGE,
    looksLikeImage, mimeTypeOfDataUrl, readBmpHeader,
    assetConversionReason, thumbnailConversionReason,
    fitRect, thumbnailSize, assetOutputType,
    prepareAssetImage, prepareThumbnail
} from './images';

/** A base64 data URL for a BMP header with the given fields (the pixels are not needed). */
function bmpDataUrl(width, height, bitsPerPixel, compression) {
    let bytes = new Uint8Array(54);
    let view = new DataView(bytes.buffer);
    bytes[0] = 0x42; bytes[1] = 0x4D;
    view.setInt32(18, width, true);
    view.setInt32(22, height, true);
    view.setUint16(28, bitsPerPixel, true);
    view.setUint32(30, compression, true);
    return 'data:image/bmp;base64,' + btoa(String.fromCharCode.apply(null, bytes));
}

/** Fakes for the browser plumbing: the file "decodes" to the given size. */
function fakeBrowser(dataUrl, width, height, {decodes = true} = {}) {
    let rendered = [];
    return {
        rendered,
        readFileAsDataUrl: () => Promise.resolve(dataUrl),
        decodeImage: () => decodes
            ? Promise.resolve({image: 'IMG', width, height})
            : Promise.reject(Object.assign(new Error('nope'), {code: NOT_AN_IMAGE})),
        renderImage: (image, w, h, rect, type, background) => {
            rendered.push({image, w, h, rect, type, background});
            return 'data:' + type + ';base64,CONVERTED';
        }
    };
}

describe('looksLikeImage', () => {
    it('accepts any image MIME type', () => {
        ['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'image/bmp', 'image/svg+xml']
            .forEach(type => expect(looksLikeImage({type, name: 'x'})).toBe(true));
    });
    it('refuses files that are something else', () => {
        expect(looksLikeImage({type: 'audio/mpeg', name: 'song.mp3'})).toBe(false);
        expect(looksLikeImage({type: 'application/pdf', name: 'doc.pdf'})).toBe(false);
        expect(looksLikeImage(null)).toBe(false);
    });
    it('falls back on the extension when the system gives no type', () => {
        expect(looksLikeImage({type: '', name: 'Photo.WEBP'})).toBe(true);
        expect(looksLikeImage({type: '', name: 'notes.txt'})).toBe(false);
        expect(looksLikeImage({type: '', name: 'noextension'})).toBe(false);
    });
});

describe('mimeTypeOfDataUrl', () => {
    it('reads the type of a data URL', () => {
        expect(mimeTypeOfDataUrl('data:image/png;base64,AAAA')).toBe('image/png');
        expect(mimeTypeOfDataUrl('data:application/octet-stream;base64,AAAA')).toBe('application/octet-stream');
        expect(mimeTypeOfDataUrl('nonsense')).toBe('');
    });
});

describe('readBmpHeader', () => {
    it('reads dimensions, depth and compression', () => {
        expect(readBmpHeader(bmpDataUrl(320, 240, 24, 0)))
            .toEqual({width: 320, height: 240, bitsPerPixel: 24, compression: 0});
        expect(readBmpHeader(bmpDataUrl(320, -240, 32, 3)))
            .toEqual({width: 320, height: -240, bitsPerPixel: 32, compression: 3});
    });
    it('returns null for something that is not a BMP', () => {
        expect(readBmpHeader('data:image/png;base64,' + btoa('\x89PNG' + '\0'.repeat(60)))).toBeNull();
        expect(readBmpHeader('data:image/bmp;base64,Qk0=')).toBeNull();
    });
});

describe('assetConversionReason', () => {
    it('keeps a BMP, PNG or JPEG that is already 320x240', () => {
        expect(assetConversionReason({type: 'image/png', width: 320, height: 240})).toBeNull();
        expect(assetConversionReason({type: 'image/jpeg', width: 320, height: 240})).toBeNull();
        expect(assetConversionReason({type: 'image/bmp', width: 320, height: 240,
            bmpHeader: {width: 320, height: 240, bitsPerPixel: 24, compression: 0}})).toBeNull();
        expect(assetConversionReason({type: 'image/bmp', width: 320, height: 240,
            bmpHeader: {width: 320, height: 240, bitsPerPixel: 4, compression: 2}})).toBeNull();
    });
    it('converts any other size', () => {
        expect(assetConversionReason({type: 'image/png', width: 1920, height: 1080})).toBe('dimensions');
        expect(assetConversionReason({type: 'image/jpeg', width: 240, height: 320})).toBe('dimensions');
        expect(assetConversionReason({type: 'image/png', width: 32, height: 24})).toBe('dimensions');
    });
    it('converts any other type, even at the right size', () => {
        ['image/webp', 'image/gif', 'image/svg+xml', 'application/octet-stream']
            .forEach(type => expect(assetConversionReason({type, width: 320, height: 240})).toBe('type'));
    });
    it('converts a BMP the device conversion would not handle', () => {
        // 32-bit, top-down, 8-bit indexed: decoded fine by a browser, not by the pack conversion
        [{bitsPerPixel: 32, compression: 3, height: 240},
         {bitsPerPixel: 24, compression: 0, height: -240},
         {bitsPerPixel: 8, compression: 0, height: 240}]
            .forEach(h => expect(assetConversionReason({type: 'image/bmp', width: 320, height: 240,
                bmpHeader: {width: 320, ...h}})).toBe('bmpDepth'));
        expect(assetConversionReason({type: 'image/bmp', width: 320, height: 240, bmpHeader: null}))
            .toBe('bmpDepth');
    });
});

describe('thumbnailConversionReason', () => {
    it('keeps a PNG up to the maximum size', () => {
        expect(thumbnailConversionReason({type: 'image/png', width: 256, height: 256})).toBeNull();
        expect(thumbnailConversionReason({type: 'image/png', width: THUMBNAIL_MAX_SIZE, height: 100})).toBeNull();
    });
    it('converts anything that is not a PNG, or too large', () => {
        expect(thumbnailConversionReason({type: 'image/jpeg', width: 256, height: 256})).toBe('type');
        expect(thumbnailConversionReason({type: 'image/png', width: 4000, height: 3000})).toBe('dimensions');
    });
});

describe('fitRect', () => {
    it('fills the screen when the proportions already match', () => {
        expect(fitRect(320, 240, 320, 240)).toEqual({x: 0, y: 0, width: 320, height: 240});
        expect(fitRect(1600, 1200, 320, 240)).toEqual({x: 0, y: 0, width: 320, height: 240});
        expect(fitRect(32, 24, 320, 240)).toEqual({x: 0, y: 0, width: 320, height: 240});
    });
    it('adds bands above and below a wider image', () => {
        // 16:9 in 4:3: 320x180, 30 px bands
        expect(fitRect(1920, 1080, 320, 240)).toEqual({x: 0, y: 30, width: 320, height: 180});
    });
    it('adds bands on the sides of a taller image', () => {
        // portrait 3:4: 180x240, 70 px bands
        expect(fitRect(3000, 4000, 320, 240)).toEqual({x: 70, y: 0, width: 180, height: 240});
        // square: 240x240
        expect(fitRect(1000, 1000, 320, 240)).toEqual({x: 40, y: 0, width: 240, height: 240});
    });
    it('stays inside the box for extreme proportions', () => {
        let r = fitRect(10000, 1, 320, 240);
        expect(r.width).toBe(320);
        expect(r.height).toBe(1);
        expect(r.y + r.height).toBeLessThanOrEqual(240);
        let t = fitRect(1, 10000, 320, 240);
        expect(t.width).toBe(1);
        expect(t.height).toBe(240);
    });
});

describe('thumbnailSize', () => {
    it('scales down to fit the maximum, keeping proportions', () => {
        expect(thumbnailSize(4000, 3000)).toEqual({width: 512, height: 384});
        expect(thumbnailSize(1000, 2000)).toEqual({width: 256, height: 512});
    });
    it('never scales up', () => {
        expect(thumbnailSize(100, 50)).toEqual({width: 100, height: 50});
    });
});

describe('assetOutputType', () => {
    it('keeps JPEG as JPEG, makes everything else PNG', () => {
        expect(assetOutputType('image/jpeg')).toBe('image/jpeg');
        ['image/png', 'image/webp', 'image/gif', 'image/bmp', 'application/octet-stream']
            .forEach(t => expect(assetOutputType(t)).toBe('image/png'));
    });
});

describe('prepareAssetImage', () => {
    it('returns a 320x240 PNG untouched', async () => {
        let fake = fakeBrowser('data:image/png;base64,ORIGINAL', 320, 240);
        let result = await prepareAssetImage({type: 'image/png', name: 'a.png'}, fake);
        expect(result.converted).toBe(false);
        expect(result.dataUrl).toBe('data:image/png;base64,ORIGINAL');
        expect(fake.rendered).toHaveLength(0);
    });

    it('converts a large JPEG to a letterboxed 320x240 JPEG on black', async () => {
        let fake = fakeBrowser('data:image/jpeg;base64,ORIGINAL', 4032, 3024);
        let result = await prepareAssetImage({type: 'image/jpeg', name: 'photo.jpg'}, fake);
        expect(result.converted).toBe(true);
        expect(result.reason).toBe('dimensions');
        expect(result.original).toEqual({type: 'image/jpeg', width: 4032, height: 3024});
        expect(result.dataUrl).toBe('data:image/jpeg;base64,CONVERTED');
        expect(fake.rendered).toEqual([{image: 'IMG', w: ASSET_WIDTH, h: ASSET_HEIGHT,
            rect: {x: 0, y: 0, width: 320, height: 240}, type: 'image/jpeg', background: undefined}]);
    });

    it('converts a WebP to PNG', async () => {
        let fake = fakeBrowser('data:image/webp;base64,ORIGINAL', 800, 450);
        let result = await prepareAssetImage({type: 'image/webp', name: 'a.webp'}, fake);
        expect(result.converted).toBe(true);
        expect(result.reason).toBe('type');
        expect(result.dataUrl).toBe('data:image/png;base64,CONVERTED');
        expect(fake.rendered[0].rect).toEqual({x: 0, y: 30, width: 320, height: 180});
    });

    it('trusts the type of the bytes read over the file type', async () => {
        // No MIME type from the system: the data URL says octet-stream, so it is re-encoded
        let fake = fakeBrowser('data:application/octet-stream;base64,ORIGINAL', 320, 240);
        let result = await prepareAssetImage({type: '', name: 'a.webp'}, fake);
        expect(result.converted).toBe(true);
        expect(result.dataUrl).toBe('data:image/png;base64,CONVERTED');
    });

    it('converts a 32-bit BMP at the right size', async () => {
        let fake = fakeBrowser(bmpDataUrl(320, 240, 32, 3), 320, 240);
        let result = await prepareAssetImage({type: 'image/bmp', name: 'a.bmp'}, fake);
        expect(result.converted).toBe(true);
        expect(result.reason).toBe('bmpDepth');
        expect(result.dataUrl).toBe('data:image/png;base64,CONVERTED');
    });

    it('keeps a 24-bit BMP at the right size', async () => {
        let dataUrl = bmpDataUrl(320, 240, 24, 0);
        let fake = fakeBrowser(dataUrl, 320, 240);
        let result = await prepareAssetImage({type: 'image/bmp', name: 'a.bmp'}, fake);
        expect(result.converted).toBe(false);
        expect(result.dataUrl).toBe(dataUrl);
    });

    it('refuses a file that is not an image, without reading it', async () => {
        let fake = fakeBrowser('data:audio/mpeg;base64,AAAA', 0, 0);
        fake.readFileAsDataUrl = jest.fn();
        await expect(prepareAssetImage({type: 'audio/mpeg', name: 'a.mp3'}, fake))
            .rejects.toMatchObject({code: NOT_AN_IMAGE});
        expect(fake.readFileAsDataUrl).not.toHaveBeenCalled();
    });

    it('refuses an image file the browser cannot decode', async () => {
        let fake = fakeBrowser('data:image/png;base64,BROKEN', 0, 0, {decodes: false});
        await expect(prepareAssetImage({type: 'image/png', name: 'broken.png'}, fake))
            .rejects.toMatchObject({code: NOT_AN_IMAGE});
    });
});

describe('prepareThumbnail', () => {
    it('returns a small PNG untouched', async () => {
        let fake = fakeBrowser('data:image/png;base64,ORIGINAL', 256, 256);
        let result = await prepareThumbnail({type: 'image/png', name: 't.png'}, fake);
        expect(result.converted).toBe(false);
        expect(result.dataUrl).toBe('data:image/png;base64,ORIGINAL');
    });

    it('converts a JPEG to PNG, scaled down, keeping transparency possible', async () => {
        let fake = fakeBrowser('data:image/jpeg;base64,ORIGINAL', 2000, 1000);
        let result = await prepareThumbnail({type: 'image/jpeg', name: 't.jpg'}, fake);
        expect(result.converted).toBe(true);
        expect(result.dataUrl).toBe('data:image/png;base64,CONVERTED');
        expect(fake.rendered).toEqual([{image: 'IMG', w: 512, h: 256,
            rect: {x: 0, y: 0, width: 512, height: 256}, type: 'image/png', background: null}]);
    });

    it('refuses a file that is not an image', async () => {
        let fake = fakeBrowser('', 0, 0);
        await expect(prepareThumbnail({type: 'text/plain', name: 'a.txt'}, fake))
            .rejects.toMatchObject({code: NOT_AN_IMAGE});
    });
});
