# Builds the 32-bit Ezcad2 bridge process (LaserBridge.exe) with the .NET Framework
# v4.0.30319 C# compiler. Outputs go to:
#   - target/classes/laser-bridge/  (classpath resource /laser-bridge/LaserBridge.exe for tests)
#   - target/laser-bridge/          (layout used by pom.xml antrun compile-laser-bridge)
#
# Run from the repository root:  powershell -File src/native/laser-bridge/build-laser-bridge.ps1
# No third-party dependencies; only System.Web.Extensions (shipped with .NET Framework).

$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path "$PSScriptRoot\..\..\..").Path
$outDir = "$repoPath\target\classes\laser-bridge"
$mavenOutDir = "$repoPath\target\laser-bridge"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
New-Item -ItemType Directory -Force -Path $mavenOutDir | Out-Null

$csc = 'C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { throw "csc.exe not found at $csc" }

& $csc /nologo /platform:x86 /optimize+ /target:exe /utf8output `
    /r:System.Web.Extensions.dll `
    /out:"$outDir\LaserBridge.exe" `
    "$PSScriptRoot\LaserBridge.cs"
if ($LASTEXITCODE -ne 0) { throw "csc failed with exit code $LASTEXITCODE" }

Copy-Item "$outDir\LaserBridge.exe" "$mavenOutDir\LaserBridge.exe" -Force

Write-Host "Built $outDir\LaserBridge.exe"
