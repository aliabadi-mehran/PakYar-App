# Run locally in a new PowerShell process. Never paste signing passwords into chat.
# This script contains names/prompts only, not signing credentials.
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$signingNames = @('PAKYAR_KEYSTORE_PATH', 'PAKYAR_STORE_PASSWORD', 'PAKYAR_KEY_ALIAS', 'PAKYAR_KEY_PASSWORD')
$storeSecret = $null
$keySecret = $null
$storeBuffer = [IntPtr]::Zero
$keyBuffer = [IntPtr]::Zero
$buildProcess = $null
$processStarted = $false
$previousJavaHome = $env:JAVA_HOME
$changedJavaHome = $false
$exitCode = 1

function Clear-SigningEnvironment {
    foreach ($name in $signingNames) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
}

function Write-ProtectedBuildLine([string]$Line) {
    if ($null -eq $Line) { return }
    # No plaintext password variable: replace directly from the temporary process environment.
    $presentNames = @('PAKYAR_STORE_PASSWORD', 'PAKYAR_KEY_PASSWORD') | Where-Object {
        -not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($_, 'Process'))
    } | Sort-Object { [Environment]::GetEnvironmentVariable($_, 'Process').Length } -Descending
    foreach ($name in $presentNames) {
        if (-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            $Line = $Line.Replace([Environment]::GetEnvironmentVariable($name, 'Process'), '[REDACTED]')
        }
    }
    if ($Line -match '^BUILD SUCCESSFUL') {
        Write-Host 'Gradle tasks completed; verifying the Release APK before reporting success.'
    } else { Write-Host $Line }
    $Line = $null
}

