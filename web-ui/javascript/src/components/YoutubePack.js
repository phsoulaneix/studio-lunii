/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import { connect } from 'react-redux';
import {withTranslation} from "react-i18next";
import {toast} from "react-toastify";

import {actionRefreshLibrary} from "../actions";
import {fetchYoutubeTools, startYoutubePack, fetchYoutubeJob, fetchCurrentYoutubeJob, cancelYoutubePack} from "../services/youtube";
import {BITRATES, defaultForm, formProblem, buildRequest, isRunning, progressPercent, lastLines} from "../utils/youtube";
import Modal from "./Modal";

import './YoutubePack.css';

const POLL_MILLIS = 1000;

/*
 * The "from YouTube" button of the local library, and its dialog: links and options, then the
 * progress of the creation, which runs in the backend. Closing the dialog does not stop it: the
 * button keeps following it, refreshes the library at the end, and says how it went.
 */
class YoutubePack extends React.Component {

    constructor(props) {
        super(props);
        this.state = {
            open: false,
            tools: null,
            toolsError: null,
            checking: false,
            form: defaultForm(null),
            // The fields the user has changed: a new check of the tools keeps them
            touched: {},
            starting: false,
            startError: null,
            job: null
        };
        this.timer = null;
    }

    componentDidMount() {
        // A creation started before the page was (re)loaded is followed again
        fetchCurrentYoutubeJob()
            .then(job => {
                if (isRunning(job)) {
                    this.setState({job}, this.follow);
                }
            })
            .catch(() => { /* no creation, or no backend support: the button works anyway */ });
    }

    componentWillUnmount() {
        this.stopFollowing();
    }

    follow = () => {
        this.stopFollowing();
        this.timer = setTimeout(this.poll, POLL_MILLIS);
    };

    stopFollowing = () => {
        if (this.timer) {
            clearTimeout(this.timer);
            this.timer = null;
        }
    };

    poll = () => {
        this.timer = null;
        const current = this.state.job;
        if (!current) {
            return;
        }
        fetchYoutubeJob(current.id)
            .then(job => {
                this.setState({job});
                if (isRunning(job)) {
                    this.follow();
                } else {
                    this.finished(job);
                }
            })
            .catch(e => {
                console.error('failed to follow the YouTube pack creation', e);
                // The backend may have restarted: the creation is gone
                if (e && e.status === 404) {
                    this.setState({job: null});
                } else {
                    this.follow();
                }
            });
    };

    finished = (job) => {
        const { t } = this.props;
        if (job.state === 'DONE') {
            this.props.refreshLibrary();
        }
        if (this.state.open) {
            return;
        }
        if (job.state === 'DONE') {
            toast.success(t('youtubePack.toasts.done', { title: job.result.title, count: job.result.stories }), { autoClose: 8000 });
        } else if (job.state === 'FAILED') {
            toast.error(t('youtubePack.toasts.failed', { message: job.message }), { autoClose: false });
        }
    };

    checkTools = (refresh) => {
        this.setState({checking: true, toolsError: null});
        fetchYoutubeTools(refresh)
            .then(tools => {
                this.setState(state => ({
                    tools,
                    checking: false,
                    // Defaults from the backend for the fields the user has not changed
                    form: {...defaultForm(tools), ...state.touched}
                }));
            })
            .catch(e => {
                console.error('failed to check yt-dlp and ffmpeg', e);
                this.setState({checking: false, toolsError: e.message || String(e)});
            });
    };

    onOpen = () => {
        this.setState({open: true, startError: null});
        this.checkTools(false);
    };

    onClose = () => {
        this.setState(state => ({
            open: false,
            // A finished creation is not shown again next time
            job: isRunning(state.job) ? state.job : null
        }));
    };

    onField = (name, kind) => {
        return (event) => {
            const value = kind === 'checkbox' ? event.target.checked : event.target.value;
            this.setState(state => ({form: {...state.form, [name]: value}, touched: {...state.touched, [name]: value}, startError: null}));
        };
    };

    onStart = () => {
        const maxLinks = this.state.tools && this.state.tools.maxLinks;
        if (this.state.starting || formProblem(this.state.form, maxLinks)) {
            return;
        }
        this.setState({starting: true, startError: null});
        startYoutubePack(buildRequest(this.state.form))
            .then(job => {
                this.setState({starting: false, job}, this.follow);
            })
            .catch(e => {
                console.error('failed to start the YouTube pack creation', e);
                this.setState({starting: false, startError: e.message || String(e)});
                if (e && e.code === 'TOOLS_MISSING') {
                    this.checkTools(true);
                }
            });
    };

