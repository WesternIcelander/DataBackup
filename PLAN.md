# DataBackup completion plan

Plan for taking DataBackup from its state on 2026-11-11 (`main` at `f6539f1`) to a finished, usable backup tool.
Work through it one unchecked step at a time. See `CLAUDE.md` in this directory for the commit, note and push routine
to follow after each step, and for the `- [~]` marker used for manual steps.

Markers: `- [ ]` is to do, `- [x]` is done, and `- [~]` is implemented, awaiting the user's verification.

## Where things stand

- **Done and working:** the `BackupFile` manifest format; directory entries (on disk, in memory, merged); file
  content types; extra data; `FileMetadataScanner` (scan, diff, reuse hashes, hash files); APFS, btrfs and NTFS VSS
  snapshot create, delete and serialize (`common/.../diskutil/`); the content-addressed `DataRepository`;
  `SimpleAuthorizer` and `NullAuthorizer`.
- **Partly done, buggy:** the HTTP server (`server/.../RequestHandler.java`, `ContentReceiver.java`). Manifest upload
  saves to the wrong folder and reads a file it has just renamed away. The missing-content check is inverted. Path
  browsing never descends. Listing ignores the machine. There are no error responses. Content upload never answers.
  `BackupRepository` isn't wired in. `cleanup()` is empty.
- **Not started:** the client (`DataBackupClient` is an empty class), restore, the web UI, retention and garbage
  collection, packaging, tests and docs.

## Decisions

These are settled; don't revisit them inside a step.

- **Client:** a one-shot CLI (`databackup backup`, `databackup list`, `databackup restore`). Scheduling comes from
  the OS (launchd, systemd timer, Task Scheduler); there's no daemon.
- **No client-side encryption.** The server is trusted. For transport security, run the server behind HTTPS (a reverse
  proxy) or on a trusted network; document this.
- **Platforms:** macOS (APFS), Linux (btrfs) and Windows (NTFS VSS). Every backup source must be on a filesystem that
  supports snapshots; the client refuses other sources with a clear error. There's no non-snapshot fallback for users.
  Tests may scan plain temp folders through a test-only path.
- **No FSEvents incremental scanning.** The JFSEvents library isn't stable. Don't add the `jfsevents` dependency. The
  `wip-fsscanners` branch can be used for ideas only; don't merge it.
- **Restore:** a CLI restore command plus a read-only web UI on the server for browsing backups and downloading files.
- **Backup layout:** one backup equals one manifest for one machine, taken in one client run.
  - The root directory gets an `ExtraDataSnapshotId` holding a new random UUID per backup run; `BackupRepository`
    needs this to identify backups.
  - Each configured source is a top-level directory in the root, named by its configured `name` (for example `Data`
    or `C`). It carries `ExtraDataFilePath` with the original live path (not the snapshot path), plus the matching
    snapshot-info extra data where one exists (`ExtraDataSnapshotInfoApfs` or `ExtraDataSnapshotInfoBtrfs`).
- **Upload protocol:** upload the manifest, upload the missing content, then commit.
  - A manifest is held as *pending* until the client commits it, so a half-uploaded backup is never listed as a real
    backup.
  - The commit fails if any referenced content is still missing.
- **Permissions** (strings in `SimpleAuthorizer` token files):
  - `upload:<machine>`: upload manifests and content for that machine.
  - `read:<machine>`: list, browse, download and restore that machine's backups.
  - `read:*` and `upload:*`: the same for every machine.
  - `globalread`: fetch any content by hash through `/content/:hash`.
  - `admin`: token management, retention and garbage-collection endpoints, if any are added.
- **Tests:** JUnit 5 (already in `~/.m2`). Server tests start a real `DataBackupServer` on an ephemeral port with a
  temp data folder.
- **Docs:** committed docs go under `docs/` in `main`. The user's private notes (`server-api.txt`,
  `Windows Volume Shadow Copy.md`) are excluded from git; read them for background, but put committed docs in `docs/`.

---

## Phase 0: Groundwork

