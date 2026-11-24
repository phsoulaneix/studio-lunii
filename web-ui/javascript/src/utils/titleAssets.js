/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Generated assets for node titles: the spoken title (server side, /api/tts) and an image showing
// the title (drawn here, on a canvas). Self-contained on purpose: no dependency on other helpers.

import {handleJsonOrError} from "./fetch";

export const LOCAL_STORAGE_TITLE_VOICE = 'titleVoice';

/** Device screen size: the editor refuses any other image size. */
export const TITLE_IMAGE_WIDTH = 320;
export const TITLE_IMAGE_HEIGHT = 240;

/** Same limit as the server. */
export const TITLE_MAX_LENGTH = 500;

// -- voice

/**
 * The voice setting, stored in this browser: 'auto' (online voice, computer voice as fallback),
 * 'system' (computer voice only, nothing leaves the computer) or an online voice name.
 */
export function getTitleVoice() {
    try {
        return window.localStorage.getItem(LOCAL_STORAGE_TITLE_VOICE) || 'auto';
    } catch (e) {
        return 'auto';
    }
}

export function setTitleVoice(value) {
    try {
        window.localStorage.setItem(LOCAL_STORAGE_TITLE_VOICE, value);
    } catch (e) {
        // Not persisted: the default is used next time.
    }
}

/** The request body for a voice setting (see getTitleVoice). */
export function voiceRequest(text, setting) {
    if (!setting || setting === 'auto') {
        return {text, engine: 'auto'};
    }
    if (setting === 'system') {
        return {text, engine: 'system'};
    }
    // A chosen online voice: auto, so that the computer voice still answers when offline.
    return {text, engine: 'auto', voice: setting};
}

export async function fetchVoiceEngines() {
    return fetch('/api/tts/engines').then(handleJsonOrError);
}

/**
 * Speaks the text on the server. Resolves to {dataUrl, engine, voice, fallbackReason}; dataUrl is a
 * device-native WAVE, attached to a node exactly as a dropped file would be.
 */
export async function speakTitle(text, setting = getTitleVoice()) {
    const answer = await fetch('/api/tts/speak', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(voiceRequest(text, setting))
    }).then(handleJsonOrError);
    return {
        dataUrl: 'data:' + (answer.mimeType || 'audio/wav') + ';base64,' + answer.data,
        engine: answer.engine,
        voice: answer.voice,
        fallbackReason: answer.fallbackReason
    };
}

// -- image

/**
 * Splits the text into lines no wider than maxWidth, as measured by measure(text) → width.
 * A word wider than a line on its own is cut between characters.
 */
export function wrapText(text, measure, maxWidth) {
    const words = String(text || '').trim().split(/\s+/).filter(w => w.length > 0);
    const lines = [];
    let current = '';
    const pushWord = (word) => {
        // Cut a word that cannot fit on any line
        while (measure(word) > maxWidth && word.length > 1) {
            let cut = word.length - 1;
            while (cut > 1 && measure(word.substring(0, cut)) > maxWidth) {
                cut--;
            }
            if (current) {
                lines.push(current);
                current = '';
            }
            lines.push(word.substring(0, cut));
            word = word.substring(cut);
        }
        return word;
    };
    words.forEach(w => {
        const word = pushWord(w);
        const candidate = current ? current + ' ' + word : word;
        if (current && measure(candidate) > maxWidth) {
            lines.push(current);
            current = word;
        } else {
            current = candidate;
        }
    });
    if (current) {
        lines.push(current);
    }
    return lines;
}

/**
 * The largest font size (from maxSize down to minSize) at which the wrapped text fits in the box.
 * measureAt(size) returns a measure function for that font size. Returns {size, lines, lineHeight}.
 */
export function layoutTitle(text, measureAt, width, height, {maxSize = 56, minSize = 14, margin = 18, lineSpacing = 1.2} = {}) {
    const innerWidth = width - 2 * margin;
    const innerHeight = height - 2 * margin;
    let best = null;
    for (let size = maxSize; size >= minSize; size -= 2) {
        const lines = wrapText(text, measureAt(size), innerWidth);
        const lineHeight = Math.round(size * lineSpacing);
        best = {size, lines, lineHeight};
        if (lines.length * lineHeight <= innerHeight) {
            return best;
        }
    }
    // Still too long at the smallest size: keep the lines that fit, the last one ending with "…"
    const maxLines = Math.max(1, Math.floor(innerHeight / best.lineHeight));
    if (best.lines.length > maxLines) {
        const measure = measureAt(best.size);
        const lines = best.lines.slice(0, maxLines);
        let last = lines[maxLines - 1] + '…';
        while (last.length > 1 && measure(last) > innerWidth) {
            last = last.substring(0, last.length - 2) + '…';
        }
        lines[maxLines - 1] = last;
        best = {...best, lines};
    }
    return best;
}

