# Runs the navigation checks against the compiled plugin.
#
# The tests build a small world in memory, so they need no running server. They cover the
# carpet, the fence and the door rules that the companions walk by.

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$buildDir = Join-Path $root 'build'

$paperRoot = Join-Path (Split-Path -Parent $root) 'Paper\Paper26.3'
$apiJar = Get-ChildItem -Path $paperRoot -Recurse -Filter 'paper-api-*.jar' -File -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $apiJar) {
    Write-Host "paper-api tidak ditemukan di $paperRoot" -ForegroundColor Red
    exit 1
}
$deps = @(Get-ChildItem -Path $paperRoot -Recurse -Filter '*.jar' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notlike '*\plugins\*' } |
    ForEach-Object { $_.FullName })
# The compiled classes come first: a copy of the plugin left in the server's plugin folder
# would otherwise sit in front of them on the classpath and the tests would be run against
# an older build.
$cp = (@((Join-Path $buildDir 'classes')) + @($deps) + @($apiJar.FullName)) -join ';'

$javac = 'C:\Program Files\Java\jdk-25.0.5.0.2\bin\javac.exe'
$java = 'C:\Program Files\Java\jdk-25.0.5.0.2\bin\java.exe'
$testSrc = Join-Path $root 'src\test\java'
$testOut = Join-Path $buildDir 'test-classes'

Write-Host "== Compile tests =="
New-Item -ItemType Directory -Force -Path $testOut | Out-Null
$testFiles = @(Get-ChildItem -Path $testSrc -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& $javac -encoding UTF-8 -cp $cp -d $testOut $testFiles
if ($LASTEXITCODE -ne 0) { exit 1 }

foreach ($name in @('com.arif.companion.PlannerRulesTest', 'com.arif.companion.GapAheadTest',
                    'com.arif.companion.LineOfSightTest', 'com.arif.companion.ChaseRulesTest')) {
    Write-Host ""
    Write-Host "== $name =="
    & $java -cp "$testOut;$cp" $name
    if ($LASTEXITCODE -ne 0) { exit 1 }
}
Write-Host ""
Write-Host "== SEMUA TEST LULUS ==" -ForegroundColor Green