    onCancel = () => {
        const job = this.state.job;
        if (!isRunning(job) || job.cancelRequested) {
            return;
        }
        cancelYoutubePack(job.id)
            .then(updated => this.setState({job: updated}))
            .catch(e => {
                console.error('failed to cancel the YouTube pack creation', e);
                this.setState({startError: e.message || String(e)});
            });
    };

    onAnother = () => {
        const cleared = {links: '', title: '', description: ''};
        this.setState(state => ({job: null, form: {...state.form, ...cleared}, touched: {...state.touched, ...cleared}}));
    };

    renderTools() {
        const { t } = this.props;
        const { tools, toolsError, checking } = this.state;
        if (checking && !tools) {
            return <p className="youtube-pack-checking">{t('youtubePack.checking')}</p>;
        }
        if (toolsError) {
            return <div className="youtube-pack-error" role="alert">{t('youtubePack.toolsError', { message: toolsError })}</div>;
        }
        if (tools && !tools.ready) {
            return <div className="youtube-pack-error" role="alert">
                <p><strong>{t('youtubePack.missingTitle')}</strong></p>
                <ul>
                    {!tools.ytDlp && <li>{t('youtubePack.missingYtDlp')}</li>}
                    {!tools.ffmpeg && <li>{t('youtubePack.missingFfmpeg')}</li>}
                </ul>
                {tools.message && <p className="youtube-pack-detail">{tools.message}</p>}
                <button type="button" className="ui-btn" disabled={checking} onClick={() => this.checkTools(true)}>{t('youtubePack.recheck')}</button>
            </div>;
        }
        if (tools && !tools.jsRuntime) {
            return <p className="youtube-pack-hint">{t('youtubePack.noJsRuntime')}</p>;
        }
        return null;
    }

    renderForm() {
        const { t } = this.props;
        const { form, tools, startError } = this.state;
        const voices = (tools && Array.isArray(tools.voices) && tools.voices.length > 0) ? tools.voices : [form.voice];
        const problem = form.links ? formProblem(form, tools && tools.maxLinks) : null;
        return <div className="youtube-pack-form">
            <label htmlFor="youtube-pack-links">{t('youtubePack.links')}</label>
            <textarea id="youtube-pack-links" rows={4} value={form.links} onChange={this.onField('links')}
                      placeholder={t('youtubePack.linksPlaceholder')} autoFocus />
            <p className="youtube-pack-hint">{t('youtubePack.linksHint')}</p>

            <div className="youtube-pack-grid">
                <label htmlFor="youtube-pack-title">{t('youtubePack.title')}</label>
                <input id="youtube-pack-title" type="text" maxLength={100} value={form.title} onChange={this.onField('title')}
                       placeholder={t('youtubePack.titlePlaceholder')} />
                <label htmlFor="youtube-pack-description">{t('youtubePack.description')}</label>
                <input id="youtube-pack-description" type="text" maxLength={500} value={form.description} onChange={this.onField('description')} />
                <label htmlFor="youtube-pack-voice">{t('youtubePack.voice')}</label>
                <select id="youtube-pack-voice" value={form.voice} onChange={this.onField('voice')}>
                    {voices.map(v => <option key={v} value={v}>{v}</option>)}
                </select>
            </div>

            <fieldset className="youtube-pack-options">
                <legend>{t('youtubePack.options')}</legend>
                {['cleanTitles', 'sponsorBlock', 'seasons', 'autoplayNext', 'pauses'].map(name =>
                    <label key={name} className="youtube-pack-check" title={t(`youtubePack.help.${name}`)}>
                        <input type="checkbox" checked={form[name] === true} onChange={this.onField(name, 'checkbox')} />
                        {' '}{t(`youtubePack.option.${name}`)}
                    </label>
                )}
                <div className="youtube-pack-grid youtube-pack-numbers">
                    <label htmlFor="youtube-pack-min">{t('youtubePack.minMinutes')}</label>
                    <input id="youtube-pack-min" type="number" min={0} step="any" value={form.minMinutes} onChange={this.onField('minMinutes')} />
                    <label htmlFor="youtube-pack-max">{t('youtubePack.maxMinutes')}</label>
                    <input id="youtube-pack-max" type="number" min={0} step="any" value={form.maxMinutes} onChange={this.onField('maxMinutes')} />
                    <label htmlFor="youtube-pack-menu" title={t('youtubePack.help.menuSize')}>{t('youtubePack.menuSize')}</label>
                    <input id="youtube-pack-menu" type="number" min={0} max={50} value={form.menuSize} onChange={this.onField('menuSize')} />
                    <label htmlFor="youtube-pack-bitrate">{t('youtubePack.bitrate')}</label>
                    <select id="youtube-pack-bitrate" value={form.bitrate} onChange={this.onField('bitrate')}>
                        {BITRATES.map(b => <option key={b} value={b}>{t('youtubePack.kbps', { value: b })}</option>)}
                    </select>
                    <label htmlFor="youtube-pack-maxvideos">{t('youtubePack.maxVideos')}</label>
                    <input id="youtube-pack-maxvideos" type="number" min={1} max={200} value={form.maxVideos} onChange={this.onField('maxVideos')} />
                </div>
            </fieldset>
            <p className="youtube-pack-hint">{t('youtubePack.privacy')}</p>
            {problem && <p className="youtube-pack-error" role="alert">{t(problem, { max: tools && tools.maxLinks })}</p>}
            {startError && <div className="youtube-pack-error" role="alert">{startError}</div>}
        </div>;
    }