const TITLE_FONT = '"Trebuchet MS", "Segoe UI", Arial, sans-serif';

/**
 * Draws the title, centred and wrapped, light on dark for the device screen, and returns a JPEG
 * data URL of 320x240 (JPEG: no alpha channel, which the device does not take).
 */
export function renderTitleImage(text, {background = '#1d2b53', foreground = '#ffffff', accent = '#ffcc33'} = {}) {
    const canvas = document.createElement('canvas');
    canvas.width = TITLE_IMAGE_WIDTH;
    canvas.height = TITLE_IMAGE_HEIGHT;
    const ctx = canvas.getContext('2d');

    ctx.fillStyle = background;
    ctx.fillRect(0, 0, TITLE_IMAGE_WIDTH, TITLE_IMAGE_HEIGHT);
    // A thin frame, so that the screen edge does not cut the composition
    ctx.strokeStyle = accent;
    ctx.lineWidth = 4;
    ctx.strokeRect(8, 8, TITLE_IMAGE_WIDTH - 16, TITLE_IMAGE_HEIGHT - 16);

    const measureAt = (size) => {
        ctx.font = `bold ${size}px ${TITLE_FONT}`;
        return (s) => ctx.measureText(s).width;
    };
    const {size, lines, lineHeight} = layoutTitle(text, measureAt, TITLE_IMAGE_WIDTH, TITLE_IMAGE_HEIGHT, {margin: 22});

    ctx.font = `bold ${size}px ${TITLE_FONT}`;
    ctx.fillStyle = foreground;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    const top = (TITLE_IMAGE_HEIGHT - lines.length * lineHeight) / 2 + lineHeight / 2;
    lines.forEach((line, i) => ctx.fillText(line, TITLE_IMAGE_WIDTH / 2, top + i * lineHeight));

    return canvas.toDataURL('image/jpeg', 0.95);
}

// -- shared

/** Asks for the text to use, prefilled with the title. Null when cancelled or empty. */
export function askTitleText(promptLabel, title) {
    const answer = window.prompt(promptLabel, title || '');
    if (answer === null) {
        return null;
    }
    const text = answer.trim();
    return text.length > 0 ? text.substring(0, TITLE_MAX_LENGTH) : null;
}

// -- every missing title voice of a pack

/** Names the editor gives new nodes: not titles anyone wants spoken. */
const PLACEHOLDER_NAME = /^(Stage title|Cover title|Menu title|Option #\d+)$/;

function isRealTitle(name) {
    return typeof name === 'string' && name.trim().length > 0 && !PLACEHOLDER_NAME.test(name.trim());
}

/**
 * The titles of the diagram that have no audio: cover and stage nodes, menu questions and menu
 * options. Story nodes are left out, their audio being the story itself, and so are nodes still
 * carrying the editor's placeholder name. Each entry is {text, apply(dataUrl)}.
 */
export function collectMissingTitleAudio(nodes) {
    const missing = [];
    (nodes || []).forEach(node => {
        const type = typeof node.getType === 'function' ? node.getType() : undefined;
        if (type === 'cover' || type === 'stage') {
            if (!node.getAudio() && isRealTitle(node.getName())) {
                missing.push({text: node.getName().trim(), apply: (dataUrl) => node.setAudio(dataUrl)});
            }
        } else if (type === 'menu') {
            if (!node.getQuestionAudio() && isRealTitle(node.getName())) {
                missing.push({text: node.getName().trim(), apply: (dataUrl) => node.setQuestionAudio(dataUrl)});
            }
            node.optionsStages.forEach((option, idx) => {
                if (!node.getOptionAudio(idx) && isRealTitle(node.getOptionName(idx))) {
                    missing.push({text: node.getOptionName(idx).trim(), apply: (dataUrl) => node.setOptionAudio(idx, dataUrl)});
                }
            });
        }
    });
    return missing;
}

/**
 * Speaks every entry, one at a time. onProgress(done, total) after each one. Stops at the first
 * failure (the server or the voice is down: the next ones would fail the same way) and rejects with
 * that error, error.generated telling how many were done.
 */
export async function generateMissingTitleAudio(missing, {speak = speakTitle, onProgress = () => {}} = {}) {
    let done = 0;
    for (const entry of missing) {
        try {
            const speech = await speak(entry.text);
            entry.apply(speech.dataUrl);
        } catch (e) {
            e.generated = done;
            throw e;
        }
        done++;
        onProgress(done, missing.length);
    }
    return done;
}
