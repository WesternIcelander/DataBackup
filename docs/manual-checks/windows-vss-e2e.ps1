<#
End-to-end check of the DataBackup client on Windows (NTFS, Volume Shadow Copy). Run it in an elevated
PowerShell (Run as administrator) on the Windows machine:

  powershell -NoProfile -ExecutionPolicy Bypass -File .\windows-vss-e2e.ps1 <client jar> <server URL> <token file>

for example

  powershell -NoProfile -ExecutionPolicy Bypass -File .\windows-vss-e2e.ps1 .\databackup-client.jar http://backup-server:8080 .\token

The server runs elsewhere (or on this machine) and must already be up, with a fresh data folder (the first backup
must upload all the test content, so the server mustn't hold it already). The token needs upload:e2e-windows and
read:e2e-windows. Build the client jar with "mvn -o -q package" (client/target/). Java 17 must be on the PATH.

Sources on Windows must be whole drives, so the backup source is C:\ with every top-level entry of C:\ excluded
except the test folder C:\databackup-e2e and the test file C:\databackup-e2e-root.txt.

What it does, with the real client jar and the real CLI commands:

  1. Fills C:\databackup-e2e with an empty file, an empty folder, nested folders, non-ASCII names, duplicate
     content, a few MiB of random data, read-only and hidden files, file and directory symlinks, and modified times
     with sub-second precision. Puts C:\databackup-e2e-root.txt in the root of C:, which only the root-listing
     workaround finds (the root of a shadow copy can't be listed). Notes which shadow copies exist before the run
     (creating one first if C: has none, so there is always one to leave alone).
  2. Writes C:\ProgramData\DataBackup-client\client.json (the client's default config; state in
     C:\ProgramData\DataBackup-client\state), moving any existing config aside and putting it back afterwards.
  3. Runs "backup" while this script holds one test file open with no sharing allowed (only a shadow copy can read
     it), then "list", then "restore" to a temp folder, and compares the restored tree with the source: names,
     types, sizes, SHA-256, modified times and symlink targets. Windows has no POSIX metadata, so the restore
     must work without it. File attributes (read-only, hidden) aren't backed up; differences are reported as INFO.
  4. Changes one file, backs up again, checks that only that file's content was uploaded, restores the second
     backup and compares again, and restores the changed file from the first backup to check its old content.
  5. Checks that every backup deletes its shadow copy ("vssadmin list shadows") and that shadow copies that
     existed before the run are left alone.
  6. Starts a backup against a local port nothing listens on (so it keeps retrying the upload while holding its
     shadow copy), kills it with Stop-Process -Force, checks the shadow copy it left is of C:, then runs
     "snapshots cleanup" and checks it's gone.

Every check prints a PASS or FAIL line, and the run ends with a RESULT line. All output also goes to
%TEMP%\databackup-e2e.log. It is safe to run more than once.
#>

param(
    [Parameter(Position = 0)] [string] $ClientJar,
    [Parameter(Position = 1)] [string] $ServerUrl,
    [Parameter(Position = 2)] [string] $TokenFile
)

$ErrorActionPreference = 'Continue'

function Refuse([string] $message) {
    [Console]::Error.WriteLine("ERROR: $message")
    exit 2
}

if (-not $ClientJar -or -not $ServerUrl -or -not $TokenFile) {
    [Console]::Error.WriteLine('Usage: windows-vss-e2e.ps1 <client jar> <server URL> <token file>')
    exit 2
}

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { Refuse 'shadow copies need administrator rights. Run it in an elevated PowerShell.' }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Refuse 'no java on the PATH.' }
if (-not (Test-Path -LiteralPath $ClientJar -PathType Leaf)) { Refuse "$ClientJar doesn't exist." }
if (-not (Test-Path -LiteralPath $TokenFile -PathType Leaf)) { Refuse "$TokenFile doesn't exist." }

$CLIENT_JAR = (Resolve-Path -LiteralPath $ClientJar).ProviderPath
$TOKEN = ([IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenFile).ProviderPath)).Trim()
$SRC = 'C:\databackup-e2e'
$ROOT_FILE_NAME = 'databackup-e2e-root.txt'
$ROOT_FILE = "C:\$ROOT_FILE_NAME"
$MARKER = '.databackup-e2e'
$LOG = Join-Path $env:TEMP 'databackup-e2e.log'
$WORK = Join-Path $env:TEMP 'databackup-e2e-work'
$CONFIG_DIR = 'C:\ProgramData\DataBackup-client'
$CONFIG = "$CONFIG_DIR\client.json"
$CONFIG_SAVED = "$WORK\client.json.saved"
$KILL_CONFIG = "$WORK\client-unreachable.json"
$STATE = "$CONFIG_DIR\state"
$MACHINE = 'e2e-windows'
$SOURCE_NAME = 'C'
$TEST_FOLDER = 'databackup-e2e'

if ((Test-Path -LiteralPath $SRC) -and -not (Test-Path -LiteralPath "$SRC\$MARKER")) {
    Refuse "$SRC exists but wasn't made by this check; move it away first."
}

