/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Dialogs of the title generator: generate one title's voice or image, and the voice settings.
// Same settings as Lunii Pack: voice, pronunciations (written = spoken), a sentence to listen to.

import React from 'react';
import ReactDOM from 'react-dom';
import {useTranslation} from 'react-i18next';
import {toast} from 'react-toastify';

import {
    fetchVoiceEngines,
    getTitlePronunciation,
    getTitleVoice,
    renderTitleImage,
    setTitlePronunciation,
    setTitleVoice,
    speakTitle,
    TITLE_MAX_LENGTH,
    TITLE_VOICES,
    VOICE_SAMPLE_TEXT
} from "../../utils/titleAssets";
import '../Modal.css';
import './widgets/composites/TitleGenerator.css';

const stop = (e) => e.stopPropagation();

/**
 * A dialog drawn on top of the page (a portal: the diagram is zoomed and panned with CSS
 * transforms, which would carry a dialog rendered inside a node). While it is open the diagram is
 * locked, so that typing Delete or Backspace in a field does not delete the selected nodes, and its
 * mouse and key events stop here instead of dragging the node it was opened from.
 */
export const TitleDialogFrame = ({id, title, model, onClose, footer, children}) => {
    React.useEffect(() => {
        if (!model) {
            return undefined;
        }
        const wasLocked = model.isLocked();
        model.setLocked(true);
        return () => model.setLocked(wasLocked);
    }, [model]);

    React.useEffect(() => {
        const onKey = (e) => {
            if (e.key === 'Escape') {
                onClose();
            }
        };
        document.addEventListener('keydown', onKey);
        return () => document.removeEventListener('keydown', onKey);
    }, [onClose]);

    return ReactDOM.createPortal(
        <div className="modal show title-dialog" id={`modal-${id}`} tabIndex="-1" role="dialog"
             onMouseDown={stop} onMouseUp={stop} onMouseMove={stop} onClick={stop} onDoubleClick={stop}
             onWheel={stop} onKeyDown={stop} onKeyUp={stop} onDragOver={stop} onDrop={stop}>
            <div className="modal-dialog modal-dialog-centered" role="document">
                <div className="modal-content">
                    <div className="modal-header">
                        <button type="button" className="close" aria-label="Close" onClick={onClose}>
                            <span aria-hidden="true">&times;</span>
                        </button>
                        <h4 className="modal-title">{title}</h4>
                    </div>
                    <div className="modal-body">{children}</div>
                    <div className="modal-footer">{footer}</div>
                </div>
            </div>
        </div>,
        document.body);
};

/** Fetches once which engines the server can use; null until known. */
export function useVoiceEngines() {
    const [engines, setEngines] = React.useState(null);
    React.useEffect(() => {
        let alive = true;
        fetchVoiceEngines()
            .then(e => alive && setEngines(e))
            .catch(e => console.warn('title voice engines unknown', e));
        return () => { alive = false; };
    }, []);
    return engines;
}

/** Voice and pronunciations, saved in this browser as soon as they change (shared by every dialog). */
export function useVoiceSettings() {
    const [voice, setVoiceState] = React.useState(getTitleVoice());
    const [pronunciation, setPronunciationState] = React.useState(getTitlePronunciation());
    const setVoice = (v) => {
        setVoiceState(v);
        setTitleVoice(v);
    };
    const setPronunciation = (p) => {
        setPronunciationState(p);
        setTitlePronunciation(p);
    };
    return {voice, setVoice, pronunciation, setPronunciation};
}

