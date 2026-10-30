# =====================================================================
#  build-plugin.ps1 - compile ulang plugin CompanionNPCs
# =====================================================================
#  Jalankan dari mana saja:
#    powershell -NoProfile -ExecutionPolicy Bypass -File .\build-plugin.ps1
#
#  Script ini portabel: semua path diturunkan dari lokasi script, JDK
#  dicari otomatis, dan library Paper dicari lewat nama file (bukan versi
#  hard-coded). Kalau folder server Paper tidak ditemukan, script
#  otomatis memakai Maven — Maven mengunduh Paper API sendiri, jadi
#  tidak perlu salinan folder server.
#
#  Opsi:
#    -ServerDir <path>   lokasi server Paper (default: cari otomatis)
#    -SkipDeploy         jangan salin JAR ke folder plugins server
# =====================================================================

param(
    [string]$ServerDir = '',
    [switch]$SkipDeploy
)

$ErrorActionPreference = 'Stop'

# ---- Lokasi proyek, diturunkan dari lokasi file ini -------------------
$root      = $PSScriptRoot
$pluginDir = Join-Path $root 'plugin'
$src       = Join-Path $pluginDir 'src\main\java'
$res       = Join-Path $pluginDir 'src\main\resources'
$stubSrc   = Join-Path $pluginDir 'build\stubs'
$stubOut   = Join-Path $pluginDir 'build\stub-classes'
$out       = Join-Path $pluginDir 'build\classes'
$jarOut    = Join-Path $pluginDir 'build\companion-npcs-1.0.0.jar'

Write-Host '== Compile CompanionNPCs ==' -ForegroundColor Cyan

# ---- Cari JDK --------------------------------------------------------
function Find-JdkTool {
    param([string]$ToolName)

    # 1. JAVA_HOME
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\$ToolName.exe"
        if (Test-Path $candidate) { return $candidate }
    }
    # 2. Folder instalasi JDK yang umum. Pakai foreach biasa: "return" di dalam
    #    pipeline ForEach-Object hanya keluar dari blok itu, bukan dari fungsi,
    #    jadi fungsi akan mengembalikan semua kandidat sekaligus.
    $jdkRoots = @(
        'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Microsoft',
        'C:\Program Files\Zulu',
        'C:\Program Files\Amazon Corretto',
        "$env:LOCALAPPDATA\Programs"
    )
    foreach ($jdkRoot in $jdkRoots) {
        if (-not (Test-Path $jdkRoot)) { continue }
        foreach ($dir in @(Get-ChildItem $jdkRoot -Directory -ErrorAction SilentlyContinue)) {
            if ($dir.Name -notmatch 'jdk|java|temurin|corretto|zulu') { continue }
            $tool = Join-Path $dir.FullName "bin\$ToolName.exe"
            if (Test-Path $tool) { return $tool }
        }
    }
    # 3. PATH
    $onPath = Get-Command "$ToolName.exe" -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    return $null
}

$javac = Find-JdkTool 'javac'
$jarTool = Find-JdkTool 'jar'
if (-not $javac) {
    throw 'javac tidak ditemukan. Pasang JDK 21+ atau set JAVA_HOME.'
}
Write-Host "  javac: $javac" -ForegroundColor DarkGray

# ---- Cari folder server Paper ----------------------------------------
function Find-ServerDir {
    # $ServerDir hanya berlaku kalau user mengetiknya; $root selalu root proyek.
    param([string]$ServerDir, [string]$Root)

    if ($ServerDir) {
        if (Test-Path $ServerDir) { return (Resolve-Path $ServerDir).Path }
        throw "ServerDir tidak ada: $ServerDir"
    }
    # Cari folder yang punya libraries/io/papermc/paper/paper-api
    $candidates = @()
    $paperRoot = Join-Path $Root 'Paper'
    if (Test-Path $paperRoot) {
        $candidates += @(Get-ChildItem $paperRoot -Directory -ErrorAction SilentlyContinue)
    }
    $candidates += @(Get-ChildItem $Root -Directory -ErrorAction SilentlyContinue)

    foreach ($candidate in $candidates) {
        $apiDir = Join-Path $candidate.FullName 'libraries\io\papermc\paper\paper-api'
        if (Test-Path $apiDir) { return $candidate.FullName }
    }
    return $null
}

$server = Find-ServerDir -ServerDir $ServerDir -Root $root
if ($server) {
    Write-Host "  server: $server" -ForegroundColor DarkGray
} else {
    Write-Host '  server: tidak ditemukan (pakai Maven)' -ForegroundColor DarkGray
}

# ---- Kumpulkan library untuk classpath -------------------------------
function Find-Library {
    param([string]$ServerPath, [string]$RelativeDir, [string]$Filter)

    if (-not $ServerPath) { return $null }
    $dir = Join-Path (Join-Path $ServerPath 'libraries') $RelativeDir
    if (-not (Test-Path $dir)) { return $null }
    $hit = Get-ChildItem $dir -Recurse -Filter $Filter -File -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($hit) { return $hit.FullName }
    return $null
}

