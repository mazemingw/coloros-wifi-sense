param(
    [Parameter(Mandatory = $true)][string]$AndroidSdk,
    [Parameter(Mandatory = $true)][string]$Jdk,
    [string]$AndroidNdk
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $Jdk
$androidJar = Join-Path $AndroidSdk 'platforms/android-36/android.jar'
$d8 = Join-Path $AndroidSdk 'build-tools/36.0.0/d8.bat'
$repoRoot = $PSScriptRoot
$build = Join-Path $repoRoot 'build'
$staging = Join-Path $build 'coloros-wifi-sense-work'
$sourceNames = @('src/ColorOsWifiSense.java', 'src/WifiDisconnectPolicy.java', 'src/WifiMonitor.java', 'src/WifiSystemEvents.java', 'src/WifiFluidSession.java', 'src/WifiBandControl.java', 'src/WifiBandPolicy.java')
$sourceNames += @('src/WifiConnectionConfig.java','src/WifiConnectionControl.java','src/WifiProbePolicy.java','src/WifiSsidGuard.java')
$sources = @($sourceNames | ForEach-Object { Join-Path $PSScriptRoot $_ })
$moduleProp = Get-Content (Join-Path $PSScriptRoot 'module.prop') -Encoding UTF8
$versionLine = $moduleProp | Where-Object { $_ -match '^version=v[0-9.]+$' } | Select-Object -First 1
if (!$versionLine) { throw 'Module version missing from module.prop' }
$version = $versionLine.Substring('version='.Length)
$zipPath = Join-Path $build "coloros-wifi-sense-$version.zip"

New-Item -ItemType Directory -Path $staging -Force | Out-Null
Get-ChildItem -LiteralPath $staging -Filter '*.class' -File | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
& (Join-Path $Jdk 'bin/javac.exe') -encoding UTF-8 --release 8 -classpath $androidJar -d $staging $sources
if ($LASTEXITCODE -ne 0) { throw 'Module Java compilation failed' }
$coreClasses = @(Get-ChildItem $staging -Filter '*.class' | Where-Object { $_.Name -match '^(ColorOsWifiSense|WifiDisconnectPolicy|WifiMonitor|WifiSystemEvents|WifiBand|WifiConnection|WifiProbe|WifiSsid)' } | ForEach-Object FullName)
& $d8 --min-api 29 --lib $androidJar --output $staging $coreClasses
if ($LASTEXITCODE -ne 0) { throw 'Module DEX generation failed' }
$fluidStaging = Join-Path $staging 'fluid'
New-Item -ItemType Directory -Path $fluidStaging -Force | Out-Null
& $d8 --min-api 29 --lib $androidJar --output $fluidStaging @(Get-ChildItem $staging -Filter 'WifiFluidSession*.class' | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { throw 'Fluid DEX generation failed' }

if (!$AndroidNdk) {
    $AndroidNdk = @(Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'ndk') -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName)[0]
}
if (!$AndroidNdk) { throw 'Android NDK is required for the runtime band helper' }
$nativeTargets = @{'arm64-v8a'='aarch64-linux-android29';'armeabi-v7a'='armv7a-linux-androideabi29';'x86_64'='x86_64-linux-android29';'x86'='i686-linux-android29'}
foreach ($abi in $nativeTargets.Keys) {
    $nativeDir = Join-Path $staging "bin/$abi"
    New-Item -ItemType Directory -Path $nativeDir -Force | Out-Null
    $compiler = Join-Path $AndroidNdk "toolchains/llvm/prebuilt/windows-x86_64/bin/$($nativeTargets[$abi])-clang.cmd"
    & $compiler -Os -Wall -Wextra -Werror -fPIE -pie '-Wl,-z,relro,-z,now' '-Wl,--build-id=none' '-Wl,-z,max-page-size=16384' -s (Join-Path $PSScriptRoot 'src/wifi_band.c') -o (Join-Path $nativeDir 'wifi-band')
    if ($LASTEXITCODE -ne 0) { throw "Band helper compilation failed: $abi" }
}

Add-Type -AssemblyName System.IO.Compression
$utf8 = [Text.UTF8Encoding]::new($false)
$stream = [IO.File]::Create($zipPath)
try {
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $files = @('module.prop', 'customize.sh', 'service.sh', 'action.sh', 'settings.sh', 'demo.sh', 'webctl.sh', 'uninstall.sh', 'src/wifi_band.c', 'README.md') + $sourceNames
        $files += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'webroot') -File | ForEach-Object { 'webroot/' + $_.Name })
        foreach ($name in $files + 'LICENSE' + 'wifi-sense.dex' + 'wifi-fluid.dex') {
            $entry = $zip.CreateEntry($name)
            $entry.ExternalAttributes = if ($name.EndsWith('.sh')) { 0x81ED0000 } else { 0x81A40000 }
            if ($name -eq 'wifi-sense.dex') {
                $bytes = [IO.File]::ReadAllBytes((Join-Path $staging 'classes.dex'))
            } elseif ($name -eq 'wifi-fluid.dex') {
                $bytes = [IO.File]::ReadAllBytes((Join-Path $fluidStaging 'classes.dex'))
            } else {
                $path = if ($name -eq 'LICENSE') { Join-Path $repoRoot $name } else { Join-Path $PSScriptRoot $name }
                $bytes = $utf8.GetBytes([IO.File]::ReadAllText($path).Replace("`r`n", "`n"))
            }
            $output = $entry.Open()
            try { $output.Write($bytes, 0, $bytes.Length) } finally { $output.Dispose() }
        }
        foreach ($abi in $nativeTargets.Keys) {
            $name = "bin/$abi/wifi-band"
            $entry = $zip.CreateEntry($name)
            $entry.ExternalAttributes = 0x81ED0000
            $bytes = [IO.File]::ReadAllBytes((Join-Path $staging $name))
            $output = $entry.Open()
            try { $output.Write($bytes, 0, $bytes.Length) } finally { $output.Dispose() }
        }
    } finally { $zip.Dispose() }
} finally { $stream.Dispose() }

Get-Item $zipPath
