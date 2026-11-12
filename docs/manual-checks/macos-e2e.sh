#!/bin/bash
#
# End-to-end check of DataBackup on macOS (APFS). Run it on a Mac, as root:
#
#   sudo docs/manual-checks/macos-e2e.sh
#
# It builds nothing. Build the runnable jars first, as your normal user (not with sudo), from the repository root:
#
#   mvn -o -q package
#
# (drop -o if Maven says a dependency isn't cached). It uses server/target/databackup-server.jar and
# client/target/databackup-client.jar.
#
# What it does, with the real jars and the real CLI commands:
#
#   1. Starts a DataBackup server on 127.0.0.1 (a free port) with a temp data folder, and creates a token.
#   2. Creates a test folder at the top of the Data volume (/System/Volumes/Data/DataBackup-e2e-test) holding a
#      symlink, a dangling symlink, an empty file, an empty folder, odd permissions (0600, 0444, 2755, 0700, 1777,
#      a file owned by you instead of root), non-ASCII names, duplicate content and a few MiB of random data.
#   3. Writes a client.json whose one source is "/" (the whole Data volume, which is what the client snapshots),
#      with every other top-level entry of the Data volume excluded, so only the test folder is scanned and uploaded.
#   4. Runs "backup", then "list", then "restore" to a temp folder, and compares the restored tree with the test
#      folder: "diff -r" plus type, permissions, owner, size, modified time and symlink targets.
#   5. Changes one file, backs up again, checks that only that file's content was uploaded, restores the second
#      backup and compares again, and restores the changed file from the first backup to check its old content.
#   6. Checks that no APFS snapshot or snapshot mount is left behind after each backup.
#   7. Starts a backup against a port nothing listens on (so it keeps retrying the upload while holding its
#      snapshot), kill -9s it, checks the snapshot was left behind, then runs "snapshots cleanup" and checks it's
#      gone.
#
# Every check prints a PASS or FAIL line, and the run ends with a RESULT line. All output also goes to
# target/manual-checks/macos-e2e.log in the repository. The script stops the server and removes the test folder,
# its temp folder (/private/var/tmp/databackup-e2e) and any snapshot it left behind, even when a check fails or it is
# interrupted. It is safe to run more than once.
#
# Notes: APFS snapshots are taken with "tmutil localsnapshot", so a Time Machine local snapshot briefly exists
# during each backup. Snapshots that existed before the run are left alone; if Time Machine makes its own snapshot
# during the run, a "no snapshot left behind" check can fail because of it (the log lists the snapshot names). If a
# check fails with "Operation not permitted", give your terminal app Full Disk Access (System Settings, Privacy &
# Security, Full Disk Access) and run the script again.

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$REPO/target/manual-checks"
LOG="$OUT_DIR/macos-e2e.log"
SERVER_JAR="$REPO/server/target/databackup-server.jar"
CLIENT_JAR="$REPO/client/target/databackup-client.jar"

DATA_VOLUME="/System/Volumes/Data"
TEST_NAME="DataBackup-e2e-test"
SRC="$DATA_VOLUME/$TEST_NAME"
WORK="/private/var/tmp/databackup-e2e"
SERVER_DATA="$WORK/server"
STATE="$WORK/state"
CONFIG="$WORK/client.json"
KILL_CONFIG="$WORK/client-unreachable.json"
MACHINE="e2e-mac"
IN_BACKUP="Data/$TEST_NAME"

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

for needed in "$SERVER_JAR" "$CLIENT_JAR"; do
    [ -f "$needed" ] || refuse "$needed is missing. Build the jars first, as your normal user, from $REPO:
  mvn -o -q package"
done
[ -d "$DATA_VOLUME" ] || refuse "$DATA_VOLUME doesn't exist; this check needs macOS 10.15 or later."

if [ "$(id -u)" -ne 0 ]; then
    refuse "this check creates and deletes APFS snapshots, which needs root. Run it with sudo:
  sudo $0"
fi

# --- Helpers ---------------------------------------------------------------------------------------------------

