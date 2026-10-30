# =====================================================================
#  rebuild-pack.ps1 - satu langkah untuk asset patch Yui & Sandrone
# =====================================================================
#  Yang dilakukan (berurutan):
#   1. Salin semua .ogg dari  resource-pack-source\  ke dalam pack
#   2. Tulis ulang assets\companion\sounds.json (event diambil dari nama file)
#   3. Rebuild  companion-voices-26.3.zip  (jalur pakai garis miring, wajib)
#   4. Salin zip ke folder resourcepacks di Minecraft client
#   5. Cetak SHA-1 zip (simpan kalau suatu saat perlu resource-pack-sha1=)
#
#  Cara pakai:
#    - klik kanan file ini -> Run with PowerShell
#    - atau dari terminal:
#      powershell -NoProfile -ExecutionPolicy Bypass -File .\rebuild-pack.ps1
#
#  Script ini portabel: lokasi proyek diambil dari lokasi file ini, dan
#  folder server Paper dicari otomatis. Kalau punya lebih dari satu server,
#  sebutkan lokasinya:
#      powershell -NoProfile -ExecutionPolicy Bypass -File .\rebuild-pack.ps1 D:\Server\Paper26.3
#
#  POLA NAMA FILE (harus ikut):
#    yui_<aksi>_<nomor>.ogg      contoh: yui_idle_09.ogg
#    sandrone_<aksi>_<nomor>.ogg  contoh: sandrone_kill_07.ogg
#  <aksi> yang sudah ada: on, deny, heal, followup, regen, kill, idle
#  Event di sounds.json dibuat dari nama file:
#    yui_idle_09.ogg  ->  event "yui.idle_09"
#    yui_on_01.ogg    ->  event "yui.on"
#
#  Catatan update dialog BARU di .sk: lihat pesan REMINDER di bawah.
# =====================================================================

$ErrorActionPreference = 'Stop'

# ---- Lokasi proyek, diturunkan dari lokasi file ini -------------------
$root = $PSScriptRoot

# Server Paper: cari folder yang punya resource-pack-source, atau terima
# lewat argumen -ServerDir. Kalau tidak ketemu, pakai folder Paper di root.
$serverDir = ''
if ($args -and $args[0] -and -not $args[0].StartsWith('-')) { $serverDir = $args[0] }
if (-not $serverDir) {
    $paperRoot = Join-Path $root 'Paper'
    if (Test-Path $paperRoot) {
        Get-ChildItem $paperRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            if (Test-Path (Join-Path $_.FullName 'resource-pack-source')) { return $_.FullName }
        }
    }
}
if (-not $serverDir) {
    $serverDir = Join-Path $root 'Paper\Paper26.3'
    if (-not (Test-Path (Join-Path $serverDir 'resource-pack-source'))) {
        $serverDir = $root
    }
}

$sourceDir = Join-Path $serverDir 'resource-pack-source'
$packDir   = Join-Path $serverDir 'resource-pack'
$sndDir    = Join-Path $packDir 'assets\companion\sounds'
$jsonPath  = Join-Path $packDir 'assets\companion\sounds.json'
$zipPath   = Join-Path $serverDir 'companion-voices-26.3.zip'
$clientZip = Join-Path $env:APPDATA '.minecraft\resourcepacks\companion-voices-26.3.zip'

Write-Host ("  lokasi proyek : " + $root)
Write-Host ("  lokasi server : " + $serverDir)

Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

function Step($n, $msg) {
    Write-Host ''
    Write-Host ("== Langkah {0}: {1}" -f $n, $msg) -ForegroundColor Cyan
}
function Warn($msg) { Write-Host ("   PERINGATAN: " + $msg) -ForegroundColor Yellow }
function Ok($msg)   { Write-Host ("   " + $msg) -ForegroundColor Green }

