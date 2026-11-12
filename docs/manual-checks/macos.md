# Manual checks on macOS

APFS snapshots need root, so these checks are run by hand on a Mac. Each one is a single script that prints a
`PASS` or `FAIL` line per check, ends with a `RESULT:` line, and writes everything to a log under
`target/manual-checks/` in the repository, where an agent can read the results later.

## APFS snapshot smoke test

Checks that the client's real snapshot code can snapshot the Data volume, mount the snapshot read-only, scan a test
folder inside it into a manifest with the right sizes and SHA-256 hashes (including a file changed after the snapshot
was taken), and delete the snapshot without leaving anything behind. It also kills a run with `kill -9` while its
snapshot is mounted and checks that `databackup snapshots cleanup` removes the leftover.

1. Build, as your normal user (not with `sudo`), from the repository root:

   ```
   mvn -o -q test-compile
   mvn -o -q org.apache.maven.plugins:maven-dependency-plugin:3.6.0:build-classpath -pl common \
       -Dmdep.includeScope=runtime -Dmdep.outputFile="$PWD/target/manual-checks/classpath.txt"
   ```

   Drop `-o` if Maven says a dependency isn't cached.

2. Run the check:

   ```
   sudo docs/manual-checks/macos-apfs-smoke.sh
   ```

   It takes under a minute. It works in `/private/var/tmp/databackup-apfs-smoke` and removes that folder, and any
   snapshot it created, when it finishes, fails or is interrupted. Running it again is safe.

3. Report back the last line (`RESULT: PASS ...` or `RESULT: FAIL ...`), or just say it has run: the full output is
   in `target/manual-checks/macos-apfs-smoke.log`.

If a check fails with "Operation not permitted", give your terminal app Full Disk Access (System Settings, Privacy &
Security, Full Disk Access) and run it again.

The script drives the client through the test-only helper
`client/src/test/java/io/siggi/databackup/client/ApfsSmokeCheck.java`, which uses the same snapshot orchestration as
`databackup backup` but scans only the test folder instead of the whole Data volume. The cleanup step uses the real
CLI.

## End-to-end check

Runs the real server and client jars together on this Mac: backup, list and restore of a small test folder through
a real APFS snapshot, a second backup that must upload only the one changed file, and `snapshots cleanup` after a
backup killed with `kill -9`.

1. Build the jars, as your normal user (not with `sudo`), from the repository root:

   ```
   mvn -o -q package
   ```

   Drop `-o` if Maven says a dependency isn't cached. This makes `server/target/databackup-server.jar` and
   `client/target/databackup-client.jar`.

2. Run the check:

   ```
   sudo docs/manual-checks/macos-e2e.sh
   ```

   It takes about a minute, most of it the interrupted backup's upload retries. It:

   - starts a server on `127.0.0.1` (the first free port from 28080) with its data folder in
     `/private/var/tmp/databackup-e2e`, and creates a token for the machine `e2e-mac`;
   - creates the test folder `/System/Volumes/Data/DataBackup-e2e-test` with a symlink, a dangling symlink, an
     empty file, an empty folder, odd permissions (0600, 0444, 2755, 0700, 1777, one file owned by you instead of
     root), non-ASCII names, duplicate content and a few MiB of random data;
   - backs up the Data volume (source `/`) with every other top-level entry of the Data volume excluded, so only the
     test folder is read and uploaded;
   - runs `list`, restores the backup to a temp folder and compares it with the test folder (`diff -r`, plus type,
     permissions, owner, size, modified time to the microsecond, and symlink targets; nanosecond and symlink times
     are reported as `INFO` lines only);
   - changes one file, backs up again, checks that only that file was uploaded, restores the second backup and
     compares again, and restores the changed file from the first backup to check its old content;
   - checks after each backup that no APFS snapshot or snapshot mount is left behind;
   - starts a backup pointed at a port nothing listens on (it retries the upload while holding its snapshot), kills
     it with `kill -9`, checks the snapshot was left behind, and checks that `databackup snapshots cleanup` removes it.

   It stops the server and removes the test folder, the temp folder and any snapshot it left, when it finishes,
   fails or is interrupted. Running it again is safe.

3. Report back the last line (`RESULT: PASS ...` or `RESULT: FAIL ...`), or just say it has run: the full output is
   in `target/manual-checks/macos-e2e.log`.

If Time Machine takes its own local snapshot during the run, a "no new APFS snapshot is left" check can fail
because of it; the log names the snapshot, and running the check again should pass. If a check fails with
"Operation not permitted", give your terminal app Full Disk Access (System Settings, Privacy & Security, Full Disk
Access) and run it again.
