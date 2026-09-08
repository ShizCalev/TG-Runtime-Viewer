$ErrorActionPreference = "Stop"
$logFile = "$PSScriptRoot\package.log"
Start-Transcript -Path $logFile -Force | Out-Null
$success = $false

try {
    & "$PSScriptRoot\build.ps1"
    if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $javaHomeLine = (& java -XshowSettings:properties -version 2>&1 | Select-String "java.home")
    $ErrorActionPreference = $prevEAP
    if (-not $javaHomeLine) { throw "Could not determine java.home - is 'java' on PATH?" }
    $javaHome = ($javaHomeLine -split "=", 2)[1].Trim()
    $jpackage = Join-Path $javaHome "bin\jpackage.exe"
    $jar = Join-Path $javaHome "bin\jar.exe"
    if (-not (Test-Path $jpackage)) {
        throw "jpackage.exe not found at $jpackage - need a full JDK (17+) with jpackage, not just a JRE."
    }

    $versionFile = "$PSScriptRoot\src\main\java\tgrv\Version.java"
    $versionMatch = Select-String -Path $versionFile -Pattern 'CURRENT\s*=\s*"([^"]+)"'
    if (-not $versionMatch) { throw "Could not read version from $versionFile" }
    $appVersion = $versionMatch.Matches[0].Groups[1].Value
    $exeVersion = "$appVersion.0"

    $jarDir = "$PSScriptRoot\build\jarinput"
    New-Item -ItemType Directory -Force -Path $jarDir | Out-Null
    Remove-Item -Force "$jarDir\tgrv.jar" -ErrorAction SilentlyContinue

    & $jar --create --file "$jarDir\tgrv.jar" --main-class tgrv.Main -C "$PSScriptRoot\build\classes" .
    if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

    $dist = "$PSScriptRoot\dist"
    $appDir = "$dist\TG Runtime Viewer"

    $cacheBackup = "$PSScriptRoot\build\tgrv-cache-backup"
    Remove-Item -Recurse -Force $cacheBackup -ErrorAction SilentlyContinue
    if (Test-Path "$appDir\tgrv-cache") {
        Move-Item "$appDir\tgrv-cache" $cacheBackup
    }
    Remove-Item -Recurse -Force $appDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $dist | Out-Null

    try {
        & $jpackage `
            --type app-image `
            --name "TG Runtime Viewer" `
            --input $jarDir `
            --main-jar tgrv.jar `
            --main-class tgrv.Main `
            --dest $dist `
            --icon "$PSScriptRoot\tgstation.ico" `
            --app-version $exeVersion `
            --vendor "ShizCalev" `
            --copyright "© 2026 ShizCalev. Licensed under GPLv3." `
            --add-modules java.base,java.desktop,java.logging,java.net.http `
            --java-options "-Dfile.encoding=UTF-8"
        if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }

        $exePath = "$appDir\TG Runtime Viewer.exe"
        if (-not (Test-Path $exePath)) {
            throw "jpackage reported success but '$exePath' wasn't created - a security tool (antivirus/SmartScreen) may have quarantined it during build. Check your antivirus quarantine/history."
        }
    } finally {
        if (Test-Path $cacheBackup) {
            New-Item -ItemType Directory -Force -Path $appDir | Out-Null
            Move-Item $cacheBackup "$appDir\tgrv-cache" -Force
        }
    }

    Write-Host ""
    Write-Host "Packaged -> $exePath" -ForegroundColor Green
    $success = $true
} catch {
    Write-Host ""
    Write-Host "PACKAGING FAILED: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    Stop-Transcript | Out-Null
    Write-Host ""
    Write-Host "Full log saved to: $logFile"
    if (-not $env:CI) {
        Read-Host "Press Enter to close this window"
    }
}

if (-not $success) {
    exit 1
}