try {
    # ---------------------------------------------------------------
    Step 1 'Salin .ogg dari resource-pack-source ke dalam pack'
    if (-not (Test-Path $sourceDir)) { throw "Folder sumber tidak ada: $sourceDir" }
    if (-not (Test-Path $sndDir))  { New-Item -ItemType Directory -Force -Path $sndDir | Out-Null }
    $srcFiles = @(Get-ChildItem $sourceDir -File -Filter '*.ogg')
    if ($srcFiles.Count -eq 0) { Warn 'tidak ada .ogg di folder source (dilewati)' }
    foreach($f in $srcFiles){ Copy-Item $f.FullName $sndDir -Force }
    $packOgg = @(Get-ChildItem $sndDir -File -Filter '*.ogg')
    Ok ("total .ogg di pack: " + $packOgg.Count)

    # ---------------------------------------------------------------
    Step 2 'Tulis ulang sounds.json dari nama file .ogg'
    $events = New-Object System.Collections.Generic.List[object]
    $seen = @{}
    $unmatched = New-Object System.Collections.Generic.List[string]

    foreach($f in ($packOgg | Sort-Object Name)){
        $name = [IO.Path]::GetFileNameWithoutExtension($f.Name)
        if($name -notmatch '^(yui|sandrone)_([a-z]+)(_(\d+))?$'){
            Warn ("nama tidak dikenal polanya, dilewati: " + $f.Name)
            $unmatched.Add($f.Name)
            continue
        }
        $char = $Matches[1]
        $act  = $Matches[2]
        $num  = $Matches[4]

        # yui_idle_09 -> yui.idle_09 ; yui_on_01 -> yui.on (yang "_01" hanya penomoran file)
        if($num -and $act -in @('on','deny','heal','followup','regen')){
            $ev = "$char.$act"
        } elseif($num){
            $ev = "$char.$act" + "_$num"
        } else {
            $ev = "$char.$act"
        }

        if($seen.ContainsKey($ev)){
            Warn ("dua file Claims event yang sama: " + $ev + " (" + $f.Name + ")")
            continue
        }
        $seen[$ev] = $true
        $events.Add([pscustomobject]@{ Event = $ev; Sound = $name })
    }

    # tulis JSON manual supaya rapi dan stabIl (2 spasi)
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('{')
    for($i=0; $i -lt $events.Count; $i++){
        $e = $events[$i]
        $comma = if($i -lt $events.Count - 1){ ',' } else { '' }
        [void]$sb.AppendLine(('  "{0}": {{' -f $e.Event))
        [void]$sb.AppendLine(('    "sounds": ["companion:{0}"]' -f $e.Sound))
        [void]$sb.AppendLine(('  }}{0}' -f $comma))
    }
    [void]$sb.AppendLine('}')
    [IO.File]::WriteAllText($jsonPath, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))

    # validasi
    $check = Get-Content $jsonPath -Raw | ConvertFrom-Json
    $evList = @($check.PSObject.Properties.Name)
    Ok ("sounds.json valid, total event: " + $evList.Count)

    # event yang tidak punya file .ogg (bisa terjadi kalau ada file dihapus)
    $fileNames = @{}
    foreach($f in $packOgg){ $fileNames[[IO.Path]::GetFileNameWithoutExtension($f.Name)] = $true }
    foreach($p in $check.PSObject.Properties){
        foreach($s in $p.Value.sounds){
            $rel = ($s -replace '^companion:','')
            if(-not $fileNames.ContainsKey($rel)){ Warn ("event tanpa file .ogg: " + $p.Name + " -> " + $s) }
        }
    }

    # ---------------------------------------------------------------
    Step 3 'Rebuild companion-voices-26.3.zip'
    if(Test-Path $zipPath){ Remove-Item $zipPath -Force }
    $root = (Resolve-Path $packDir).Path.TrimEnd('\')
    $fs = [System.IO.File]::Open($zipPath, [System.IO.FileMode]::CreateNew)
    $archive = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
    foreach($f in (Get-ChildItem $packDir -Recurse -File | Sort-Object FullName)){
        $rel = $f.FullName.Substring($root.Length + 1).Replace('\','/')
        $entry = $archive.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
        $es = $entry.Open()
        $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
        $es.Write($bytes, 0, $bytes.Length)
        $es.Close()
    }
    $archive.Dispose()
    $fs.Close()

    $z = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    $names = @($z.Entries | ForEach-Object { $_.FullName })
    $z.Dispose()
    $bad = @($names | Where-Object { $_.IndexOf('\') -ge 0 })
    if($bad.Count -gt 0){ throw ("zip bermasalah: ada jalur backslash: " + ($bad -join ', ')) }
    Ok ("zip jadi: " + $names.Count + " entri, " + (Get-Item $zipPath).Length + " bytes")

    # ---------------------------------------------------------------
    Step 4 'Salin zip ke client Minecraft'
    $clientDir = Split-Path $clientZip
    if(Test-Path $clientDir){ Copy-Item $zipPath $clientZip -Force; Ok ("client diperbarui: " + $clientZip) }
    else { throw "folder client tidak ada: " + $clientDir }

    # ---------------------------------------------------------------
    Step 5 'SHA-1 zip (untuk resource-pack-sha1=, boleh diabaikan)'
    Write-Host ("   SHA-1: " + (Get-FileHash $zipPath -Algorithm SHA1).Hash.ToLower()) -ForegroundColor White

    # ---------------------------------------------------------------
    Write-Host ''
    Write-Host '== SELESAI ==' -ForegroundColor Cyan
    if($unmatched.Count -gt 0){
        Write-Host ('file tidak masuk event (cek namanya): ' + ($unmatched -join ', ')) -ForegroundColor Yellow
    }
    Write-Host ''
    Write-Host 'REMINDER kalau ini penambahan DIALOG BARU:' -ForegroundColor Cyan
    Write-Host '  1. tambah baris dialog + baris suaranya di on load file .sk yang cocok'
    Write-Host '     (kalau belum ada kodenya, kirim ke Claude)'
    Write-Host '  2. sk reload yui_companion LALU sk reload sandrone_companion'
    Write-Host '  3. di Minecraft: keluar ke menu utama lalu masuk lagi (pack dimuat ulang)'
    Write-Host ''
    Write-Host 'Catatan: server TIDAK perlu restart untuk ganti audio.' -ForegroundColor Gray
}
catch {
    Write-Host ('GAGAL: ' + $_.Exception.Message) -ForegroundColor Red
}
Write-Host ''
Read-Host 'Tekan Enter untuk menutup'