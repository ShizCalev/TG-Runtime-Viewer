$ErrorActionPreference = "Stop"
$src = Get-ChildItem -Recurse -Filter *.java -Path "$PSScriptRoot\src\main\java" | ForEach-Object { $_.FullName }
New-Item -ItemType Directory -Force -Path "$PSScriptRoot\build\classes" | Out-Null
& javac -d "$PSScriptRoot\build\classes" -encoding UTF-8 $src
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

$resourcesDir = "$PSScriptRoot\src\main\resources"
if (Test-Path $resourcesDir) {
    Copy-Item -Recurse -Force "$resourcesDir\*" "$PSScriptRoot\build\classes"
}

Write-Host "Build OK -> build\classes"
