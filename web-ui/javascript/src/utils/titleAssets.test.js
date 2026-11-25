/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {
    applyPronunciations,
    collectMissingTitleAudio,
    parsePronunciations,
    generateMissingTitleAudio,
    layoutTitle,
    speakTitle,
    voiceRequest,
    wrapText
} from './titleAssets';

// 10 px per character, at any size unless said otherwise
const measure = (s) => s.length * 10;

describe('wrapText', () => {
    it('keeps words together and breaks between them', () => {
        expect(wrapText('Le petit chaperon rouge', measure, 100)).toEqual(['Le petit', 'chaperon', 'rouge']);
    });
    it('cuts a word longer than a line', () => {
        expect(wrapText('abcdefghijkl', measure, 50)).toEqual(['abcde', 'fghij', 'kl']);
    });
    it('ignores extra white space and empty text', () => {
        expect(wrapText('  un   deux\n trois ', measure, 1000)).toEqual(['un deux trois']);
        expect(wrapText('', measure, 100)).toEqual([]);
        expect(wrapText(null, measure, 100)).toEqual([]);
    });
});

describe('layoutTitle', () => {
    const measureAt = (size) => (s) => s.length * size * 0.5;

    it('uses the largest size that fits', () => {
        const short = layoutTitle('Loup', measureAt, 320, 240);
        expect(short.size).toBe(56);
        expect(short.lines).toEqual(['Loup']);
        const long = layoutTitle('Les aventures extraordinaires du petit chaperon rouge dans la forêt', measureAt, 320, 240);
        expect(long.size).toBeLessThan(56);
        expect(long.lines.length * long.lineHeight).toBeLessThanOrEqual(240 - 36);
        long.lines.forEach(l => expect(measureAt(long.size)(l)).toBeLessThanOrEqual(320 - 36));
    });

    it('ends with an ellipsis when even the smallest size is too big', () => {
        const layout = layoutTitle('mot '.repeat(200), measureAt, 320, 240);
        expect(layout.size).toBe(14);
        expect(layout.lines.length * layout.lineHeight).toBeLessThanOrEqual(240 - 36);
        expect(layout.lines[layout.lines.length - 1].endsWith('…')).toBe(true);
    });
});

describe('voiceRequest', () => {
    it('maps the voice setting to engine and voice', () => {
        expect(voiceRequest('a', 'auto')).toEqual({text: 'a', engine: 'auto'});
        expect(voiceRequest('a', undefined)).toEqual({text: 'a', engine: 'auto'});
        expect(voiceRequest('a', 'system')).toEqual({text: 'a', engine: 'system'});
        expect(voiceRequest('a', 'fr-FR-HenriNeural')).toEqual({text: 'a', engine: 'auto', voice: 'fr-FR-HenriNeural'});
    });
});

describe('speakTitle', () => {
    afterEach(() => {
        delete global.fetch;
    });

    it('posts JSON and answers a data URL', async () => {
        global.fetch = jest.fn(() => Promise.resolve({
            ok: true,
            json: () => Promise.resolve({success: true, engine: 'edge', voice: 'fr-FR-DeniseNeural', mimeType: 'audio/wav', data: 'UklGRg=='})
        }));
        const speech = await speakTitle('Bonjour', 'system');
        expect(speech.dataUrl).toBe('data:audio/wav;base64,UklGRg==');
        expect(speech.engine).toBe('edge');
        const [url, options] = global.fetch.mock.calls[0];
        expect(url).toBe('/api/tts/speak');
        expect(options.method).toBe('POST');
        expect(options.headers['Content-Type']).toBe('application/json');
        expect(JSON.parse(options.body)).toEqual({text: 'Bonjour', engine: 'system'});
    });

    it('rejects with the server message and code', async () => {
        global.fetch = jest.fn(() => Promise.resolve({
            ok: false, status: 503, statusText: 'Service Unavailable',
            text: () => Promise.resolve(JSON.stringify({success: false, error: 'TTS_UNAVAILABLE', message: 'Aucune voix disponible'}))
        }));
        await expect(speakTitle('Bonjour')).rejects.toMatchObject({status: 503, code: 'TTS_UNAVAILABLE'});
    });
});

// Minimal stand-ins for the diagram node models
const stage = (type, name, audio = null) => ({
    getType: () => type, name, audio,
    getName() { return this.name; }, getAudio() { return this.audio; }, setAudio(a) { this.audio = a; }
});
const menu = (name, question, options) => ({
    getType: () => 'menu', name, question, optionsStages: options.map(o => ({...o})),
    getName() { return this.name; },
    getQuestionAudio() { return this.question; }, setQuestionAudio(a) { this.question = a; },
    getOptionName(i) { return this.optionsStages[i].name; },
    getOptionAudio(i) { return this.optionsStages[i].audio; }, setOptionAudio(i, a) { this.optionsStages[i].audio = a; }
});