- [x] **Set up JUnit 5 for all modules**
  - Done: `87ca486` (2026-11-12). Parent `pom.xml` now declares `junit-jupiter` 5.13.4 (test scope) for every module
    and `maven-surefire-plugin` 3.5.3; `common` has `JUnitSetupTest`. Also set `project.build.sourceEncoding` to UTF-8.
  - Add the JUnit Jupiter dependency (test scope) and a current `maven-surefire-plugin` to the parent `pom.xml`, so
    every module can have tests under `src/test/java`. Use a version already in `~/.m2/repository/org/junit/` so
    `mvn -o test` works.
  - Add one trivial test in `common` to prove the setup works.
  - Done when `mvn -o -q test` passes from the repo root and runs that test.

- [x] **Fix `BackupFile.open` and `TimeUtil.fromString`, which are broken against SiggiTools**
  - Done: `6ed49e6` (2026-11-12). Switched to SiggiTools' `FilePointer` and `RafOutputStream` and removed DataBackup's
    `FilePointer`, `RafOutputStream`, `RafInputStream` and `CountingInputStream` (nothing else used them).
    `TimeUtil.fromString` returns `-1` for null or invalid input. Both in-place writers now buffer each `write()` in
    the new `PositionedBufferOutputStream` and write it through a fresh `data.writeTo(position)`; they also refuse
    read-only data up front. Tests: `BackupFileTest` (the interleaving test fails against the old writers; it needs a
    manifest bigger than the 8 KiB read buffer to show the bug) and `TimeUtilTest`. Still unsafe: two writers open at
    once on the same `BackupFile` both start at the same end-of-file offset, so keep to one open writer at a time.
  - `BackupFile.open` always throws `ClassCastException`. It casts the stream from `RandomAccessData.getInputStream`
    to DataBackup's own `io.siggi.databackup.util.stream.FilePointer`, but since `RandomAccessData` moved to
    SiggiTools (commit `af87b2e`), the stream implements `io.siggi.tools.io.FilePointer`. Use the SiggiTools interface.
    Check every other cast to or `instanceof` check against DataBackup's `FilePointer`, and remove DataBackup's
    `FilePointer` if nothing else needs it.
  - `TimeUtil.fromString` must return `-1` for invalid input, as `BackupRepository.reload` expects. SiggiTools'
    `SimpleDateTime.parse` throws `DateTimeParseException` (or `NullPointerException` for null) instead.
  - Add tests: write a `BackupFile`, open it read-only and writable, update entries in place, reopen, and check the
    contents. Also test `TimeUtil` round trips and invalid input.
  - Make in-place writes safe against SiggiTools' shared file pointer. `RandomAccessDataFile` reads and writes through
    one `RandomAccessFile`, so any read made between `writeTo(offset)` and writing to the returned stream moves where
    the write lands. The `ObjectWriter`s returned by `DirectoryEntryDirectoryDisk.updateEntries()` and
    `DirectoryEntryFile.updateFileContents()` keep a single `writeTo()` stream open across many `write()` calls, and
    callers such as `FileMetadataScanner.copyHashes` read in between. Make each `write()` (and `close()`) call
    `data.writeTo(<tracked position>)` again instead of reusing the stream. Add a test that interleaves reads of the
    same `BackupFile` with writer calls and checks the result.
  - Note: a stream that is already open when a write happens can still return the old bytes (it buffers ahead).
    Open new streams after writing rather than reusing ones from before. Reusing closed streams after a write was a
    SiggiTools bug; it's fixed in `1.1-SNAPSHOT` `a0db869`, which the build uses.

- [x] **Fix `SimpleAuthorizer` token parsing and add auth tests**
  - Done: `90c9167` (2026-11-12). Fixed the fall-through switch; token files are read as UTF-8 with blank lines, `#`
    comments and unknown keys ignored and keys/values trimmed. `checkPermission` treats `prefix:*` as granting any
    `prefix:<x>` (so `read:*`/`upload:*` work; there is no bare `*`). Tests: `serverlib` `SimpleAuthorizerTest`.
  - In `serverlib/.../auth/simple/SimpleAuthorizer.java`, the `switch` in `readTokenFile` has no `break`s, so an
    `id=` line also sets the name and adds a permission. Fix it.
  - Ignore blank lines and lines starting with `#`.
  - Add a wildcard check to `SimpleAuthorization.checkPermission`, so that `read:*` grants `read:anything` (see the
    **Permissions** decision).
  - Add tests covering token lookup (the file is named after the SHA-256 of the token), parsing, and wildcard and
    exact permission checks.

