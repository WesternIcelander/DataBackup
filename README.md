# DataBackup

DataBackup backs up whole machines to a server you run yourself. Every backup is taken from a filesystem snapshot
(APFS on macOS, btrfs on Linux, NTFS shadow copies on Windows), so it is consistent even while files are in use. File
content is stored once per unique SHA-256, shared between all backups and machines, so a backup only uploads what the
server doesn't have yet.

- **Client:** a one-shot command line tool (`backup`, `list`, `restore`). The operating system's scheduler runs it;
  there is no client daemon.
- **Server:** a small HTTP server that stores backups, prunes old ones by a retention policy, removes content no
  backup needs any more, and has a read-only web UI for browsing backups and downloading files.

Contents:

- [How it works](#how-it-works)
- [Building](#building)
- [Setting up the server](#setting-up-the-server)
- [Setting up a client](#setting-up-a-client)
- [Backing up](#backing-up)
- [Listing and restoring](#listing-and-restoring)
- [The web UI](#the-web-ui)
- [Retention and garbage collection](#retention-and-garbage-collection)
- [Further documentation](#further-documentation)

## How it works

```
 client machine                                          server
┌────────────────────────────────────┐   HTTP(S)   ┌─────────────────────────────────┐
│ databackup backup                  │             │ databackup-server.jar           │
│  1. snapshot every source          │  manifest   │  backups/<machine>/<backup>     │
│  2. scan the snapshots, hash files ├────────────►│  repository/  (content by hash) │
│  3. upload manifest, then content  │  content    │  tokens/      (access tokens)   │
│  4. commit, delete the snapshots   ├────────────►│  server.json  (settings)        │
└────────────────────────────────────┘             └─────────────────────────────────┘
```

- **Sources.** The client backs up the *sources* listed in its config. A source is a whole snapshottable volume: an
  APFS volume on macOS, a btrfs subvolume on Linux, an NTFS drive such as `C:\` on Windows. Parts of a source that
  you don't want are left out with exclude patterns. Sources on any other filesystem are refused with an error;
  there is no fallback that copies live files.
- **Manifests.** A backup is one *manifest* for one machine, from one client run: a compact binary file holding the
  whole directory tree with names, sizes, modified times, POSIX permissions and owners, symlink targets, and the
  content ID of each file. The manifest's root has one folder per source, named after the source.
- **Content store.** File data is stored separately in the server's content store, keyed by its SHA-256 (the
  *content ID*), and gzip-compressed when that saves enough space. Identical files, whether in the same backup,
  in later backups or on other machines, are stored once. The client remembers the last manifest it uploaded, so it
  doesn't even re-hash files whose size and modified time haven't changed.
- **Upload protocol.** The client uploads the manifest first; the server stores it as *pending* and answers with
  the content IDs it is missing. The client uploads those, then *commits*. Only committed backups are listed, so an
  interrupted run never shows up as a half-finished backup.
- **Security.** The server is trusted: there's no client-side encryption. Access is controlled with tokens holding
  permissions per machine. The server speaks plain HTTP; use it behind an HTTPS reverse proxy or only on a trusted
  network (see [HTTPS](#https)).

## Building

You need Java 17 or newer and Maven. DataBackup also uses four of the author's libraries that aren't on Maven
Central: `io.siggi:SiggiTools:1.1-SNAPSHOT`, `io.siggi:SiggiHTTP:1.10`, `io.siggi:Stat:1.0` and
`io.siggi:ReflectionFreedom:1.0`. Install them into your local Maven repository (`mvn install` in each project)
first.

From the repository root:

```
mvn -q package
```

This runs the tests and builds two runnable jars that include all their dependencies:

- `server/target/databackup-server.jar`
- `client/target/databackup-client.jar`

Check them with:

```
java -jar server/target/databackup-server.jar help
java -jar client/target/databackup-client.jar help
```

The examples below call the client `databackup`; that stands for `java -jar <path to>/databackup-client.jar`. Use a
shell alias or a small wrapper script if you like.

## Setting up the server

### The data folder

The server keeps everything in one data folder:

| Path            | What it holds                                                                     |
|-----------------|-----------------------------------------------------------------------------------|
| `server.json`   | Settings (optional).                                                              |
| `tokens/`       | One file per access token, named after the token's SHA-256.                       |
| `backups/`      | One folder per machine, holding its committed manifests and a `.pending/` folder. |
| `repository/`   | The content store.                                                                |
| `tmp/`          | Uploads in progress.                                                              |
| `server.lock`   | Keeps a second server (or `gc`) off the same folder while the server runs.        |

Choose the folder with `-Ddatabackupdir=<folder>` before `-jar`. Without it the server uses `./databackup` if that
folder exists, and otherwise the OS default: `/var/lib/databackup` on Linux, `/Library/Application Support/DataBackup`
on macOS, `C:\ProgramData\DataBackup` on Windows. Pass the same `-Ddatabackupdir` to every server command
(`serve`, `token ...`, `prune`, `gc`), and run them as the user the server runs as. The server itself doesn't need
root.

### `server.json`

Every setting is optional; a missing file or key means the default. This example shows every key with its default
value, except `bindAddress` (default: all interfaces) and `machineRetention` (default: none):

```json
{
  "bindAddress": "127.0.0.1",
  "port": 8080,
  "retention": {"keepLast": 10, "keepDaily": 14, "keepWeekly": 8, "keepMonthly": 24},
  "machineRetention": {"laptop": {"keepLast": 30}},
  "gcGracePeriodHours": 48,
  "minCompressionSavings": 0.05
}
```

| Key                     | Meaning                                                                                       |
|-------------------------|-----------------------------------------------------------------------------------------------|
| `bindAddress`           | The address to listen on. Leave it out to listen on all interfaces; use `127.0.0.1` behind a local reverse proxy. |
| `port`                  | The TCP port.                                                                                  |
| `retention`             | Which backups to keep; see [Retention](#retention).                                           |
| `machineRetention`      | Per-machine overrides of `retention`, by machine name. Keys left out fall back to `retention`. |
| `gcGracePeriodHours`    | How long unreferenced content and unfinished uploads are kept; see [Garbage collection](#garbage-collection). |
| `minCompressionSavings` | Content is stored gzip-compressed only if that saves at least this fraction (0.05 is 5%).     |

The server reads `server.json` at startup; restart it after a change. An invalid value stops it from starting, with
a message naming the key.

### Tokens and permissions

Every request needs a token. Create one per client machine, plus one for each person who restores or browses
backups. A token holds permissions:

| Permission         | Grants                                                                              |
|--------------------|-------------------------------------------------------------------------------------|
| `upload:<machine>` | Upload backups for that machine.                                                    |
| `read:<machine>`   | List, browse, download and restore that machine's backups.                          |
| `upload:*`, `read:*` | The same for every machine.                                                       |
| `globalread`       | Download any stored content by its content ID, outside any backup.                  |
| `admin`            | Reserved; no route uses it yet.                                                     |

A client that should back up and also restore its own files needs both `upload:` and `read:` for its machine name:

```
java -Ddatabackupdir=/var/lib/databackup -jar databackup-server.jar \
    token create --name laptop --permission upload:laptop read:laptop
```

The token is printed once, on standard output (the message around it goes to standard error), and only its SHA-256
is stored, so copy it now. Then:

```
java -Ddatabackupdir=/var/lib/databackup -jar databackup-server.jar token list
java -Ddatabackupdir=/var/lib/databackup -jar databackup-server.jar token revoke <id>
```

`token list` shows each token's ID, name, creation time and permissions. Token changes take effect immediately, also
while the server is running.

### Running the server

```
java -Ddatabackupdir=/var/lib/databackup -jar databackup-server.jar
```

(`serve` is the default command.) It logs `DataBackup server listening on port 8080, data folder ...` and runs until
it is stopped. To run it as a service, use the systemd unit or launchd plist in [`docs/scheduling/`](docs/scheduling/);
the [scheduling README](docs/scheduling/README.md) has the install steps.

### HTTPS

The server speaks plain HTTP, and the client does no encryption of its own, so tokens and file contents travel as
they are. Either use the server only on a network you trust, or put it behind a reverse proxy that adds HTTPS and
set `bindAddress` to `127.0.0.1` so the plain-HTTP port isn't reachable from elsewhere. For example, with Caddy:

```
backup.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

or nginx:

```
server {
    listen 443 ssl;
    server_name backup.example.com;
    # ssl_certificate ... and ssl_certificate_key ...

    client_max_body_size 0;          # manifests and content uploads can be large
    proxy_request_buffering off;
    proxy_buffering off;
    proxy_read_timeout 1h;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
    }
}
```

These two are starting points rather than tested configurations. Whatever proxy you use, it must pass request
bodies of any size and allow long requests. Sending `X-Forwarded-Proto: https` makes
the web UI's login cookie `Secure`. Clients then use `https://backup.example.com` as their `serverUrl`.

## Setting up a client

### Privileges

The client creates snapshots, which needs full privileges: run `backup` and `snapshots cleanup` as root on macOS
and Linux, and as SYSTEM or from an elevated prompt on Windows. `list` and `restore` don't need them, but the
default config file should be readable only by root, so either run them with `sudo` too, or give them a config of
your own with `--config` (see [Listing and restoring](#listing-and-restoring)). `restore` only restores file owners
when it runs as root.

### `client.json`

The client reads its config from `--config <file>`, or by default from:

| OS      | Default config file                                         | Default state folder                                  |
|---------|-------------------------------------------------------------|-------------------------------------------------------|
| macOS   | `/Library/Application Support/DataBackup-client/client.json` | `/Library/Application Support/DataBackup-client/state` |
| Linux   | `/etc/databackup/client.json`                               | `/var/lib/databackup-client`                          |
| Windows | `C:\ProgramData\DataBackup-client\client.json`               | `C:\ProgramData\DataBackup-client\state`               |

The config holds the token, so make it readable only by root (or SYSTEM and Administrators on Windows).

```json
{
  "serverUrl": "https://backup.example.com",
  "tokenFile": "token.txt",
  "machineName": "laptop",
  "sources": [
    {"name": "Data", "path": "/", "excludes": ["private/var/vm", "Users/*/Library/Caches"]}
  ],
  "excludes": ["**/.Trash", "**/node_modules"]
}
```

| Key           | Meaning                                                                                              |
|---------------|------------------------------------------------------------------------------------------------------|
| `serverUrl`   | The server's base URL, `http://` or `https://`.                                                      |
| `token`       | The token, inline. Set either `token` or `tokenFile`.                                                |
| `tokenFile`   | A file holding the token (surrounding whitespace is ignored). Relative paths are relative to the config file's folder. |
| `machineName` | This machine's name on the server: 1 to 128 letters, digits, `.`, `_` or `-`, not starting with `.`. |
| `stateDir`    | Optional. Where the client keeps its state; relative paths are relative to the config file's folder. |
| `sources`     | The volumes to back up, each with a `name` (its folder name in the backup, same rule as `machineName`, unique ignoring case), an absolute `path`, and optional `excludes`. |
| `excludes`    | Exclude patterns applied to every source.                                                           |

Unknown keys are rejected, so a typo doesn't go unnoticed. Any command checks the config first, so
`sudo databackup list` is a quick way to test a new config and token.

**Exclude patterns** are globs matched against the whole path relative to the source, with `/` as the separator on
every OS. `*` matches within one path piece, `**` across pieces, and `**/` matches any number of folders (including
none), so `**/node_modules` excludes every `node_modules` folder and `Users/*/Library/Caches` excludes each user's
caches. `?`, `[a-z]`, `[!a-z]` and `{a,b}` work too, and `\` makes the next character literal. An excluded folder is
skipped with everything in it. Independently of the config, a folder containing a file named `.nobackup` is never
backed up.

**The state folder** holds the last uploaded manifest (for skipping unchanged files), the list of snapshots a run
has created (so `snapshots cleanup` can remove them after a crash), a lock file that stops two runs overlapping, and
on Linux the btrfs snapshots themselves.

### Sources on each OS

- **macOS (APFS):** a source must be the mount point of an APFS volume. Use `/` for the startup disk: the client
  snapshots the Data volume (`/System/Volumes/Data`), which holds `Users`, `Applications`, `Library` and so on, so
  exclude patterns for that source are relative to the Data volume, for example `Users/me/Downloads`. The read-only
  system volume isn't backed up. Other APFS volumes are sources of their own (`/Volumes/Projects`).
- **Linux (btrfs):** a source must be a btrfs subvolume (the root of a btrfs filesystem, or one made with
  `btrfs subvolume create`); it doesn't have to be a mount point. Snapshots are created inside the state folder, so
  the state folder must be on the same btrfs filesystem as the sources.
- **Windows (NTFS):** a source must be a whole NTFS drive, written like `C:\` (in JSON: `"C:\\"`); leave out what
  you don't want with excludes, such as `Windows`, `pagefile.sys`, `hiberfil.sys` and `swapfile.sys`. Shadow copies
  are created and deleted through PowerShell's CIM cmdlets; shadow copies made by other programs are left alone.

On macOS, give the Java binary Full Disk Access (System Settings, Privacy & Security, Full Disk Access), or some
folders can't be read and the backup lists them as failed.

### Scheduling

Run the first backup by hand (see below). Then let the OS run it daily: [`docs/scheduling/`](docs/scheduling/) has a
launchd daemon for macOS, a systemd service and timer for Linux, and a Task Scheduler task for Windows, with install
steps in its [README](docs/scheduling/README.md). A failed run exits non-zero and is simply tried again next time.

## Backing up

```
sudo databackup backup
```

(on Windows, run it from an elevated prompt). One run:

1. Deletes snapshots an earlier, interrupted run left behind.
2. Snapshots every source.
3. Scans the snapshots and hashes new and changed files, printing progress at most once a second.
4. Uploads the manifest, then the content the server is missing, read from the snapshots.
5. Commits the backup, and deletes the snapshots.

It ends with a summary such as `Backup 2026-11-12-14-03-27 complete: 52341 files (12.3 GiB), 1.2 MiB uploaded,
took 41.7s.`, and lists any files it couldn't read (those are left out of the backup). Backups are named after the
time of the commit, in UTC.

Exit codes for every client command: `0` success, `1` failure, `2` usage or config error.

If a run was killed and left a snapshot behind, the next `backup` removes it; to do it right away, run:

```
sudo databackup snapshots cleanup
```

## Listing and restoring

```
databackup list                                  # machines this token can read
databackup list laptop                           # laptop's backups, oldest first
databackup list laptop latest                    # the top level of laptop's newest backup: one folder per source
databackup list laptop latest Data/Users/me      # a folder inside it
```

These read the default config, so on a machine where only root can read it, put `sudo` in front, or copy the
config somewhere you can read it (with a token file you can read, perhaps holding a token with only `read:`
permissions) and add `--config`, as in `databackup --config ~/databackup.json list laptop`. A config used only for
`list` and `restore` still needs a valid `sources` entry, but its sources aren't touched.

`latest` stands for the newest backup wherever a backup name is expected. A directory listing shows one entry per
line: type (`f` file, `d` directory, `l` symlink), permissions, size, modified time and name.

```
databackup restore <machine> <backup> <path-in-backup> <destination> [--overwrite]
```

- A directory's *contents* are restored into `<destination>`, which is created if needed and merged into if it
  exists. `databackup restore laptop latest Data/Users/me/Documents ~/Documents-restored` gives
  `~/Documents-restored/<the files in Documents>`.
- A file is restored to `<destination>`, or into it if `<destination>` is an existing directory.
- `/` as the path restores the whole backup, one folder per source.
- Nothing is written if any file in the way already exists, unless `--overwrite` is given. A non-empty directory is
  never replaced, and the restore never writes through a symlink.
- Each distinct piece of content is downloaded once and checked against its SHA-256. Modified times (to the
  nanosecond where the filesystem allows), POSIX permissions and, when running as root, owners are restored too.
  Windows file attributes (read-only, hidden) aren't backed up.

Restoring needs `read:<machine>` and can run on any machine: to restore a lost laptop's files onto a new one, use a
config there whose token can read the old machine.

## The web UI

Open `/ui/` on the server (for example `https://backup.example.com/ui/`) and log in with a token. You see the
machines that token can read, each machine's backups (newest first), and a folder browser with sizes, modified times
and permissions; files download on click. The UI is read-only and uses no JavaScript. Logging in sets an `HttpOnly`,
`SameSite=Strict` session cookie; log out with the link at the top of each page. For a person who only browses,
create a token with just `read:` permissions.

## Retention and garbage collection

### Retention

Every hour (and once at startup), the server *prunes* each machine's backups by its retention policy. A backup is
kept if any rule keeps it:

- `keepLast`: the newest N backups.
- `keepDaily`: the newest backup of each of the last N days that have backups.
- `keepWeekly`: the same per ISO week.
- `keepMonthly`: the same per calendar month.

Days, weeks and months use the server's time zone. Periods without backups don't count, so a machine that was off
for a month doesn't lose its older backups. The newest backup is never deleted. Set a rule to `0` to turn it off.

To see what the current policies would delete, or to prune right away, run:

```
java -Ddatabackupdir=/var/lib/databackup -jar databackup-server.jar prune --dry-run
java -Ddatabackupdir=/var/lib/databackup -jar databackup-server.jar prune
```

A running server doesn't see backups deleted by the `prune` command until it restarts, so stop the server before
pruning by hand, or leave pruning to the server.

### Garbage collection

Deleting a backup only deletes its manifest. Right after pruning, the server collects garbage:

- Content that no backup (and no pending upload) references is deleted once it hasn't been used for
  `gcGracePeriodHours` (48 by default). Uploading content or committing a backup that uses it counts as use, so
  content uploaded for a backup that is still running isn't removed.
- Pending uploads that were never committed, and leftover files in `tmp/`, are deleted once older than the grace
  period.

A backup run must finish within the grace period. To collect garbage by hand, stop the server first (`gc` refuses to
run while the server holds the data folder), then:

```
java -Ddatabackupdir=/var/lib/databackup -jar databackup-server.jar gc --dry-run
java -Ddatabackupdir=/var/lib/databackup -jar databackup-server.jar gc
```

## Further documentation

- [`docs/scheduling/`](docs/scheduling/README.md): running the client daily and the server as a service.
- [`docs/server-api.md`](docs/server-api.md): the server's HTTP API, for writing other clients or scripts.
- [`docs/manual-checks/`](docs/manual-checks/): the end-to-end check scripts used to test each platform.
