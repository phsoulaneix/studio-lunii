/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Opening a pack of the local library in the editor. It used to download the whole archive (up to a
 * gigabyte) and unzip it in the browser, turning every audio file into a base64 data URL: more than a
 * minute and several gigabytes of JavaScript heap for a large pack. readFromLibrary reads story.json,
 * then each asset once, as an entry served by the library, and keeps the assets as object URLs.
 */

import {readFromLibrary, releaseLibraryAssets} from "./reader";

const STAGE_UUID = '00000000-0000-0000-0000-000000000001';
const SECOND_UUID = '00000000-0000-0000-0000-000000000002';
const CONTROLS = {wheel: false, ok: true, home: false, pause: false, autoplay: true};

function story() {
    return {
        title: 'Été',
        version: 2,
        description: 'Une description',
        nightModeAvailable: true,
        actionNodes: [],
        stageNodes: [
            {uuid: STAGE_UUID, type: 'stage', squareOne: true, name: 'Départ', image: 'cover.png', audio: 'shared.mp3',
                okTransition: null, homeTransition: null, controlSettings: CONTROLS, position: {x: 10, y: 20}},
            {uuid: SECOND_UUID, type: 'stage', name: 'Suite', image: null, audio: 'shared.mp3',
                okTransition: null, homeTransition: null, controlSettings: CONTROLS, position: {x: 30, y: 40}}
        ]
    };
}

function libraryOf(entries) {
    const requested = [];
    const loadEntry = (name) => {
        requested.push(name);
        return Promise.resolve(name in entries ? new Blob([entries[name]]) : null);
    };
    return {loadEntry, requested};
}

describe('readFromLibrary', () => {
    let created;
    let revoked;

    beforeEach(() => {
        created = [];
        revoked = [];
        let counter = 0;
        URL.createObjectURL = jest.fn(blob => {
            const url = 'blob:test/' + (++counter);
            created.push({url, type: blob.type});
            return url;
        });
        URL.revokeObjectURL = jest.fn(url => revoked.push(url));
        jest.spyOn(console, 'log').mockImplementation(() => {});
    });

    afterEach(() => {
        console.log.mockRestore();
    });

    it('builds the model from story.json, with assets as object URLs, each loaded once', async () => {
        const {loadEntry, requested} = libraryOf({
            'story.json': JSON.stringify(story()),
            'thumbnail.png': 'png bytes',
            'assets/cover.png': 'image bytes',
            'assets/shared.mp3': 'audio bytes'
        });

        const model = await readFromLibrary(loadEntry);

        expect(model.title).toBe('Été');
        expect(model.version).toBe(2);
        expect(model.nightModeAvailable).toBe(true);
        expect(model.thumbnail).toMatch(/^data:/);
        const stage = model.getNodes().find(node => node.getUuid && node.getUuid() === STAGE_UUID);
        const second = model.getNodes().find(node => node.getUuid && node.getUuid() === SECOND_UUID);
        expect(stage.getImage()).toMatch(/^blob:/);
        expect(stage.getAudio()).toMatch(/^blob:/);
        expect(second.getAudio()).toBe(stage.getAudio());
        expect(requested.filter(name => name === 'assets/shared.mp3')).toHaveLength(1);
        expect(created.map(c => c.type).sort()).toEqual(['audio/mpeg', 'image/png']);
    });

    it('does without a thumbnail', async () => {
        const {loadEntry} = libraryOf({
            'story.json': JSON.stringify(story()),
            'assets/cover.png': 'image bytes',
            'assets/shared.mp3': 'audio bytes'
        });

        const model = await readFromLibrary(loadEntry);

        expect(model.thumbnail).toBeNull();
    });

    it('fails on a missing asset, and releases the object URLs it made', async () => {
        const {loadEntry} = libraryOf({
            'story.json': JSON.stringify(story()),
            'assets/shared.mp3': 'audio bytes'
        });

        await expect(readFromLibrary(loadEntry)).rejects.toThrow('Missing asset: cover.png');
        expect(revoked.sort()).toEqual(created.map(c => c.url).sort());
    });

    it('releases the assets of the last pack read, and tells which model they belonged to', async () => {
        const model = await readFromLibrary(libraryOf({
            'story.json': JSON.stringify(story()),
            'assets/cover.png': 'image bytes',
            'assets/shared.mp3': 'audio bytes'
        }).loadEntry);

        expect(revoked).toEqual([]);
        expect(releaseLibraryAssets()).toBe(model);
        expect(revoked.sort()).toEqual(created.map(c => c.url).sort());
        expect(releaseLibraryAssets()).toBeNull();
    });
});