- [x] **Add round-trip tests for the backup format**
  - Done: `13a1ae6` (2026-11-12). Added `BackupFormatRoundTripTest`, `util/DiffMakerTest` and
    `scanner/FileMetadataScannerTest`, with shared `testutil/TreeBuilder` and `testutil/TreeAssert` (deep tree
    comparison for any `DirectoryEntryDirectory`). Fixed `ExtraData.deserialize` silently dropping unknown extra data
    types (it now returns `ExtraDataUnsupported`, which gained `equals`/`hashCode`). No other bugs showed up; one
    hazard is added as the next step.
  - Tests in `common` for:
    - `BackupFile.writeToFile` then `BackupFile.open`, covering a tree with nested directories, files with
      `ContentIDFileContent`, `InlinedFileContent` and `ZeroFilledFileContent`, symlinks, an empty directory, and
      extra data (POSIX permissions, nanosecond modified date, file path, snapshot ID).
    - `DiffMaker.diff` with `DirectoryEntryDirectoryMerge`: merging a diff onto its base gives back the target tree,
      including deletions (`DirectoryEntryNull`).
    - `FileMetadataScanner` on a temp folder: correct counts and hashes, `.nobackup` excludes a directory, the path
      predicate excludes paths, and `copyHashes` skips re-hashing unchanged files.
  - Fix any bugs the tests uncover, in this step if they're small; otherwise add them as new plan steps.

- [x] **Stop `BackupFile.writeToFile` from changing the tree it writes**
  - Done: `bc20078` (2026-11-12). `Serialization` tracks placeholder positions locally; `serializeDirectoryEntry` now
    returns its placeholder position (or -1) instead of setting it on the entry, also when used by `updateEntries`.
    `DirectoryEntryDirectoryMerge` already wrote copies, so merges weren't affected; writing an opened tree directly
    was. Tests in `BackupFileTest` (opened tree nested under a new root, and a merge of two opened files).
  - `Serialization.serializeDirectory`/`serializeDirectoryEntry` call `setFileContentOffsetOffset`,
    `setFileContentOffset` and `setDirectoryOffsetOffset` on the entries being written. When the tree comes from an
    open `BackupFile` (for example writing a `DirectoryEntryDirectoryMerge` of two opened manifests, which the server
    will do), those entries then point at offsets in the *new* file: their contents read wrong once the cached
    lists are dropped, and a later in-place `updateEntries`/`updateFileContents` on a writable source patches the wrong
    place. Track the offsets locally during serialization instead, and add a test that writes an opened
    `BackupFile`'s tree to a new file and then reads and updates the original.

## Phase 1: Server

- [ ] **Make `DataBackupServer` testable, and add a server test harness**
  - `DataBackupServer`:
    - Allow port 0 and expose the bound port.
    - Make `start()` and `shutdown()` public; `shutdown()` should wait for the listener thread to stop.
    - Accept an `Authorizer` (or token folder) through the constructor. `main` keeps today's defaults.
  - Add a reusable test harness in `server/src/test/java` that:
    - Creates a temp data folder and starts the server on port 0.
    - Writes token files with chosen permissions.
    - Offers small helpers for HTTP calls through `java.net.http.HttpClient`.
  - Add one smoke test: a request without a token is rejected.

- [ ] **Add proper HTTP error responses and permission checks**
  - In `RequestHandler`, replace every silent `return` with a response:
    - `401` when the token is missing or invalid.
    - `403` when the token lacks the permission.
    - `404` for an unknown route, machine, backup or path.
    - `405` for a wrong HTTP method.
    - `400` for a malformed request.
    - `409` for a conflict.
    - `500` for unexpected errors, logged on the server.
  - Use one helper for this, with a `text/plain` body.
  - Apply the permission model from **Decisions** consistently. Today `GET /backups/...` checks no permission at all,
    and `PUT` checks both `upload` and `upload:<machine>`.
  - Validate machine names (for example `[A-Za-z0-9._-]+`, not starting with `.`) and backup names, so no request
    can escape `backupsDir` (`..`, absolute paths, separators).
  - Add tests for each error case.

