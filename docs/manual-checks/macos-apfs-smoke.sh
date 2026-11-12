#!/bin/bash
#
# APFS snapshot smoke test for the DataBackup client. Run it on a Mac, as root:
#
#   sudo docs/manual-checks/macos-apfs-smoke.sh
#
# It builds nothing. Build first, as your normal user (not with sudo), from the repository root:
#
#   mvn -o -q test-compile
#   mvn -o -q org.apache.maven.plugins:maven-dependency-plugin:3.6.0:build-classpath -pl common \
#       -Dmdep.includeScope=runtime -Dmdep.outputFile="$PWD/target/manual-checks/classpath.txt"
#
# (drop -o if Maven says a dependency isn't cached).
#
# What it does, through the client's real snapshot and scan code (SnapshotCommands, VolumeResolver,
# SnapshotManager, ApfsDiskUtil, ManifestBuilder), driven by the test-only helper
# client/src/test/java/io/siggi/databackup/client/ApfsSmokeCheck.java:
#
#   1. Creates a small test folder on the Data volume (/private/var/tmp/databackup-apfs-smoke/files).
#   2. Snapshots the Data volume (source "/"), mounts the snapshot, changes one live file after the snapshot was
#      taken, scans the test folder inside the snapshot into a manifest, then unmounts and deletes the snapshot.
#   3. Checks the manifest lists the test files with the right sizes and SHA-256 hashes (the changed file must have
#      its snapshot-time content), and that no snapshot or mount is left behind.
#   4. Starts another run, kill -9s it once its snapshot is mounted, checks the snapshot was left behind and
#      recorded, then runs the real CLI's "snapshots cleanup" and checks everything is gone.
#
# Every check prints a PASS or FAIL line. All output also goes to target/manual-checks/macos-apfs-smoke.log in the
# repository. The script is safe to run more than once, and removes its test folder and any snapshot it created
# even when a check fails or it is interrupted.
#
# Note: Time Machine local snapshots ("com.apple.TimeMachine.<date>.local") are how APFS snapshots are taken, so
# "tmutil listlocalsnapshots /" briefly shows one during the run. Snapshots that existed before the run are left
# alone. If a check fails with "Operation not permitted", give your terminal app Full Disk Access (System Settings,
# Privacy & Security) and run the script again.

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$REPO/target/manual-checks"
LOG="$OUT_DIR/macos-apfs-smoke.log"
CLASSPATH_FILE="$OUT_DIR/classpath.txt"
WORK="/private/var/tmp/databackup-apfs-smoke"
FILES="$WORK/files"
STATE="$WORK/state"
CONFIG="$WORK/client.json"
HELPER="io.siggi.databackup.client.ApfsSmokeCheck"
CLI="io.siggi.databackup.client.DataBackupClient"

refuse() {
    echo "ERROR: $*" >&2
    exit 2
}

# --- Prerequisites (checked before asking for root, so a plain run tells you what's missing) -------------------

[ "$(uname -s)" = "Darwin" ] || refuse "this check is for macOS only."

JAVA=""
if JAVA_HOME_17="$(/usr/libexec/java_home -v 17+ 2>/dev/null)" && [ -x "$JAVA_HOME_17/bin/java" ]; then
    JAVA="$JAVA_HOME_17/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA="$(command -v java)"
fi
[ -n "$JAVA" ] || refuse "no Java found. Install Java 17 or later."
JAVA_SPEC="$("$JAVA" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p')"
JAVA_MAJOR="${JAVA_SPEC%%.*}"
case "$JAVA_MAJOR" in
    ''|*[!0-9]*) refuse "can't tell which Java version $JAVA is." ;;
esac
[ "$JAVA_MAJOR" -ge 17 ] || refuse "$JAVA is Java $JAVA_SPEC; Java 17 or later is needed."

for needed in \
    "$REPO/common/target/classes/io/siggi/databackup/diskutil/apfs/ApfsDiskUtil.class" \
    "$REPO/client/target/classes/io/siggi/databackup/client/DataBackupClient.class" \
    "$REPO/client/target/test-classes/io/siggi/databackup/client/ApfsSmokeCheck.class" \
    "$CLASSPATH_FILE"; do
    [ -f "$needed" ] || refuse "$needed is missing. Build first (see the top of $0):
  mvn -o -q test-compile
  mvn -o -q org.apache.maven.plugins:maven-dependency-plugin:3.6.0:build-classpath -pl common -Dmdep.includeScope=runtime -Dmdep.outputFile=\"\$PWD/target/manual-checks/classpath.txt\""
