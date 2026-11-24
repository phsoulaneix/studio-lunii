# Device API for scripted clients

This fork (studio-lunii) makes the local API usable by a program, not only by the web UI. Every
route below is under `http://localhost:8080/api/device`. Bodies are JSON.

## Requests the server accepts

- The `Host` header must be `localhost` or `127.0.0.1` (port 8080 or none); anything else is a 403.
  This blocks DNS rebinding from a web page.
- A browser request carrying an `Origin` is accepted only from `http://localhost:8080` or
  `http://127.0.0.1:8080` (any local port with `-Denv=dev`).
- Every `POST` must send `Content-Type: application/json`, except `/api/library/upload`, which
  takes `multipart/form-data` only; otherwise 415.
- Pages are served with `X-Frame-Options: DENY`; the SockJS iframe page and its script-tag
  transports are disabled (the event bus uses WebSocket, or XHR as fallback).
- JSON bodies are limited to 1 MiB, uploads to 4 GiB (`-Dstudio.maxUploadBytes`).
- To run `yarn start` (UI on :3000) against a real device, start STUdio with
  `-Dstudio.devOrigin=http://localhost:3000` so the event bus accepts that origin.
- Pack UUIDs must be written in full canonical form (`8-4-4-4-12` hex digits), otherwise 400: a
  shortened form names a different pack folder than the pack it parses to.
- `path` in `addFromLibrary` must be a direct entry of the library (no `..`, no sub-folder, no
  absolute path), otherwise 400.

## Errors

A refused request answers with a status that says what kind of problem it is, and this body:

```json
{"success": false, "error": "PACK_ALREADY_ON_DEVICE", "message": "The pack … is already on the device"}
```

| Status | `error` | Meaning |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | missing field, bad UUID, bad folder name, unknown format |
| 404 | `LIBRARY_PACK_NOT_FOUND` | the `path` given is not in the library |
| 404 | `PACK_NOT_ON_DEVICE` | the pack (or folder) to act on is not on the device |
| 409 | `PACK_ALREADY_ON_DEVICE` | remove it first, or skip it |
| 409 | `CONTENT_FOLDER_EXISTS` | a residual folder blocks the upload; see *Orphan folders* |
| 409 | `FOLDER_STILL_REFERENCED` | the folder belongs to an indexed pack and was not removed |
| 503 | `NO_DEVICE` | no device plugged, or a device operation stopped answering (see below) |
| 507 | `NOT_ENOUGH_SPACE` | not enough free space on the device |
| 500 | `INTERNAL_ERROR` | anything else; `message` says what |

`POST /api/library/convert` uses the same body for its failures.

## Transfers

`POST /addFromLibrary` `{"uuid": "…", "path": "<library entry>"}` answers at once with
`{"transferId": "…"}`. Device operations (uploads, removals, reorders, extractions, orphan
removal) run **one at a time on the server**, in the order received, so several uploads can be
sent back to back without corrupting the pack index.

`GET /transfer/{transferId}`:

```json
{
  "transferId": "…", "uuid": "…", "operation": "add",
  "state": "RUNNING", "done": false, "success": false,
  "transferred": 1048576, "total": 4194304, "progress": 0.25,
  "createdAt": 1764000000000, "startedAt": 1764000001000
}
```

`state` is `QUEUED`, `RUNNING`, `SUCCEEDED` or `FAILED`. A failed transfer also carries `error`,
`reason` and `message`. A transfer is `SUCCEEDED` only once the pack index has been written. The
last 200 finished transfers are kept; `GET /transfers` lists them all, oldest first. An unknown id
answers 404 `TRANSFER_NOT_FOUND`.

An operation that does not end within 30 minutes of starting (time spent waiting its turn does
not count, `-Dstudio.device.operationTimeoutMinutes`) is **not** declared failed: it may still be
writing. Its transfer stays `RUNNING` with `"stalled": true` and a `message`, and still records
its real outcome when it ends. Meanwhile the operations queued behind it, and any new one, are
refused with 503 `NO_DEVICE`. Unplugging the device fails every transfer still queued or running.

A second `addFromLibrary` for a pack whose transfer is still queued or running is refused with 409
`PACK_ALREADY_ON_DEVICE`. On a 1.x device, `GET /infos` and `GET /packs` answer the last values read
(with `"cached": true` for infos) while a device operation is running.

`POST /addToLibrary` answers 409 `CONTENT_FOLDER_EXISTS` when the pack is already in the library.
An extraction is written under a hidden name (`.part-…` for a 1.x `.pack`, `.staging-…` for a
2.x+ folder) and renamed once complete; a failed one leaves nothing behind.

`POST /dump` (1.x only) ignores any `outputPath` and writes into a new folder under STUdio's
temporary directory, returned as `{"success": true, "path": "…"}`; 503 without a 1.x device.

The web UI keeps using the event bus (events are now published to every connected tab): `storyteller.transfer.<id>.done` now also carries `error`
and `message` on failure.

## Orphan folders

An upload that fails part-way can leave a `.content/<XXXXXXXX>` folder that the index does not
reference. It takes space and makes any later upload of the same pack refuse with
`CONTENT_FOLDER_EXISTS`.

