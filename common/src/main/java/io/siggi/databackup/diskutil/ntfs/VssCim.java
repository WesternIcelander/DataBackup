package io.siggi.databackup.diskutil.ntfs;

import io.siggi.databackup.diskutil.SnapshotException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates, lists and deletes Volume Shadow Copies through the {@code Win32_ShadowCopy} CIM class, using Windows
 * PowerShell. This replaces {@code wmic} (deprecated and removed from newer Windows builds) and the parsing of
 * {@code vssadmin}'s localized output: the scripts print tab-separated, tagged lines whose values come straight from
 * CIM properties, so nothing depends on the display language.
 */
final class VssCim {

    static final String POWERSHELL_PATH = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";

    private static final Pattern GUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private VssCim() {
    }

    /** One {@code Win32_ShadowCopy} instance. */
    record ShadowCopy(UUID id, UUID volumeId, String deviceObject) {
    }

    // Shared prologue: stop on any error, print it as one ERROR line and exit 1.
    private static final String PROLOGUE = """
            $ErrorActionPreference = 'Stop'
            $ProgressPreference = 'SilentlyContinue'
            trap {
                [Console]::Out.WriteLine("ERROR`t" + ($_.Exception.Message -replace '\\s+', ' ').Trim())
                exit 1
            }
            """;

    static String createScript(String drive) {
        return PROLOGUE + """
                $r = Invoke-CimMethod -ClassName Win32_ShadowCopy -MethodName Create -Arguments @{ Volume = '%s'; Context = 'ClientAccessible' }
                [Console]::Out.WriteLine("CREATED`t" + $r.ReturnValue + "`t" + $r.ShadowID)
                """.formatted(drive);
    }

    static String listScript() {
        return PROLOGUE + """
                foreach ($s in @(Get-CimInstance -ClassName Win32_ShadowCopy)) {
                    [Console]::Out.WriteLine("SHADOW`t" + $s.ID + "`t" + $s.VolumeName + "`t" + $s.DeviceObject)
                }
                [Console]::Out.WriteLine("END")
                """;
    }

    static String deleteScript(UUID shadowCopyId) {
        return PROLOGUE + """
                $s = @(Get-CimInstance -ClassName Win32_ShadowCopy -Filter "ID='{%s}'")
                if ($s.Count -eq 0) {
                    [Console]::Out.WriteLine("NOTFOUND")
                    exit 2
                }
                $s | Remove-CimInstance
                [Console]::Out.WriteLine("DELETED")
                """.formatted(shadowCopyId.toString());
    }

    /** Creates a shadow copy of {@code drive} (such as {@code C:\}) and returns its ID. */
    static UUID create(String drive) throws SnapshotException {
        return parseCreate(run(createScript(drive)));
    }

    static List<ShadowCopy> list() throws SnapshotException {
        return parseList(run(listScript()));
    }

    static void delete(UUID shadowCopyId) throws SnapshotException {
        String output = run(deleteScript(shadowCopyId));
        for (String line : lines(output)) {
            if (line.equals("DELETED")) return;
            if (line.equals("NOTFOUND")) {
                throw new SnapshotException("Shadow copy {" + shadowCopyId + "} doesn't exist.");
            }
        }
        throw failure("Unable to delete shadow copy {" + shadowCopyId + "}", output);
    }

    static UUID parseCreate(String output) throws SnapshotException {
        for (String line : lines(output)) {
            if (!line.startsWith("CREATED\t")) continue;
            String[] parts = line.split("\t", -1);
            int returnValue;
            try {
                returnValue = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                throw new SnapshotException("Unable to create shadow copy: unexpected output: " + line);
            }
            if (returnValue != 0) {
                throw new SnapshotException("Unable to create shadow copy: " + describeCreateError(returnValue));
            }
            UUID id = parts.length > 2 ? findGuid(parts[2]) : null;
            if (id == null) {
                throw new SnapshotException("Unable to create shadow copy: no shadow copy ID in output: " + line);
            }
            return id;
        }
        throw failure("Unable to create shadow copy", output);
    }

    static List<ShadowCopy> parseList(String output) throws SnapshotException {
        List<ShadowCopy> result = new ArrayList<>();
        boolean ended = false;
        for (String line : lines(output)) {
            if (line.equals("END")) {
                ended = true;
            } else if (line.startsWith("SHADOW\t")) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 4) continue;
                UUID id = findGuid(parts[1]);
                UUID volumeId = findGuid(parts[2]);
                String deviceObject = parts[3].trim();
                if (id == null || volumeId == null) continue;
                result.add(new ShadowCopy(id, volumeId, deviceObject.isEmpty() ? null : deviceObject));
            }
        }
        if (!ended) throw failure("Unable to list shadow copies", output);
        return result;
    }

    static String describeCreateError(int returnValue) {
        String reason = switch (returnValue) {
            case 1 -> "access denied (run as an administrator)";
            case 2 -> "invalid argument";
            case 3 -> "the volume was not found";
            case 4 -> "the volume does not support shadow copies";
            case 5 -> "unsupported shadow copy context";
            case 6 -> "insufficient storage for the shadow copy";
            case 7 -> "the volume is in use";
            case 8 -> "the maximum number of shadow copies has been reached";
            case 9 -> "another shadow copy operation is in progress";
            case 10 -> "a shadow copy provider vetoed the operation";
            case 11 -> "the shadow copy provider is not registered";
            case 12 -> "the shadow copy provider failed";
            default -> "unknown error";
        };
        return reason + " (Win32_ShadowCopy.Create returned " + returnValue + ")";
    }

    /** The argument PowerShell's {@code -EncodedCommand} takes: base64 of the UTF-16LE script. */
    static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private static String run(String script) throws SnapshotException {
        try {
            ProcessBuilder builder = new ProcessBuilder(POWERSHELL_PATH, "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encode(script));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            process.getOutputStream().close();
            String output = readAll(process.getInputStream());
            process.waitFor();
            return output;
        } catch (IOException e) {
            throw new SnapshotException("Unable to run PowerShell (" + POWERSHELL_PATH + ").", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SnapshotException("Interrupted while waiting for PowerShell.", e);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            // Our tagged lines are ASCII; anything else (error text) is shown on a best-effort basis.
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static List<String> lines(String output) {
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\r?\n")) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        return lines;
    }

    private static SnapshotException failure(String what, String output) {
        for (String line : lines(output)) {
            if (line.startsWith("ERROR\t")) {
                return new SnapshotException(what + ": " + line.substring(6).trim());
            }
        }
        String trimmed = output.trim();
        return new SnapshotException(what + (trimmed.isEmpty() ? "." : ": " + trimmed));
    }

    private static UUID findGuid(String text) {
        Matcher matcher = GUID.matcher(text);
        if (!matcher.find()) return null;
        return UUID.fromString(matcher.group().toLowerCase(Locale.ROOT));
    }
}