- [ ] **Rework manifest upload into upload, then commit**
  - `PUT /backups/:machine` receives a manifest, optionally gzipped. Fix the `NullPointerException` when there's no
    `Content-Encoding` header.
  - Re-serialize the manifest into canonical form (as today) and store it as pending under
    `backups/<machine>/.pending/<uuid>`. `BackupRepository.reload` already skips dot-files.
  - Reject manifests without an `ExtraDataSnapshotId`, and reject a UUID the machine already has (`409`).
  - Response: JSON `{"pendingId": "...", "missing": ["<contentId>", ...]}`. Fix the inverted check in
    `scanForMissingContent`, which reports content that exists instead of content that's missing. The `missing` list
    must not contain duplicates.
  - Add `POST /backups/:machine/.pending/:pendingId/commit`:
    - Re-check for missing content and return `409` with the missing list if anything is still absent.
    - Otherwise move the backup into place through `BackupRepository.add(file, time)` and return the final backup
      name.
    - `touch()` every referenced `StoredData`, so garbage collection's grace period protects it.
  - Wire `BackupRepositories` into `DataBackupServer` and use it for all backup lookups, so the server no longer
    reads `backupsDir` directly.
  - Add tests: upload with content missing, then upload the content, then commit, then the backup is listed; commit
    with content still missing gives `409`; a duplicate UUID gives `409`; gzipped and plain uploads both work.

- [ ] **Fix content upload**
  - In `ContentReceiver.handle`, respond with JSON `{"stored": [...], "alreadyPresent": [...]}`. Today it builds
    `allItems` and never sends it.
  - When the content already exists, `touch()` it instead of reporting failure.
  - Record the multipart part's filename as a known filename (`addKnownFilename`), if one was given.
  - Requires `upload:<machine>` for at least one machine (or `upload:*`). Content is global and identified only by
    its hash, so it isn't tied to one machine.
  - Add tests: a multi-file upload, re-uploading existing content, and that the stored content's hash matches its ID.

- [ ] **Fix backup listing, browsing and download**
  - `GET /backups/:machine/` lists that machine's backups from `BackupRepository`; today it lists `backupsDir`.
    `GET /backups/` lists the machines the token can read.
  - Browsing: fix the loop in `RequestHandler` that drops the result of `getEntry(pathPieces[i])`, so paths really
    descend. URL-decode the path pieces. Return `404` for a path that doesn't exist.
  - File download:
    - Return `503`, or `500` with a message, when content is missing (this is the `TODO` at line 178).
    - Handle `InlinedFileContent` and `ZeroFilledFileContent`.
    - Set `Content-Length`.
  - In `returnStoredData`:
    - Don't crash when `getKnownFilenames()` is empty or the MIME lookup returns `null`.
    - Fix the `Content-Disposition` header (it should be `filename=`, not `name=`).
    - Keep the rule of never sending `text/html` for stored content.
  - Add `GET /backups/:machine/:backup?manifest` (or a similarly clear route) that returns the raw manifest file. The
    restore CLI needs it. Requires `read:<machine>`.
  - Add a JSON listing for directories, triggered by `Accept: application/json` or `?json`. The response is an array
    of entries with name, type, size, modified time, symlink target and permissions, for the CLI and web UI to use.
  - Add tests for each route.

- [ ] **Compress stored content when it helps**
  - When content is stored (in `ContentReceiver` and `DataRepository`/`StoredData.store`), try gzip and keep the
    compressed copy only if it saves at least 5% (make this configurable). Otherwise mark it `setDoNotCompress()`.
  - Skip the attempt for obviously already-compressed types, by known filename extension (jpg, png, mp4, zip, gz,
    etc.).
  - Downloads must still work for clients that don't accept gzip (`returnStoredData` already handles both cases).
    Test both.

- [ ] **Add server configuration and token management**
  - Server config file `server.json` in the data folder, read with Gson: bind address, port, retention defaults
    (used in Phase 5), garbage-collection grace period. Missing file means today's defaults. The
    `-Ddatabackupdir` property still selects the data folder.
  - Add CLI subcommands to the server's `main`:
    - `serve` (the default).
    - `token create --name <n> --permission <p>...`: prints a new random token once and writes
      `tokens/<sha256>.txt`.
    - `token list`.
    - `token revoke <id>`.
  - Tests for token create, list and revoke.

- [ ] **Write server API docs**
  - Write `docs/server-api.md` describing every endpoint, its method, required permission, request and response
    formats, and status codes, as they are now implemented.
  - The user's private `server-api.txt` is outdated; don't edit it.