describe('collectMissingTitleAudio', () => {
    it('lists covers, stages, menu questions and options without audio, nothing else', () => {
        const cover = stage('cover', 'Mes histoires');
        const done = stage('stage', 'Déjà là', 'data:audio/wav;base64,AA==');
        const placeholder = stage('stage', 'Stage title');
        const story = stage('story', 'Le loup');
        const action = {getType: () => 'action'};
        const m = menu('Choisis ton histoire', null, [
            {name: 'Le loup', audio: null}, {name: 'Option #2', audio: null}, {name: 'La chèvre', audio: 'x'}]);
        const missing = collectMissingTitleAudio([cover, done, placeholder, story, action, m]);
        expect(missing.map(e => e.text)).toEqual(['Mes histoires', 'Choisis ton histoire', 'Le loup']);

        missing.forEach((e, i) => e.apply('audio' + i));
        expect(cover.audio).toBe('audio0');
        expect(m.question).toBe('audio1');
        expect(m.optionsStages[0].audio).toBe('audio2');
        expect(m.optionsStages[1].audio).toBe(null);
        expect(story.audio).toBe(null);
    });

    it('skips the names given to nodes dropped from the tray', () => {
        const missing = collectMissingTitleAudio([
            stage('stage', 'Stage node'), stage('cover', 'Cover node'), menu('Menu node', null, []),
            stage('stage', 'Le vrai titre')]);
        expect(missing.map(e => e.text)).toEqual(['Le vrai titre']);
    });
});

describe('generateMissingTitleAudio', () => {
    it('speaks one title at a time and reports progress', async () => {
        const applied = [];
        const order = [];
        let running = 0;
        const speak = jest.fn(async (text) => {
            running++;
            expect(running).toBe(1);
            order.push(text);
            await Promise.resolve();
            running--;
            return {dataUrl: 'data:' + text};
        });
        const progress = [];
        const count = await generateMissingTitleAudio(
            [{text: 'a', apply: d => applied.push(d)}, {text: 'b', apply: d => applied.push(d)}],
            {speak, onProgress: (d, t) => progress.push(d + '/' + t)});
        expect(count).toBe(2);
        expect(order).toEqual(['a', 'b']);
        expect(applied).toEqual(['data:a', 'data:b']);
        expect(progress).toEqual(['1/2', '2/2']);
    });

    it('stops at the first failure and says how many were done', async () => {
        const speak = jest.fn()
            .mockResolvedValueOnce({dataUrl: 'ok'})
            .mockRejectedValueOnce(new Error('hors ligne'));
        const entries = [{text: 'a', apply: () => {}}, {text: 'b', apply: () => {}}, {text: 'c', apply: () => {}}];
        await expect(generateMissingTitleAudio(entries, {speak})).rejects.toMatchObject({message: 'hors ligne', generated: 1});
        expect(speak).toHaveBeenCalledTimes(2);
    });

    it('leaves alone a title given an audio while the batch runs', async () => {
        const first = stage('stage', 'Premier');
        const second = stage('stage', 'Second');
        const missing = collectMissingTitleAudio([first, second]);
        const speak = jest.fn(async (text) => {
            // While the first title is spoken, the user drops a recording on the second node
            if (text === 'Premier') second.audio = 'data:recorded';
            return {dataUrl: 'data:tts-' + text};
        });

        await generateMissingTitleAudio(missing, {speak});

        expect(first.audio).toBe('data:tts-Premier');
        expect(second.audio).toBe('data:recorded');
        expect(speak).toHaveBeenCalledTimes(1);
    });

    it('stops once another pack is shown, writing nothing more', async () => {
        const first = stage('stage', 'Premier');
        const second = stage('stage', 'Second');
        let current = true;
        const speak = jest.fn(async () => { current = false; return {dataUrl: 'data:x'}; });

        await expect(generateMissingTitleAudio(collectMissingTitleAudio([first, second]), {speak, isCurrent: () => current}))
            .rejects.toMatchObject({code: 'PACK_CHANGED', generated: 0});
        expect(first.audio).toBeFalsy();
        expect(second.audio).toBeFalsy();
    });
});

describe('pronunciations (as in Lunii Pack)', () => {
    it('reads "written = spoken" pairs, longest first', () => {
        expect(parsePronunciations('Luke = Louc ; Lucky Luke=Lucky Louc\nDalton=Daltonne; sans egal ; =vide'))
            .toEqual([['Lucky Luke', 'Lucky Louc'], ['Dalton', 'Daltonne'], ['Luke', 'Louc']]);
        expect(parsePronunciations('')).toEqual([]);
        expect(parsePronunciations(null)).toEqual([]);
    });

    it('replaces whole words, ignoring case, and keeps the plural', () => {
        const pairs = parsePronunciations('Dalton = Daltonne ; Luke = Louc ; Lucky Luke = Lucky Louc');
        expect(applyPronunciations('Lucky Luke contre les Daltons', pairs)).toBe('Lucky Louc contre les Daltonnes');
        expect(applyPronunciations('luke et DALTON', pairs)).toBe('Louc et Daltonne');
        // not inside another word, accents included
        expect(applyPronunciations('Lukeé Daltonien éDalton', pairs)).toBe('Lukeé Daltonien éDalton');
        expect(applyPronunciations('(Luke) - Dalton.', pairs)).toBe('(Louc) - Daltonne.');
    });

    it('treats the written form literally, not as a pattern', () => {
        expect(applyPronunciations('Mr. X et MrsX', parsePronunciations('Mr. = Monsieur'))).toBe('Monsieur X et MrsX');
    });

    it('are applied to the text sent for speech', async () => {
        global.fetch = jest.fn(() => Promise.resolve({ok: true, json: () => Promise.resolve({data: 'AA=='})}));
        await speakTitle('Les Daltons', 'fr-FR-HenriNeural', 'Dalton=Daltonne');
        expect(JSON.parse(global.fetch.mock.calls[0][1].body))
            .toEqual({text: 'Les Daltonnes', engine: 'auto', voice: 'fr-FR-HenriNeural'});
        delete global.fetch;
    });
});
