# =====================================================================
#  build-plugin.ps1 - compile ulang plugin CompanionNPCs
# =====================================================================
#  Jalankan dari terminal:
#    powershell -NoProfile -ExecutionPolicy Bypass -File G:\GameServerJava\build-plugin.ps1
#
#  Compile semua .java, salin resource, kemas jar, lalu taruh ke
#  folder plugins server Paper. Setelah ini: RESTART SERVER (bukan reload).
# =====================================================================

$ErrorActionPreference = 'Stop'

$javac = 'C:\Program Files\Java\jdk-25.0.5.0.2\bin\javac.exe'
$jar   = 'C:\Program Files\Java\jdk-25.0.5.0.2\bin\jar.exe'
$base  = 'G:\GameServerJava\Paper\Paper26.3\libraries'
$api   = Join-Path $base 'io\papermc\paper\paper-api\26.3.build.223-stable\paper-api-26.3.build.223-stable.jar'
$advApi= Join-Path $base 'net\kyori\adventure-api\5.2.0\adventure-api-5.2.0.jar'
$advKey= Join-Path $base 'net\kyori\adventure-key\5.2.0\adventure-key-5.2.0.jar'
$advTxt= Join-Path $base 'net\kyori\adventure-text-serializer-legacy\5.2.0\adventure-text-serializer-legacy-5.2.0.jar'
$advPln= Join-Path $base 'net\kyori\adventure-text-serializer-plain\5.2.0\adventure-text-serializer-plain-5.2.0.jar'
$advCom= Join-Path $base 'net\kyori\adventure-text-serializer-commons\5.2.0\adventure-text-serializer-commons-5.2.0.jar'
$guava = Join-Path $base 'com\google\guava\guava\33.6.0-jre\guava-33.6.0-jre.jar'
$bungee= Join-Path $base 'net\md-5\bungeecord-chat\1.21-R0.2-deprecated+build.21\bungeecord-chat-1.21-R0.2-deprecated+build.21.jar'
$stubOut= 'G:\GameServerJava\plugin\build\stub-classes'
$cp = "$api;$advApi;$advKey;$advTxt;$advPln;$advCom;$guava;$bungee;$stubOut"

$src   = 'G:\GameServerJava\plugin\src\main\java'
$res   = 'G:\GameServerJava\plugin\src\main\resources'
$out   = 'G:\GameServerJava\plugin\build\classes'
$jarOut= 'G:\GameServerJava\plugin\build\companion-npcs-1.0.0.jar'
$serverJar = 'G:\GameServerJava\Paper\Paper26.3\plugins\companion-npcs-1.0.0.jar'

Write-Host '== Compile CompanionNPCs ==' -ForegroundColor Cyan

if(-not (Test-Path $stubOut)){
  Write-Host '  stub anotasi belum ada, membuat...'
  $stubSrc='G:\GameServerJava\plugin\build\stubs'
  $stubFiles = @(Get-ChildItem $stubSrc -Recurse -Filter '*.java' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
  if($stubFiles.Count -gt 0){
    New-Item -ItemType Directory -Force -Path $stubOut | Out-Null
    $sa = @('-encoding','UTF-8','--release','25','-d',$stubOut) + $stubFiles
    $sp = Start-Process -FilePath $javac -ArgumentList $sa -NoNewWindow -Wait -PassThru -RedirectStandardError "$env:TEMP\stub_err.txt"
    if($sp.ExitCode -ne 0){ Get-Content "$env:TEMP\stub_err.txt"; throw 'compile stub gagal' }
  }
}

if(Test-Path $out){ Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$files = @(Get-ChildItem $src -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
Write-Host ("  file java: " + $files.Count)

$args = @('-encoding','UTF-8','--release','25','-classpath', $cp, '-d', $out) + $files
$proc = Start-Process -FilePath $javac -ArgumentList $args -NoNewWindow -Wait -PassThru -RedirectStandardError "$env:TEMP\jc_err.txt"
$err = Get-Content "$env:TEMP\jc_err.txt" -ErrorAction SilentlyContinue
if($proc.ExitCode -ne 0){
  Write-Host '  GAGAL:' -ForegroundColor Red
  if($err){ foreach($l in $err){ Write-Host ('   ' + $l) -ForegroundColor Red } }
  throw 'javac gagal'
}
Write-Host ("  class: " + @(Get-ChildItem $out -Recurse -Filter '*.class').Count) -ForegroundColor Green

foreach($f in @('plugin.yml','config.yml')){
  $s = Join-Path $res $f
  if(Test-Path $s){ Copy-Item $s $out -Force }
}

if(Test-Path $jarOut){ Remove-Item $jarOut -Force }
Push-Location $out
try {
  $pj = Start-Process -FilePath $jar -ArgumentList @('--create','--file',"$jarOut",'.') -NoNewWindow -Wait -PassThru -RedirectStandardError "$env:TEMP\jar_err.txt"
  if($pj.ExitCode -ne 0){ throw 'jar gagal' }
} finally { Pop-Location }

if(-not (Test-Path $jarOut)){ throw 'jar tidak terbentuk' }
Copy-Item $jarOut $serverJar -Force
Write-Host ("  jar: " + (Get-Item $jarOut).Length + " bytes -> server plugins") -ForegroundColor Green
Write-Host ''
Write-Host '== SELESAI: RESTART SERVER untuk memuat plugin baru ==' -ForegroundColor Cyan
Write-Host '   (di console server: stop, lalu jalankan start.bat)'
Write-Host ''
