#!/bin/bash
#
# End-to-end check of the DataBackup client on Linux (btrfs). Run it as root on the Linux machine:
#
#   sudo ./linux-btrfs-e2e.sh <client jar> <server URL> <token file> <source subvolume>
#
# for example
#
#   sudo ./linux-btrfs-e2e.sh ./databackup-client.jar http://backup-server:8080 ./token /home/me/subvolume
#
# The server runs elsewhere (or on this machine) and must already be up, with a fresh data folder (the first backup
# must upload all the test content, so the server mustn't hold it already). The token needs upload:e2e-linux and
# read:e2e-linux. The source must be an EMPTY btrfs subvolume (not just a folder): the script fills it with test
# files and empties it again at the end. Build the client jar with "mvn -o -q package" (client/target/).
#
# What it does, with the real client jar and the real CLI commands:
#
#   1. Fills the source subvolume with a symlink, a dangling symlink, an empty file, an empty folder, odd
#      permissions (0600, 0444, 2755, 0700, 1777, files owned by other users), non-ASCII names, duplicate content
#      and a few MiB of random data.
#   2. Writes /etc/databackup/client.json (the client's default config; state in /var/lib/databackup-client) with
#      that subvolume as the only source, moving any existing config aside and putting it back afterwards.
#   3. Runs "backup", then "list", then "restore" to a temp folder, and compares the restored tree with the source:
#      "diff -r" plus type, permissions, owner, size, modified time and symlink targets.
#   4. Changes one file, backs up again, checks that only that file's content was uploaded, restores the second
#      backup and compares again, and restores the changed file from the first backup to check its old content.
#   5. Checks that no btrfs snapshot is left behind after each backup ("btrfs subvolume list /").
#   6. Starts a backup against a local port nothing listens on (so it keeps retrying the upload while holding its
#      snapshot), kill -9s it, checks that the snapshot it left is a subvolume on the same btrfs filesystem as the
#      source, then runs "snapshots cleanup" and checks it's gone.
#
# Every check prints a PASS or FAIL line, and the run ends with a RESULT line. All output also goes to
# /var/tmp/databackup-e2e.log. It is safe to run more than once.

set -u