PASSES=0
FAILS=0
SERVER_PID=""
CLIENT_PID=""
SEEN_SNAPSHOTS="$WORK/new-snapshots.txt"   # every snapshot that appeared during the run, for the final cleanup
LEFTOVER_SNAPSHOTS=""

pass() {
    PASSES=$((PASSES + 1))
    echo "PASS: $*"
}

fail() {
    FAILS=$((FAILS + 1))
    echo "FAIL: $*"
}

info() {
    echo "INFO: $*"
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

equals() {
    [ "$1" = "$2" ]
}

contains_line() {
    printf '%s\n' "$1" | grep -qxF -- "$2"
}

contains_text() {
    printf '%s\n' "$1" | grep -qF -- "$2"
}

not_exists() {
    [ ! -e "$1" ] && [ ! -L "$1" ]
}

# Writable APFS volumes, as /dev/diskNsM.
apfs_volumes() {
    /sbin/mount | awk '/^\/dev\/disk[0-9]+s[0-9]+ on / && /\(apfs,/ && !/read-only/ { print $1 }' | sort -u
}

# "<device> <snapshot name>" for every snapshot on every writable APFS volume, sorted.
all_snapshots() {
    local dev
    for dev in $(apfs_volumes); do
        /usr/sbin/diskutil apfs listSnapshots "$dev" 2>/dev/null \
            | sed -n 's/^[| ]*Name: *//p' | while read -r name; do echo "$dev $name"; done
    done | LC_ALL=C sort
}

# Lines of all_snapshots that aren't in the file $1 (taken earlier with all_snapshots). Also remembers them.
new_snapshots_since() {
    local now
    now="$(all_snapshots)"
    printf '%s\n' "$now" | LC_ALL=C comm -13 "$1" - | sed '/^$/d' | tee -a "$SEEN_SNAPSHOTS"
}

# Mount points of mounted snapshots (the client mounts them at /private/tmp/snapshot-*).
snapshot_mounts() {
    /sbin/mount | sed -n 's/^.* on \(\/private\/tmp\/snapshot-[^ ]*\) (.*$/\1/p' | LC_ALL=C sort
}

new_mounts_since() {
    snapshot_mounts | LC_ALL=C comm -13 "$1" - | sed '/^$/d'
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

# Escapes a file name for use as a literal glob in an exclude, then for a JSON string.
exclude_json() {
    printf '%s' "$1" | sed -e 's/[][*?{}\\]/\\&/g' -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# Writes a client config to $1 for server URL $2. Its only source is "/", with every top-level entry of the Data
# volume other than the test folder excluded. Re-run before each backup, so new top-level entries are excluded too.
write_config() {
    local file="$1" url="$2" name first=1 excludes=""
    while IFS= read -r name; do
        [ -n "$name" ] || continue
        [ "$name" = "$TEST_NAME" ] && continue
        if [ $first -eq 1 ]; then first=0; else excludes="$excludes, "; fi
        excludes="$excludes\"$(exclude_json "$name")\""
    done < <(ls -A "$DATA_VOLUME")
    cat > "$file" <<EOF
{
  "serverUrl": "$url",
  "token": "$TOKEN",
  "machineName": "$MACHINE",
  "stateDir": "$STATE",
  "sources": [{"name": "Data", "path": "/", "excludes": [$excludes]}]
}
EOF
}

client() {
    "$JAVA" -jar "$CLIENT_JAR" --config "$CONFIG" "$@"
}

# The modified time with microseconds, and with nanoseconds.
mtime_us() {
    stat -f '%Fm' "$1" | sed 's/^\([0-9-]*\)\.\([0-9]\{6\}\).*$/\1.\2/'
}

mtime_ns() {
    stat -f '%Fm' "$1"
}

# A sorted listing of everything under $1, one line per item: type, path, permissions (with setuid, setgid and
# sticky bits), owner, size, modified time to the microsecond (to the nanosecond with $2 = ns), symlink target.
# Symlinks get no permissions, size or time (the restore doesn't set a symlink's mode, and its time only when
# recorded), only their owner and target.
tree_listing() {
    local root="$1" precision="${2:-us}"
    (cd "$root" && find . -mindepth 1 | LC_ALL=C sort | while IFS= read -r p; do
        if [ -L "$p" ]; then
            printf 'link\t%s\t%s\t-> %s\n' "$p" "$(stat -f '%u:%g' "$p")" "$(readlink "$p")"
        elif [ -d "$p" ]; then
            printf 'dir\t%s\t%s\t%s\n' "$p" "$(stat -f '%Mp%Lp %u:%g' "$p")" "$(mtime_$precision "$p")"
        else
            printf 'file\t%s\t%s\t%s\n' "$p" "$(stat -f '%Mp%Lp %u:%g %z' "$p")" "$(mtime_$precision "$p")"
        fi
    done)
}

# Symlink modified times (informational only).
link_times() {
    (cd "$1" && find . -mindepth 1 -type l | LC_ALL=C sort | while IFS= read -r p; do
        printf '%s\t%s\n' "$p" "$(mtime_ns "$p")"
    done)
}

# Compares a restored tree with the test folder: diff -r, then the metadata listing.
compare_trees() {
    local label="$1" restored="$2"
    if [ ! -d "$restored" ]; then
        fail "$label: the restore folder $restored exists"
        return
    fi
    echo "diff -r --no-dereference $SRC $restored"
    if diff -r --no-dereference "$SRC" "$restored"; then
        pass "$label: diff -r finds no differences in content, names or symlink targets"
    else
        fail "$label: diff -r finds no differences in content, names or symlink targets (differences above)"
    fi
    tree_listing "$SRC" > "$WORK/expected-listing.tsv"
    tree_listing "$restored" > "$WORK/restored-listing.tsv"
    if diff -u "$WORK/expected-listing.tsv" "$WORK/restored-listing.tsv"; then
        pass "$label: types, permissions, owners, sizes, modified times (to the microsecond) and symlink targets match"
    else
        fail "$label: types, permissions, owners, sizes, modified times (to the microsecond) and symlink targets match" \
            "(diff above: - test folder, + restored)"
        return
    fi
    tree_listing "$SRC" ns > "$WORK/expected-listing-ns.tsv"
    tree_listing "$restored" ns > "$WORK/restored-listing-ns.tsv"
    if diff -q "$WORK/expected-listing-ns.tsv" "$WORK/restored-listing-ns.tsv" >/dev/null; then
        info "$label: modified times also match to the nanosecond"
    else
        info "$label: modified times differ below a microsecond (not counted as a failure):"
        diff -u "$WORK/expected-listing-ns.tsv" "$WORK/restored-listing-ns.tsv" | sed -n 's/^[-+]\([^-+]\)/  &/p'
    fi
    if diff -q <(link_times "$SRC") <(link_times "$restored") >/dev/null; then
        info "$label: symlink modified times match too"
    else
        info "$label: symlink modified times differ (not counted as a failure):"
        diff -u <(link_times "$SRC") <(link_times "$restored") | sed -n 's/^[-+]\([^-+]\)/  &/p'
    fi
}

# Runs a backup, checking its exit code and that no snapshot or snapshot mount is left. Sets BACKUP_OUTPUT and
# BACKUP_NAME.
BACKUP_OUTPUT=""
BACKUP_NAME=""
run_backup() {
    local label="$1" code leftover_snaps leftover_mounts
    all_snapshots > "$WORK/snapshots-before.txt"
    snapshot_mounts > "$WORK/mounts-before.txt"
    write_config "$CONFIG" "$SERVER_URL"
    echo "Running: databackup --config $CONFIG backup"
    client backup > "$WORK/backup.out" 2>&1
    code=$?
    BACKUP_OUTPUT="$(cat "$WORK/backup.out")"
    sed 's/^/  /' "$WORK/backup.out"
    check "$label: backup finished with exit code 0 (was $code)" equals "$code" 0
    BACKUP_NAME="$(printf '%s\n' "$BACKUP_OUTPUT" | sed -n 's/^Backup \(.*\) complete: .*$/\1/p' | tail -n 1)"
    check "$label: backup printed its summary line (backup ${BACKUP_NAME:-unknown})" test -n "$BACKUP_NAME"
    check "$label: no files failed to scan" not_contains_failed
    leftover_snaps="$(new_snapshots_since "$WORK/snapshots-before.txt")"
    leftover_mounts="$(new_mounts_since "$WORK/mounts-before.txt")"
    if [ -z "$leftover_snaps" ]; then
        pass "$label: no new APFS snapshot is left on any volume (diskutil apfs listSnapshots)"
    else
        fail "$label: no new APFS snapshot is left on any volume (diskutil apfs listSnapshots); left:"
        printf '%s\n' "$leftover_snaps" | sed 's/^/  /'
    fi
    check "$label: tmutil listlocalsnapshots / lists nothing new" tmutil_nothing_new
    if [ -z "$leftover_mounts" ]; then
        pass "$label: no snapshot is left mounted under /private/tmp/snapshot-*"
    else
        fail "$label: no snapshot is left mounted under /private/tmp/snapshot-*; left:"
        printf '%s\n' "$leftover_mounts" | sed 's/^/  /'
    fi
    check "$label: snapshots.json records no snapshots" nothing_recorded
}

not_contains_failed() {
    ! printf '%s\n' "$BACKUP_OUTPUT" | grep -q '^Failed (couldn'"'"'t be read'
}

tmutil_nothing_new() {
    # tmutil lists Data-volume Time Machine snapshots by date; anything not there before is new.
    local before now
    before="$(sed -n 's/^.* com\.apple\.TimeMachine\.\(.*\)\.local$/\1/p' "$WORK/snapshots-before.txt" | LC_ALL=C sort -u)"
    now="$(/usr/bin/tmutil listlocalsnapshots / 2>/dev/null | sed -n 's/^com\.apple\.TimeMachine\.\(.*\)\.local$/\1/p' \
        | LC_ALL=C sort -u)"
    [ -z "$(LC_ALL=C comm -13 <(printf '%s\n' "$before") <(printf '%s\n' "$now") | sed '/^$/d')" ]
}

uploaded_count() {
    printf '%s\n' "$BACKUP_OUTPUT" | sed -n 's/^Uploading \([0-9]*\) pieces of content .*$/\1/p' | head -n 1
}

# Number of distinct file contents in the test folder, the empty one included: what a first backup must upload.
distinct_contents() {
    find "$SRC" -type f -exec shasum -a 256 {} + | awk '{ print $1 }' | sort -u | wc -l | tr -d ' '
}

stop_server() {
    [ -n "$SERVER_PID" ] || return 0
    if kill -0 "$SERVER_PID" 2>/dev/null; then
        kill "$SERVER_PID" 2>/dev/null
        local waited=0
        while kill -0 "$SERVER_PID" 2>/dev/null && [ "$waited" -lt 20 ]; do
            sleep 0.5
            waited=$((waited + 1))
        done
        kill -9 "$SERVER_PID" 2>/dev/null
        wait "$SERVER_PID" 2>/dev/null
    fi
    SERVER_PID=""
    rm -f "$WORK/server.pid"
}

# Removes a snapshot by name from whichever volume has it, unmounting it first. Last-resort cleanup only.
force_delete_snapshot() {
    local name="$1" dev mountpoint
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

# Cleans up after this run, or a previous one that was killed: client, server, snapshots, folders.
cleanup_everything() {
    if [ -n "$CLIENT_PID" ] && kill -0 "$CLIENT_PID" 2>/dev/null; then
        kill -9 "$CLIENT_PID" 2>/dev/null
        wait "$CLIENT_PID" 2>/dev/null
    fi
    CLIENT_PID=""
    stop_server
    if [ -f "$WORK/server.pid" ]; then
        local old_pid
        old_pid="$(cat "$WORK/server.pid")"
        if [ -n "$old_pid" ] && ps -p "$old_pid" -o command= 2>/dev/null | grep -qF "$SERVER_JAR"; then
            echo "Stopping a server left by a previous run (pid $old_pid)"
            kill "$old_pid" 2>/dev/null
            sleep 2
            kill -9 "$old_pid" 2>/dev/null
        fi
    fi
    if [ -f "$CONFIG" ] && ! nothing_recorded; then
        echo "Snapshots are recorded in $STATE/snapshots.json; running snapshots cleanup"
        client snapshots cleanup
    fi
    local line name
    if [ -f "$SEEN_SNAPSHOTS" ]; then
        while read -r line; do
            name="${line#* }"
            case "$name" in
                com.apple.TimeMachine.*) force_delete_snapshot "$name" ;;
            esac
        done < <(LC_ALL=C sort -u "$SEEN_SNAPSHOTS")
    fi
    rm -rf "$WORK" "$SRC"
}

cleanup() {
    local status=$?
    trap - EXIT
    trap '' INT TERM PIPE
    echo
    echo "--- Cleaning up ---"
    cleanup_everything
    echo "Stopped the server and removed $WORK and $SRC"
    exit "$status"
}

free_port() {
    local port="$1"
    while /usr/bin/nc -z 127.0.0.1 "$port" 2>/dev/null; do
        port=$((port + 1))
    done
    echo "$port"
}

# --- The checks ------------------------------------------------------------------------------------------------

make_test_folder() {
    local owner_uid owner_gid
    owner_uid="$(id -u "${SUDO_USER:-nobody}" 2>/dev/null || echo 501)"
    owner_gid=20 # staff
    mkdir -p "$SRC" || return 1
    cd "$SRC" || return 1
    printf 'hello world\n' > hello.txt
    : > empty
    mkdir -p "sub dir/deeper" "empty dir" "Ünïcødé folder" locked sticky
    printf 'a file in a folder with a space\n' > "sub dir/nested file.txt"
    printf 'deeper\n' > "sub dir/deeper/deep.txt"
    printf 'non-ASCII name\n' > "Grüße 日本語.txt"
    printf 'inside a non-ASCII folder\n' > "Ünïcødé folder/ファイル.txt"
    head -c 3145728 /dev/urandom > random.bin
    head -c 65536 /dev/urandom > dup-a.bin
    cp dup-a.bin dup-b.bin
    head -c 262144 /dev/urandom > change-me.bin
    printf 'secret\n' > private.txt && chmod 600 private.txt && chown "$owner_uid:$owner_gid" private.txt
    printf 'read only\n' > readonly.txt && chmod 444 readonly.txt
    printf '#!/bin/sh\necho hi\n' > script.sh && chmod 755 script.sh
    printf 'setgid\n' > setgid-file && chmod 2755 setgid-file
    printf 'in a locked folder\n' > locked/inside.txt && chmod 700 locked
    printf 'in a sticky folder\n' > sticky/inside.txt && chmod 1777 sticky
    chmod 750 "sub dir"
    ln -s hello.txt link-to-hello
    ln -s "sub dir" link-to-dir
    ln -s "Ünïcødé folder/ファイル.txt" link-non-ascii
    ln -s does-not-exist dangling-link
    ln -s /etc/hosts absolute-link
    chown -h "$owner_uid:$owner_gid" link-to-hello
    # Fixed whole-second times on some items, so a time that's restored to the wrong second stands out.
    touch -t 202001020304.05 hello.txt empty
    touch -t 201912312359.59 "sub dir/deeper/deep.txt"
    touch -t 202001020304.05 "sub dir/deeper" "empty dir"
    cd /
}

main() {
    trap cleanup EXIT
    trap 'exit 130' INT TERM
    run_checks
    local finished=$?

    echo
    if [ "$finished" -ne 0 ]; then
        echo "The checks stopped early because a step they depend on failed (see the FAIL line above)."
        [ "$FAILS" -gt 0 ] || FAILS=1
    fi
    if [ "$FAILS" -eq 0 ]; then
        echo "RESULT: PASS (all $PASSES checks passed)"
    else
        echo "RESULT: FAIL ($FAILS of $((PASSES + FAILS)) checks failed)"
    fi
    [ "$FAILS" -eq 0 ]
}

run_checks() {

    echo "DataBackup macOS end-to-end check"
    echo "Started:  $(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "macOS:    $(sw_vers -productVersion) ($(sw_vers -buildVersion)), $(uname -m)"
    echo "Java:     $JAVA (Java $JAVA_SPEC)"
    echo "Commit:   $(git -c safe.directory="$REPO" -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    echo "Jars:     client $(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "$CLIENT_JAR"), server $(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "$SERVER_JAR")"

    section "Preparing"
    if [ -e "$WORK" ] || [ -e "$SRC" ]; then
        echo "Cleaning up what a previous run left behind"
        cleanup_everything
    fi
    mkdir -p "$WORK" "$SERVER_DATA" "$STATE" || { fail "create $WORK"; return 1; }
    : > "$SEEN_SNAPSHOTS"
    if make_test_folder; then
        pass "created the test folder $SRC"
    else
        fail "created the test folder $SRC"
        return 1
    fi
    echo "Test folder:"
    tree_listing "$SRC" | sed 's/^/  /'
    echo "Snapshots on writable APFS volumes before the run:"
    all_snapshots | sed 's/^/  /'

    section "Starting the server"
    local port
    port="$(free_port 28080)"
    SERVER_URL="http://127.0.0.1:$port"
    printf '{\n  "bindAddress": "127.0.0.1",\n  "port": %s\n}\n' "$port" > "$SERVER_DATA/server.json"
    TOKEN="$("$JAVA" -Ddatabackupdir="$SERVER_DATA" -jar "$SERVER_JAR" token create --name e2e \
        --permission "upload:$MACHINE" "read:$MACHINE" 2>"$WORK/token.err")"
    local code=$?
    sed 's/^/  /' "$WORK/token.err"
    check "token create finished with exit code 0 (was $code)" equals "$code" 0
    [ -n "$TOKEN" ] || { fail "token create printed a token"; return 1; }
    "$JAVA" -Ddatabackupdir="$SERVER_DATA" -jar "$SERVER_JAR" serve > "$WORK/server.log" 2>&1 &
    SERVER_PID=$!
    echo "$SERVER_PID" > "$WORK/server.pid"
    local waited=0
    while [ "$waited" -lt 60 ] && kill -0 "$SERVER_PID" 2>/dev/null && ! grep -q 'listening on port' "$WORK/server.log"; do
        sleep 0.5
        waited=$((waited + 1))
    done
    sed 's/^/  /' "$WORK/server.log"
    if grep -q "listening on port $port" "$WORK/server.log"; then
        pass "the server is listening on $SERVER_URL"
    else
        fail "the server is listening on $SERVER_URL"
        return 1
    fi

    section "First backup"
    run_backup "first backup"
    local first_backup="$BACKUP_NAME" expected_uploads uploads
    expected_uploads="$(distinct_contents)"
    uploads="$(uploaded_count)"
    check "first backup: uploaded every distinct content (the empty one too) once ($expected_uploads expected, ${uploads:-none} uploaded)" \
        equals "$uploads" "$expected_uploads"
    check "first backup: the manifest excluded the rest of the Data volume (an \"Ignored\" summary was printed)" \
        contains_text "$BACKUP_OUTPUT" "Ignored (excluded or .nobackup)"

    section "list"
    local listing
    listing="$(client list 2>&1)"; code=$?
    printf '%s\n' "$listing" | sed 's/^/  /'
    check "list finished with exit code 0 (was $code) and lists the machine $MACHINE" \
        equals "$code:$(contains_line "$listing" "$MACHINE" && echo yes)" "0:yes"
    listing="$(client list "$MACHINE" 2>&1)"; code=$?
    printf '%s\n' "$listing" | sed 's/^/  /'
    check "list $MACHINE finished with exit code 0 (was $code) and shows the backup ${first_backup:-?}" \
        equals "$code:$(printf '%s\n' "$listing" | awk -v b="$first_backup" '$1 == b' | wc -l | tr -d ' ')" "0:1"
    listing="$(client list "$MACHINE" latest "$IN_BACKUP" 2>&1)"; code=$?
    printf '%s\n' "$listing" | sed 's/^/  /'
    check "list $MACHINE latest $IN_BACKUP finished with exit code 0 (was $code)" equals "$code" 0
    check "the directory listing shows hello.txt" contains_text "$listing" " hello.txt"
    check "the directory listing shows the non-ASCII name Grüße 日本語.txt" contains_text "$listing" "Grüße 日本語.txt"
    check "the directory listing shows the symlink link-to-hello" contains_text "$listing" "link-to-hello"
    listing="$(client list "$MACHINE" latest Data 2>&1)"
    check "the backup's Data source holds only the test folder" equals "$(printf '%s\n' "$listing" | wc -l | tr -d ' ')" 1

    section "Restore of the first backup"
    echo "Running: databackup restore $MACHINE latest $IN_BACKUP $WORK/restore1"
    client restore "$MACHINE" latest "$IN_BACKUP" "$WORK/restore1" > "$WORK/restore.out" 2>&1; code=$?
    sed 's/^/  /' "$WORK/restore.out"
    check "restore finished with exit code 0 (was $code)" equals "$code" 0
    compare_trees "first restore" "$WORK/restore1"
    client restore "$MACHINE" latest "$IN_BACKUP" "$WORK/restore1" > "$WORK/restore-again.out" 2>&1; code=$?
    sed 's/^/  /' "$WORK/restore-again.out"
    check "restoring again onto the same folder without --overwrite is refused (exit code 1, was $code)" \
        equals "$code" 1

    section "Second backup after changing one file"
    local old_hash new_hash
    old_hash="$(shasum -a 256 "$SRC/change-me.bin" | cut -d' ' -f1)"
    head -c 262144 /dev/urandom > "$SRC/change-me.bin"
    new_hash="$(shasum -a 256 "$SRC/change-me.bin" | cut -d' ' -f1)"
    echo "Changed change-me.bin: $old_hash -> $new_hash"
    run_backup "second backup"
    local second_backup="$BACKUP_NAME"
    uploads="$(uploaded_count)"
    check "second backup: uploaded exactly one piece of content (${uploads:-none})" equals "$uploads" 1
    check "second backup: uploaded 256.0 KiB, the size of change-me.bin" \
        contains_text "$BACKUP_OUTPUT" "Uploading 1 pieces of content (256.0 KiB)"
    printf '%s\n' "$BACKUP_OUTPUT" | grep '^Manifest:' | sed 's/^/INFO: second backup: /'
    check "second backup has a different name from the first ($first_backup, $second_backup)" \
        test -n "$second_backup" -a "$second_backup" != "$first_backup"
    listing="$(client list "$MACHINE" 2>&1)"
    printf '%s\n' "$listing" | sed 's/^/  /'
    check "list $MACHINE shows two backups" equals "$(printf '%s\n' "$listing" | sed '/^$/d' | wc -l | tr -d ' ')" 2

    section "Restore of the second backup"
    client restore "$MACHINE" latest "$IN_BACKUP" "$WORK/restore2" > "$WORK/restore2.out" 2>&1; code=$?
    sed 's/^/  /' "$WORK/restore2.out"
    check "restore of the second backup finished with exit code 0 (was $code)" equals "$code" 0
    compare_trees "second restore" "$WORK/restore2"
    if [ -n "$first_backup" ]; then
        client restore "$MACHINE" "$first_backup" "$IN_BACKUP/change-me.bin" "$WORK/old-change-me.bin" \
            > "$WORK/restore3.out" 2>&1; code=$?
        sed 's/^/  /' "$WORK/restore3.out"
        check "restoring change-me.bin from the first backup finished with exit code 0 (was $code)" equals "$code" 0
        check "the first backup's change-me.bin has the old content" \
            equals "$(shasum -a 256 "$WORK/old-change-me.bin" 2>/dev/null | cut -d' ' -f1)" "$old_hash"
    fi

    section "Interrupted backup: kill -9 while it holds its snapshot, then snapshots cleanup"
    local dead_port
    dead_port="$(free_port $((port + 1)))"
    all_snapshots > "$WORK/snapshots-before.txt"
    snapshot_mounts > "$WORK/mounts-before.txt"
    write_config "$KILL_CONFIG" "http://127.0.0.1:$dead_port"
    echo "Starting a backup against $dead_port, where nothing listens, so it retries the upload while holding its snapshot"
    "$JAVA" -jar "$CLIENT_JAR" --config "$KILL_CONFIG" backup > "$WORK/killed.out" 2>&1 &
    CLIENT_PID=$!
    waited=0
    while [ "$waited" -lt 240 ] && kill -0 "$CLIENT_PID" 2>/dev/null \
        && ! { grep -q '^Uploading the manifest' "$WORK/killed.out" && ! nothing_recorded; }; do
        sleep 0.5
        waited=$((waited + 1))
    done
    local was_running=no
    kill -0 "$CLIENT_PID" 2>/dev/null && was_running=yes
    kill -9 "$CLIENT_PID" 2>/dev/null
    wait "$CLIENT_PID" 2>/dev/null
    echo "Killed the backup (pid $CLIENT_PID) with kill -9"
    CLIENT_PID=""
    echo "Output of the killed backup:"
    sed 's/^/  /' "$WORK/killed.out"
    check "the backup was still running, uploading the manifest, when it was killed" equals "$was_running" yes
    LEFTOVER_SNAPSHOTS="$(new_snapshots_since "$WORK/snapshots-before.txt")"
    echo "New snapshots after the kill:"
    printf '%s\n' "$LEFTOVER_SNAPSHOTS" | sed '/^$/d; s/^/  /'
    check "the killed backup left a snapshot behind (so the cleanup has something to do)" test -n "$LEFTOVER_SNAPSHOTS"
    check "snapshots.json records it ($(recorded_snapshots) recorded)" test "$(recorded_snapshots)" != 0
    echo "Running: databackup --config $CONFIG snapshots cleanup"
    client snapshots cleanup > "$WORK/cleanup.out" 2>&1; code=$?
    sed 's/^/  /' "$WORK/cleanup.out"
    check "snapshots cleanup finished with exit code 0 (was $code)" equals "$code" 0
    local still
    still="$(new_snapshots_since "$WORK/snapshots-before.txt")"
    if [ -z "$still" ]; then
        pass "no new APFS snapshot is left on any volume after the cleanup"
    else
        fail "no new APFS snapshot is left on any volume after the cleanup; left:"
        printf '%s\n' "$still" | sed 's/^/  /'
    fi
    check "tmutil listlocalsnapshots / lists nothing new after the cleanup" tmutil_nothing_new
    still="$(new_mounts_since "$WORK/mounts-before.txt")"
    if [ -z "$still" ]; then
        pass "no snapshot is left mounted under /private/tmp/snapshot-* after the cleanup"
    else
        fail "no snapshot is left mounted under /private/tmp/snapshot-* after the cleanup; left:"
        printf '%s\n' "$still" | sed 's/^/  /'
    fi
    check "snapshots.json records no snapshots after the cleanup" nothing_recorded
    local again
    again="$(client snapshots cleanup 2>&1)"; code=$?
    echo "  $again"
    check "a second snapshots cleanup succeeds and finds nothing" equals "$code:$again" "0:No leftover snapshots."

    section "Stopping the server"
    stop_server
    check "the server stopped" equals "$SERVER_PID" ""

    section "Snapshots on writable APFS volumes after the run"
    all_snapshots | sed 's/^/  /'
    return 0
}

TOKEN=""
SERVER_URL=""
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