const VoiceSettingsFields = ({settings, engines, idPrefix}) => {
    const {t} = useTranslation();
    const unavailable = ' ' + t('editor.titleVoice.unavailable');
    const known = ['system', 'auto'].indexOf(settings.voice) !== -1 || TITLE_VOICES.some(v => v.value === settings.voice);
    return <>
        <div className="form-group">
            <label htmlFor={`${idPrefix}-voice`}>{t('editor.titleVoice.choose')}</label>
            <select id={`${idPrefix}-voice`} className="form-control" value={settings.voice} onChange={e => settings.setVoice(e.target.value)}>
                <optgroup label={t('editor.titleVoice.online') + (engines && !engines.edge ? unavailable : '')}>
                    {TITLE_VOICES.map(v => <option key={v.value} value={v.value}>{t('editor.titleVoice.voices.' + v.key)}</option>)}
                </optgroup>
                <optgroup label={t('editor.titleVoice.offline')}>
                    <option value="system">{t('editor.titleVoice.system')}{engines && !engines.system ? unavailable : ''}</option>
                </optgroup>
                {settings.voice === 'auto' && <option value="auto">{t('editor.titleVoice.auto')}</option>}
                {!known && <option value={settings.voice}>{settings.voice}</option>}
            </select>
            <p className="help-block">
                {settings.voice === 'system' ? t('editor.titleVoice.offlineHelp') : t('editor.titleVoice.onlineHelp')}
                {engines && !engines.edge && settings.voice !== 'system' && <> <strong>{t('editor.titleVoice.onlineMissing')}</strong></>}
            </p>
        </div>
        <div className="form-group">
            <label htmlFor={`${idPrefix}-pronunciation`}>{t('editor.titleVoice.pronunciation')}</label>
            <input id={`${idPrefix}-pronunciation`} type="text" className="form-control"
                   placeholder={t('editor.titleVoice.pronunciationPlaceholder')}
                   value={settings.pronunciation} onChange={e => settings.setPronunciation(e.target.value)}/>
        </div>
    </>;
};

/** Speaks text with the settings and keeps the result to play or attach. */
function useSpeech(settings) {
    const {t} = useTranslation();
    const [state, setState] = React.useState({busy: false, speech: null, key: null});
    const speak = async (text) => {
        const key = `${settings.voice}|${settings.pronunciation}|${text}`;
        if (state.speech && state.key === key) {
            return state.speech;
        }
        setState({busy: true, speech: null, key: null});
        try {
            const speech = await speakTitle(text, settings.voice, settings.pronunciation);
            if (speech.fallbackReason) {
                toast.warning(t('toasts.editor.titleVoiceFallback', {reason: speech.fallbackReason}));
            }
            setState({busy: false, speech, key});
            return speech;
        } catch (e) {
            console.error('failed to generate the title voice', e);
            toast.error(t('toasts.editor.titleVoiceFailed', {message: e.message}));
            setState({busy: false, speech: null, key: null});
            return null;
        }
    };
    const current = (text) => state.key === `${settings.voice}|${settings.pronunciation}|${text}` ? state.speech : null;
    return {busy: state.busy, speak, current};
}

const engineLabel = (t, speech) => {
    if (speech.engine !== 'edge') {
        return t('editor.titleVoice.generatedOffline');
    }
    const known = TITLE_VOICES.find(v => v.value === speech.voice);
    return t('editor.titleVoice.generatedOnline', {voice: known ? t('editor.titleVoice.voices.' + known.key) : speech.voice});
};

/**
 * Generates the voice (kind="audio") or the image (kind="image") of one title. The text starts as
 * the node title and can be changed; nothing is attached until "Attach" is clicked.
 */
