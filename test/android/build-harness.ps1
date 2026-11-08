param([string]$AndroidSdk='D:/Android/SDK',[string]$Jdk='C:/Program Files/Java/jdk-17')
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$out=Join-Path $root 'build/device-harness'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $root 'src') -Filter '*.java' | ForEach-Object FullName)
$sources+=Join-Path $PSScriptRoot 'WifiDeviceHarness.java'
& (Join-Path $Jdk 'bin/javac.exe') -encoding UTF-8 --release 8 -classpath "$AndroidSdk/platforms/android-36/android.jar" -d $out $sources
if($LASTEXITCODE -ne 0){throw 'Device harness compilation failed'}
$classes=@(Get-ChildItem -LiteralPath $out -Filter '*.class' | ForEach-Object FullName)
& "$AndroidSdk/build-tools/36.0.0/d8.bat" --min-api 29 --lib "$AndroidSdk/platforms/android-36/android.jar" --output $out $classes
if($LASTEXITCODE -ne 0){throw 'Device harness DEX failed'}
Get-Item -LiteralPath (Join-Path $out 'classes.dex')
