/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {
    collectMissingTitleAudio,
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
});