## Phase 2: Backup client

- [ ] **Set up the client config and CLI skeleton**
  - In `client`, `DataBackupClient.main` dispatches subcommands (`backup`, `list`, `restore`, `snapshots cleanup`,
    `help`) through a small hand-written argument parser; don't add a CLI library.
  - Config file `client.json`, read with Gson:
    - `serverUrl`, `token` (or `tokenFile`), `machineName`.
    - `stateDir`.
    - `sources`: a list of `{name, path, excludes[]}`.
    - Global `excludes[]` (glob patterns matched against paths relative to the source).
  - Default config and state locations by OS:
    - macOS: `/Library/Application Support/DataBackup-client`
    - Linux: `/etc/databackup/client.json` and `/var/lib/databackup-client`
    - Windows: `C:\ProgramData\DataBackup-client`
    - Override with `--config`.
  - Validate the config with clear error messages. Tests for parsing and validation.

- [ ] **Add an HTTP API client for the server**
  - `ServerApi` class built on `java.net.http.HttpClient`, with:
    - `uploadManifest(machine, file)` (gzipped; returns the pending ID and missing list).
    - `uploadContent(List<Path or stream, contentId>)` (multipart, batched by count and size).
    - `commit(machine, pendingId)`.
    - `listMachines()`, `listBackups(machine)`, `downloadManifest(machine, backup)`, `listDirectory(...)`,
      `downloadFile(...)`.
  - Map HTTP errors to a typed exception carrying the status and message.
  - Retry transient failures (connection errors, 5xx) with backoff.
  - Tests against the server test harness. Add the `server` module as a test-scoped dependency of `client`, or move
    the harness into a shared test-jar.

- [ ] **Orchestrate snapshots in the client**
  - For each source, work out its filesystem and pick the matching `DiskUtil`:
    - macOS: `MountedDisk.getAll()`, `apfs`. The root `/` maps to `/System/Volumes/Data`, as `ApfsScanner` on
      `wip-fsscanners` does.
    - Linux: btrfs. A source can be any btrfs **subvolume**, not just a mount point: the test VM has `/` on btrfs and
      the source is a subvolume inside it, which `mount` doesn't list. Detect it with `btrfs subvolume show <path>`
      (needs root), and use the mount table only to confirm that the filesystem holding it is btrfs.
    - Windows: drive letter, NTFS. `MountedDisk.getAll()` throws on Windows, so add Windows support or detect drives
      another way.
  - If the filesystem doesn't support snapshots, or the source isn't a snapshottable volume or subvolume, fail with a
    clear error.
  - Create snapshots for all sources first, then scan.
  - Pass a destination folder under `stateDir` when `snapshotsRequireDestination()` is true (btrfs).
  - Before scanning, write each created snapshot to `stateDir/snapshots.json` using `SnapshotSerialization`. Delete
    the snapshots in a `finally` block and remove them from the file afterwards.
  - On startup and with `snapshots cleanup`, delete any snapshots left in `snapshots.json` by a crashed run.
  - Unit-test the source-to-`DiskUtil` mapping and the bookkeeping in `snapshots.json` with a fake `DiskUtil`.
  - Test with real snapshots on the test VMs (see "Test VMs" in `CLAUDE.md`): on `databackup-ubuntu`, detect, snapshot
    and clean up the test subvolume; on `databackup-win10`, the same for `C:`. Use a small throwaway `main` or test
    class copied over with the jars. Check that nothing is left behind (`btrfs subvolume list /`,
    `vssadmin list shadows`, keeping any shadow copies that existed before the run).

