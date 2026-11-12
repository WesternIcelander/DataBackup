# DataBackup server HTTP API

This describes the HTTP API of the DataBackup server (`server` module, `RequestHandler` and `ContentReceiver`) as it
is implemented now. The backup client uses it to upload backups, and to list, browse and restore them.

## Overview

| Method | Path                                             | Permission                    | Purpose                                      |
|--------|--------------------------------------------------|-------------------------------|----------------------------------------------|
| `POST` | `/upload`                                        | any `upload:<machine>`        | Upload file content (multipart)              |
| `GET`  | `/content/:contentId`                            | `globalread`                  | Download stored content by its hash          |
| `GET`  | `/backups/`                                      | none beyond a valid token     | List the machines the token can read         |
| `GET`  | `/backups/:machine/`                             | `read:<machine>`              | List a machine's backups                     |
| `PUT`  | `/backups/:machine`                              | `upload:<machine>`            | Upload a manifest as a pending backup        |
| `POST` | `/backups/:machine/.pending/:pendingId/commit`   | `upload:<machine>`            | Commit a pending backup                      |
| `GET`  | `/backups/:machine/:backup?manifest`             | `read:<machine>`              | Download a backup's raw manifest             |
| `GET`  | `/backups/:machine/:backup/[path/]`              | `read:<machine>`              | List a directory inside a backup             |
| `GET`  | `/backups/:machine/:backup/path/to/file`         | `read:<machine>`              | Download a file from a backup                |
| `GET`  | `/ui/...`                                        | see [Web UI](#web-ui)         | Read-only web UI for browsers                |

A typical backup run is:

1. `PUT /backups/:machine` with the manifest. The response gives a `pendingId` and the content IDs the server is
   missing.
2. `POST /upload` with the missing content, in as many requests as convenient.
3. `POST /backups/:machine/.pending/:pendingId/commit`. The backup is listed from then on.

## Transport security

The server speaks plain HTTP and the client does no encryption of its own; the server is trusted. Tokens and backup
data travel in the clear, so run the server behind an HTTPS reverse proxy, or only on a trusted network.

## Authentication

Every request needs a token, sent either as

- an `Authorization: Bearer <token>` header (the `Bearer` scheme name is matched case-insensitively), or
- a `token` cookie (`Cookie: token=<token>`), for browsers.

If both are present, the header wins. A missing or empty token gives `401 Missing token`, and an unknown or revoked
token gives `401 Invalid token`; both responses carry `WWW-Authenticate: Bearer`. The token is checked before
anything else, so even an unknown route answers `401` without a valid token. The one exception is the web UI under
`/ui/` (see [Web UI](#web-ui)), which sends a browser without a valid token to its login page instead.

Tokens are created, listed and revoked with the server's command line (`token create`, `token list`,
`token revoke`; see `java -jar databackup-server.jar help`). They are stored in the data folder's `tokens/` folder as
`<sha256 of token>.txt`.

## Permissions

A token holds a list of permission strings:

| Permission         | Grants                                                                                    |
|--------------------|-------------------------------------------------------------------------------------------|
| `upload:<machine>` | Upload and commit manifests for that machine, and upload content (`POST /upload`).        |
| `read:<machine>`   | List, browse and download that machine's backups, and download its manifests.             |
| `upload:*`         | `upload:<machine>` for every machine.                                                     |
| `read:*`           | `read:<machine>` for every machine.                                                       |
| `globalread`       | Download any stored content by hash through `GET /content/:contentId`.                    |
| `admin`            | Reserved for token management, retention and garbage-collection endpoints. No HTTP route uses it yet. |

There is no bare `*` permission, and a plain `upload` or `read` without a machine grants nothing.

Permission checks come before existence checks: a request for a machine the token can't read gives `403`, whether
or not the machine exists, so a token can't probe for other machines' names.

## Names and identifiers

- **Machine and backup names** match `[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}`: letters, digits, `.`, `_` and `-`, at
  most 128 characters, not starting with `.`. Anything else gives `400 Invalid machine name` or
  `400 Invalid backup name`.
- **Backup names** are the commit time in UTC, formatted `yyyy-MM-dd-HH-mm-ss` (for example
  `2026-11-12-14-03-27`). They sort chronologically.
- **Content IDs** are the SHA-256 of the content's uncompressed bytes, as 64 lowercase hex digits. Uppercase hex is
  rejected with `400 Invalid content ID`.
- **Pending IDs** are random UUIDs in lowercase canonical form (`8-4-4-4-12` hex digits).
- **Paths inside a backup** are URL-decoded by the server before use. A path piece must not be empty, `.` or `..`
  (`400 Invalid path`).

## Responses

### Success bodies

- **JSON** responses use `Content-Type: application/json; charset=utf-8` and end with a newline.
- **Text listings** use `Content-Type: text/plain; charset=utf-8`, one item per line, each line ending in `\n`. An
  empty listing has an empty body.
- Listings that support JSON return it when the request has the query parameter `?json` (any value, or none) or an
  `Accept` header naming `application/json` (parameters such as `;q=0.9` are ignored). Otherwise they return text.

### Errors

Errors have a `text/plain; charset=utf-8` body holding a one-line message, for example `Missing permission:
read:laptop`. Server internals and stack traces are never sent; unexpected errors are logged on the server and
answered with `500 Internal server error`. The one exception is a commit refused for missing content, which answers
`409` with JSON (see below).

| Status | Meaning                                                                                                  |
|--------|----------------------------------------------------------------------------------------------------------|
| `400`  | Malformed request: invalid name or ID, bad path, unsupported `Content-Encoding`, malformed gzip, manifest or multipart body, manifest without a snapshot ID. |
| `401`  | Token missing or invalid. Carries `WWW-Authenticate: Bearer`.                                             |
| `403`  | The token lacks the permission. The message names it, as in `Missing permission: upload:laptop`.         |
| `404`  | Unknown route, machine, backup, pending ID, path or content.                                             |
| `405`  | Wrong HTTP method for the route. Carries `Allow` with the accepted method(s).                            |
| `409`  | Conflict: a snapshot ID the machine already has, or content still missing at commit.                    |
| `500`  | Unexpected server error.                                                                                 |
| `503`  | A file's content is referenced by a backup but missing from the content store.                           |

If an error happens after the response has started (for example while streaming a large download), the server can't
change the status any more and closes the connection instead, so the client sees a truncated response.

## Content

### `POST /upload`

Uploads file content to the content store. Content is global: it is identified only by its hash and shared between
all machines and backups, so it isn't uploaded for a particular machine.

- **Permission:** at least one `upload:<machine>` (or `upload:*`).
- **Request:** `Content-Type: multipart/form-data; boundary=...`. Each file part (a part with a `filename` in its
  `Content-Disposition`) is one piece of content, sent as raw, uncompressed bytes. The part's field name doesn't
  matter, and parts that aren't files are ignored. Send any number of parts per request.
- The server computes each part's SHA-256 itself; the client doesn't send content IDs. The part's filename (its last
  path component, if non-empty) is recorded as a known filename for the content, used to pick a content type and a
  download name in `GET /content/:contentId`.
- New content is stored gzip-compressed when that saves at least `minCompressionSavings` (default 5%, set in
  `server.json`) and its extension isn't a known compressed format; otherwise it is stored as-is. This is invisible
  to clients.
- Uploading content the server already has is fine: it is reported as already present and kept as stored. Every
  uploaded part, new or not, restarts that content's garbage-collection grace period.

**Response `200`:**

```json
{"stored": ["<contentId>", ...], "alreadyPresent": ["<contentId>", ...]}
```

`stored` lists content that was new; `alreadyPresent` lists content that was already on the server, including a part
repeated within the same request (it is `stored` the first time and `alreadyPresent` after that). Both are in the
order the parts were received.

**Errors:** `400 Expected multipart/form-data` (missing or other `Content-Type`), `400 Malformed multipart body`,
`403`, `405` (`Allow: POST`).

### `GET /content/:contentId`

Downloads stored content by its hash, outside any backup.

- **Permission:** `globalread`.
- **Response `200`:** the content's bytes. Headers:
  - `Content-Type` is picked from the first known filename's extension, or `application/octet-stream` when there's
    none or the type is unknown. Types a browser could run scripts from (HTML, XHTML, any XML type, SVG) are always
    sent as `application/octet-stream`.
  - `Content-Disposition: inline; filename="..."; filename*=UTF-8''...` with the first known filename, if any.
    `filename` is an ASCII fallback (other characters become `_`); `filename*` is the exact UTF-8 name.
  - `X-Content-Type-Options: nosniff`.
  - Content stored gzip-compressed is sent with `Content-Encoding: gzip` when the request's `Accept-Encoding` allows
    `gzip` (or `x-gzip`) with a non-zero `q`; otherwise the server decompresses it on the way out. In both cases the
    response carries `Vary: Accept-Encoding`. A decompressed response has no `Content-Length`, because the server
    doesn't know the uncompressed length here.
- **Errors:** `400 Invalid content ID`, `403`, `404 No such content`, `405` (`Allow: GET`).

## Backups

### `GET /backups/`

Lists machines. `GET /backups` (without the slash) does the same.

- **Permission:** a valid token. Only machines the token has `read:<machine>` (or `read:*`) for are listed, sorted by
  name. A token that can read nothing gets an empty list.
- A machine exists once it has a folder in the data folder's `backups/`, which happens on its first manifest upload,
  so a machine can be listed with no backups yet.
- **Text:** one machine name per line.
- **JSON:** an array of names, `["desktop", "laptop"]`.
- **Errors:** `405` (`Allow: GET`).

### `GET /backups/:machine/`

Lists a machine's committed backups, oldest first. `GET /backups/:machine` (without the slash) does the same. Pending
(uncommitted) backups are never listed.

- **Permission:** `read:<machine>`.
- **Text:** one backup name per line.
- **JSON:** an array of objects:

  ```json
  [{"name": "2026-11-12-14-03-27", "time": 1762956207000, "snapshotId": "3f1c...-..."}]
  ```

  `time` is the backup time in milliseconds since the epoch; `snapshotId` is the UUID from the manifest's
  `ExtraDataSnapshotId`.
- **Errors:** `400 Invalid machine name`, `403`, `404 Unknown machine`, `405` (`Allow: GET, PUT`).

The server caches each machine's backup list; files placed in `backups/<machine>/` by hand may not be seen until the
server restarts.

### `PUT /backups/:machine`

Uploads a manifest and holds it as a pending backup. It isn't listed until it is committed.

- **Permission:** `upload:<machine>`.
- **Request body:** a manifest in DataBackup's binary manifest format (`BackupFile`). Send it either plain or
  gzip-compressed with `Content-Encoding: gzip`; no header or `Content-Encoding: identity` means plain. The root
  directory must carry an `ExtraDataSnapshotId` holding a UUID that is new for this machine. The server re-writes the
  manifest in canonical form before storing it.
- Files reference their data through content IDs (`ContentIDFileContent`); inlined and zero-filled contents carry no
  content ID and need no upload.

**Response `200`:**

```json
{"pendingId": "8d2e6c1a-...", "missing": ["<contentId>", ...]}
```

`missing` lists each content ID the manifest references that the server doesn't have, once each, in the order first
found. Upload those with `POST /upload`, then commit. Each upload gets a new `pendingId`, so a client can simply
upload again after a failure; pending manifests that are never committed are left in
`backups/<machine>/.pending/` (nothing removes them yet).

**Errors:**

- `400 Unsupported Content-Encoding: ...`, `400 Malformed gzip data`, `400 Malformed manifest`,
  `400 Manifest has no snapshot ID`, `400 Invalid machine name`.
- `403`.
- `405` (`Allow: GET, PUT`).
- `409 This machine already has a backup with snapshot ID <uuid>`: that snapshot ID was already committed for this
  machine.

### `POST /backups/:machine/.pending/:pendingId/commit`

Turns a pending manifest into a listed backup. The request body is ignored (send an empty one).

- **Permission:** `upload:<machine>`.
- The server checks again that every referenced content ID is stored. If all are, it restarts the
  garbage-collection grace period of all referenced content, then moves the manifest into place, named after the
  current time. If that name is already taken, it moves on one second at a time to the next free name.
- Commits for one machine are handled one at a time.

**Response `200`:**

```json
{"backup": "2026-11-12-14-03-27"}
```

**Response `409` for missing content** (JSON, unlike other errors):

```json
{"error": "Content is still missing", "missing": ["<contentId>", ...]}
```

The pending manifest stays in place; upload the missing content and commit again.

**Other errors:**

- `400 Invalid pending ID` (not a lowercase UUID), `400 Invalid machine name`.
- `403`.
- `404 No such pending backup`: unknown ID, an ID belonging to another machine, or one that was already committed.
  Any other path under `.pending` (without `/commit`, or with another last piece) is `404 Not found`.
- `405` (`Allow: POST`).
- `409 This machine already has a backup with snapshot ID <uuid>`: another manifest with the same snapshot ID was
  committed after this one was uploaded.

### `GET /backups/:machine/:backup?manifest`

Downloads the backup's manifest file exactly as stored on the server (canonical form, uncompressed). The restore
client uses this to read a whole backup at once.

- **Permission:** `read:<machine>`.
- **Response `200`:** `Content-Type: application/octet-stream`,
  `Content-Disposition: attachment; filename="<machine>-<backup>.manifest"; filename*=...`,
  `X-Content-Type-Options: nosniff`.
- `?manifest` only applies to the backup itself. `GET /backups/:machine/:backup/?manifest` (with the slash) also
  works, but on a path inside the backup it gives `400 ?manifest only applies to a backup, not a path inside it`.
- **Errors:** `400`, `403`, `404 Unknown machine`, `404 Unknown backup`, `405` (`Allow: GET`).

### `GET /backups/:machine/:backup/[path/]`

Lists a directory inside a backup. `/backups/:machine/:backup/` is the backup's root directory, which holds one
directory per backed-up source (for example `Data` or `C`).

- **Permission:** `read:<machine>`.
- Directory URLs end in `/`. A request for a directory without the trailing slash is redirected with `303 See Other`
  to the same URL with the slash added, keeping the original URL encoding and query string (so
  `/backups/laptop/2026-11-12-14-03-27/Data/Sub%20dir?json` goes to `.../Data/Sub%20dir/?json`).
- Entries are listed in manifest order. Entries that are neither files, directories nor symlinks (such as deletion
  markers) are left out.
- **Text:** one line per entry:

  ```
  F notes.txt
  D Sub dir/
  L link -> ../target
  ```

  `F` is a file, `D` a directory (its name followed by `/`), `L` a symlink with its target.
- **JSON:** an array of objects:

  ```json
  [
    {"name": "notes.txt", "type": "file", "size": 1234, "modified": 1700000000123,
     "modifiedNanos": 1700000000123456789,
     "permissions": {"mode": 420, "string": "rw-r--r--", "owner": 501, "group": 20}},
    {"name": "Sub dir", "type": "directory"},
    {"name": "link", "type": "symlink", "target": "../target"}
  ]
  ```

  | Field           | Present for                                    | Meaning                                                      |
  |-----------------|------------------------------------------------|--------------------------------------------------------------|
  | `name`          | all                                            | Entry name.                                                  |
  | `type`          | all                                            | `file`, `directory` or `symlink`.                            |
  | `size`          | files                                          | Size in bytes.                                               |
  | `modified`      | files; others when a nanosecond date is stored | Modification time, milliseconds since the epoch.             |
  | `modifiedNanos` | entries with a nanosecond modification date    | Modification time, nanoseconds since the epoch.              |
  | `target`        | symlinks                                       | Symlink target, as stored.                                   |
  | `permissions`   | entries with POSIX permissions recorded        | `mode` (permission bits as a number; `420` is octal `0644`), `string` (`rw-r--r--`), numeric `owner` and `group` IDs. |

- **Errors:** `400 Invalid backup name`, `400 Invalid path`, `403`, `404 Unknown machine`, `404 Unknown backup`,
  `404 No such path` (including a file requested with a trailing slash, or a path that descends through a file),
  `405` (`Allow: GET`).

### `GET /backups/:machine/:backup/path/to/file`

Downloads a file from a backup.

- **Permission:** `read:<machine>`.
- **Response `200`:** the file's bytes, with `Content-Type`, `Content-Disposition: inline` and
  `X-Content-Type-Options: nosniff` chosen from the file's own name, by the same rules as `GET /content/:contentId`.
  - A file made of exactly one piece of stored content covering the whole file is sent like
    `GET /content/:contentId`: gzip-stored content goes out with `Content-Encoding: gzip` to clients that accept it,
    and decompressed otherwise; a decompressed or uncompressed response has a `Content-Length` of the file's size.
  - Any other file (several pieces, inlined or zero-filled content, gaps) is assembled in offset order, with gaps
    filled with zero bytes up to the file's size, and sent uncompressed with `Content-Length` set to its size.
- Symlinks have no content to download; requesting one gives `404 No such path`. Their target is in the directory
  listing.
- **Errors:** as for directory listings, plus `503 Some of this file's content is missing on the server`. A file
  whose stored contents overlap or run past its size gives `500`.

## Web UI

A read-only, server-rendered HTML interface for browsing backups and downloading files. It uses no JavaScript.

| Method          | Path                                     | Token needed | Purpose                                              |
|-----------------|------------------------------------------|--------------|------------------------------------------------------|
| `GET`           | `/ui`                                    | no           | Redirects (`303`) to `/ui/`                          |
| `GET`           | `/ui/login`                              | no           | Login form (redirects to `/ui/` when logged in)      |
| `POST`          | `/ui/login`                              | no           | Log in with form field `token`                       |
| `GET`, `POST`   | `/ui/logout`                             | no           | Clears the cookie, redirects to `/ui/login`          |
| `GET`           | `/ui/style.css`                          | no           | The pages' stylesheet                                |
| `GET`           | `/ui/`                                   | yes          | The machines the token can read                      |
| `GET`           | `/ui/backups/:machine/`                  | `read:<m>`   | The machine's backups, newest first                  |
| `GET`           | `/ui/backups/:machine/:backup/[path/]`   | `read:<m>`   | A directory: name, size, modified time, permissions  |

- **Login:** `POST /ui/login` with `application/x-www-form-urlencoded` body `token=<token>`. A valid token answers
  `303` to `/ui/` with `Set-Cookie: token=<percent-encoded token>; Path=/; HttpOnly; SameSite=Strict`, plus `Secure`
  when the request came over HTTPS (or through a proxy sending `X-Forwarded-Proto: https`). The cookie is a session
  cookie and also authenticates the API routes, so the UI's file links go straight to
  `GET /backups/:machine/:backup/path/to/file`. An empty token gives `400`, an invalid one `401`, both with the login
  form again and no cookie. Any valid token can log in; it sees only the machines its `read:` permissions allow.
- **Pages without a valid token** answer `303` to `/ui/login`; a cookie holding an invalid or revoked token is
  cleared on the way.
- **Browsing:** a directory without its trailing slash redirects to the slash form, and a file's `/ui/backups/...`
  path redirects to its download route. Folders are listed first, then files and symlinks (shown with their target),
  each sorted by name. Times are shown in UTC.
- **Errors** are HTML pages with the same statuses as the API: `400` for invalid names or paths, `403` without
  `read:<machine>` (before any existence check), `404` for unknown machines, backups, paths and pages, `405` for
  other methods.
- **Headers:** every page is `text/html; charset=utf-8` with `Content-Security-Policy: default-src 'self';
  base-uri 'none'; form-action 'self'; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` and `Cache-Control: no-store`. Every name is HTML-escaped
  and every link piece percent-encoded. Downloaded backup content keeps the API's rule of never being sent as HTML.

## Unknown routes and methods

- Any path other than those above gives `404 Not found` (with a valid token), including `/`. Unknown pages under
  `/ui/` give an HTML `404` page.
- A known path with the wrong method gives `405` with an `Allow` header: `POST` for `/upload` and commits, `GET` for
  `/content/...`, `/backups/` and paths inside backups, `GET, PUT` for `/backups/:machine`.