# ---- Kompilasi dengan javac, atau Maven sebagai cadangan --------------
function Build-WithJavac {
    param([string]$ServerPath)

    $libs = @(
        (Find-Library $ServerPath 'io\papermc\paper\paper-api'        'paper-api-*.jar'),
        (Find-Library $ServerPath 'net\kyori\adventure-api'           'adventure-api-*.jar'),
        (Find-Library $ServerPath 'net\kyori\adventure-key'           'adventure-key-*.jar'),
        (Find-Library $ServerPath 'net\kyori\adventure-text-serializer-legacy' 'adventure-text-serializer-legacy-*.jar'),
        (Find-Library $ServerPath 'net\kyori\adventure-text-serializer-plain'   'adventure-text-serializer-plain-*.jar'),
        (Find-Library $ServerPath 'net\kyori\adventure-text-serializer-commons' 'adventure-text-serializer-commons-*.jar'),
        (Find-Library $ServerPath 'com\google\guava\guava'             'guava-*.jar'),
        (Find-Library $ServerPath 'net\md-5\bungeecord-chat'           'bungeecord-chat-*.jar')
    ) | Where-Object { $_ }

    if (-not $libs) {
        Write-Host '  library Paper tidak ditemukan, pakai Maven' -ForegroundColor Yellow
        return $false
    }

    # Stub anotasi JetBrains, dikompilasi sekali saja.
    if (-not (Test-Path $stubOut)) {
        Write-Host '  stub anotasi belum ada, membuat...'
        $stubFiles = @(Get-ChildItem $stubSrc -Recurse -Filter '*.java' -ErrorAction SilentlyContinue |
            ForEach-Object { $_.FullName })
        if ($stubFiles.Count -gt 0) {
            New-Item -ItemType Directory -Force -Path $stubOut | Out-Null
            $sa = @('-encoding', 'UTF-8', '--release', '25', '-d', $stubOut) + $stubFiles
            $sp = Start-Process -FilePath $javac -ArgumentList $sa -NoNewWindow -Wait -PassThru `
                -RedirectStandardError "$env:TEMP\stub_err.txt"
            if ($sp.ExitCode -ne 0) {
                Get-Content "$env:TEMP\stub_err.txt"
                throw 'compile stub gagal'
            }
        }
    }

    if (Test-Path $out) { Remove-Item $out -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $out | Out-Null

    $files = @(Get-ChildItem $src -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
    Write-Host ("  file java: " + $files.Count)

    $cp = (($libs + $stubOut) -join ';')
    $jcArgs = @('-encoding', 'UTF-8', '--release', '25', '-classpath', $cp, '-d', $out) + $files
    $proc = Start-Process -FilePath $javac -ArgumentList $jcArgs -NoNewWindow -Wait -PassThru `
        -RedirectStandardError "$env:TEMP\jc_err.txt"
    $err = Get-Content "$env:TEMP\jc_err.txt" -ErrorAction SilentlyContinue
    if ($proc.ExitCode -ne 0) {
        Write-Host '  GAGAL:' -ForegroundColor Red
        if ($err) { foreach ($l in $err) { Write-Host ('   ' + $l) -ForegroundColor Red } }
        throw 'javac gagal'
    }
    Write-Host ("  class: " + @(Get-ChildItem $out -Recurse -Filter '*.class').Count) -ForegroundColor Green

    foreach ($f in @('plugin.yml', 'config.yml')) {
        $s = Join-Path $res $f
        if (Test-Path $s) { Copy-Item $s $out -Force }
    }

    if (Test-Path $jarOut) { Remove-Item $jarOut -Force }
    Push-Location $out
    try {
        $pj = Start-Process -FilePath $jarTool -ArgumentList @('--create', '--file', "$jarOut", '.') `
            -NoNewWindow -Wait -PassThru -RedirectStandardError "$env:TEMP\jar_err.txt"
        if ($pj.ExitCode -ne 0) { throw 'jar gagal' }
    } finally { Pop-Location }
    return $true
}

function Build-WithMaven {
    $mvn = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
    if (-not $mvn) { $mvn = Get-Command 'mvn' -ErrorAction SilentlyContinue }
    if (-not $mvn) {
        throw 'Maven tidak ditemukan. Pasang Maven, atau sediakan folder server Paper agar library-nya bisa dipakai.'
    }
    Write-Host '  build dengan Maven (mengunduh Paper API sendiri)...' -ForegroundColor Yellow
    Push-Location $pluginDir
    try {
        $pm = Start-Process -FilePath $mvn.Source -ArgumentList @('-q', 'package') -NoNewWindow -Wait -PassThru `
            -RedirectStandardError "$env:TEMP\mvn_err.txt"
        if ($pm.ExitCode -ne 0) {
            Get-Content "$env:TEMP\mvn_err.txt" -ErrorAction SilentlyContinue
            throw 'mvn package gagal'
        }
    } finally { Pop-Location }
    $built = Join-Path $pluginDir 'target\companion-npcs-1.0.0.jar'
    if (-not (Test-Path $built)) { throw 'JAR hasil Maven tidak ditemukan' }
    Copy-Item $built $jarOut -Force
    return $true
}

if (-not (Build-WithJavac -ServerPath $server)) {
    Build-WithMaven | Out-Null
}

if (-not (Test-Path $jarOut)) { throw 'JAR tidak terbentuk' }

# ---- Salin ke server (kalau ada) --------------------------------------
if (-not $SkipDeploy -and $server) {
    $pluginsDir = Join-Path $server 'plugins'
    if (Test-Path $pluginsDir) {
        $serverJar = Join-Path $pluginsDir 'companion-npcs-1.0.0.jar'
        Copy-Item $jarOut $serverJar -Force
        Write-Host ("  jar: " + (Get-Item $jarOut).Length + " bytes -> server plugins") -ForegroundColor Green
    } else {
        Write-Host ("  jar: " + (Get-Item $jarOut).Length + " bytes -> plugin/build (folder plugins server tidak ada)") -ForegroundColor Yellow
    }
} else {
    Write-Host ("  jar: " + (Get-Item $jarOut).Length + " bytes -> plugin/build") -ForegroundColor Yellow
}

Write-Host ''
Write-Host '== SELESAI: RESTART SERVER untuk memuat plugin baru ==' -ForegroundColor Cyan
Write-Host '   (di console server: stop, lalu jalankan start.bat)'
Write-Host ''