# Non-ASCII names, built from code points so this file is plain ASCII (Windows PowerShell 5.1 reads a script
# without a byte order mark in the system code page).
$GRUSSE = 'Gr' + [char]0xFC + [char]0xDF + 'e ' + [char]0x65E5 + [char]0x672C + [char]0x8A9E + '.txt'
$UNICODE_FOLDER = [string][char]0xDC + 'n' + [char]0xEF + 'c' + [char]0xF8 + 'd' + [char]0xE9 + ' folder'
$FAIRU = [string][char]0x30D5 + [char]0x30A1 + [char]0x30A4 + [char]0x30EB + '.txt'

# The real java.exe (not the javapath launcher stub), found through java.home.
$JAVA = 'java'
foreach ($line in (cmd /c 'java -XshowSettings:properties -version 2>&1')) {
    if ("$line" -match '^\s*java\.home = (.+)$') { $JAVA = Join-Path $Matches[1].Trim() 'bin\java.exe' }
}

$UTF8 = New-Object Text.UTF8Encoding($false)
$ANSI = [Text.Encoding]::Default

# --- Helpers ------------------------------------------------------------------------------------------------------

$script:PASSES = 0
$script:FAILS = 0
$script:LockStream = $null
$script:KillProcess = $null
$script:CreatedShadow = $null

function Say([string] $line = '') {
    Write-Host $line
    [IO.File]::AppendAllText($LOG, $line + "`r`n", $UTF8)
}

function Indent([string] $text) {
    foreach ($l in ($text -split "`r?`n")) { if ($l -ne '') { Say "  $l" } }
}

function Pass([string] $what) { $script:PASSES++; Say "PASS: $what" }
function Fail([string] $what) { $script:FAILS++; Say "FAIL: $what" }
function Info([string] $what) { Say "INFO: $what" }
function Check([string] $what, [bool] $ok) { if ($ok) { Pass $what } else { Fail $what } }
function Section([string] $what) { Say; Say "=== $what ===" }

function Write-Utf8([string] $path, [string] $text) { [IO.File]::WriteAllText($path, $text, $UTF8) }

# Runs the client with arguments $arguments, output (stdout and stderr together) to $outFile. Returns the exit code.
# The client writes in the system code page, which is how the output is read back.
function Invoke-Client([string[]] $arguments, [string] $outFile) {
    $quoted = ($arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = "$env:SystemRoot\System32\cmd.exe"
    $psi.Arguments = '/d /s /c ""' + $JAVA + '" -jar "' + $CLIENT_JAR + '" ' + $quoted + ' > "' + $outFile + '" 2>&1"'
    $psi.UseShellExecute = $false
    $process = [Diagnostics.Process]::Start($psi)
    $process.WaitForExit()
    return $process.ExitCode
}

# Reads a client's output file. Retries for a while if something (such as an antivirus scan) has it open.
function Read-Output([string] $file) {
    if (-not (Test-Path -LiteralPath $file)) { return '' }
    for ($try = 1; ; $try++) {
        try {
            $stream = New-Object IO.FileStream($file, 'Open', 'Read', 'ReadWrite, Delete')
            try {
                $reader = New-Object IO.StreamReader($stream, $ANSI)
                return $reader.ReadToEnd()
            } finally {
                $stream.Dispose()
            }
        } catch {
            if ($try -ge 50) { throw }
            Start-Sleep -Milliseconds 200
        }
    }
}

# Removes a file or folder tree, retrying for a while if something (such as an antivirus scan) has a file open.
function Remove-Retrying([string] $path) {
    for ($try = 1; (Test-Path -LiteralPath $path); $try++) {
        try {
            Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction Stop
        } catch {
            if ($try -ge 50) { throw }
            Start-Sleep -Milliseconds 200
        }
    }
}

# Makes a symlink with mklink (/D for a directory symlink); the target is stored as given, relative or not.
function New-Symlink([string] $link, [string] $target, [switch] $Directory) {
    $flag = if ($Directory) { '/D ' } else { '' }
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = "$env:SystemRoot\System32\cmd.exe"
    $psi.Arguments = '/d /s /c "mklink ' + $flag + '"' + $link + '" "' + $target + '" >nul"'
    $psi.UseShellExecute = $false
    $process = [Diagnostics.Process]::Start($psi)
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "mklink $flag$link $target failed" }
}

function Json-String([string] $s) {
    $b = New-Object Text.StringBuilder
    foreach ($c in $s.ToCharArray()) {
        switch ($c) {
            '"' { [void]$b.Append('\"') }
            '\' { [void]$b.Append('\\') }
            default {
                if ([int]$c -lt 0x20) { [void]$b.Append(('\u{0:x4}' -f [int]$c)) } else { [void]$b.Append($c) }
            }
        }
    }
    return '"' + $b.ToString() + '"'
}