- [ ] **Build the manifest from the scan**
  - For each source, run `FileMetadataScanner` on the snapshot's mounted path:
    - Apply the source and global excludes through `setPathPredicate`. `.nobackup` is already honored.
    - Set `ExtraDataFilePath` to the original live path, not the snapshot path.
    - Add the snapshot-info extra data for that filesystem.
  - Windows VSS: the root of a shadow copy can't be listed with `File.listFiles()`. List the root from the live
    drive, but read every subdirectory and file from the shadow copy. The user's VSS notes describe this workaround.
    Verified on the test VM on 2026-11-12: `File.listFiles()` on `\\?\GLOBALROOT\Device\HarddiskVolumeShadowCopyN\`
    returns `null`, but `...\Users` lists fine. Plain NIO (`Paths.get`) rejects `\\?\` paths entirely, so the scanner
    depends on `WindowsPathHack` (reflection into JDK internals). Run the scanner against a real shadow copy on
    `databackup-win10` in this step to confirm `WindowsPathHack` works on Java 17.
  - Reuse hashes: if `stateDir/last-manifest` exists for this machine, call `copyHashes` from the matching source
    directory before `fillInHashes`, so unchanged files aren't re-hashed.
  - Assemble the root directory per **Decisions** (a new `ExtraDataSnapshotId` UUID, one child directory per source)
    and write it with `BackupFile.writeToFile` to `stateDir/pending-manifest`.
  - Print progress (files and bytes left to hash) at most once a second, and print a summary of failed and ignored
    items at the end.
  - Add a test-only entry point that builds a manifest from plain temp folders without snapshots. It must not be
    reachable from the CLI. Use it to test the layout and hash reuse.

- [ ] **(manual) Prepare an APFS snapshot smoke test for the user**
  - Agents can't run this: APFS snapshots need root, and `sudo` asks for a password. **Don't wait for the user and
    don't block the run.** Prepare everything, mark the step `- [~]`, and return ok so the workflow continues. No
    later step may depend on this one's result.
  - Write `docs/manual-checks/macos-apfs-smoke.sh`, a single script the user runs with `sudo` that:
    - Builds nothing itself; uses the jars or classes already built by the agent (say in the script header which
      `mvn` command to run first if they're missing).
    - Creates a small test folder on the Data volume, then, through the client's real snapshot and scan code (not
      shell commands), creates an APFS snapshot, mounts it, scans the test folder inside the snapshot into a manifest,
      unmounts it and deletes it.
    - Checks the manifest lists the test files with the right sizes and hashes, and that no snapshot or mount is left
      behind (`tmutil listlocalsnapshots /`, `mount`).
    - Also runs `snapshots cleanup` after a run killed partway through (start it in the background, `kill -9` it
      after the snapshot is created).
    - Prints a clear `PASS`/`FAIL` line per check and writes all output to
      `/Users/siggi/Desktop/java/DataBackup/target/manual-checks/macos-apfs-smoke.log` (`target` is gitignored), so
      an agent can read the results later.
    - Is safe to run more than once, and cleans up its test folder and any snapshot it created even when a check
      fails.
  - Check that the script at least parses (`bash -n`) and that every non-root part works (for example, run it
    without `sudo` and confirm it refuses with a clear message).
  - In the `- [~]` note, give the one command the user needs to run.
  - When the user reports back, or the log file exists: read the log, add fix steps right after this one for any
    `FAIL`, and mark this step `- [x]` only when everything passes.

- [ ] **Upload the backup and implement the `backup` command**
  - `databackup backup`:
    1. Clean up stale snapshots.
    2. Create snapshots and build the manifest.
    3. Upload the manifest.
    4. Upload the missing content, reading each file from the snapshot; a content ID can map to many paths, so read
       it once.
    5. Commit.
    6. On success, move `pending-manifest` to `last-manifest`.
    7. Delete the snapshots.
  - The snapshots must stay until the content upload finishes.
  - If a file changed or vanished between hashing and upload (its hash doesn't match), report it and fail the backup
    rather than uploading the wrong content. This shouldn't happen when reading from a snapshot.
  - Exit codes: 0 for success, 1 for failure, 2 for a config error. Print a one-line summary: files, bytes uploaded,
    duration, backup name.
  - Add an end-to-end test through the test-only scan path: temp source folder, then the server harness, then
    `backup`, then the backup is listed and its files download with the right bytes. Run a second backup after
    changing one file and check that only that file's content is uploaded.

- [ ] **Add packaging and scheduling examples**
  - Use `maven-shade-plugin` to build runnable jars for `server` and `client`, with `Main-Class` set
    (`dependency-reduced-pom.xml` is already gitignored).
  - Add `docs/scheduling/` with:
    - A launchd plist (macOS, runs as root, daily).
    - A systemd `.service` and `.timer` (Linux).
    - A Task Scheduler XML or `schtasks` command (Windows, runs as SYSTEM with highest privileges, which VSS needs).
    - A systemd unit and launchd plist for running the server.
  - Done when `mvn -o -q package` produces the jars and `java -jar client.jar help` runs.

## Phase 3: Restore

- [ ] **Add the `list` command**
  - `databackup list` lists the machines the token can read. `databackup list <machine>` lists that machine's
    backups (name and time). `databackup list <machine> <backup> [path]` lists a directory inside a backup (type,
    size, modified time, name).
  - `latest` is accepted as a backup name.
  - Uses the JSON endpoints from Phase 1. Tests against the harness.

- [ ] **Add the `restore` command**
  - `databackup restore <machine> <backup> <path-in-backup> <destination> [--overwrite]` downloads the manifest
    once, then restores the file or directory tree under it:
    - Content comes from the per-machine file download route (needs `read:<machine>`, not `globalread`).
    - Recreate directories, files, symlinks and empty files.
    - Inlined and zero-filled content is written locally, without downloading.
    - Restore the modified time (with nanoseconds when `ExtraDataNanosecondModifiedDate` is present). Restore POSIX
      mode bits; restore uid and gid only when running as root. On Windows, skip the POSIX metadata.
    - Download content shared by several files once and copy it locally.
  - Refuse to overwrite existing files unless `--overwrite` is given. Never follow a symlink outside the
    destination while restoring.
  - Verify the SHA-256 of each restored file against its content ID.
  - Add a round-trip test: back up a temp tree through the test-only path, restore it to another folder, then
    compare contents, symlinks, modified times and modes.

## Phase 4: Web UI

- [ ] **Add the read-only web UI**
  - Server-rendered HTML under `/ui/`:
    - A login page that takes a token and sets an `HttpOnly`, `SameSite=Strict` `token` cookie (plus `Secure` when
      the request came through HTTPS).
    - A machine list, a backup list per machine, a directory browser with breadcrumbs, size and modified time, and
      download links to the existing file route.
    - A logout link.
  - Only show machines and backups the token can read.
  - Escape every name shown on the page. Send `Content-Security-Policy: default-src 'self'` and
    `X-Content-Type-Options: nosniff`. Don't use JavaScript unless it's needed. Inline CSS is fine, kept small.
  - The UI's own pages may use `text/html`. Downloaded stored content must keep the existing rule of never being
    served as `text/html`.
  - Add tests: login sets the cookie; pages list only what the token can read; a file named
    `<script>alert(1)</script>` shows up escaped.

## Phase 5: Retention and garbage collection

- [ ] **Add a retention policy and pruning**
  - Policy in `server.json`: a default plus optional per-machine overrides. Settings: keep the last N backups, plus
    keep-daily D, keep-weekly W and keep-monthly M (keep the newest backup in each period, working back from the
    latest).
  - Add a pruning function that works out which backups to delete for a machine, and test it thoroughly as a pure
    function over timestamps.
  - Apply it in `cleanup()` (the hourly `cleanupThread`). Deleting a backup removes its manifest and reloads
    `BackupRepository`. Never delete the newest backup.
  - Add `server prune [--dry-run]` to the server CLI.

- [ ] **Add garbage collection for content and temp files**
  - Mark and sweep:
    - Mark every content ID referenced by any committed or pending manifest of any machine.
    - Then go through `DataRepository.openStream()` and delete unreferenced content whose `getLastTouch()` is older
      than the grace period (default 48 hours, from `server.json`), so content uploaded for an in-progress backup is
      kept.
  - Also delete pending manifests older than the grace period, and stale files in `tmp/`.
  - Run garbage collection after pruning in `cleanup()`, and add `server gc [--dry-run]`. Log a summary: items and
    bytes freed.
  - Make sure it can't run while a commit is in progress (use a lock shared with the commit path).
  - Tests: referenced content survives; unreferenced old content is deleted; unreferenced new content survives; a
    pending manifest's content survives.

## Phase 6: Real-platform checks

- [ ] **(manual) Prepare the macOS end-to-end check for the user**
  - **Don't wait for the user and don't block the run.** Prepare everything, mark the step `- [~]`, and return ok so
    the workflow continues. No later step may depend on this one's result.
  - Build the jars, then write `docs/manual-checks/macos-e2e.sh`, a single script the user runs with `sudo` that:
    - Starts a server on a temp data folder on this Mac and writes a `client.json` with a small test source on the
      Data volume (with a symlink, an empty file, odd permissions and a non-ASCII name).
    - Runs `backup`, then `list`, then `restore` to a temp folder, then compares with `diff -r` plus permissions and
      modified times.
    - Changes one file, backs up again, and checks that only that file's content was uploaded.
    - Checks the APFS snapshot is gone afterwards (`tmutil listlocalsnapshots /` or `diskutil apfs listSnapshots`),
      and that `snapshots cleanup` cleans up after a backup killed with `kill -9`.
    - Prints `PASS`/`FAIL` per check, writes all output to
      `/Users/siggi/Desktop/java/DataBackup/target/manual-checks/macos-e2e.log`, stops the server, and cleans up
      even when a check fails. It must be safe to run more than once.
  - Check that the script parses and that it refuses to run without `sudo`. In the `- [~]` note, give the one command
    the user needs to run.
  - When the user reports back, or the log file exists: read the log, add fix steps right after this one for any
    `FAIL`, and mark this step `- [x]` only when everything passes.

- [ ] **Run an end-to-end check on Linux with btrfs (test VM)**
  - Use `databackup-ubuntu` (see "Test VMs" in `CLAUDE.md`). Revert it first.
  - Run the server on this Mac and copy the client jar and a `client.json` to the VM, pointing at the Mac. The source
    is the test subvolume; put a few files in it first, including a symlink, an empty file, odd permissions and a
    file with a non-ASCII name.
  - Run `backup` as root, then `list`, then `restore` to a temp folder, then compare with `diff -r` plus permissions
    and modified times.
  - Change a file and back up again; check that only the changed content is uploaded.
  - Check that the snapshot destination is on the same btrfs filesystem, and that no snapshots are left afterwards
    (`btrfs subvolume list /`). Kill a backup partway through (`kill -9`), then check that `snapshots cleanup`
    removes what it left behind.
  - Fix whatever fails, in this step if small, otherwise as new steps right after this one. Stop the VM when done.

- [ ] **Run an end-to-end check on Windows with NTFS VSS (test VM)**
  - Use `databackup-win10` (see "Test VMs" in `CLAUDE.md`). Revert it first. The SSH session is elevated.
  - The same flow as the Linux step: the server on this Mac, the client on the VM, backing up a test folder on `C:`.
    Also check:
    - The root-listing workaround works.
    - The shadow copy is deleted afterwards (`vssadmin list shadows`); shadow copies that existed before the run must
      be left alone.
    - Restore works without POSIX metadata.
  - Fix whatever fails, in this step if small, otherwise as new steps right after this one. Stop the VM when done.

- [ ] **Replace `wmic` for creating shadow copies**
  - `NtfsDiskUtil` creates shadow copies with `wmic shadowcopy call create`. `wmic` is deprecated and removed from
    newer Windows 11 builds. Switch to PowerShell's CIM (`Invoke-CimMethod -ClassName Win32_ShadowCopy -MethodName
    Create`) or another supported way, keeping `vssadmin` (or CIM) for listing and deleting.
  - Don't depend on English output where it can be avoided; CIM returns structured data.
  - Verify on `databackup-win10`: create, list, mount path and delete all work, then rerun the Windows end-to-end
    backup.

## Phase 7: Finishing

- [ ] **Write the README and user docs**
  - `README.md`: what DataBackup is, the architecture (client, server, content store, manifests), building, server
    setup (data folder, `server.json`, tokens, HTTPS through a reverse proxy), client setup per OS (`client.json`,
    privileges, scheduling from `docs/scheduling/`), backup, list, restore, the web UI, retention and garbage
    collection.
  - Check that every command shown in the docs works as written.

- [ ] **Do a final review and clean up**
  - Run a correctness review over the whole `main` branch: `/code-review` at `high`, or an equivalent careful
    review.
  - Fix confirmed bugs, or add them as new steps above this one.
  - Remove dead code, such as unused imports and any leftover unused classes from `util/compression` versus
    `serverlib/compression`, keeping one compression abstraction.
  - Check that `mvn -o -q verify` passes cleanly.
  - Ask the user whether to delete the old branches (`main-old`, `main-old-old`, `wip-fsscanners`); never delete
    them without asking.