function Find-LocalKeystores {
    # Keep the locally-owned key directory private in source control while resolving
    # the same per-user location at runtime.
    $keyDirectory = Join-Path $env:USERPROFILE 'Key'
    $root = Get-Item -LiteralPath $keyDirectory -ErrorAction Stop
    if (-not $root.PSIsContainer -or ($root.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "$keyDirectory must be a real directory, not a link."
    }
    $pending = New-Object 'System.Collections.Generic.Stack[string]'
    $pending.Push($root.FullName)
    while ($pending.Count -gt 0) {
        foreach ($entry in (Get-ChildItem -LiteralPath $pending.Pop() -Force -ErrorAction Stop)) {
            # Never follow a junction or symbolic link outside the permitted key directory.
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
            if ($entry.PSIsContainer) { $pending.Push($entry.FullName) }
            elseif ($entry.Extension -in @('.jks', '.keystore')) { $entry.FullName }
        }
    }
}

function Find-BuildTools {
    $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    $localProperties = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        # Read only the SDK-location property; never read signing properties.
        $sdkLine = Select-String -LiteralPath $localProperties -Pattern '^sdk\.dir\s*=' | Select-Object -First 1
        if ($null -ne $sdkLine) {
            $roots += (($sdkLine.Line -replace '^sdk\.dir\s*=\s*', '').Replace('\:', ':').Replace('\\', '\'))
        }
    }
    $candidates = @(
        foreach ($root in ($roots | Where-Object { $_ } | Select-Object -Unique)) {
            $toolsRoot = Join-Path $root 'build-tools'
            if (-not (Test-Path -LiteralPath $toolsRoot -PathType Container)) { continue }
            foreach ($directory in (Get-ChildItem -LiteralPath $toolsRoot -Directory)) {
                $version = $null
                if ([version]::TryParse(($directory.Name -replace '-.*$', ''), [ref]$version)) {
                    [PSCustomObject]@{ Directory = $directory.FullName; Version = $version; Stable = ($directory.Name -notmatch '-') }
                }
            }
        }
    )
    $newest = $candidates | Sort-Object -Property Version, Stable -Descending | Select-Object -First 1
    if ($null -eq $newest) { throw 'Install Android SDK Build Tools before building a signed Release.' }
    $signer = Join-Path $newest.Directory 'apksigner.bat'
    $aapt = Join-Path $newest.Directory 'aapt2.exe'
    if (-not (Test-Path -LiteralPath $signer) -or -not (Test-Path -LiteralPath $aapt)) {
        throw 'The newest installed Build Tools must contain apksigner and aapt2. Repair that installation first.'
    }
    return [PSCustomObject]@{ Signer = $signer; Aapt = $aapt }
}

function Invoke-PublicTool([string]$Tool, [string[]]$ToolArguments) {
    # Called only after all signing environment variables and password buffers are cleared.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $Tool @ToolArguments 2>&1 | ForEach-Object { $_.ToString() })
        $nativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($nativeExit -ne 0) {
        $output | ForEach-Object { Write-Host $_ }
        throw 'APK verification tool failed; the artifact is not approved for release.'
    }
    return $output
}

try {
    Clear-SigningEnvironment
    $javaAvailable = $false
    if ($env:JAVA_HOME) { $javaAvailable = Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    if (-not $javaAvailable -and -not $env:JAVA_HOME) { $javaAvailable = $null -ne (Get-Command java -ErrorAction SilentlyContinue) }
    if (-not $javaAvailable) {
        $jbr = 'C:\Program Files\Android\Android Studio\jbr'
        if (-not (Test-Path -LiteralPath (Join-Path $jbr 'bin\java.exe'))) { throw 'Java is unavailable. Install Android Studio JBR.' }
        $env:JAVA_HOME = $jbr
        $changedJavaHome = $true
    }
    $tools = Find-BuildTools
    $keystores = @(Find-LocalKeystores | Sort-Object)
    if ($keystores.Count -eq 0) { throw "No .jks or .keystore file was found inside $(Join-Path $env:USERPROFILE 'Key')." }
    if ($keystores.Count -eq 1) {
        Write-Host $keystores[0]
        if ((Read-Host 'Use this keystore? Type YES to confirm') -cne 'YES') { throw 'Release build cancelled.' }
        $selectedKeystore = $keystores[0]
    } else {
        for ($i = 0; $i -lt $keystores.Count; $i++) { Write-Host ('{0}. {1}' -f ($i + 1), $keystores[$i]) }
        $choice = 0
        if (-not [int]::TryParse((Read-Host 'Select the keystore number'), [ref]$choice) -or $choice -lt 1 -or $choice -gt $keystores.Count) {
            throw 'Invalid keystore selection; nothing was built.'
        }
        $selectedKeystore = $keystores[$choice - 1]
    }
    if ([IO.Path]::GetFileName($selectedKeystore) -ieq 'debug.keystore') { throw 'The debug keystore cannot be used for Release.' }
    $keyAlias = (Read-Host 'Key alias').Trim()
    if ([string]::IsNullOrWhiteSpace($keyAlias)) { throw 'A key alias is required.' }
    $storeSecret = Read-Host 'Keystore password' -AsSecureString
    $keySecret = Read-Host 'Key password' -AsSecureString
    if ($storeSecret.Length -eq 0 -or $keySecret.Length -eq 0) { throw 'Both masked password prompts require a value.' }

    $buildStarted = Get-Date
    try {
        [Environment]::SetEnvironmentVariable('PAKYAR_KEYSTORE_PATH', $selectedKeystore, 'Process')
        [Environment]::SetEnvironmentVariable('PAKYAR_KEY_ALIAS', $keyAlias, 'Process')
        $storeBuffer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($storeSecret)
        $keyBuffer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($keySecret)
        # Environment APIs necessarily create transient managed strings; do not retain plaintext variables.
        [Environment]::SetEnvironmentVariable('PAKYAR_STORE_PASSWORD', [Runtime.InteropServices.Marshal]::PtrToStringBSTR($storeBuffer), 'Process')
        [Environment]::SetEnvironmentVariable('PAKYAR_KEY_PASSWORD', [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyBuffer), 'Process')
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($storeBuffer); $storeBuffer = [IntPtr]::Zero
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyBuffer); $keyBuffer = [IntPtr]::Zero
        $storeSecret.Dispose(); $storeSecret = $null
        $keySecret.Dispose(); $keySecret = $null

        $startInfo = New-Object Diagnostics.ProcessStartInfo
        $startInfo.FileName = $env:ComSpec
        $startInfo.WorkingDirectory = $projectRoot
        # Required tasks plus safety flags: no cache/scan, no reusable Gradle or Kotlin daemon.
        # No credential ever appears in the command line.
        $startInfo.Arguments = '/d /s /c ""' + (Join-Path $projectRoot 'gradlew.bat') + '" clean :app:testDebugUnitTest :app:assembleRelease --no-daemon --no-configuration-cache --no-build-cache --no-scan --console=plain --warn --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process"'
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $startInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
        $startInfo.StandardErrorEncoding = [Text.Encoding]::UTF8
        $buildProcess = New-Object Diagnostics.Process
        $buildProcess.StartInfo = $startInfo
        if (-not $buildProcess.Start()) { throw 'Unable to start Gradle.' }
        $processStarted = $true
        $stdout = $buildProcess.StandardOutput.ReadLineAsync()
        $stderr = $buildProcess.StandardError.ReadLineAsync()
        while ($null -ne $stdout -or $null -ne $stderr) {
            if ($null -ne $stdout -and $stdout.IsCompleted) {
                $line = $stdout.GetAwaiter().GetResult()
                if ($null -eq $line) { $stdout = $null }
                else { Write-ProtectedBuildLine $line; $line = $null; $stdout = $buildProcess.StandardOutput.ReadLineAsync() }
            }
            if ($null -ne $stderr -and $stderr.IsCompleted) {
                $line = $stderr.GetAwaiter().GetResult()
                if ($null -eq $line) { $stderr = $null }
                else { Write-ProtectedBuildLine $line; $line = $null; $stderr = $buildProcess.StandardError.ReadLineAsync() }
            }
            if ($null -ne $stdout -or $null -ne $stderr) { Start-Sleep -Milliseconds 50 }
        }
        $buildProcess.WaitForExit()
        if ($buildProcess.ExitCode -ne 0) {
            Write-Host ('Gradle exit code: {0}' -f $buildProcess.ExitCode)
            throw 'Gradle build failed.'
        }
    } catch {
        # Do not propagate arbitrary exceptions from the secret-bearing build phase.
        # Useful Gradle diagnostics have already passed through the redactor above.
        throw 'The signing build failed or was interrupted. See the redacted Gradle diagnostics above.'
    } finally {
        # Clear immediately after the build, before any APK-verification child process starts.
        Clear-SigningEnvironment
        if ($storeBuffer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($storeBuffer); $storeBuffer = [IntPtr]::Zero }
        if ($keyBuffer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyBuffer); $keyBuffer = [IntPtr]::Zero }
        $line = $null; $stdout = $null; $stderr = $null
    }

    $apk = Get-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk') -ErrorAction Stop
    $sourceInputs = @(
        Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src') -Recurse -File
        Get-ChildItem -LiteralPath (Join-Path $projectRoot 'gradle') -Recurse -File
        Get-Item -LiteralPath (Join-Path $projectRoot 'app\build.gradle.kts'), (Join-Path $projectRoot 'build.gradle.kts'), (Join-Path $projectRoot 'settings.gradle.kts'), (Join-Path $projectRoot 'gradle.properties'), $PSCommandPath
    )
    $latestInput = $sourceInputs | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($apk.LastWriteTimeUtc -le $buildStarted.ToUniversalTime() -or $apk.LastWriteTimeUtc -le $latestInput.LastWriteTimeUtc) {
        throw 'The APK is not newer than this build and its source inputs. No release artifact is approved.'
    }
    $signature = @(Invoke-PublicTool $tools.Signer @('verify', '--verbose', '--print-certs', $apk.FullName))
    $certificateNames = @($signature | Where-Object { $_ -match '^Signer #\d+ certificate DN:' })
    $fingerprints = @($signature | Where-Object { $_ -match '^Signer #\d+ certificate SHA-256 digest:' })
    if ($certificateNames.Count -eq 0 -or $fingerprints.Count -eq 0 -or ($certificateNames -match '(?i)CN\s*=\s*Android\s+Debug(?:\s*,|\s*$)')) {
        throw 'The APK is unsigned or uses an Android Debug certificate. Do not distribute it.'
    }
    $badging = @(Invoke-PublicTool $tools.Aapt @('dump', 'badging', $apk.FullName))
    $package = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
    if ($package -notmatch "name='ir\.mehran\.pakyar'.*versionCode='2'.*versionName='1\.0\.0'") { throw 'Unexpected Release package or version.' }
    if ($badging -match 'application-debuggable|uses-permission.*android\.permission\.INTERNET') { throw 'Release APK requests Internet access or is debuggable.' }
    $manifestTree = @(Invoke-PublicTool $tools.Aapt @('dump', 'xmltree', '--file', 'AndroidManifest.xml', $apk.FullName))
    $unsafeFlags = @($manifestTree | Where-Object {
        $_ -match 'android:(debuggable|testOnly)' -and
        $_ -match '=\s*(?:\(type\s+0x12\))?\s*(?:true|"true"|0xffffffff|0x1)(?:\s|$)'
    })
    if ($unsafeFlags.Count -gt 0) { throw 'Release APK has a debuggable or test-only flag.' }
    Write-Host 'BUILD SUCCESSFUL'
    Write-Host ('APK: ' + $apk.FullName)
    Write-Host ('Size: ' + $apk.Length + ' bytes')
    Write-Host ('LastWriteTime: ' + $apk.LastWriteTime.ToString('o'))
    Write-Host ('SHA-256: ' + (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash)
    $fingerprints | ForEach-Object { Write-Host $_ }
    Write-Host 'versionCode: 2'
    Write-Host 'versionName: 1.0.0'
    $exitCode = 0
} catch {
    # Build output was sanitized before display; never dump exception objects or environment state.
    Write-ProtectedBuildLine ('Release build stopped: ' + $_.Exception.Message)
} finally {
    Clear-SigningEnvironment
    if ($storeBuffer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($storeBuffer) }
    if ($keyBuffer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyBuffer) }
    if ($null -ne $storeSecret) { $storeSecret.Dispose() }
    if ($null -ne $keySecret) { $keySecret.Dispose() }
    if ($null -ne $buildProcess) {
        if ($processStarted -and -not $buildProcess.HasExited) {
            # Stop only the child process tree created by this script when interrupted.
            try { & "$env:SystemRoot\System32\taskkill.exe" /PID $buildProcess.Id /T /F 2>$null | Out-Null } catch { }
        }
        $buildProcess.Dispose()
    }
    $storeSecret = $null; $keySecret = $null; $storeBuffer = [IntPtr]::Zero; $keyBuffer = [IntPtr]::Zero
    $line = $null; $stdout = $null; $stderr = $null; $startInfo = $null
    if ($changedJavaHome) { [Environment]::SetEnvironmentVariable('JAVA_HOME', $previousJavaHome, 'Process') }
}
exit $exitCode
