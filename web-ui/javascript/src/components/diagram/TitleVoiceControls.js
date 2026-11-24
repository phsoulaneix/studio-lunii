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
    fetchVoiceEngines,
    generateMissingTitleAudio,
    getTitleVoice,
    setTitleVoice
} from "../../utils/titleAssets";
import "./widgets/composites/TitleGenerator.css";

/**
 * Editor toolbar: the voice used for generated titles, and "generate every missing title voice".
 * getModel() returns the open diagram; onChanged() repaints it.
 */
const TitleVoiceControls = ({getModel, onChanged}) => {
    const {t} = useTranslation();
    const [voice, setVoice] = React.useState(getTitleVoice());
    const [engines, setEngines] = React.useState(null);
    const [busy, setBusy] = React.useState(false);

    React.useEffect(() => {
        let alive = true;
        fetchVoiceEngines()
            .then(e => alive && setEngines(e))
            .catch(e => console.warn('title voice engines unknown', e));
        return () => { alive = false; };
    }, []);

    const onVoiceChange = (e) => {
        setVoice(e.target.value);
        setTitleVoice(e.target.value);
    };

    const generateAll = async () => {
        if (busy) {
            return;
        }
        const missing = collectMissingTitleAudio(getModel().getNodes());
        if (missing.length === 0) {
            toast.info(t('toasts.editor.titleVoiceNothingMissing'));
            return;
        }
        setBusy(true);
        const toastId = toast(t('toasts.editor.titleVoiceGenerating', {done: 0, total: missing.length}), {autoClose: false});
        try {
            await generateMissingTitleAudio(missing, {
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

    const voices = (engines && engines.voices) || [];
    const edgeMissing = engines && !engines.edge;
    return <>
        <select className="title-voice-select"
                value={voice}
                onChange={onVoiceChange}
                title={t('editor.titleVoice.choose')}>
            <option value="auto">{t('editor.titleVoice.auto')}{edgeMissing ? ' ' + t('editor.titleVoice.unavailable') : ''}</option>
            <option value="system">{t('editor.titleVoice.system')}{engines && !engines.system ? ' ' + t('editor.titleVoice.unavailable') : ''}</option>
            {voices.map(v => <option key={v} value={v}>{v.replace(/Neural$/, '').replace(/Multilingual$/, ' (multilingue)')}</option>)}
            {voice !== 'auto' && voice !== 'system' && voices.indexOf(voice) === -1 && <option value={voice}>{voice}</option>}
        </select>
        <span title={t('editor.actions.generateTitleVoices')}
              className={`btn btn-default glyphicon ${busy ? 'glyphicon-refresh' : 'glyphicon-bullhorn'}`}
              onClick={generateAll}/>
    </>;
};

export default TitleVoiceControls;
