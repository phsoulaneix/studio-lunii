/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

/** A refused or failed title synthesis, with the code and status the API answers. */
public class TitleVoiceException extends RuntimeException {

    public enum Code {
        /** Missing, empty or too long text, unknown engine, malformed voice name. */
        INVALID_REQUEST(400),
        /** The requested engine (or every engine) is not installed. */
        TTS_UNAVAILABLE(503),
        /** The engine ran and failed, timed out, or produced nothing usable. */
        TTS_FAILED(502);

        public final int status;

        Code(int status) {
            this.status = status;
        }
    }

    private final Code code;

    public TitleVoiceException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public TitleVoiceException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