function Glob-Escape([string] $name) {
    return [regex]::Replace($name, '[\[\]*?{}\\]', { param($m) '\' + $m.Value })
}

# Writes a client config to $file for server URL $url. Its only source is C:\, with every top-level entry other
# than the test folder and the test file excluded. Rewritten before each backup, so new top-level entries are
# excluded too.
function Write-Config([string] $file, [string] $url) {
    $excludes = @()
    foreach ($item in (Get-ChildItem -LiteralPath 'C:\' -Force)) {
        if ($item.Name -eq $TEST_FOLDER -or $item.Name -eq $ROOT_FILE_NAME) { continue }
        $excludes += (Json-String (Glob-Escape $item.Name))
    }
    $json = "{`n" +
        "  `"serverUrl`": $(Json-String $url),`n" +
        "  `"token`": $(Json-String $TOKEN),`n" +
        "  `"machineName`": $(Json-String $MACHINE),`n" +
        "  `"sources`": [{`"name`": $(Json-String $SOURCE_NAME), `"path`": `"C:\\`", `"excludes`": [" +
        ($excludes -join ', ') + ", $(Json-String "$TEST_FOLDER/$MARKER")]}]`n" +
        "}`n"
    Write-Utf8 $file $json
}

# The IDs of all shadow copies, lower case, sorted.
function Get-ShadowIds {
    $ids = @()
    foreach ($line in (& "$env:SystemRoot\System32\vssadmin.exe" list shadows 2>&1)) {
        if ("$line" -match 'Shadow Copy ID: \{([0-9a-fA-F-]+)\}') { $ids += $Matches[1].ToLower() }
    }
    return @($ids | Sort-Object)
}

# The ID of every shadow copy of C:.
function Get-ShadowIdsOfC {
    $ids = @()
    $current = $null
    foreach ($line in (& "$env:SystemRoot\System32\vssadmin.exe" list shadows 2>&1)) {
        if ("$line" -match 'Shadow Copy ID: \{([0-9a-fA-F-]+)\}') { $current = $Matches[1].ToLower() }
        elseif ("$line" -match 'Original Volume: \(C:\)' -and $current) { $ids += $current }
    }
    return @($ids | Sort-Object)
}

function New-Shadows([string[]] $before) {
    return @(Get-ShadowIds | Where-Object { $before -notcontains $_ })
}

function Recorded-Snapshots {
    $file = "$STATE\snapshots.json"
    if (-not (Test-Path -LiteralPath $file)) { return 0 }
    return ([regex]::Matches([IO.File]::ReadAllText($file), '"source"')).Count
}

function Is-Link($item) { return [bool]($item.Attributes -band [IO.FileAttributes]::ReparsePoint) }

# Every item under $root (not following links), sorted by relative path.
function Get-Tree([string] $root) {
    $result = New-Object Collections.Generic.List[object]
    $pending = New-Object Collections.Generic.Stack[string]
    $pending.Push($root)
    while ($pending.Count -gt 0) {
        $dir = $pending.Pop()
        foreach ($item in (Get-ChildItem -LiteralPath $dir -Force)) {
            $relative = $item.FullName.Substring($root.Length + 1)
            if ($relative -eq $MARKER) { continue }
            $result.Add([pscustomobject]@{ Relative = $relative; Item = $item })
            if ($item.PSIsContainer -and -not (Is-Link $item)) { $pending.Push($item.FullName) }
        }
    }
    return @($result | Sort-Object { $_.Relative } -Culture ([Globalization.CultureInfo]::InvariantCulture))
}

function Link-Target($item) {
    $target = $item.Target
    if ($target -is [array]) { $target = $target[0] }
    return "$target"
}

# One line per item: type, path, size, SHA-256 and modified time (100 ns ticks, UTC). Symlinks get their kind (file
# or directory symlink) and target only.
function Tree-Listing([string] $root) {
    $lines = @()
    foreach ($entry in (Get-Tree $root)) {
        $item = $entry.Item
        if (Is-Link $item) {
            $kind = if ($item.PSIsContainer) { 'dirlink' } else { 'filelink' }
            $lines += "$kind`t$($entry.Relative)`t-> $(Link-Target $item)"
        } elseif ($item.PSIsContainer) {
            $lines += "dir`t$($entry.Relative)`t$($item.LastWriteTimeUtc.Ticks)"
        } else {
            $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $item.FullName).Hash.ToLower()
            $lines += "file`t$($entry.Relative)`t$($item.Length)`t$hash`t$($item.LastWriteTimeUtc.Ticks)"
        }
    }
    return $lines
}

function Attribute-Listing([string] $root) {
    $mask = [IO.FileAttributes]::ReadOnly -bor [IO.FileAttributes]::Hidden -bor [IO.FileAttributes]::System
    $lines = @()
    foreach ($entry in (Get-Tree $root)) {
        $lines += "$($entry.Relative)`t$($entry.Item.Attributes -band $mask)"
    }
    return $lines
}

function Link-Times([string] $root) {
    $lines = @()
    foreach ($entry in (Get-Tree $root)) {
        if (Is-Link $entry.Item) { $lines += "$($entry.Relative)`t$($entry.Item.LastWriteTimeUtc.Ticks)" }
    }
    return $lines
}

function Show-Diff([string[]] $expected, [string[]] $actual) {
    foreach ($d in (Compare-Object -ReferenceObject $expected -DifferenceObject $actual -CaseSensitive)) {
        $sign = if ($d.SideIndicator -eq '<=') { '-' } else { '+' }
        Say "  $sign $($d.InputObject)"
    }
}

function Same-Lines([string[]] $a, [string[]] $b) {
    return ((@($a) -join "`n") -ceq (@($b) -join "`n"))
}

function Compare-Trees([string] $label, [string] $restored) {
    if (-not (Test-Path -LiteralPath $restored -PathType Container)) {
        Fail "${label}: the restore folder $restored exists"
        return
    }
    $expected = Tree-Listing $SRC
    $actual = Tree-Listing $restored
    if (Same-Lines $expected $actual) {
        Pass "${label}: names, types, sizes, SHA-256, modified times (to 100 ns) and symlink targets match"
    } else {
        Fail "${label}: names, types, sizes, SHA-256, modified times (to 100 ns) and symlink targets match (- source, + restored):"
        Show-Diff $expected $actual
    }
    $expectedAttributes = Attribute-Listing $SRC
    $actualAttributes = Attribute-Listing $restored
    if (Same-Lines $expectedAttributes $actualAttributes) {
        Info "${label}: read-only, hidden and system attributes match too"
    } else {
        Info "${label}: read-only, hidden and system attributes differ (not backed up; not counted as a failure):"
        Show-Diff $expectedAttributes $actualAttributes
    }
    if (Same-Lines (Link-Times $SRC) (Link-Times $restored)) {
        Info "${label}: symlink modified times match too"
    } else {
        Info "${label}: symlink modified times differ (not counted as a failure):"
        Show-Diff (Link-Times $SRC) (Link-Times $restored)
    }
}

$script:BackupOutput = ''
$script:BackupName = ''
function Run-Backup([string] $label) {
    $before = @(Get-ShadowIds)
    Write-Config $CONFIG $ServerUrl
    Say 'Running: databackup backup'
    $code = Invoke-Client @('backup') "$WORK\backup.out"
    $script:BackupOutput = Read-Output "$WORK\backup.out"
    Indent $script:BackupOutput
    Check "${label}: backup finished with exit code 0 (was $code)" ($code -eq 0)
    $script:BackupName = ''
    foreach ($l in ($script:BackupOutput -split "`r?`n")) {
        if ($l -match '^Backup (.*) complete: ') { $script:BackupName = $Matches[1] }
    }
    $shown = if ($script:BackupName) { $script:BackupName } else { 'unknown' }
    Check "${label}: backup printed its summary line (backup $shown)" ([bool]$script:BackupName)
    Check "${label}: no files failed to scan" (-not ($script:BackupOutput -match "(?m)^Failed \(couldn't be read"))
    $left = @(New-Shadows $before)
    if ($left.Count -eq 0) {
        Pass "${label}: the backup deleted its shadow copy (vssadmin list shadows shows no new one)"
    } else {
        Fail "${label}: the backup deleted its shadow copy (vssadmin list shadows); left: $($left -join ', ')"
    }
    Check "${label}: snapshots.json records no snapshots" ((Recorded-Snapshots) -eq 0)
}

function Uploaded-Count {
    if ($script:BackupOutput -match '(?m)^Uploading (\d+) pieces of content ') { return [int]$Matches[1] }
    return -1
}

function Distinct-Contents {
    $hashes = @()
    foreach ($entry in (Get-Tree $SRC)) {
        if (-not $entry.Item.PSIsContainer -and -not (Is-Link $entry.Item)) {
            $hashes += (Get-FileHash -Algorithm SHA256 -LiteralPath $entry.Item.FullName).Hash
        }
    }
    $hashes += (Get-FileHash -Algorithm SHA256 -LiteralPath $ROOT_FILE).Hash
    return @($hashes | Sort-Object -Unique).Count
}

function Free-LocalPort([int] $port) {
    $used = @(Get-NetTCPConnection -ErrorAction SilentlyContinue | ForEach-Object { $_.LocalPort })
    while ($used -contains $port) { $port++ }
    return $port
}

function Random-File([string] $path, [int] $size) {
    $bytes = New-Object byte[] $size
    (New-Object Random).NextBytes($bytes)
    [IO.File]::WriteAllBytes($path, $bytes)
}

function Set-Time([string] $path, [datetime] $utc) {
    (Get-Item -LiteralPath $path -Force).LastWriteTimeUtc = $utc
}

function Release-Lock {
    if ($script:LockStream) { $script:LockStream.Dispose(); $script:LockStream = $null }
}

function Remove-Test-Files {
    Release-Lock
    if (Test-Path -LiteralPath $SRC) {
        # Clear read-only attributes, and remove links before the folders they point into.
        Get-ChildItem -LiteralPath $SRC -Recurse -Force -ErrorAction SilentlyContinue |
            Where-Object { -not (Is-Link $_) } | ForEach-Object { $_.Attributes = 'Normal' }
        Get-ChildItem -LiteralPath $SRC -Force | Where-Object { Is-Link $_ } | ForEach-Object {
            if ($_.PSIsContainer) { [IO.Directory]::Delete($_.FullName) } else { [IO.File]::Delete($_.FullName) }
        }
        Remove-Retrying $SRC
    }
    if (Test-Path -LiteralPath $ROOT_FILE) { Remove-Item -LiteralPath $ROOT_FILE -Force }
}

function Cleanup-Everything {
    if ($script:KillProcess -and -not $script:KillProcess.HasExited) {
        Stop-Process -Id $script:KillProcess.Id -Force -ErrorAction SilentlyContinue
        $script:KillProcess.WaitForExit()
    }
    $script:KillProcess = $null
    if ((Test-Path -LiteralPath $CONFIG) -and (Recorded-Snapshots) -gt 0) {
        Say "Snapshots are recorded in $STATE\snapshots.json; running snapshots cleanup"
        [void](Invoke-Client @('snapshots', 'cleanup') "$env:TEMP\databackup-e2e-cleanup.out")
        Indent (Read-Output "$env:TEMP\databackup-e2e-cleanup.out")
        Remove-Item -LiteralPath "$env:TEMP\databackup-e2e-cleanup.out" -Force -ErrorAction SilentlyContinue
    }
    Remove-Test-Files
    if (Test-Path -LiteralPath $STATE) { Remove-Item -LiteralPath $STATE -Recurse -Force }
    if (Test-Path -LiteralPath $CONFIG_SAVED) {
        Move-Item -LiteralPath $CONFIG_SAVED -Destination $CONFIG -Force
    } elseif (Test-Path -LiteralPath $CONFIG) {
        Remove-Item -LiteralPath $CONFIG -Force
    }
    if (Test-Path -LiteralPath $WORK) {
        Get-ChildItem -LiteralPath $WORK -Recurse -Force -ErrorAction SilentlyContinue |
            Where-Object { -not (Is-Link $_) } | ForEach-Object { $_.Attributes = 'Normal' }
        Get-ChildItem -LiteralPath $WORK -Recurse -Force -ErrorAction SilentlyContinue | Where-Object { Is-Link $_ } |
            ForEach-Object { if ($_.PSIsContainer) { [IO.Directory]::Delete($_.FullName) } else { [IO.File]::Delete($_.FullName) } }
        Remove-Retrying $WORK
    }
    if ($script:CreatedShadow) {
        & "$env:SystemRoot\System32\vssadmin.exe" delete shadows "/Shadow={$($script:CreatedShadow)}" /Quiet | Out-Null
        Say "Deleted the shadow copy this check made before the run ($($script:CreatedShadow))"
        $script:CreatedShadow = $null
    }
}

function Make-Test-Folder {
    New-Item -ItemType Directory -Path $SRC -Force | Out-Null
    Write-Utf8 "$SRC\$MARKER" ''
    Write-Utf8 "$SRC\hello.txt" "hello world`n"
    Write-Utf8 "$SRC\empty" ''
    foreach ($d in @('sub dir\deeper', 'empty dir', $UNICODE_FOLDER)) {
        New-Item -ItemType Directory -Path (Join-Path $SRC $d) -Force | Out-Null
    }
    Write-Utf8 "$SRC\sub dir\nested file.txt" "a file in a folder with a space`n"
    Write-Utf8 "$SRC\sub dir\deeper\deep.txt" "deeper`n"
    Write-Utf8 (Join-Path $SRC $GRUSSE) "non-ASCII name`n"
    Write-Utf8 (Join-Path (Join-Path $SRC $UNICODE_FOLDER) $FAIRU) "inside a non-ASCII folder`n"
    Random-File "$SRC\random.bin" 3145728
    Random-File "$SRC\dup-a.bin" 65536
    Copy-Item -LiteralPath "$SRC\dup-a.bin" -Destination "$SRC\dup-b.bin"
    Random-File "$SRC\change-me.bin" 262144
    Write-Utf8 "$SRC\locked.txt" "held open with no sharing during the first backup`n"
    Write-Utf8 "$SRC\readonly.txt" "read only`n"
    (Get-Item -LiteralPath "$SRC\readonly.txt").Attributes = 'ReadOnly'
    Write-Utf8 "$SRC\hidden.txt" "hidden`n"
    (Get-Item -LiteralPath "$SRC\hidden.txt").Attributes = 'Hidden'
    New-Symlink "$SRC\link-to-hello" 'hello.txt'
    New-Symlink "$SRC\link-to-dir" 'sub dir' -Directory
    New-Symlink "$SRC\link-non-ascii" "$UNICODE_FOLDER\$FAIRU"
    New-Symlink "$SRC\dangling-link" "does-not-exist"
    Write-Utf8 $ROOT_FILE "in the root of C:, found only through the root-listing workaround`n"
    $base = [datetime]::SpecifyKind([datetime]'2020-01-02 03:04:05', 'Utc')
    Set-Time "$SRC\hello.txt" $base.AddTicks(1234567)
    Set-Time "$SRC\empty" $base.AddTicks(1234567)
    Set-Time "$SRC\sub dir\deeper\deep.txt" ([datetime]::SpecifyKind([datetime]'2019-12-31 23:59:59', 'Utc'))
    Set-Time "$SRC\sub dir\deeper" $base
    Set-Time "$SRC\empty dir" $base
    return $true
}

function Run-Checks {
    Say 'DataBackup Windows (NTFS VSS) end-to-end check'
    Say "Started:  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
    $os = Get-CimInstance Win32_OperatingSystem
    Say "System:   $($os.Caption) $($os.Version), $($os.OSArchitecture)"
    Say "Java:     $((cmd /c 'java -version 2>&1') | Select-Object -First 1) ($JAVA)"
    Say "Source:   C:\ (filesystem $((Get-Volume -DriveLetter C).FileSystem)), test folder $SRC"

    Section 'Preparing'
    if ((Test-Path -LiteralPath $WORK) -or (Test-Path -LiteralPath "$SRC\$MARKER")) {
        Say 'Cleaning up what a previous run left behind'
        Cleanup-Everything
    }
    New-Item -ItemType Directory -Path $WORK -Force | Out-Null
    New-Item -ItemType Directory -Path $CONFIG_DIR -Force | Out-Null
    if (Test-Path -LiteralPath $CONFIG) { Copy-Item -LiteralPath $CONFIG -Destination $CONFIG_SAVED }
    if (Test-Path -LiteralPath $STATE) { Remove-Item -LiteralPath $STATE -Recurse -Force }
    if (-not (Get-ShadowIdsOfC)) {
        Say 'C: has no shadow copies; making one, so there is one the client must leave alone'
        $result = Invoke-CimMethod -ClassName Win32_ShadowCopy -MethodName Create `
            -Arguments @{ Volume = 'C:\'; Context = 'ClientAccessible' }
        if ($result.ReturnValue -eq 0) { $script:CreatedShadow = "$($result.ShadowID)".Trim('{}').ToLower() }
    }
    $preexisting = @(Get-ShadowIds)
    Say "Shadow copies before the run: $(if ($preexisting) { $preexisting -join ', ' } else { 'none' })"
    Check "there is a shadow copy of C: from before the run, which must be left alone" (@(Get-ShadowIdsOfC).Count -gt 0)
    try { $made = Make-Test-Folder } catch { Say "$_"; $made = $false }
    Check "filled the test folder $SRC and made $ROOT_FILE" $made
    if (-not $made) { return $false }
    Say 'Test folder:'
    foreach ($l in (Tree-Listing $SRC)) { Say "  $l" }

    Write-Config $CONFIG $ServerUrl
    $code = Invoke-Client @('list', $MACHINE) "$WORK\list-before.out"
    $backupsBefore = 0
    if ($code -eq 0) {
        $backupsBefore = @((Read-Output "$WORK\list-before.out") -split "`r?`n" | Where-Object { $_ -match '\S' }).Count
    }
    Info "the server holds $backupsBefore backups of $MACHINE before the run"

    Section 'First backup (with locked.txt held open, no sharing)'
    $script:LockStream = [IO.File]::Open("$SRC\locked.txt", 'Open', 'ReadWrite', 'None')
    $lockedOut = $false
    try { [IO.File]::ReadAllBytes("$SRC\locked.txt") | Out-Null } catch { $lockedOut = $true }
    Check 'locked.txt can''t be read from the live drive while held open' $lockedOut
    Run-Backup 'first backup'
    Release-Lock
    $firstBackup = $script:BackupName
    $expectedUploads = Distinct-Contents
    $uploads = Uploaded-Count
    Check "first backup: uploaded every distinct content (the empty one too) once ($expectedUploads expected, $uploads uploaded)" ($uploads -eq $expectedUploads)
    Check "first backup: the state folder is the Windows default $STATE" (Test-Path -LiteralPath $STATE -PathType Container)

    Section 'list'
    $code = Invoke-Client @('list') "$WORK\list.out"; $listing = Read-Output "$WORK\list.out"; Indent $listing
    Check "list finished with exit code 0 (was $code) and lists the machine $MACHINE" (($code -eq 0) -and ($listing -split "`r?`n" -contains $MACHINE))
    $code = Invoke-Client @('list', $MACHINE) "$WORK\list.out"; $listing = Read-Output "$WORK\list.out"; Indent $listing
    $matching = @($listing -split "`r?`n" | Where-Object { ($_ -split '\s+')[0] -eq $firstBackup }).Count
    Check "list $MACHINE finished with exit code 0 (was $code) and shows the backup $firstBackup" (($code -eq 0) -and $matching -eq 1)
    $code = Invoke-Client @('list', $MACHINE, 'latest', $SOURCE_NAME) "$WORK\list.out"; $listing = Read-Output "$WORK\list.out"; Indent $listing
    Check "list $MACHINE latest $SOURCE_NAME finished with exit code 0 (was $code)" ($code -eq 0)
    $rootLines = @($listing -split "`r?`n" | Where-Object { $_ -match '\S' })
    $rootFolderShown = @($rootLines | Where-Object { $_ -like "d * $TEST_FOLDER/" }).Count -eq 1
    $rootFileShown = @($rootLines | Where-Object { $_ -like "f * $ROOT_FILE_NAME" }).Count -eq 1
    Check "the root of C (listed through the root-listing workaround) holds exactly the folder $TEST_FOLDER and the file $ROOT_FILE_NAME" (
        $rootLines.Count -eq 2 -and $rootFolderShown -and $rootFileShown)
    $code = Invoke-Client @('list', $MACHINE, 'latest', "$SOURCE_NAME/$TEST_FOLDER") "$WORK\list.out"; $listing = Read-Output "$WORK\list.out"; Indent $listing
    Check "list $MACHINE latest $SOURCE_NAME/$TEST_FOLDER finished with exit code 0 (was $code)" ($code -eq 0)
    Check 'the directory listing shows hello.txt' $listing.Contains(' hello.txt')
    Check 'the directory listing shows locked.txt (read from the shadow copy while locked)' $listing.Contains(' locked.txt')
    Check 'the directory listing shows the symlink link-to-hello' $listing.Contains('link-to-hello')
    Check 'the directory listing leaves out the excluded marker file' (-not $listing.Contains($MARKER))
    $grusseAnsi = $ANSI.GetString($ANSI.GetBytes($GRUSSE))
    if ($listing.Contains($GRUSSE)) {
        Pass "the directory listing shows the non-ASCII name $GRUSSE"
    } elseif ($listing.Contains($grusseAnsi)) {
        Info "the directory listing shows the non-ASCII name as $grusseAnsi (the console code page can't show all of it)"
    } else {
        Fail "the directory listing shows the non-ASCII name $GRUSSE"
    }

    Section 'Restore of the first backup (no POSIX metadata on Windows)'
    Say "Running: databackup restore $MACHINE latest $SOURCE_NAME/$TEST_FOLDER $WORK\restore1"
    $code = Invoke-Client @('restore', $MACHINE, 'latest', "$SOURCE_NAME/$TEST_FOLDER", "$WORK\restore1") "$WORK\restore.out"
    $out = Read-Output "$WORK\restore.out"; Indent $out
    Check "restore finished with exit code 0 (was $code)" ($code -eq 0)
    Check 'restore reported no failed items' (-not $out.Contains('Restore incomplete'))
    Compare-Trees 'first restore' "$WORK\restore1"
    $code = Invoke-Client @('restore', $MACHINE, 'latest', "$SOURCE_NAME/$TEST_FOLDER", "$WORK\restore1") "$WORK\restore-again.out"
    Indent (Read-Output "$WORK\restore-again.out")
    Check "restoring again onto the same folder without --overwrite is refused (exit code 1, was $code)" ($code -eq 1)
    $code = Invoke-Client @('restore', $MACHINE, 'latest', "$SOURCE_NAME/$ROOT_FILE_NAME", "$WORK\root-file.txt") "$WORK\restore-root.out"
    Indent (Read-Output "$WORK\restore-root.out")
    Check "restoring $ROOT_FILE_NAME from the root of C finished with exit code 0 (was $code)" ($code -eq 0)
    $same = (Test-Path -LiteralPath "$WORK\root-file.txt") -and
        ((Get-FileHash -LiteralPath "$WORK\root-file.txt").Hash -eq (Get-FileHash -LiteralPath $ROOT_FILE).Hash)
    Check "the restored $ROOT_FILE_NAME has the right content" $same

    Section 'Second backup after changing one file'
    $oldHash = (Get-FileHash -LiteralPath "$SRC\change-me.bin").Hash
    Random-File "$SRC\change-me.bin" 262144
    $newHash = (Get-FileHash -LiteralPath "$SRC\change-me.bin").Hash
    Say "Changed change-me.bin: $oldHash -> $newHash"
    Run-Backup 'second backup'
    $secondBackup = $script:BackupName
    $uploads = Uploaded-Count
    Check "second backup: uploaded exactly one piece of content ($uploads)" ($uploads -eq 1)
    Check 'second backup: uploaded 256.0 KiB, the size of change-me.bin' $script:BackupOutput.Contains('Uploading 1 pieces of content (256.0 KiB)')
    foreach ($l in ($script:BackupOutput -split "`r?`n")) { if ($l -match '^Manifest:') { Info "second backup: $l" } }
    Check "second backup has a different name from the first ($firstBackup, $secondBackup)" ($secondBackup -and $secondBackup -ne $firstBackup)
    $code = Invoke-Client @('list', $MACHINE) "$WORK\list.out"; $listing = Read-Output "$WORK\list.out"; Indent $listing
    $count = @($listing -split "`r?`n" | Where-Object { $_ -match '\S' }).Count
    Check "list $MACHINE shows two more backups than before the run ($count, was $backupsBefore)" ($count -eq $backupsBefore + 2)

    Section 'Restore of the second backup'
    $code = Invoke-Client @('restore', $MACHINE, 'latest', "$SOURCE_NAME/$TEST_FOLDER", "$WORK\restore2") "$WORK\restore2.out"
    Indent (Read-Output "$WORK\restore2.out")
    Check "restore of the second backup finished with exit code 0 (was $code)" ($code -eq 0)
    Compare-Trees 'second restore' "$WORK\restore2"
    if ($firstBackup) {
        $code = Invoke-Client @('restore', $MACHINE, $firstBackup, "$SOURCE_NAME/$TEST_FOLDER/change-me.bin", "$WORK\old-change-me.bin") "$WORK\restore3.out"
        Indent (Read-Output "$WORK\restore3.out")
        Check "restoring change-me.bin from the first backup finished with exit code 0 (was $code)" ($code -eq 0)
        $restoredOld = if (Test-Path -LiteralPath "$WORK\old-change-me.bin") { (Get-FileHash -LiteralPath "$WORK\old-change-me.bin").Hash } else { '' }
        Check "the first backup's change-me.bin has the old content" ($restoredOld -eq $oldHash)
    }

    Section 'Interrupted backup: Stop-Process -Force while it holds its shadow copy, then snapshots cleanup'
    $deadPort = Free-LocalPort 28090
    $before = @(Get-ShadowIds)
    Write-Config $KILL_CONFIG "http://127.0.0.1:$deadPort"
    Say "Starting a backup against local port $deadPort, where nothing listens, so it retries while holding its shadow copy"
    $script:KillProcess = Start-Process -FilePath $JAVA -ArgumentList @('-jar', "`"$CLIENT_JAR`"", '--config', "`"$KILL_CONFIG`"", 'backup') `
        -RedirectStandardOutput "$WORK\killed.out" -RedirectStandardError "$WORK\killed.err" -NoNewWindow -PassThru
    $waited = 0
    while ($waited -lt 480 -and -not $script:KillProcess.HasExited -and
           -not ((Read-Output "$WORK\killed.out").Contains('Uploading the manifest') -and (Recorded-Snapshots) -gt 0)) {
        Start-Sleep -Milliseconds 500
        $waited++
    }
    $wasRunning = -not $script:KillProcess.HasExited
    Stop-Process -Id $script:KillProcess.Id -Force -ErrorAction SilentlyContinue
    $script:KillProcess.WaitForExit()
    Say "Killed the backup (pid $($script:KillProcess.Id)) with Stop-Process -Force"
    $script:KillProcess = $null
    Say 'Output of the killed backup:'
    Indent (Read-Output "$WORK\killed.out")
    Indent (Read-Output "$WORK\killed.err")
    Check 'the backup was still running, uploading the manifest, when it was killed' $wasRunning
    $left = @(New-Shadows $before)
    Say "New shadow copies after the kill: $(if ($left) { $left -join ', ' } else { 'none' })"
    Check "the killed backup left exactly one shadow copy behind (so the cleanup has something to do)" ($left.Count -eq 1)
    Check "snapshots.json records it ($(Recorded-Snapshots) recorded)" ((Recorded-Snapshots) -eq 1)
    if ($left.Count -eq 1) {
        Check 'the leftover shadow copy is of C:' (@(Get-ShadowIdsOfC) -contains $left[0])
        $recorded = [IO.File]::ReadAllText("$STATE\snapshots.json")
        Check 'snapshots.json records that shadow copy by its ID' ($recorded.ToLower().Contains($left[0]))
    }
    Say 'Running: databackup snapshots cleanup'
    $code = Invoke-Client @('snapshots', 'cleanup') "$WORK\cleanup.out"
    Indent (Read-Output "$WORK\cleanup.out")
    Check "snapshots cleanup finished with exit code 0 (was $code)" ($code -eq 0)
    $still = @(New-Shadows $before)
    if ($still.Count -eq 0) { Pass 'no new shadow copy is left after the cleanup' }
    else { Fail "no new shadow copy is left after the cleanup; left: $($still -join ', ')" }
    Check 'snapshots.json records no snapshots after the cleanup' ((Recorded-Snapshots) -eq 0)
    $code = Invoke-Client @('snapshots', 'cleanup') "$WORK\cleanup2.out"
    $again = (Read-Output "$WORK\cleanup2.out").Trim()
    Say "  $again"
    Check 'a second snapshots cleanup succeeds and finds nothing' (($code -eq 0) -and $again -eq 'No leftover snapshots.')

    Section 'Shadow copies after the run'
    $after = @(Get-ShadowIds)
    Say "Shadow copies now: $(if ($after) { $after -join ', ' } else { 'none' })"
    $missing = @($preexisting | Where-Object { $after -notcontains $_ })
    Check "every shadow copy that existed before the run is still there ($($preexisting.Count))" ($missing.Count -eq 0)
    $extra = @($after | Where-Object { $preexisting -notcontains $_ })
    Check 'no shadow copy made during the run is left' ($extra.Count -eq 0)
    return $true
}

# --- Main -----------------------------------------------------------------------------------------------------------

if (Test-Path -LiteralPath $LOG) { Remove-Item -LiteralPath $LOG -Force }
$finished = $false
try {
    $finished = Run-Checks
} catch {
    Say "Error: $_"
    Say "$($_.ScriptStackTrace)"
} finally {
    Say
    Say '--- Cleaning up ---'
    try { Cleanup-Everything } catch { Say "Cleanup error: $_" }
    Say "Removed $SRC, $ROOT_FILE, $WORK and $STATE"
}
Say
if (-not $finished) {
    Say 'The checks stopped early because a step they depend on failed (see the FAIL or Error line above).'
    if ($script:FAILS -eq 0) { $script:FAILS = 1 }
}
if ($script:FAILS -eq 0) {
    Say "RESULT: PASS (all $($script:PASSES) checks passed)"
} else {
    Say "RESULT: FAIL ($($script:FAILS) of $($script:PASSES + $script:FAILS) checks failed)"
}
Write-Host
Write-Host "Log written to $LOG"
if ($script:FAILS -eq 0) { exit 0 } else { exit 1 }
