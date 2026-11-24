/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import {useTranslation} from 'react-i18next';
import {toast} from 'react-toastify';

import {askTitleText, renderTitleImage, speakTitle} from "../../../../utils/titleAssets";
import './TitleGenerator.css';

/**
 * A small button next to an asset: "generate the voice" (kind="audio") or "generate the image"
 * (kind="image") from the node title. The text is asked first, prefilled with the title.
 * onGenerated(dataUrl) receives the asset, in a format the editor already accepts.
 */
const TitleGenerator = ({kind, text, onGenerated}) => {
    const {t} = useTranslation();
    const [busy, setBusy] = React.useState(false);
    const audio = kind === 'audio';

    const stop = (e) => {
        // Neither select nor drag the node, nor open the file selector of the asset
        e.stopPropagation();
    };

    const onClick = async (e) => {
        e.stopPropagation();
        e.preventDefault();
        if (busy) {
            return;
        }
        const spoken = askTitleText(t(audio ? 'editor.diagram.titleGenerator.askAudio' : 'editor.diagram.titleGenerator.askImage'), text);
        if (!spoken) {
            return;
        }
        if (!audio) {
            onGenerated(renderTitleImage(spoken));
            return;
        }
        setBusy(true);
        try {
            const speech = await speakTitle(spoken);
            onGenerated(speech.dataUrl);
            if (speech.fallbackReason) {
                toast.warning(t('toasts.editor.titleVoiceFallback', {reason: speech.fallbackReason}));
            }
        } catch (err) {
            console.error('failed to generate the title voice', err);
            toast.error(t('toasts.editor.titleVoiceFailed', {message: err.message}));
        } finally {
            setBusy(false);
        }
    };

    return <span className={`title-generator ${busy ? 'busy' : ''}`}
                 title={t(audio ? 'editor.diagram.titleGenerator.audio' : 'editor.diagram.titleGenerator.image')}
                 onMouseDown={stop}
                 onDoubleClick={stop}
                 onClick={onClick}>
        <span className={`glyphicon ${busy ? 'glyphicon-refresh' : audio ? 'glyphicon-bullhorn' : 'glyphicon-text-background'}`}/>
    </span>;
};

export default TitleGenerator;
