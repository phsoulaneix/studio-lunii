/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import {useTranslation} from 'react-i18next';
import {toast} from 'react-toastify';

import {
    collectMissingTitleAudio,
    generateMissingTitleAudio,
    getTitleVoice,
    TITLE_VOICES
} from "../../utils/titleAssets";
import {TitleVoiceSettingsDialog} from "./TitleDialogs";
import "./widgets/composites/TitleGenerator.css";

/**
 * Editor toolbar: the voice that reads the titles (a button showing it, which opens the settings:
 * voice, pronunciations, a sentence to listen to) and "generate every missing title voice".
 * getModel() returns the open diagram; onChanged() repaints it.
 */
const TitleVoiceControls = ({getModel, onChanged}) => {
    const {t} = useTranslation();
    const [voice, setVoice] = React.useState(getTitleVoice());
    const [settingsOpen, setSettingsOpen] = React.useState(false);
    const [busy, setBusy] = React.useState(false);

    const closeSettings = React.useCallback(() => {
        setSettingsOpen(false);
        setVoice(getTitleVoice());
    }, []);

    const generateAll = async () => {
        if (busy) {
            return;
        }
        const model = getModel();
        const missing = collectMissingTitleAudio(model.getNodes());
        if (missing.length === 0) {
            toast.info(t('toasts.editor.titleVoiceNothingMissing'));
            return;
        }
        setBusy(true);
        const toastId = toast(t('toasts.editor.titleVoiceGenerating', {done: 0, total: missing.length}), {autoClose: false});
        try {
            await generateMissingTitleAudio(missing, {
                isCurrent: () => getModel() === model,
                onProgress: (done, total) => {
                    toast.update(toastId, {render: t('toasts.editor.titleVoiceGenerating', {done, total})});
                    onChanged();
                }
            });
            toast.update(toastId, {type: toast.TYPE.SUCCESS, render: t('toasts.editor.titleVoiceGenerated', {count: missing.length}), autoClose: 5000});
        } catch (e) {
            console.error('failed to generate the missing title voices', e);
            toast.update(toastId, {type: toast.TYPE.ERROR, render: t('toasts.editor.titleVoiceStopped', {done: e.generated || 0, total: missing.length, message: e.message}), autoClose: false});
        } finally {
            onChanged();
            setBusy(false);
        }
    };

    const known = TITLE_VOICES.find(v => v.value === voice);
    const voiceLabel = voice === 'system' ? t('editor.titleVoice.systemShort')
        : voice === 'auto' ? t('editor.titleVoice.auto')
        : known ? t('editor.titleVoice.voices.' + known.key).replace(/ \(.*\)$/, '') : voice;
    return <>
        <span title={t('editor.titleVoice.settingsTitle')}
              className="btn btn-default title-voice-button"
              onClick={() => setSettingsOpen(true)}>
            <span className="glyphicon glyphicon-volume-up"/> {voiceLabel}
        </span>
        <span title={t('editor.actions.generateTitleVoices')}
              className={`btn btn-default glyphicon ${busy ? 'glyphicon-refresh title-spin' : 'glyphicon-bullhorn'}`}
              onClick={generateAll}/>
        {settingsOpen && <TitleVoiceSettingsDialog model={getModel()} onClose={closeSettings}/>}
    </>;
};

export default TitleVoiceControls;
