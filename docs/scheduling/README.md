# Packaging and scheduling

The DataBackup client is a one-shot command: each `databackup backup` takes snapshots, backs them up and exits. There
is no client daemon; the operating system's scheduler starts it. The server is a long-running service.

## Building the jars

From the repository root:

```
mvn -o -q package
```

(Drop `-o` if Maven says a dependency isn't cached.) This builds two runnable jars with every dependency included:

- `server/target/databackup-server.jar`
- `client/target/databackup-client.jar`

Both need Java 17 or newer. Check them with `java -jar client/target/databackup-client.jar help` and
`java -jar server/target/databackup-server.jar help`.

## Where the examples expect things

| | Client jar | Client config | Server jar | Server data folder |
|---|---|---|---|---|
| macOS | `/Library/Application Support/DataBackup-client/databackup-client.jar` | `/Library/Application Support/DataBackup-client/client.json` | `/Library/Application Support/DataBackup/databackup-server.jar` | `/Library/Application Support/DataBackup` |
| Linux | `/opt/databackup/databackup-client.jar` | `/etc/databackup/client.json` | `/opt/databackup/databackup-server.jar` | `/var/lib/databackup` |
| Windows | `C:\ProgramData\DataBackup-client\databackup-client.jar` | `C:\ProgramData\DataBackup-client\client.json` | | |

The client config locations are the client's defaults, so the scheduled commands don't pass `--config`. If you put
the config elsewhere, add `--config <file>` before `backup`. The client's config holds the server token, so make it
readable only by root (or SYSTEM and Administrators on Windows).

Before scheduling anything, run one backup by hand as root (`sudo java -jar <client jar> backup`, or from an elevated
prompt on Windows) and check it ends with `Backup ... complete`.

## Client

The client must run with full privileges: as root on macOS and Linux (APFS and btrfs snapshots need it) and as
SYSTEM with highest privileges on Windows (VSS shadow copies need it). All three examples run once a day around
03:00 and catch up after the machine was off or asleep. A run that fails exits with a non-zero code and is simply
tried again the next day; `snapshots cleanup` runs automatically at the start of every backup.

### macOS: launchd

[`io.siggi.databackup.client.plist`](io.siggi.databackup.client.plist) is a LaunchDaemon (root). Its header has the
install commands. Create the log folder first with `sudo mkdir -p /Library/Logs/DataBackup`. Output goes to
`/Library/Logs/DataBackup/client.log`. Start a run now with
`sudo launchctl kickstart system/io.siggi.databackup.client`.

The plist runs `/usr/bin/java`, which works with any installed JDK. macOS privacy protection may stop a background
process from reading some folders (Mail, Messages, Desktop and so on); if the log shows "Operation not permitted",
give the Java binary Full Disk Access in System Settings, Privacy & Security, Full Disk Access.

### Linux: systemd

[`databackup-client.service`](databackup-client.service) runs one backup as root, and
[`databackup-client.timer`](databackup-client.timer) starts it daily (03:00 plus a random delay of up to 30
minutes). Install them with:

```
sudo cp databackup-client.service databackup-client.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now databackup-client.timer
```

Run a backup now with `sudo systemctl start databackup-client.service`, see the next run with
`systemctl list-timers databackup-client.timer`, and read the output with `journalctl -u databackup-client.service`.

### Windows: Task Scheduler

[`databackup-client-task.xml`](databackup-client-task.xml) is a task that runs as SYSTEM with highest privileges.
Import it from an elevated Command Prompt or PowerShell:

```
schtasks /Create /TN "DataBackup client" /XML databackup-client-task.xml
```

Or create the same task without the XML (it lacks the XML's random delay, catch-up after downtime and 12-hour time
limit):

```
schtasks /Create /TN "DataBackup client" /RU SYSTEM /RL HIGHEST /SC DAILY /ST 03:00 /TR "cmd.exe /c java.exe -jar C:\ProgramData\DataBackup-client\databackup-client.jar backup >> C:\ProgramData\DataBackup-client\client.log 2>&1"
```

Both expect `java.exe` on the system `PATH` (Java installers add it) and append output to
`C:\ProgramData\DataBackup-client\client.log`. Start a run now with `schtasks /Run /TN "DataBackup client"`, and
check the result with `schtasks /Query /TN "DataBackup client" /V /FO LIST` (`Last Result: 0` means success).

## Server

The server doesn't need root. It reads `server.json` and its tokens from the data folder given with
`-Ddatabackupdir`. Create tokens by running the server jar with the same data folder, as the user the server runs
as, for example on Linux:

```
sudo -u databackup java -Ddatabackupdir=/var/lib/databackup -jar /opt/databackup/databackup-server.jar \
    token create --name laptop --permission upload:laptop read:laptop
```

The server speaks plain HTTP. Run it on a trusted network, or behind a reverse proxy that adds HTTPS.

### Linux: systemd

[`databackup-server.service`](databackup-server.service) runs the server as the system user `databackup`, and
systemd creates `/var/lib/databackup` for it. Its header has the install commands. Read its output with
`journalctl -u databackup-server.service`.

### macOS: launchd

[`io.siggi.databackup.server.plist`](io.siggi.databackup.server.plist) starts the server at boot and restarts it if
it exits. Its header has the install commands; create `/Library/Logs/DataBackup` first. It runs as root because
macOS has no ready-made service account for it; change `UserName` to an account that owns the data folder if you
prefer.

## What was checked

On 2026-11-12, on an Ubuntu VM with btrfs: `systemd-analyze verify` accepts the three units, the server unit starts
the server as `databackup` in `/var/lib/databackup`, and the client service ran a real backup to it. On a Windows 10
VM: both the XML import and the `schtasks` command ran a real VSS backup as SYSTEM. The two plists pass
`plutil -lint`; they haven't been loaded into launchd yet.