done
CP="$REPO/client/target/test-classes:$REPO/client/target/classes:$REPO/common/target/classes:$(cat "$CLASSPATH_FILE")"

if [ "$(id -u)" -ne 0 ]; then
    refuse "this check creates and deletes APFS snapshots, which needs root. Run it with sudo:
  sudo $0"
fi

# --- Helpers ---------------------------------------------------------------------------------------------------

PASSES=0
FAILS=0
BACKUP_SNAPSHOT=""
HOLD_SNAPSHOT=""
HOLD_PID=""

pass() {
    PASSES=$((PASSES + 1))
    echo "PASS: $*"
}

fail() {
    FAILS=$((FAILS + 1))
    echo "FAIL: $*"
}

# check <description> <command...>: PASS if the command succeeds.
check() {
    local description="$1"
    shift
    if "$@"; then pass "$description"; else fail "$description"; fi
}

section() {
    echo
    echo "=== $* ==="
}

# Writable APFS volumes, as /dev/diskNsM.
apfs_volumes() {
    /sbin/mount | awk '/^\/dev\/disk[0-9]+s[0-9]+ on / && /\(apfs,/ && !/read-only/ { print $1 }' | sort -u
}

# "<device> <snapshot name>" for every snapshot on every writable APFS volume.
all_snapshots() {
    local dev
    for dev in $(apfs_volumes); do
        /usr/sbin/diskutil apfs listSnapshots "$dev" 2>/dev/null \
            | sed -n 's/^[| ]*Name: *//p' | while read -r name; do echo "$dev $name"; done
    done
}

snapshot_exists() {
    all_snapshots | awk -v n="$1" '$2 == n { found = 1 } END { exit !found }'
}

snapshot_gone() {
    ! snapshot_exists "$1"
}

# Snapshots on any volume whose name contains the same timestamp as $1 (tmutil snapshots all volumes at once).
same_time_snapshots() {
    local stamp
    stamp="$(echo "$1" | sed -n 's/^com\.apple\.TimeMachine\.\(.*\)\.local$/\1/p')"
    [ -n "$stamp" ] || stamp="$1"
    all_snapshots | awk -v s="$stamp" 'index($2, s) > 0'
}

no_same_time_snapshots() {
    [ -z "$(same_time_snapshots "$1")" ]
}

in_tmutil_list() {
    /usr/bin/tmutil listlocalsnapshots / 2>/dev/null | grep -qxF "$1"
}

not_in_tmutil_list() {
    ! in_tmutil_list "$1"
}

snapshot_mounted() {
    /sbin/mount | grep -qF "$1@"
}

snapshot_not_mounted() {
    ! snapshot_mounted "$1"
}

path_not_mounted() {
    ! /sbin/mount | grep -qF " on $1 ("
}

recorded_snapshots() {
    if [ -f "$STATE/snapshots.json" ]; then
        grep -o '"snapshotUuid"' "$STATE/snapshots.json" | wc -l | tr -d ' '
    else
        echo 0
    fi
}

nothing_recorded() {
    [ "$(recorded_snapshots)" = "0" ]
}

something_recorded() {
    [ "$(recorded_snapshots)" != "0" ]
}

result_value() {
    sed -n "s/^$1=//p" "$WORK/result.txt" 2>/dev/null | head -n 1
}

equals() {
    [ "$1" = "$2" ]
}

starts_with() {
    case "$1" in "$2"*) return 0 ;; *) return 1 ;; esac
}

not_exists() {
    [ ! -e "$1" ]
}

run_cli_cleanup() {
    "$JAVA" -cp "$CP" "$CLI" --config "$CONFIG" snapshots cleanup
}

