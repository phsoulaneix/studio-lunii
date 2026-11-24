/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import { toast } from 'react-toastify';
import {Mutex, withTimeout} from 'async-mutex';

import IssueReportToast from "../components/IssueReportToast";
import PackDiagramModel from "../components/diagram/models/PackDiagramModel";
import {fetchDeviceInfos, fetchDevicePacks, addFromLibrary, removeFromDevice, reorderPacks, addToLibrary, fetchTransfer} from '../services/device';
import {SUBSCRIBED, CHANNEL_OPEN} from '../services/eventBusChannel';
import {fetchLibraryInfos, fetchLibraryPacks, downloadFromLibrary, fetchLibraryPackEntry, uploadToLibrary, convertInLibrary, removeFromLibrary} from '../services/library';
import {fetchEvergreenInfos, fetchEvergreenLatestRelease, fetchEvergreenAnnounce} from '../services/evergreen';
import {generateFilename, sortPacks} from "../utils/packs";
import {readFromArchive, readFromLibrary, releaseLibraryAssets} from "../utils/reader";
import {errorMessage} from "../utils/fetch";
import {simplifiedSample} from "../utils/sample";


const mutex = withTimeout(new Mutex(), 100);

export const actionLoadLibrary = (t) => {
    return dispatch => {
        let toastId = toast(t('toasts.library.loading'), { autoClose: false });
        return fetchLibraryInfos()
            .then(metadata => {
                console.log("fetching library packs...");
                toast.update(toastId,{ render: t('toasts.library.fetching') });
                return fetchLibraryPacks()
                    .then(packs => {
                        toast.update(toastId, { type: toast.TYPE.INFO, render: t('toasts.library.fetched', { count: packs.length }), autoClose: 5000 });
                        dispatch(setLibrary(metadata, packs));
                    })
                    .catch(e => {
                        console.error('failed to fetch library packs', e);
                        toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.fetchingFailed')}</>} error={e} />, autoClose: false });
                    });
            })
            .catch(e => {
                console.error('failed to fetch library infos', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.loadingFailed')}</>} error={e} />, autoClose: false });
            });
    }
};

export const setLibrary = (metadata, packs) => ({
    type: 'SET_LIBRARY',
    metadata,
    packs: sortPacks(packs)
});


export const actionCheckDevice = (t) => {
    return dispatch => mutex.acquire()
        .then(
            release => {
                let toastId = toast(t('toasts.device.checking'), { autoClose: false });
                return fetchDeviceInfos()
                    .then(metadata => {
                        if (metadata && Object.keys(metadata).length > 0 && metadata.plugged) {
                            toast.update(toastId, { type: toast.TYPE.INFO, render: t('toasts.device.plugged'), autoClose: 5000 });
                            dispatch(actionDevicePlugged(metadata, t));
                        } else {
                            // Device not plugged, nothing to do
                            toast.dismiss(toastId);
                        }
                    })
                    .catch(e => {
                        console.error('failed to fetch device infos', e);
                        toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.checkingFailed')}</>} error={e} />, autoClose: false });
                    })
                    .finally(() => {
                        // Always release the mutex
                        release();
                    });
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
            });
};

export const actionDevicePlugged = (metadata, t) => {
    return dispatch => mutex.acquire()
        .then(
            release => {
            dispatch(devicePlugged(metadata));

            console.log("fetching device packs...");
            let toastId = toast(t('toasts.device.fetching'), { autoClose: false });
            return fetchDevicePacks()
                .then(packs => {
                    toast.update(toastId, { type: toast.TYPE.INFO, render: t('toasts.device.fetched', { count: packs.length }), autoClose: 5000 });
                    dispatch(setDevicePacks(packs));
                })
                .catch(e => {
                    console.error('failed to fetch device packs', e);
                    toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.fetchingFailed')}</>} error={e} />, autoClose: false });
                })
                .finally(() => {
                    // Always release the mutex
                    release();
                });
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
            });
};

export const actionRefreshDevice = (t) => {
    return dispatch => mutex.acquire()
        .then(
            release => {
                return fetchDeviceInfos()
                    .then(metadata => {
                        if (metadata && Object.keys(metadata).length > 0 && metadata.plugged) {
                            return fetchDevicePacks()
                                .then(packs => {
                                    dispatch(devicePlugged(metadata));
                                    dispatch(setDevicePacks(packs));
                                });
                        } else {
                            // The device went away (possibly while its unplugged event was lost)
                            dispatch(deviceUnplugged());
                        }
                    })
                    .catch(e => {
                        console.error('failed to refresh device', e);
                        toast(<IssueReportToast content={<>{t('toasts.device.refreshingFailed')}</>} error={e} />, { type: toast.TYPE.ERROR, autoClose: false });
                    })
                    .finally(() => {
                        // Always release the mutex
                        release();
                    });
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
            });
};

/** How often a transfer whose verdict has not arrived is re-read over HTTP. */
export const TRANSFER_RECONCILE_INTERVAL_MS = 3000;

/**
 * How many registry reads in a row may fail (other than with a 404) before the transfer is given
 * up on: about two minutes at TRANSFER_RECONCILE_INTERVAL_MS. Past that, its outcome is reported
 * as unknown and the device is re-read, rather than polling forever.
 */
export const TRANSFER_RECONCILE_MAX_FAILURES = 40;

/*
 * Follows one transfer until its verdict is known, and calls onVerdict(verdict) exactly once.
 *
 * The event bus alone cannot be trusted with this: an event published before the subscription is
 * on the wire, or while the socket is down, is lost and never replayed. So the transfer registry
 * (GET /api/device/transfer/{id}) is read right after subscribing, every few seconds until the
 * verdict is known, and on every reconnection. Whichever source answers first wins; `settled`
 * makes sure the other is ignored. Only one registry read is in flight at a time. Everything
 * (interval, subscriptions, state listener) is torn down on settlement.
 *
 * verdict is {success, error?, message?} — the same fields in the done event and in the registry —
 * or {success: false, unknown: true} when the outcome cannot be known: the backend answers 404 for
 * the transfer (e.g. after a restart), or its registry could not be read
 * TRANSFER_RECONCILE_MAX_FAILURES times in a row. Waiting longer would be pointless.
 *
 * Progress handed to onProgress never goes backwards, whichever source reports it.
 *
 * Returns what subscribing to the done address returned (SUBSCRIBED or DEFERRED).
 */
function followTransfer(transferId, channel, {onProgress, onVerdict}) {
    const progressAddress = 'storyteller.transfer.' + transferId + '.progress';
    const doneAddress = 'storyteller.transfer.' + transferId + '.done';
    let settled = false;
    let interval = null;
    let removeStateListener = null;
    let inFlight = false;
    let failures = 0;
    let lastProgress = 0;

    const settle = (verdict) => {
        if (settled) {
            return;
        }
        settled = true;
        if (interval !== null) {
            clearInterval(interval);
            interval = null;
        }
        channel.unsubscribe(progressAddress, handleProgress);
        channel.unsubscribe(doneAddress, handleDone);
        if (removeStateListener) {
            removeStateListener();
            removeStateListener = null;
        }
        onVerdict(verdict);
    };

    const reportProgress = (progress) => {
        if (settled || typeof progress !== 'number' || isNaN(progress)) {
            return;
        }
        // The event bus and the registry may answer out of order: never show a step back.
        lastProgress = Math.max(lastProgress, progress);
        onProgress(lastProgress);
    };

    const handleProgress = (error, message) => {
        if (error || !message || !message.body) {
            return;
        }
        reportProgress(message.body.progress);
    };
    const handleDone = (error, message) => {
        if (error || !message || !message.body) {
            return;
        }
        console.log('transfer ' + transferId + ': backend reported ' + (message.body.success ? 'success' : 'failure') + ' (event bus)');
        settle(message.body);
    };

    const reconcile = () => {
        if (settled || inFlight) {
            // A read still pending answers for this tick as well: do not pile requests up.
            return Promise.resolve();
        }
        inFlight = true;
        return Promise.resolve()
            .then(() => fetchTransfer(transferId))
            .then(transfer => {
                failures = 0;
                if (settled || !transfer) {
                    return;
                }
                if (transfer.done) {
                    console.log('transfer ' + transferId + ': backend reported ' + (transfer.success ? 'success' : 'failure') + ' (registry)');
                    settle(transfer);
                } else if (transfer.total > 0) {
                    reportProgress(transfer.progress);
                }
            })
            .catch(e => {
                if (settled) {
                    return;
                }
                if (e && (e.status === 404 || e.code === 'TRANSFER_NOT_FOUND')) {
                    console.warn('transfer ' + transferId + ': unknown to the backend, its outcome cannot be known');
                    settle({success: false, unknown: true});
                    return;
                }
                failures++;
                if (failures >= TRANSFER_RECONCILE_MAX_FAILURES) {
                    console.warn('transfer ' + transferId + ': state unreadable ' + failures + ' times in a row, giving up', e);
                    settle({success: false, unknown: true});
                } else {
                    // Not a verdict: try again on the next tick.
                    console.warn('transfer ' + transferId + ': could not read its state, will retry', e);
                }
            })
            .finally(() => {
                inFlight = false;
            });
    };

    channel.subscribe(progressAddress, handleProgress);
    const doneSubscription = channel.subscribe(doneAddress, handleDone);
    if (typeof channel.addStateListener === 'function') {
        removeStateListener = channel.addStateListener(state => {
            if (state === CHANNEL_OPEN) {
                reconcile();
            }
        });
    }
    interval = setInterval(reconcile, TRANSFER_RECONCILE_INTERVAL_MS);
    // The verdict may already be known: the transfer can end before the subscription is active.
    reconcile();
    return doneSubscription;
}

/** Toast progress: the bar is only meaningful below 1. */
const updateProgress = (toastId) => (progress) => {
    if (typeof progress === 'number' && progress < 1) {
        toast.update(toastId, {progress, autoClose: false});
    }
};

/** A failure toast that shows the backend's reason when it gave one. */
function failureRender(content, verdict) {
    return verdict && verdict.message
        ? <IssueReportToast content={content} error={new Error(verdict.message)} showDetails />
        : <IssueReportToast content={content} />;
}

/*
 * Packs whose transfer has been accepted and is still being followed, keyed by direction and uuid.
 * The mutex is released as soon as the backend accepts a transfer (it queues device operations
 * itself), so this is what stops the very same pack from being sent twice in a row by a double
 * drop. The backend would refuse the second one anyway (409); this only spares the round trip.
 */
const followedPacks = new Set();

export const actionAddFromLibrary = (uuid, path, format, driver, context, t) => {
    return dispatch => mutex.acquire()
        .then(
            release => {
                const key = 'device:' + uuid;
                // First, make sure the story pack is in the right format.
                if (driver !== format) {
                    console.error('pack format is not compatible with the device');
                    toast.error(t('toasts.device.notCompatible'));
                    // Always release the mutex
                    release();
                } else if (followedPacks.has(key)) {
                    console.warn('pack ' + uuid + ' is already being transferred to the device');
                    toast.error(t('toasts.device.busy'));
                    release();
                } else {
                    // Then start transfer
                    let toastId = toast(t('toasts.device.adding'), { autoClose: false });
                    return addFromLibrary(uuid, path)
                        .then(resp => {
                            // From here on the backend has accepted the transfer and is running it.
                            // Nothing that happens to the monitoring channel below changes that, so
                            // no failure reported from this point may claim the transfer failed.
                            // The backend queues device operations itself, so the mutex is released
                            // right away: other actions (a refresh on reconnection, a removal) are
                            // not refused for the whole length of the transfer. The verdict is still
                            // followed, from the event bus or from the transfer registry.
                            const transferId = resp.transferId;
                            console.log('transfer ' + transferId + ': accepted by the backend (add to device)');
                            followedPacks.add(key);

                            const doneSubscription = followTransfer(transferId, context.channel, {
                                onProgress: updateProgress(toastId),
                                onVerdict: verdict => {
                                    followedPacks.delete(key);
                                    if (verdict.success) {
                                        toast.update(toastId, {progress: null, type: toast.TYPE.SUCCESS, render: t('toasts.device.added'), autoClose: 5000});
                                    } else if (verdict.unknown) {
                                        toast.update(toastId, {progress: null, type: toast.TYPE.WARNING, render: t('toasts.device.trackingLost'), autoClose: false});
                                    } else {
                                        // The backend says why the transfer failed. Show it, as for a
                                        // refusal at request time. Without a message there is nothing to
                                        // show, and no detail block is asked for.
                                        toast.update(toastId, {progress: null, type: toast.TYPE.ERROR, render: failureRender(<>{t('toasts.device.addingFailed')}</>, verdict), autoClose: false});
                                    }
                                    // Refresh device metadata and packs list
                                    if (verdict.success || verdict.unknown) {
                                        dispatch(actionRefreshDevice(t));
                                    }
                                }
                            });

                            if (doneSubscription !== SUBSCRIBED) {
                                // The channel is down, so the outcome cannot be pushed, but the
                                // registry is polled until the verdict is known: tracking is not
                                // lost. The "adding" toast stays as it is.
                                console.warn('transfer ' + transferId + ': monitoring channel unavailable, following the transfer through its registry');
                            }
                            release();
                        })
                        .catch(e => {
                            // Only a real request failure reaches this point: subscribing can no
                            // longer throw, so a dropped channel never lands here.
                            console.error('failed to add pack to device', e);
                            // showDetails: an upload can be refused for a precise reason the driver
                            // spells out (e.g. a destination folder that was already there).
                            toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.addingFailed')}</>} error={e} showDetails />, autoClose: false });
                            // Always release the mutex
                            release();
                        });
                }
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
            });
};

export const actionRemoveFromDevice = (uuid, t) => {
    return dispatch => mutex.acquire()
        .then(
            release => {
                let toastId = toast(t('toasts.device.removing'), { autoClose: false });
                return removeFromDevice(uuid)
                    .then(resp => {
                        if (resp.success) {
                            toast.update(toastId, {type: toast.TYPE.SUCCESS, render: t('toasts.device.removed'), autoClose: 5000});
                            // Refresh device metadata and packs list
                            dispatch(actionRefreshDevice(t));
                        } else {
                            toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.removingFailed')}</>} />, autoClose: false });
                        }
                    })
                    .catch(e => {
                        console.error('failed to remove pack from device', e);
                        toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.removingFailed')}</>} error={e} showDetails />, autoClose: false });
                    })
                    .finally(() => {
                        // Always release the mutex
                        release();
                    });
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
            });
};

export const actionReorderOnDevice = (uuids, t) => {
    return (dispatch, getState) => mutex.acquire()
        .then(
            release => {
                let toastId = toast(t('toasts.device.reordering'), { autoClose: false });
                return reorderPacks(uuids)
                    .then(resp => {
                        if (resp.success) {
                            toast.update(toastId, {type: toast.TYPE.SUCCESS, render: t('toasts.device.reordered'), autoClose: 5000});
                            // Refresh device metadata and packs list
                            dispatch(actionRefreshDevice(t));
                        } else {
                            toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.reorderingFailed')}</>} />, autoClose: false });
                            // The list shown was reordered optimistically: re-read the real order
                            dispatch(actionRefreshDevice(t));
                        }
                    })
                    .catch(e => {
                        console.error('failed to reorder packs on device', e);
                        toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.reorderingFailed')}</>} error={e} showDetails />, autoClose: false });
                        // The list shown was reordered optimistically: re-read the real order
                        dispatch(actionRefreshDevice(t));
                    })
                    .finally(() => {
                        // Always release the mutex
                        release();
                    });
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
                // The list shown was reordered optimistically: put back the order the store holds
                // (a new array, so that the view picks it up again).
                const state = typeof getState === 'function' ? getState() : null;
                if (state && state.device && Array.isArray(state.device.packs)) {
                    dispatch(setDevicePacks([...state.device.packs]));
                }
            });
};

export const actionAddToLibrary = (uuid, driver, context, t) => {
    return dispatch => mutex.acquire()
        .then(
            release => {
                const key = 'library:' + uuid;
                if (followedPacks.has(key)) {
                    console.warn('pack ' + uuid + ' is already being extracted to the library');
                    toast.error(t('toasts.device.busy'));
                    release();
                    return;
                }
                let toastId = toast(t('toasts.library.adding'), { autoClose: false });
                return addToLibrary(uuid, driver)
                    .then(resp => {
                        // Same contract as actionAddFromLibrary: past this point the extraction is
                        // running on the backend and the channel's health says nothing about it.
                        // The backend queues it, so the mutex is released right away.
                        const transferId = resp.transferId;
                        console.log('transfer ' + transferId + ': accepted by the backend (add to library)');
                        followedPacks.add(key);

                        const doneSubscription = followTransfer(transferId, context.channel, {
                            onProgress: updateProgress(toastId),
                            onVerdict: verdict => {
                                followedPacks.delete(key);
                                if (verdict.success) {
                                    toast.update(toastId, {progress: null, type: toast.TYPE.SUCCESS, render: t('toasts.library.added'), autoClose: 5000});
                                } else if (verdict.unknown) {
                                    toast.update(toastId, {progress: null, type: toast.TYPE.WARNING, render: t('toasts.library.trackingLost'), autoClose: false});
                                } else {
                                    toast.update(toastId, {progress: null, type: toast.TYPE.ERROR, render: failureRender(<>{t('toasts.library.addingFailed')}</>, verdict), autoClose: false });
                                }
                                if (verdict.success || verdict.unknown) {
                                    // Refresh library metadata and packs list
                                    dispatch(actionRefreshLibrary(t));
                                }
                            }
                        });

                        if (doneSubscription !== SUBSCRIBED) {
                            // Followed through the registry: the "adding" toast stays as it is.
                            console.warn('transfer ' + transferId + ': monitoring channel unavailable, following the extraction through its registry');
                        }
                        release();
                    })
                    .catch(e => {
                        console.error('failed to add pack to library', e);
                        toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.addingFailed')}</>} error={e} showDetails />, autoClose: false });
                        // Always release the mutex
                        release();
                    });
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
            });
};

export const actionRefreshLibrary = (t) => {
    return dispatch => {
        return fetchLibraryInfos()
            .then(metadata => {
                return fetchLibraryPacks()
                    .then(packs => {
                        dispatch(setLibrary(metadata, packs));
                    });
            })
            .catch(e => {
                console.error('failed to refresh library', e);
                toast(<IssueReportToast content={<>{t('toasts.library.refreshingFailed')}</>} error={e} />, { type: toast.TYPE.ERROR, autoClose: false });
            });
    }
};

export const actionDownloadFromLibrary = (uuid, path, t) => {
    return dispatch => {
        let toastId = toast(t('toasts.library.downloading'), { autoClose: false });
        return downloadFromLibrary(uuid, path)
            .then(async resp => {
                if (!resp.ok) {
                    const text = await resp.text().catch(() => '');
                    const error = new Error(resp.statusText + ': ' + errorMessage(text));
                    error.status = resp.status;
                    throw error;
                }
                const reader = resp.body.getReader();
                const contentLength = +resp.headers.get('Content-Length') || 0;
                // Read chunks and monitor progress
                let receivedLength = 0;
                let chunks = [];
                while (true) {
                    const {done, value} = await reader.read();
                    if (done) {
                        break;
                    }
                    chunks.push(value);
                    receivedLength += value.length;
                    if (contentLength > 0) {
                        // Without a length there is no ratio to show (NaN or Infinity otherwise)
                        let progress = Math.min(receivedLength / contentLength, 0.99);
                        toast.update(toastId, {progress: progress, autoClose: false});
                    }
                }
                let blob = new Blob(chunks);
                toast.update(toastId, {
                    progress: null,
                    type: toast.TYPE.SUCCESS,
                    render: t('toasts.library.downloaded'),
                    autoClose: 5000
                });
                return blob;
            })
            .catch(e => {
                console.error('failed to download pack from library', e);
                toast.update(toastId, { progress: null, type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.downloadingFailed')}</>} error={e} showDetails />, autoClose: false });
            });
    }
};

export const actionLoadPackInEditor = (packData, filename, t) => {
    return dispatch => {
        let toastId = toast(t('toasts.editor.loading'), { autoClose: false });
        readFromArchive(packData)
            .then(loadedModel => {
                toast.update(toastId, { type: toast.TYPE.SUCCESS, render: t('toasts.editor.loaded'), autoClose: 5000});
                // Set loaded model in editor
                dispatch(setEditorDiagram(loadedModel, filename));
                // Show editor
                dispatch(showEditor());
            })
            .catch(e => {
                console.error('failed to load story pack', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.editor.loadingFailed')}</>} error={e} />, autoClose: false });
            });
    }
};

// A library pack is read entry by entry on the server: no download of the whole archive, no unzipping in the browser
export const actionLoadLibraryPackInEditor = (path, t) => {
    return (dispatch, getState) => {
        let toastId = toast(t('toasts.editor.loading'), { autoClose: false });
        // The pack about to be replaced lets go of its assets first, to leave room for the new one
        let releasedModel = releaseLibraryAssets();
        return readFromLibrary(entry => fetchLibraryPackEntry(path, entry))
            .then(loadedModel => {
                toast.update(toastId, { type: toast.TYPE.SUCCESS, render: t('toasts.editor.loaded'), autoClose: 5000});
                // Set loaded model in editor
                dispatch(setEditorDiagram(loadedModel, path));
                // Show editor
                dispatch(showEditor());
            })
            .catch(e => {
                console.error('failed to load story pack from library', e);
                if (releasedModel && getState().editor.diagram === releasedModel) {
                    // Its assets are gone: an empty editor rather than a pack that can neither play nor save them
                    dispatch(setEditorDiagram(new PackDiagramModel()));
                }
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.editor.loadingFailed')}</>} error={e} />, autoClose: false });
            });
    }
};

export const actionCreatePackInEditor = (t) => {
    return dispatch => {
        // Set empty model in editor
        let model = new PackDiagramModel();
        dispatch(setEditorDiagram(model));
        // Show editor
        dispatch(showEditor());
    }
};

export const actionLoadSampleInEditor = (t) => {
    return dispatch => {
        // Set sample model in editor
        let model = simplifiedSample();
        dispatch(setEditorDiagram(model, generateFilename(model)));
        // Show editor
        dispatch(showEditor());
    }
};

export const actionUploadToLibrary = (uuid, path, packData, t) => {
    return dispatch => {
        let toastId = toast(t('toasts.library.uploading'), { autoClose: false });
        return uploadToLibrary(uuid, path, packData,
            progressEvent => {
                if (progressEvent.lengthComputable) {
                    let progress = (progressEvent.loaded / progressEvent.total);
                    console.log(`Uploaded ${progressEvent.loaded} of ${progressEvent.total}: ${progress}`);
                    toast.update(toastId, {progress: progress, autoClose: false});
                }
            })
            .then(resp => {
                if (resp.success) {
                    toast.update(toastId, {
                        progress: null,
                        type: toast.TYPE.SUCCESS,
                        render: t('toasts.library.uploaded'),
                        autoClose: 5000
                    });
                    // Refresh device metadata and packs list
                    dispatch(actionRefreshLibrary(t));
                } else {
                    toast.update(toastId, { progress: null, type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.uploadingFailed')}</>} />, autoClose: false });
                }
            })
            .catch(e => {
                console.error('failed to upload pack to library', e);
                toast.update(toastId, { progress: null, type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.uploadingFailed')}</>} error={e} showDetails />, autoClose: false });
            });
    }
};

/**
 * Several pack files into the library, one after the other. One toast follows the whole batch and
 * the library is refreshed once, at the end. Sequential on purpose: a pack can weigh a gigabyte,
 * and the server writes each upload in full before answering it.
 *
 * Resolves with {total, failed}, failed being the names of the files that were not imported.
 */
export const actionUploadManyToLibrary = (files, t) => {
    return async dispatch => {
        const total = files.length;
        const failed = [];
        const label = (i) => t('toasts.library.uploadingMany', {current: i + 1, total, name: files[i].name});
        const toastId = toast(label(0), { autoClose: false });
        for (let i = 0; i < total; i++) {
            const file = files[i];
            toast.update(toastId, { render: label(i), progress: 0, autoClose: false });
            try {
                const resp = await uploadToLibrary(null, file.name, file, progressEvent => {
                    if (progressEvent.lengthComputable) {
                        // Capped below 1: a controlled progress bar that reaches 1 closes its toast,
                        // and this one has to survive until the last file.
                        const progress = Math.min(progressEvent.loaded / progressEvent.total, 0.99);
                        toast.update(toastId, { progress, autoClose: false });
                    }
                });
                if (!resp || !resp.success) {
                    failed.push(file.name);
                }
            } catch (e) {
                console.error('failed to upload pack to library: ' + file.name, e);
                failed.push(file.name);
            }
        }
        dispatch(actionRefreshLibrary(t));
        if (failed.length === 0) {
            toast.update(toastId, {
                progress: null,
                type: toast.TYPE.SUCCESS,
                render: t('toasts.library.uploadedMany', {count: total}),
                autoClose: 5000
            });
        } else {
            toast.update(toastId, {
                progress: null,
                type: toast.TYPE.ERROR,
                render: t('toasts.library.uploadedManyPartial', {ok: total - failed.length, total, failed: failed.join(', ')}),
                autoClose: false
            });
        }
        return {total, failed};
    }
};

export const actionConvertInLibrary = (uuid, path, format, allowEnriched, context, t) => {
    return dispatch => {
        let toastId = toast(t('toasts.library.converting'), { autoClose: false });
        return convertInLibrary(uuid, path, format, allowEnriched)
            .then(resp => {
                if (resp.success) {
                    console.log("Story pack converted. Path is: " + resp.path);
                    toast.update(toastId, {
                        progress: null,
                        type: toast.TYPE.SUCCESS,
                        render: t('toasts.library.converted'),
                        autoClose: 5000
                    });
                    // Refresh device metadata and packs list
                    dispatch(actionRefreshLibrary(t));
                    return resp.path;
                } else {
                    toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.convertingFailed')}</>} />, autoClose: false });
                }
            })
            .catch(e => {
                console.error('failed to convert pack in library', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.convertingFailed')}</>} error={e} />, autoClose: false });
            });
    }
};

export const actionRemoveFromLibrary = (path, t) => {
    return dispatch => {
        let toastId = toast(t('toasts.library.removing'), { autoClose: false });
        return removeFromLibrary(path)
            .then(resp => {
                if (resp.success) {
                    toast.update(toastId, {type: toast.TYPE.SUCCESS, render: t('toasts.library.removed'), autoClose: 5000});
                    // Refresh library metadata and packs list
                    dispatch(actionRefreshLibrary(t));
                } else {
                    toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.removingFailed')}</>} />, autoClose: false });
                }
            })
            .catch(e => {
                console.error('failed to remove pack from library', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.removingFailed')}</>} error={e} showDetails />, autoClose: false });
            });
    }
};

export const actionLoadEvergreen = (announceOptOut, t) => {
    return dispatch => {
        let toastId = toast(t('toasts.evergreen.loading'), { autoClose: false });
        return fetchEvergreenInfos()
            .then(infos => {
                dispatch(setApplicationVersion(infos.version));
                console.log("fetching latest release...");
                toast.update(toastId,{ render: t('toasts.evergreen.fetching') });
                return fetchEvergreenLatestRelease()
                    .then(latest => {
                        if (latest.name !== infos.version && Date.parse(latest.published_at) > Date.parse(infos.timestamp)) {
                            toast.update(toastId, { type: toast.TYPE.SUCCESS, render: <><p>{t('toasts.evergreen.fetched.newRelease.label')}</p><p><a href={latest.html_url}>{t('toasts.evergreen.fetched.newRelease.link', { version: latest.name })}</a></p></> });
                        } else {
                            toast.update(toastId, { type: toast.TYPE.INFO, render: t('toasts.evergreen.fetched.upToDate', { version: infos.version }), autoClose: 5000 });
                        }
                        // Check if the user opted-out
                        if (announceOptOut) {
                            console.log("user opted-out of announces. no need to fetch.");
                        } else {
                            console.log("fetching announce...");
                            return fetchEvergreenAnnounce()
                                .then(announce => {
                                    dispatch(setAnnounce(announce));
                                })
                                .catch(e => {
                                    console.error('failed to fetch announce', e);
                                });
                        }
                    })
                    .catch(e => {
                        console.error('failed to fetch latest release', e);
                        toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.evergreen.fetchingFailed')}</>} error={e} />, autoClose: false });
                    });
            })
            .catch(e => {
                console.error('failed to fetch current version', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.evergreen.loadingFailed')}</>} error={e} />, autoClose: false });
            });
    }
};

export const devicePlugged = (metadata) => ({
    type: 'DEVICE_PLUGGED',
    metadata
});

export const deviceUnplugged = () => ({
    type: 'DEVICE_UNPLUGGED'
});

export const setDevicePacks = (packs) => ({
    type: 'SET_DEVICE_PACKS',
    packs: packs
});

export const showViewer = () => ({
    type: 'SHOW_VIEWER'
});

export const hideViewer = () => ({
    type: 'HIDE_VIEWER'
});

export const setViewerDiagram = (diagram) => ({
    type: 'SET_VIEWER_DIAGRAM',
    diagram
});

export const setViewerStage = (stage) => ({
    type: 'SET_VIEWER_STAGE',
    stage
});

export const setViewerAction = (action) => ({
    type: 'SET_VIEWER_ACTION',
    action
});

export const setViewerOptions = (options) => ({
    type: 'SET_VIEWER_OPTIONS',
    options
});

export const setEditorDiagram = (diagram, filename = null) => ({
    type: 'SET_EDITOR_DIAGRAM',
    diagram,
    filename
});

export const setEditorFilename = (filename = null) => ({
    type: 'SET_EDITOR_FILENAME',
    filename
});

export const setDiagramErrors= (errors) => ({
    type: 'SET_DIAGRAM_ERRORS',
    errors
});

export const showLibrary = () => ({
    type: 'SHOW_LIBRARY'
});

export const showEditor = () => ({
    type: 'SHOW_EDITOR'
});

export const setApplicationVersion = (version) => ({
    type: 'SET_APPLICATION_VERSION',
    version
});

export const setAnnounce = (announce) => ({
    type: 'SET_ANNOUNCE',
    announce
});

export const setAnnounceOptOut = (announceOptOut) => ({
    type: 'SET_ANNOUNCE_OPTOUT',
    announceOptOut
});

export const setAllowEnriched = (allowEnriched) => ({
    type: 'SET_ALLOW_ENRICHED',
    allowEnriched
});
