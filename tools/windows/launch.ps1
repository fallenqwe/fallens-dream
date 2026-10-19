# Fallen's Dream Windows pre-launch entry point. MIT; no game process is started.
# Prism 10 can expose an empty INST_JAVA when the global Java path is automatic.
$ErrorActionPreference = 'Stop'
$minecraftDirectory = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$channelUrl = 'https://fallenqwe.github.io/fallens-dream/channel.properties'

function Get-JavaMajor([string] $Executable) {
    if ([string]::IsNullOrWhiteSpace($Executable) -or -not [System.IO.File]::Exists($Executable)) {
        return $null
    }
    $probe = New-Object System.Diagnostics.Process
    $probe.StartInfo.FileName = $Executable
    $probe.StartInfo.Arguments = '-XshowSettings:properties -version'
    $probe.StartInfo.UseShellExecute = $false
    $probe.StartInfo.CreateNoWindow = $true
    $probe.StartInfo.RedirectStandardOutput = $true
    $probe.StartInfo.RedirectStandardError = $true
    try {
        [void] $probe.Start()
        $stdout = $probe.StandardOutput.ReadToEndAsync()
        $stderr = $probe.StandardError.ReadToEndAsync()
        if (-not $probe.WaitForExit(10000)) {
            $probe.Kill()
            return $null
        }
        $text = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        if ($probe.ExitCode -eq 0 -and $text -match '(?m)^\s*java\.specification\.version\s*=\s*(\d+)\s*$') {
            return [int] $Matches[1]
        }
    } catch {
        return $null
    } finally {
        $probe.Dispose()
    }
    return $null
}

try {
    $candidates = New-Object 'System.Collections.Generic.List[string]'
    if (-not [string]::IsNullOrWhiteSpace($env:INST_JAVA)) {
        $candidates.Add($env:INST_JAVA)
    }
    $instanceDirectory = [System.IO.Directory]::GetParent($minecraftDirectory).FullName
    $instancesDirectory = [System.IO.Directory]::GetParent($instanceDirectory).FullName
    $dataDirectory = [System.IO.Directory]::GetParent($instancesDirectory).FullName
    $runtimeRoots = @((Join-Path $dataDirectory 'java'))
    if (-not [string]::IsNullOrWhiteSpace($env:APPDATA)) {
        $runtimeRoots += Join-Path $env:APPDATA 'PrismLauncher/java'
    }
    foreach ($root in ($runtimeRoots | Select-Object -Unique)) {
        $candidates.Add((Join-Path $root 'java-runtime-epsilon/bin/java.exe'))
        if (Test-Path -LiteralPath $root -PathType Container) {
            foreach ($runtime in (Get-ChildItem -LiteralPath $root -Directory)) {
                $candidates.Add((Join-Path $runtime.FullName 'bin/java.exe'))
            }
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates.Add((Join-Path $env:JAVA_HOME 'bin/java.exe'))
    }
    $fromPath = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($fromPath) { $candidates.Add($fromPath[0].Source) }

    $selected = $null
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        # Use the console binary for reliable updater output, even if Prism chose javaw.
        if ([System.IO.Path]::GetFileName($candidate) -ieq 'javaw.exe') {
            $candidate = Join-Path ([System.IO.Path]::GetDirectoryName($candidate)) 'java.exe'
        }
        if ((Get-JavaMajor $candidate) -eq 25) {
            $selected = $candidate
            break
        }
    }
    if ($null -eq $selected) {
        throw 'Java 25 was not found. Enable automatic Java download in Prism or select Java 25 for this instance.'
    }
    Write-Output "Fallen's Dream: Java 25 found; checking the update channel."
    & $selected '-Djava.awt.headless=true' '-jar' (Join-Path $PSScriptRoot 'launcher.jar') $minecraftDirectory $channelUrl
    exit $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine("Fallen's Dream pre-launch failed: " + $_.Exception.Message)
    exit 1
}