# Removes a snapshot by name from whichever volume has it, unmounting it first. Last-resort cleanup only.
force_delete_snapshot() {
    local name="$1" line dev mountpoint
    [ -n "$name" ] || return 0
    /sbin/mount | grep -F "$name@" | sed -n 's/^.* on \(.*\) (.*$/\1/p' | while read -r mountpoint; do
        echo "Cleanup: unmounting $mountpoint"
        /sbin/umount "$mountpoint" || /usr/sbin/diskutil unmount force "$mountpoint"
        rmdir "$mountpoint" 2>/dev/null
    done
    all_snapshots | awk -v n="$name" '$2 == n { print $1 }' | while read -r dev; do
        echo "Cleanup: deleting snapshot $name on $dev"
        /usr/sbin/diskutil apfs deleteSnapshot "$dev" -name "$name"
    done
}

cleanup() {
    local status=$?
    trap - EXIT
    trap '' INT TERM PIPE
    echo
    echo "--- Cleaning up ---"
    if [ -n "$HOLD_PID" ] && kill -0 "$HOLD_PID" 2>/dev/null; then
        kill -9 "$HOLD_PID" 2>/dev/null
        wait "$HOLD_PID" 2>/dev/null
    fi
    if [ -f "$CONFIG" ] && something_recorded; then
        echo "Leftover snapshots are recorded; running snapshots cleanup"
        run_cli_cleanup
    fi
    local name
    for name in "$BACKUP_SNAPSHOT" "$HOLD_SNAPSHOT"; do
        if [ -n "$name" ] && { snapshot_exists "$name" || snapshot_mounted "$name"; }; then
            force_delete_snapshot "$name"
        fi
    done
    rm -rf "$WORK"
    echo "Removed $WORK"
    exit "$status"
}

# --- The checks ------------------------------------------------------------------------------------------------