export const TitleAssetDialog = ({kind, title, model, onClose, onGenerated}) => {
    const {t} = useTranslation();
    const audio = kind === 'audio';
    const [text, setText] = React.useState((title || '').substring(0, TITLE_MAX_LENGTH));
    const settings = useVoiceSettings();
    const engines = useVoiceEngines();
    const speech = useSpeech(settings);
    const player = React.useRef(null);
    const trimmed = text.trim();
    const preview = React.useMemo(() => (!audio && trimmed ? renderTitleImage(trimmed) : null), [audio, trimmed]);
    const ready = audio ? speech.current(trimmed) : null;

    const listen = async () => {
        if (await speech.speak(trimmed) && player.current) {
            // The player shows the new source on the next render
            setTimeout(() => player.current && player.current.play().catch(() => {}), 0);
        }
    };
    const attach = async () => {
        if (!trimmed) {
            return;
        }
        if (!audio) {
            onGenerated(preview);
            onClose();
            return;
        }
        const result = await speech.speak(trimmed);
        if (result) {
            onGenerated(result.dataUrl);
            onClose();
        }
    };

    const footer = <>
        <button type="button" className="btn btn-default" onClick={onClose}>{t('dialogs.shared.cancel')}</button>
        {audio && <button type="button" className="btn btn-default" disabled={!trimmed || speech.busy} onClick={listen}>
            <span className={`glyphicon ${speech.busy ? 'glyphicon-refresh title-spin' : 'glyphicon-play'}`}/> {t('editor.titleGenerator.listen')}
        </button>}
        <button type="button" className="btn btn-primary" disabled={!trimmed || speech.busy} onClick={attach}>
            {t('editor.titleGenerator.attach')}
        </button>
    </>;

    return <TitleDialogFrame id={`title-${kind}`} model={model} onClose={onClose} footer={footer}
                             title={t(audio ? 'editor.titleGenerator.audioTitle' : 'editor.titleGenerator.imageTitle')}>
        <div className="form-group">
            <label htmlFor="title-dialog-text">{t(audio ? 'editor.titleGenerator.askAudio' : 'editor.titleGenerator.askImage')}</label>
            <textarea id="title-dialog-text" className="form-control" rows={2} autoFocus maxLength={TITLE_MAX_LENGTH}
                      value={text} onChange={e => setText(e.target.value)}
                      onKeyDown={e => {
                          if (e.key === 'Enter' && !e.shiftKey) {
                              e.preventDefault();
                              attach();
                          }
                      }}/>
            <p className="help-block title-dialog-count">{text.length} / {TITLE_MAX_LENGTH}</p>
        </div>
        {audio && <VoiceSettingsFields settings={settings} engines={engines} idPrefix="title-dialog"/>}
        {audio && ready && <div className="title-dialog-result">
            <audio ref={player} controls src={ready.dataUrl}/>
            <span className="help-block">{engineLabel(t, ready)}</span>
        </div>}
        {audio && !ready && <audio ref={player} style={{display: 'none'}}/>}
        {!audio && <div className="title-dialog-image">
            {preview ? <img src={preview} alt="" width={320} height={240}/> : <div className="title-dialog-empty">{t('editor.titleGenerator.emptyText')}</div>}
        </div>}
    </TitleDialogFrame>;
};

/** Toolbar dialog: voice, pronunciations, a sentence to listen to, and what is available. */
export const TitleVoiceSettingsDialog = ({model, onClose}) => {
    const {t} = useTranslation();
    const settings = useVoiceSettings();
    const engines = useVoiceEngines();
    const speech = useSpeech(settings);
    const [sample, setSample] = React.useState(VOICE_SAMPLE_TEXT);
    const player = React.useRef(null);
    const ready = speech.current(sample.trim());

    const listen = async () => {
        if (sample.trim() && await speech.speak(sample.trim())) {
            setTimeout(() => player.current && player.current.play().catch(() => {}), 0);
        }
    };
    const status = (ok) => ok == null ? '…' : ok ? t('editor.titleVoice.available') : t('editor.titleVoice.notInstalled');

    return <TitleDialogFrame id="title-voice-settings" model={model} onClose={onClose}
                             title={t('editor.titleVoice.settingsTitle')}
                             footer={<button type="button" className="btn btn-primary" onClick={onClose}>{t('dialogs.shared.ok')}</button>}>
        <VoiceSettingsFields settings={settings} engines={engines} idPrefix="title-settings"/>
        <div className="form-group">
            <label htmlFor="title-settings-sample">{t('editor.titleVoice.listenPrompt')}</label>
            <div className="input-group">
                <input id="title-settings-sample" type="text" className="form-control" value={sample}
                       maxLength={TITLE_MAX_LENGTH}
                       onChange={e => setSample(e.target.value)}
                       onKeyDown={e => e.key === 'Enter' && listen()}/>
                <span className="input-group-btn">
                    <button type="button" className="btn btn-default" disabled={!sample.trim() || speech.busy} onClick={listen}>
                        <span className={`glyphicon ${speech.busy ? 'glyphicon-refresh title-spin' : 'glyphicon-play'}`}/> {t('editor.titleGenerator.listen')}
                    </button>
                </span>
            </div>
        </div>
        {ready && <div className="title-dialog-result">
            <audio ref={player} controls src={ready.dataUrl}/>
            <span className="help-block">{engineLabel(t, ready)}</span>
        </div>}
        {!ready && <audio ref={player} style={{display: 'none'}}/>}
        <p className="help-block title-dialog-engines">
            {t('editor.titleVoice.online')} : {status(engines && engines.edge)}
            {' · '}
            {t('editor.titleVoice.offline')} : {status(engines && engines.system)}
        </p>
    </TitleDialogFrame>;
};
