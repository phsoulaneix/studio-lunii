/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {handleJsonOrError, errorMessage, errorCode} from "../utils/fetch";

export const fetchLibraryInfos = () => {
    return fetch('/api/library/infos')
        .then(handleJsonOrError);
};

export const fetchLibraryPacks = () => {
    return fetch('/api/library/packs')
        .then(handleJsonOrError);
};

export const downloadFromLibrary = async (uuid, path) => {
    return await fetch('/api/library/download', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({uuid, path})
    });
};

// One entry of a library archive (story.json, thumbnail.png, assets/...), as a Blob, or null if the archive has none
export const fetchLibraryPackEntry = async (path, entry) => {
    const resp = await fetch('/api/library/entry', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({path, entry})
    });
    if (resp.status === 404) {
        return null;
    }
    if (!resp.ok) {
        const text = await resp.text().catch(() => '');
        const error = new Error(resp.statusText + ': ' + errorMessage(text));
        error.status = resp.status;
        throw error;
    }
    return resp.blob();
};

export const uploadToLibrary = async (uuid, path, packData, progressHandler) => {
    return new Promise((resolve, reject) => {
        let xhr = new XMLHttpRequest();
        if (xhr.upload) {
            xhr.upload.onprogress = progressHandler;
        }
        xhr.onload = () => {
            let body;
            try {
                body = JSON.parse(xhr.responseText);
            } catch (e) {
                body = undefined;
            }
            if (xhr.status < 200 || xhr.status >= 300) {
                // Same shape as handleJsonOrError, so the toast shows the backend's explanation.
                const text = xhr.responseText || '';
                const error = new Error((xhr.statusText || ('HTTP ' + xhr.status)) + ': ' + errorMessage(text));
                error.status = xhr.status;
                const code = errorCode(text);
                if (code) {
                    error.code = code;
                }
                reject(error);
                return;
            }
            if (body === undefined) {
                reject(new Error('Invalid response from the library upload: ' + xhr.responseText));
                return;
            }
            console.log('xhr upload complete: %o', body);
            resolve(body);
        };
        xhr.onerror = () => {
            reject(new Error('Network error while uploading the pack to the library'));
        };
        xhr.onabort = () => {
            reject(new Error('Upload to the library was aborted'));
        };
        xhr.open('post', '/api/library/upload', true);
        let formData = new FormData();
        formData.append("uuid", uuid);
        formData.append("path", path);
        formData.append("pack", packData);
        xhr.send(formData);
    });
};

export const convertInLibrary = async (uuid, path, format, allowEnriched) => {
    return await fetch('/api/library/convert', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({uuid, path, format, allowEnriched})
    })
        .then(handleJsonOrError);
};

export const removeFromLibrary = (path) => {
    return fetch('/api/library/remove', {
        method: "POST",
        headers: { "Content-Type" : "application/json" },
        body: JSON.stringify({path})
    })
        .then(handleJsonOrError);
};

/*
 * Whether a converted pack still corresponds to the source it was made from.
 *
 * Asked only when a pack is dropped on a device, never while listing: the backend reads both
 * artefacts in full to answer. It returns a verdict and nothing else — no digest, no record — so
 * this side has nothing to decide and nothing to get wrong.
 */
/*
 * Library entries that serve no purpose any more, as the backend sees them now:
 * {candidates: [{path, uuid, title, format, size, timestamp, reason, selected, keptPath}], totalSize}.
 */
export const fetchPurgeCandidates = () => {
    return fetch('/api/library/purge/candidates')
        .then(handleJsonOrError);
};

/*
 * Removes the named candidates. The backend checks each one again and leaves alone any that is no
 * longer a candidate: {success, freed, results: [{path, removed, size, error?}]}.
 */
export const purgeLibrary = (paths) => {
    return fetch('/api/library/purge', {
        method: "POST",
        headers: { "Content-Type" : "application/json" },
        body: JSON.stringify({paths})
    })
        .then(handleJsonOrError);
};

export const verifyConversion = async (sourcePath, convertedPath) => {
    return await fetch('/api/library/verify-conversion', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({sourcePath, convertedPath})
    })
        .then(handleJsonOrError);
};
