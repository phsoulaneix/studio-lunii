/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import {useTranslation} from 'react-i18next';

import {TitleAssetDialog} from "../../TitleDialogs";
import './TitleGenerator.css';

/**
 * A small button next to an asset: "generate the voice" (kind="audio") or "generate the image"
 * (kind="image") from the node title. It opens a dialog where the text can be changed and the
 * result heard or seen before it is attached. onGenerated(dataUrl) receives the asset, in a format
 * the editor already accepts. engine is the diagram engine, locked while the dialog is open.
 */
const TitleGenerator = ({kind, text, onGenerated, engine}) => {
    const {t} = useTranslation();
    const [open, setOpen] = React.useState(false);
    const audio = kind === 'audio';

    const stop = (e) => {
        // Neither select nor drag the node, nor open the file selector of the asset
        e.stopPropagation();
    };
    const onClick = (e) => {
        e.stopPropagation();
        e.preventDefault();
        setOpen(true);
    };
    const close = React.useCallback(() => setOpen(false), []);

    return <>
        <span className="title-generator"
              title={t(audio ? 'editor.titleGenerator.audio' : 'editor.titleGenerator.image')}
              onMouseDown={stop}
              onDoubleClick={stop}
              onClick={onClick}>
            <span className={`glyphicon ${audio ? 'glyphicon-bullhorn' : 'glyphicon-text-background'}`}/>
        </span>
        {open && <TitleAssetDialog kind={kind} title={text} onGenerated={onGenerated} onClose={close}
                                   model={engine ? engine.getModel() : null}/>}
    </>;
};

export default TitleGenerator;