main() {
    trap cleanup EXIT
    trap 'exit 130' INT TERM

    echo "DataBackup APFS snapshot smoke test"
    echo "Started:  $(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "macOS:    $(sw_vers -productVersion) ($(sw_vers -buildVersion)), $(uname -m)"
    echo "Java:     $JAVA (Java $JAVA_SPEC)"
    echo "Commit:   $(git -c safe.directory="$REPO" -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    echo "Classes:  $(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "$REPO/client/target/test-classes/io/siggi/databackup/client/ApfsSmokeCheck.class")"

    section "Preparing"
    if [ -f "$CONFIG" ] && something_recorded; then
        echo "A previous run left snapshots recorded in $STATE/snapshots.json; cleaning them up first"
        run_cli_cleanup
    fi
    rm -rf "$WORK"
    mkdir -p "$FILES/sub dir/deeper" "$STATE" || { fail "create the test folder $FILES"; return; }
    printf 'hello world\n' > "$FILES/hello.txt"
    : > "$FILES/empty"
    printf 'a file in a folder with a space\n' > "$FILES/sub dir/nested file.txt"
    printf 'deeper\n' > "$FILES/sub dir/deeper/deep.txt"
    head -c 3145728 /dev/urandom > "$FILES/random.bin"
    printf 'original content\n' > "$FILES/modify-me.txt"
    cat > "$CONFIG" <<EOF
{
  "serverUrl": "http://127.0.0.1:9",
  "token": "smoke-test-not-used",
  "machineName": "apfs-smoke",
  "stateDir": "$STATE",
  "sources": [{"name": "Data", "path": "/"}]
}
EOF
    # The expected manifest, computed from the live files before the snapshot.
    (cd "$FILES" && find . -type f | sed 's|^\./||' | while IFS= read -r f; do
        printf 'file\t%s\t%s\t%s\n' "$f" "$(stat -f %z "$f")" "$(shasum -a 256 "$f" | cut -d' ' -f1)"
    done) | LC_ALL=C sort > "$WORK/expected.tsv"
    echo "Test files (path, size, SHA-256):"
    cut -f2- "$WORK/expected.tsv" | sed 's/^/  /'
    ORIGINAL_MODIFY_HASH="$(shasum -a 256 "$FILES/modify-me.txt" | cut -d' ' -f1)"
    echo "Snapshots on writable APFS volumes before the run:"
    all_snapshots | sed 's/^/  /'

    section "Backup run: snapshot, mount, scan, unmount, delete"
    "$JAVA" -cp "$CP" "$HELPER" backup "$CONFIG" "$FILES" "$FILES/modify-me.txt" "$WORK/result.txt"
    local code=$?
    echo "Helper result:"
    sed 's/^/  /' "$WORK/result.txt" 2>/dev/null
    check "the snapshot-and-scan run finished with exit code 0 (was $code)" equals "$code" 0
    BACKUP_SNAPSHOT="$(result_value snapshotName)"
    local scan_path manifest
    scan_path="$(result_value scanPath)"
    manifest="$(result_value manifest)"
    check "an APFS snapshot was created (${BACKUP_SNAPSHOT:-none})" starts_with "$BACKUP_SNAPSHOT" "com.apple.TimeMachine."
    check "the source / was resolved to the Data volume ($(result_value volume))" \
        equals "$(result_value volume)" "/System/Volumes/Data"
    check "the snapshot was mounted under /private/tmp/snapshot-* while scanning (${scan_path:-none})" \
        starts_with "$scan_path" "/private/tmp/snapshot-"
    check "the mount table showed the snapshot at that path during the scan" \
        equals "$(result_value mountedPathDuringScan)" "$scan_path"
    check "the test folder existed inside the snapshot ($(result_value scanFolder))" \
        equals "$(result_value scanFolderExists)" "true"
    check "the snapshot mount was read-only" equals "$(result_value scanFolderWritable)" "false"
    check "no files failed to scan (failedItems=$(result_value failedItems))" equals "$(result_value failedItems)" "0"

    section "Manifest contents"
    if [ -n "$manifest" ] && [ -f "$manifest" ]; then
        "$JAVA" -cp "$CP" "$HELPER" dump "$manifest" > "$WORK/dump.txt" 2>&1
        echo "Manifest dump:"
        sed 's/^/  /' "$WORK/dump.txt"
        grep '^file	' "$WORK/dump.txt" | LC_ALL=C sort > "$WORK/actual.tsv"
        if diff -u "$WORK/expected.tsv" "$WORK/actual.tsv"; then
            pass "the manifest lists exactly the test files, with the right sizes and SHA-256 hashes"
        else
            fail "the manifest lists exactly the test files, with the right sizes and SHA-256 hashes (diff above:" \
                "- expected, + manifest)"
        fi
        local modify_hash live_hash
        modify_hash="$(awk -F '\t' '$2 == "modify-me.txt" { print $4 }' "$WORK/actual.tsv")"
        live_hash="$(shasum -a 256 "$FILES/modify-me.txt" | cut -d' ' -f1)"
        check "the live modify-me.txt was changed after the snapshot was taken" \
            test "$live_hash" != "$ORIGINAL_MODIFY_HASH"
        check "the manifest has modify-me.txt as it was in the snapshot, not the live change" \
            equals "$modify_hash" "$ORIGINAL_MODIFY_HASH"
        local apfs_line file_path
        apfs_line="$(grep '^apfs	' "$WORK/dump.txt" | cut -f2- | tr '\t' ' ')"
        file_path="$(grep '^filePath	' "$WORK/dump.txt" | cut -f2-)"
        check "the source carries the APFS snapshot info ($apfs_line)" \
            equals "$apfs_line" "$BACKUP_SNAPSHOT $(result_value snapshotXid)"
        check "the source carries its live path, not the snapshot path ($file_path)" equals "$file_path" "$FILES"
        check "the root carries a snapshot ID" grep -qE '^snapshotId	[0-9a-f-]{36}$' "$WORK/dump.txt"
    else
        fail "the manifest was written (${manifest:-no path reported})"
    fi

    section "Nothing left behind by the backup run"
    if [ -n "$BACKUP_SNAPSHOT" ]; then
        check "the snapshot $BACKUP_SNAPSHOT was deleted (diskutil apfs listSnapshots)" snapshot_gone "$BACKUP_SNAPSHOT"
        check "no snapshot from the same tmutil run is left on any volume" no_same_time_snapshots "$BACKUP_SNAPSHOT"
        check "tmutil listlocalsnapshots / doesn't list it" not_in_tmutil_list "$BACKUP_SNAPSHOT"
        check "the snapshot isn't mounted (mount)" snapshot_not_mounted "$BACKUP_SNAPSHOT"
    fi
    if [ -n "$scan_path" ]; then
        check "nothing is mounted at $scan_path" path_not_mounted "$scan_path"
        check "the mount point folder $scan_path was removed" not_exists "$scan_path"
    fi
    check "snapshots.json records no snapshots" nothing_recorded

    section "Interrupted run: kill -9 after the snapshot is mounted, then snapshots cleanup"
    "$JAVA" -cp "$CP" "$HELPER" hold "$CONFIG" > "$WORK/hold.out" 2>&1 &
    HOLD_PID=$!
    local waited=0
    while [ "$waited" -lt 180 ] && kill -0 "$HOLD_PID" 2>/dev/null && ! grep -q '^SNAPSHOT_READY' "$WORK/hold.out"; do
        sleep 1
        waited=$((waited + 1))
    done
    local ready hold_mount
    ready="$(grep '^SNAPSHOT_READY' "$WORK/hold.out" | head -n 1)"
    if [ -n "$ready" ]; then
        pass "the second run created and mounted a snapshot ($ready)"
        HOLD_SNAPSHOT="$(echo "$ready" | sed -n 's/^SNAPSHOT_READY name=\([^ ]*\) mount=.*$/\1/p')"
        hold_mount="$(echo "$ready" | sed -n 's/^.* mount=//p')"
        kill -9 "$HOLD_PID" 2>/dev/null
        wait "$HOLD_PID" 2>/dev/null
        echo "Killed the run (pid $HOLD_PID) with kill -9"
        HOLD_PID=""
        echo "Output of the killed run:"
        sed 's/^/  /' "$WORK/hold.out"
        check "the killed run left its snapshot behind (so the cleanup has something to do)" \
            snapshot_exists "$HOLD_SNAPSHOT"
        check "snapshots.json records the leftover snapshot ($(recorded_snapshots) recorded)" something_recorded

        echo "Running: databackup --config $CONFIG snapshots cleanup"
        run_cli_cleanup
        code=$?
        check "snapshots cleanup finished with exit code 0 (was $code)" equals "$code" 0
        check "the leftover snapshot $HOLD_SNAPSHOT was deleted" snapshot_gone "$HOLD_SNAPSHOT"
        check "no snapshot from the same tmutil run is left on any volume" no_same_time_snapshots "$HOLD_SNAPSHOT"
        check "tmutil listlocalsnapshots / doesn't list it" not_in_tmutil_list "$HOLD_SNAPSHOT"
        check "the leftover snapshot isn't mounted (mount)" snapshot_not_mounted "$HOLD_SNAPSHOT"
        if [ -n "$hold_mount" ]; then
            check "the mount point folder $hold_mount was removed" not_exists "$hold_mount"
        fi
        check "snapshots.json records no snapshots after the cleanup" nothing_recorded

        echo "Running snapshots cleanup again (should find nothing):"
        local again
        again="$(run_cli_cleanup 2>"$WORK/cleanup2.err")"
        code=$?
        echo "  $again"
        sed 's/^/  /' "$WORK/cleanup2.err"
        check "a second snapshots cleanup succeeds and finds nothing" \
            equals "$code:$again" "0:No leftover snapshots."
    else
        fail "the second run created and mounted a snapshot within 180 seconds"
        echo "Output of the second run:"
        sed 's/^/  /' "$WORK/hold.out"
    fi

    section "Snapshots on writable APFS volumes after the run"
    all_snapshots | sed 's/^/  /'

    echo
    if [ "$FAILS" -eq 0 ]; then
        echo "RESULT: PASS (all $PASSES checks passed)"
    else
        echo "RESULT: FAIL ($FAILS of $((PASSES + FAILS)) checks failed)"
    fi
    [ "$FAILS" -eq 0 ]
}

mkdir -p "$OUT_DIR"
# tee ignores Ctrl-C so the cleanup's output still reaches the screen and the log.
main 2>&1 | (trap '' INT; exec tee "$LOG")
STATUS=${PIPESTATUS[0]}
if [ -n "${SUDO_USER:-}" ]; then
    chown "$SUDO_USER" "$LOG" 2>/dev/null
fi
echo
echo "Log written to $LOG"
exit "$STATUS"
