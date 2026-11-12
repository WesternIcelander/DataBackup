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