    renderJob() {
        const { t } = this.props;
        const { job, startError } = this.state;
        const percent = progressPercent(job);
        return <div className="youtube-pack-job">
            <p className="youtube-pack-step">
                <strong>{t(`youtubePack.steps.${job.step}`)}</strong>
                {isRunning(job) && job.total > 0 && <span> ({job.current}/{job.total})</span>}
                {isRunning(job) && job.currentTitle && <span className="youtube-pack-current" title={job.currentTitle}> {job.currentTitle}</span>}
            </p>
            <div className={`youtube-pack-progress ${job.state.toLowerCase()}`} role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}>
                <div className="youtube-pack-progress-bar" style={{width: `${percent}%`}} />
                <span className="youtube-pack-progress-label">{percent} %</span>
            </div>
            {job.state === 'DONE' && <div className="youtube-pack-success" role="status">
                {t('youtubePack.done', { title: job.result.title, count: job.result.stories, path: job.result.path })}
                {job.result.silentTitles > 0 && <p>{t('youtubePack.silent', { count: job.result.silentTitles })}</p>}
                {job.result.blocked && <p>{t('youtubePack.blocked')}</p>}
            </div>}
            {job.state === 'FAILED' && <div className="youtube-pack-error" role="alert">{t('youtubePack.failed', { message: job.message })}</div>}
            {job.state === 'CANCELLED' && <div className="youtube-pack-error" role="status">{t('youtubePack.cancelled')}</div>}
            {job.warnings > 0 && <p className="youtube-pack-hint">{t('youtubePack.warnings', { count: job.warnings })}</p>}
            <pre className="youtube-pack-log">{lastLines(job, 12).join('\n')}</pre>
            {isRunning(job) && <p className="youtube-pack-hint">{t('youtubePack.background')}</p>}
            {startError && <div className="youtube-pack-error" role="alert">{startError}</div>}
        </div>;
    }

    renderDialog() {
        const { t } = this.props;
        const { job, tools, starting, form } = this.state;
        let content;
        let buttons;
        if (job) {
            content = this.renderJob();
            buttons = isRunning(job)
                ? [
                    { label: t('youtubePack.close'), onClick: this.onClose },
                    { label: job.cancelRequested ? t('youtubePack.cancelling') : t('youtubePack.cancel'), onClick: this.onCancel, disabled: job.cancelRequested === true }
                ]
                : [
                    { label: t('youtubePack.another'), onClick: this.onAnother },
                    { label: t('youtubePack.close'), onClick: this.onClose }
                ];
        } else {
            content = <div>{this.renderTools()}{this.renderForm()}</div>;
            const ready = !!tools && tools.ready === true;
            buttons = [
                { label: t('youtubePack.close'), onClick: this.onClose },
                { label: starting ? t('youtubePack.starting') : t('youtubePack.start'), onClick: this.onStart,
                  disabled: !ready || starting || formProblem(form, tools && tools.maxLinks) !== null }
            ];
        }
        return <Modal id="youtube-pack"
                      className="youtube-pack-modal"
                      title={t('youtubePack.dialogTitle')}
                      content={content}
                      buttons={buttons}
                      onClose={this.onClose}
        />;
    }

    render() {
        const { t } = this.props;
        const running = isRunning(this.state.job);
        return <>
            <button type="button" title={t('youtubePack.buttonTitle')}
                    className={`btn btn-default ui-btn youtube-pack-button ${running ? 'running' : ''}`}
                    onClick={this.onOpen}>
                <span className="glyphicon glyphicon-film"/> {running ? t('youtubePack.buttonRunning', { percent: progressPercent(this.state.job) }) : t('youtubePack.button')}
            </button>
            {this.state.open && this.renderDialog()}
        </>;
    }
}

const mapDispatchToProps = (dispatch, ownProps) => ({
    refreshLibrary: () => dispatch(actionRefreshLibrary(ownProps.t))
});

export default withTranslation()(
    connect(
        null,
        mapDispatchToProps
    )(YoutubePack)
);