if [ $# -ne 4 ]; then
    sed -n '3,6p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
fi

CLIENT_JAR="$(realpath "$1")"
SERVER_URL="$2"
TOKEN_FILE="$(realpath "$3")"
SRC="$(realpath "$4")"
LOG="/var/tmp/databackup-e2e.log"
WORK="/var/tmp/databackup-e2e"
CONFIG_DIR="/etc/databackup"
CONFIG="$CONFIG_DIR/client.json"
CONFIG_SAVED="$WORK/client.json.saved"
KILL_CONFIG="$WORK/client-unreachable.json"
STATE="/var/lib/databackup-client"
MACHINE="e2e-linux"
SOURCE_NAME="test"

refuse() {
    echo "ERROR: $*" >&2
    exit 2
}

[ "$(uname -s)" = "Linux" ] || refuse "this check is for Linux only."
[ "$(id -u)" -eq 0 ] || refuse "btrfs snapshots need root. Run it with sudo."
command -v java >/dev/null 2>&1 || refuse "no java on the PATH."
command -v btrfs >/dev/null 2>&1 || refuse "the btrfs command isn't installed (btrfs-progs)."
[ -f "$CLIENT_JAR" ] || refuse "$CLIENT_JAR doesn't exist."
[ -f "$TOKEN_FILE" ] || refuse "$TOKEN_FILE doesn't exist."
btrfs subvolume show "$SRC" >/dev/null 2>&1 || refuse "$SRC isn't a btrfs subvolume."
[ -z "$(ls -A "$SRC")" ] || [ -f "$SRC/.databackup-e2e" ] \
    || refuse "$SRC isn't empty; the check fills the source with test files and empties it afterwards."
TOKEN="$(tr -d '\r\n' < "$TOKEN_FILE")"

# --- Helpers ---------------------------------------------------------------------------------------------------

PASSES=0
FAILS=0
CLIENT_PID=""

pass() { PASSES=$((PASSES + 1)); echo "PASS: $*"; }
fail() { FAILS=$((FAILS + 1)); echo "FAIL: $*"; }
info() { echo "INFO: $*"; }

check() {
    local description="$1"
    shift
    if "$@"; then pass "$description"; else fail "$description"; fi
}

section() { echo; echo "=== $* ==="; }
equals() { [ "$1" = "$2" ]; }
contains_line() { printf '%s\n' "$1" | grep -qxF -- "$2"; }
contains_text() { printf '%s\n' "$1" | grep -qF -- "$2"; }

client() {
    java -jar "$CLIENT_JAR" "$@"
}

# Every btrfs subvolume on the filesystem holding /, by path, sorted.
all_subvolumes() {
    btrfs subvolume list / | sed 's/^.* path //' | LC_ALL=C sort
}

new_subvolumes_since() {
    all_subvolumes | LC_ALL=C comm -13 "$1" - | sed '/^$/d'
}

recorded_snapshots() {
    if [ -f "$STATE/snapshots.json" ]; then
        grep -o '"source"' "$STATE/snapshots.json" | wc -l | tr -d ' '
    else
        echo 0
    fi
}

nothing_recorded() { [ "$(recorded_snapshots)" = "0" ]; }

write_config() {
    local file="$1" url="$2"
    cat > "$file" <<EOF
{
  "serverUrl": "$url",
  "token": "$TOKEN",
  "machineName": "$MACHINE",
  "sources": [{"name": "$SOURCE_NAME", "path": "$SRC", "excludes": [".databackup-e2e"]}]
}
EOF
    chmod 600 "$file"
}

# A sorted listing of everything under $1, one line per item: type, path, permissions (with setuid, setgid and
# sticky bits), owner, size, modified time (to the microsecond, or the nanosecond with $2 = ns), symlink target.
# Symlinks get only their owner and target.
tree_listing() {
    local root="$1" precision="${2:-us}"
    (cd "$root" && find . -mindepth 1 ! -name .databackup-e2e \
        -printf '%y\t%p\t%m\t%U:%G\t%s\t%T@\t%l\n' | LC_ALL=C sort -t "$(printf '\t')" -k2,2 \
        | awk -F '\t' -v OFS='\t' -v p="$precision" '{
            t = $6
            if (p == "us") { t = substr(t, 1, index(t, ".") + 6) }
            if ($1 == "l") print "link", $2, $4, "-> " $7
            else if ($1 == "d") print "dir", $2, $3, $4, t
            else print $1, $2, $3, $4, $5, t
        }')
}

link_times() {
    (cd "$1" && find . -mindepth 1 -type l -printf '%p\t%T@\n' | LC_ALL=C sort)
}

compare_trees() {
    local label="$1" restored="$2"
    if [ ! -d "$restored" ]; then
        fail "$label: the restore folder $restored exists"
        return
    fi
    echo "diff -r --no-dereference --exclude=.databackup-e2e $SRC $restored"
    if diff -r --no-dereference --exclude=.databackup-e2e "$SRC" "$restored"; then
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
            "(diff above: - source, + restored)"
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

BACKUP_OUTPUT=""
BACKUP_NAME=""
run_backup() {
    local label="$1" code leftover
    all_subvolumes > "$WORK/subvolumes-before.txt"
    echo "Running: databackup backup"
    client backup > "$WORK/backup.out" 2>&1
    code=$?
    BACKUP_OUTPUT="$(cat "$WORK/backup.out")"
    sed 's/^/  /' "$WORK/backup.out"
    check "$label: backup finished with exit code 0 (was $code)" equals "$code" 0
    BACKUP_NAME="$(printf '%s\n' "$BACKUP_OUTPUT" | sed -n 's/^Backup \(.*\) complete: .*$/\1/p' | tail -n 1)"
    check "$label: backup printed its summary line (backup ${BACKUP_NAME:-unknown})" test -n "$BACKUP_NAME"
    check "$label: no files failed to scan" not_contains_failed
    leftover="$(new_subvolumes_since "$WORK/subvolumes-before.txt")"
    if [ -z "$leftover" ]; then
        pass "$label: no new btrfs subvolume or snapshot is left (btrfs subvolume list /)"
    else
        fail "$label: no new btrfs subvolume or snapshot is left (btrfs subvolume list /); left:"
        printf '%s\n' "$leftover" | sed 's/^/  /'
    fi
    check "$label: snapshots.json records no snapshots" nothing_recorded
}

not_contains_failed() {
    ! printf '%s\n' "$BACKUP_OUTPUT" | grep -q '^Failed (couldn'"'"'t be read'
}

uploaded_count() {
    printf '%s\n' "$BACKUP_OUTPUT" | sed -n 's/^Uploading \([0-9]*\) pieces of content .*$/\1/p' | head -n 1
}

distinct_contents() {
    find "$SRC" -type f ! -name .databackup-e2e -exec sha256sum {} + | awk '{ print $1 }' | sort -u \
        | wc -l | tr -d ' '
}

free_local_port() {
    local port="$1"
    while (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; do
        port=$((port + 1))
    done
    echo "$port"
}

empty_source() {
    # Removes the test files, leaving the subvolume itself in place.
    find "$SRC" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
}

cleanup_everything() {
    if [ -n "$CLIENT_PID" ] && kill -0 "$CLIENT_PID" 2>/dev/null; then
        kill -9 "$CLIENT_PID" 2>/dev/null
        wait "$CLIENT_PID" 2>/dev/null
    fi
    CLIENT_PID=""
    if [ -f "$CONFIG" ] && ! nothing_recorded; then
        echo "Snapshots are recorded in $STATE/snapshots.json; running snapshots cleanup"
        client snapshots cleanup
    fi
    empty_source
    rm -rf "$STATE"
    if [ -f "$CONFIG_SAVED" ]; then
        mv -f "$CONFIG_SAVED" "$CONFIG"
    else
        rm -f "$CONFIG"
    fi
    rm -rf "$WORK"
}

cleanup() {
    local status=$?
    trap - EXIT
    trap '' INT TERM PIPE
    echo
    echo "--- Cleaning up ---"
    cleanup_everything
    echo "Emptied $SRC and removed $WORK and $STATE"
    exit "$status"
}

make_test_folder() {
    cd "$SRC" || return 1
    : > .databackup-e2e
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
    printf 'secret\n' > private.txt && chmod 600 private.txt && chown 1000:1000 private.txt
    printf 'nobody owns this\n' > nobody.txt && chown 65534:65534 nobody.txt
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
    chown -h 1000:1000 link-to-hello
    touch -d '2020-01-02 03:04:05.123456789' hello.txt empty
    touch -d '2019-12-31 23:59:59' "sub dir/deeper/deep.txt"
    touch -d '2020-01-02 03:04:05' "sub dir/deeper" "empty dir"
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
    echo "DataBackup Linux (btrfs) end-to-end check"
    echo "Started:  $(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "System:   $(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-unknown}"), $(uname -r)"
    echo "Java:     $(java -version 2>&1 | head -n 1)"
    echo "Source:   $SRC (filesystem $(findmnt -n -o UUID --target "$SRC"))"

    section "Preparing"
    if [ -e "$WORK" ] || [ -f "$SRC/.databackup-e2e" ]; then
        echo "Cleaning up what a previous run left behind"
        cleanup_everything
    fi
    mkdir -p "$WORK" "$CONFIG_DIR" || { fail "create $WORK"; return 1; }
    [ -f "$CONFIG" ] && cp -p "$CONFIG" "$CONFIG_SAVED"
    rm -rf "$STATE"
    if make_test_folder; then pass "filled the test subvolume $SRC"; else fail "filled the test subvolume $SRC"; return 1; fi
    echo "Test folder:"
    tree_listing "$SRC" | sed 's/^/  /'
    write_config "$CONFIG" "$SERVER_URL"
    echo "Subvolumes before the run:"
    all_subvolumes | sed 's/^/  /'

    local backups_before
    backups_before="$(client list "$MACHINE" 2>/dev/null | sed '/^$/d' | wc -l | tr -d ' ')"
    info "the server holds $backups_before backups of $MACHINE before the run"

    section "First backup"
    run_backup "first backup"
    local first_backup="$BACKUP_NAME" expected_uploads uploads code listing
    expected_uploads="$(distinct_contents)"
    uploads="$(uploaded_count)"
    check "first backup: uploaded every distinct content (the empty one too) once ($expected_uploads expected, ${uploads:-none} uploaded)" \
        equals "$uploads" "$expected_uploads"
    check "first backup: the state folder is the Linux default $STATE" test -d "$STATE"

    section "list"
    listing="$(client list 2>&1)"; code=$?
    printf '%s\n' "$listing" | sed 's/^/  /'
    check "list finished with exit code 0 (was $code) and lists the machine $MACHINE" \
        equals "$code:$(contains_line "$listing" "$MACHINE" && echo yes)" "0:yes"
    listing="$(client list "$MACHINE" 2>&1)"; code=$?
    printf '%s\n' "$listing" | sed 's/^/  /'
    check "list $MACHINE finished with exit code 0 (was $code) and shows the backup ${first_backup:-?}" \
        equals "$code:$(printf '%s\n' "$listing" | awk -v b="$first_backup" '$1 == b' | wc -l | tr -d ' ')" "0:1"
    listing="$(client list "$MACHINE" latest "$SOURCE_NAME" 2>&1)"; code=$?
    printf '%s\n' "$listing" | sed 's/^/  /'
    check "list $MACHINE latest $SOURCE_NAME finished with exit code 0 (was $code)" equals "$code" 0
    check "the directory listing shows hello.txt" contains_text "$listing" " hello.txt"
    check "the directory listing shows the non-ASCII name Grüße 日本語.txt" contains_text "$listing" "Grüße 日本語.txt"
    check "the directory listing shows the symlink link-to-hello" contains_text "$listing" "link-to-hello"
    check "the directory listing leaves out the excluded marker file" \
        equals "$(contains_text "$listing" ".databackup-e2e" && echo yes)" ""

    section "Restore of the first backup"
    echo "Running: databackup restore $MACHINE latest $SOURCE_NAME $WORK/restore1"
    client restore "$MACHINE" latest "$SOURCE_NAME" "$WORK/restore1" > "$WORK/restore.out" 2>&1; code=$?
    sed 's/^/  /' "$WORK/restore.out"
    check "restore finished with exit code 0 (was $code)" equals "$code" 0
    compare_trees "first restore" "$WORK/restore1"
    client restore "$MACHINE" latest "$SOURCE_NAME" "$WORK/restore1" > "$WORK/restore-again.out" 2>&1; code=$?
    sed 's/^/  /' "$WORK/restore-again.out"
    check "restoring again onto the same folder without --overwrite is refused (exit code 1, was $code)" \
        equals "$code" 1

    section "Second backup after changing one file"
    local old_hash new_hash
    old_hash="$(sha256sum "$SRC/change-me.bin" | cut -d' ' -f1)"
    head -c 262144 /dev/urandom > "$SRC/change-me.bin"
    new_hash="$(sha256sum "$SRC/change-me.bin" | cut -d' ' -f1)"
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
    check "list $MACHINE shows two more backups than before the run" \
        equals "$(printf '%s\n' "$listing" | sed '/^$/d' | wc -l | tr -d ' ')" "$((backups_before + 2))"

    section "Restore of the second backup"
    client restore "$MACHINE" latest "$SOURCE_NAME" "$WORK/restore2" > "$WORK/restore2.out" 2>&1; code=$?
    sed 's/^/  /' "$WORK/restore2.out"
    check "restore of the second backup finished with exit code 0 (was $code)" equals "$code" 0
    compare_trees "second restore" "$WORK/restore2"
    if [ -n "$first_backup" ]; then
        client restore "$MACHINE" "$first_backup" "$SOURCE_NAME/change-me.bin" "$WORK/old-change-me.bin" \
            > "$WORK/restore3.out" 2>&1; code=$?
        sed 's/^/  /' "$WORK/restore3.out"
        check "restoring change-me.bin from the first backup finished with exit code 0 (was $code)" equals "$code" 0
        check "the first backup's change-me.bin has the old content" \
            equals "$(sha256sum "$WORK/old-change-me.bin" 2>/dev/null | cut -d' ' -f1)" "$old_hash"
    fi

    section "Interrupted backup: kill -9 while it holds its snapshot, then snapshots cleanup"
    local dead_port waited=0 was_running=no leftover still snapshot
    dead_port="$(free_local_port 28090)"
    all_subvolumes > "$WORK/subvolumes-before.txt"
    write_config "$KILL_CONFIG" "http://127.0.0.1:$dead_port"
    echo "Starting a backup against local port $dead_port, where nothing listens, so it retries while holding its snapshot"
    java -jar "$CLIENT_JAR" --config "$KILL_CONFIG" backup > "$WORK/killed.out" 2>&1 &
    CLIENT_PID=$!
    while [ "$waited" -lt 240 ] && kill -0 "$CLIENT_PID" 2>/dev/null \
        && ! { grep -q '^Uploading the manifest' "$WORK/killed.out" && ! nothing_recorded; }; do
        sleep 0.5
        waited=$((waited + 1))
    done
    kill -0 "$CLIENT_PID" 2>/dev/null && was_running=yes
    kill -9 "$CLIENT_PID" 2>/dev/null
    wait "$CLIENT_PID" 2>/dev/null
    echo "Killed the backup (pid $CLIENT_PID) with kill -9"
    CLIENT_PID=""
    echo "Output of the killed backup:"
    sed 's/^/  /' "$WORK/killed.out"
    check "the backup was still running, uploading the manifest, when it was killed" equals "$was_running" yes
    leftover="$(new_subvolumes_since "$WORK/subvolumes-before.txt")"
    echo "New subvolumes after the kill:"
    printf '%s\n' "$leftover" | sed '/^$/d; s/^/  /'
    check "the killed backup left exactly one snapshot behind (so the cleanup has something to do)" \
        equals "$(printf '%s\n' "$leftover" | sed '/^$/d' | wc -l | tr -d ' ')" 1
    check "snapshots.json records it ($(recorded_snapshots) recorded)" equals "$(recorded_snapshots)" 1
    snapshot="/$(printf '%s\n' "$leftover" | head -n 1)"
    if [ "$snapshot" != "/" ]; then
        echo "btrfs subvolume show $snapshot:"
        btrfs subvolume show "$snapshot" 2>&1 | sed 's/^/  /'
        check "the snapshot is in the state folder's snapshots folder ($snapshot)" \
            equals "$(dirname "$snapshot")" "$STATE/snapshots"
        check "the snapshot is on the same btrfs filesystem as the source" \
            equals "$(findmnt -n -o FSTYPE,UUID --target "$snapshot")" "$(findmnt -n -o FSTYPE,UUID --target "$SRC")"
        check "the snapshot is a snapshot of the source subvolume" \
            equals "$(btrfs subvolume show "$snapshot" | sed -n 's/^[[:space:]]*Parent UUID:[[:space:]]*//p')" \
                   "$(btrfs subvolume show "$SRC" | sed -n 's/^[[:space:]]*UUID:[[:space:]]*//p')"
        check "the snapshot is read-only" equals "$(btrfs property get -ts "$snapshot" ro 2>/dev/null)" "ro=true"
    fi
    echo "Running: databackup snapshots cleanup"
    client snapshots cleanup > "$WORK/cleanup.out" 2>&1; code=$?
    sed 's/^/  /' "$WORK/cleanup.out"
    check "snapshots cleanup finished with exit code 0 (was $code)" equals "$code" 0
    still="$(new_subvolumes_since "$WORK/subvolumes-before.txt")"
    if [ -z "$still" ]; then
        pass "no new btrfs subvolume or snapshot is left after the cleanup"
    else
        fail "no new btrfs subvolume or snapshot is left after the cleanup; left:"
        printf '%s\n' "$still" | sed 's/^/  /'
    fi
    check "the snapshot's folder is gone" test ! -e "$snapshot"
    check "snapshots.json records no snapshots after the cleanup" nothing_recorded
    local again
    again="$(client snapshots cleanup 2>&1)"; code=$?
    echo "  $again"
    check "a second snapshots cleanup succeeds and finds nothing" equals "$code:$again" "0:No leftover snapshots."

    section "Subvolumes after the run"
    all_subvolumes | sed 's/^/  /'
    return 0
}

main 2>&1 | (trap '' INT; exec tee "$LOG")
STATUS=${PIPESTATUS[0]}
echo
echo "Log written to $LOG"
exit "$STATUS"