- `GET /orphans` → `{"folders": ["0A0B0C0D", …]}`. Listed in the device queue, so an upload in
  progress is never reported as an orphan.
- `POST /orphans/remove` `{"folders": ["0A0B0C0D"]}` → `{"success": true, "results": [{"folder":
  "0A0B0C0D", "removed": true}]}`. Only folders named explicitly are removed, and only if the index,
  re-read at that moment, does not reference them. **Removal is permanent.**

A typical recovery: an upload fails with `CONTENT_FOLDER_EXISTS` for pack `…5555aaaabbbb`; its
folder is the last eight hex digits, upper case (`AAAABBBB`). If `GET /orphans` lists it, remove it
and upload again.

## Old packs in the library

Under `http://localhost:8080/api/library` (not `/api/device`). When a pack is sent to a device,
STUdio converts it and keeps the conversion in the library as `<uuid>.converted_<millis>` (a folder
for V2/V3, a `.pack` for V1). Converting again after the source changed adds a new one and keeps the
old one. These routes find and remove such leftovers.

- `GET /purge/candidates` → `{"candidates": [{"path", "uuid", "title", "format", "size", "timestamp",
  "reason", "selected", "keptPath"}], "totalSize": …}`. Read-only. `size` is in bytes (-1 if
  unknown), `timestamp` is the modification time in ms, `keptPath` the entry kept in its place.
  It may read packs in full (see below), so it can take a few seconds on a large library.
- `POST /purge` `{"paths": ["<library entry>", …]}` → `{"success": true, "freed": …, "results":
  [{"path", "removed", "size", "error"}]}`. The candidates are computed again, from the disk as it
  is now; a name that is not among them is left alone with `"error": "NOT_A_CANDIDATE"`
  (`"REMOVAL_FAILED"` if the deletion itself failed). `success` is true only if every name was
  removed. A missing or empty `paths`, a non-string, or any name that is not a direct entry of the
  library is a 400 `INVALID_REQUEST`, and **nothing** is removed. **Removal is permanent.**

What is offered, per pack UUID (`reason`):

| `reason` | Selected by default | Entry |
| --- | --- | --- |
| `SUPERSEDED` | yes | a conversion for which another conversion of the same format is more recent, both by modification time and by the timestamp in its name |
| `SOURCE_CHANGED` | yes | the most recent conversion of its format, whose recorded provenance (re-checked against its current content) proves it was made from content no other entry holds any more, while a source to convert from is present |
| `DUPLICATE_SOURCE` | no | a source file (not a conversion) older than another of the same format with the very same bytes |

Never offered: the most recent source of each format (the current archive), an older source whose
content differs, a conversion whose provenance is unknown or cannot be checked (no record, made
before records existed, unreadable), a conversion when the modification time and its name disagree
on which is older, anything that would leave a pack with no entry, and entries that are not
readable packs.

## Spoken titles (editor)

`/api/tts` speaks a node title for the editor's *Générer la voix* button. Same request rules as
above (`Host`/`Origin` checked, `POST` in JSON only, body ≤ 1 MiB).

`POST /api/tts/speak` `{"text": "Le petit chaperon rouge", "engine": "auto", "voice": "fr-FR-DeniseNeural"}`:

```json
{"success": true, "engine": "edge", "voice": "fr-FR-DeniseNeural", "mimeType": "audio/wav",
 "durationMillis": 2850, "data": "<base64>"}
```

- `text`: required, at most 500 characters once control characters and repeated spaces are
  removed.
- `engine`: `auto` (default), `edge` or `system`.
  - `edge`: the `edge-tts` Python package, as Lunii Pack uses it. **The text is sent to Microsoft's
    online speech service.** Python is looked for in `-Dstudio.tts.python=<python.exe>`, then the
    `STUDIO_TTS_PYTHON` variable, then `python` / `python3` on the `PATH`; it must be able to
    `import edge_tts`.
  - `system`: the Windows voice (SAPI, `System.Speech`), offline; a French voice when one is
    installed. Nothing leaves the computer.
  - `auto`: `edge` when available, the system voice if `edge` is missing or fails (the answer then
    carries `fallbackReason`).
- `voice`: an edge voice name (`xx-XX-NameNeural`); ignored by the system voice.
- Defaults can be set at start-up: `-Dstudio.tts.engine=system` (never go online),
  `-Dstudio.tts.voice=fr-FR-HenriNeural`, `-Dstudio.tts.timeoutSeconds=60`.

The audio is always a device-native WAVE (PCM 16 bits, mono, 32 kHz) with 400 ms of silence at
each end. Processes are started from an argument list, never through a shell; the text is passed
in a UTF-8 file, never on a command line; each run is killed after the time limit.

| Status | `error` | Meaning |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | missing, empty or too long text, unknown engine, malformed voice |
| 503 | `TTS_UNAVAILABLE` | the requested engine (or every engine) is not installed |
| 502 | `TTS_FAILED` | the engine failed, timed out or produced no sound |

`GET /api/tts/engines` → `{"edge": true, "system": true, "defaultEngine": "auto",
"defaultVoice": "fr-FR-DeniseNeural", "voices": […], "maxLength": 500}`.
