param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath (Split-Path $PSScriptRoot -Parent)).Path
$taskTemp = 'C:\Users\Administrator\AppData\Local\Temp\fluidcloud-investigation-20261107'
$script:freed = 0L

function Clear-OwnedTree([string]$Directory, [string]$AllowedParent) {
    if (-not (Test-Path -LiteralPath $Directory)) { return }
    $resolved = (Resolve-Path -LiteralPath $Directory).Path
    $parent = [IO.Path]::GetFullPath($AllowedParent).TrimEnd('\')
    if (-not $resolved.StartsWith($parent + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "Outside allowed root: $resolved" }
    if ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Refuse junction: $resolved" }
    Write-Output "Owned temporary directory: $resolved"
    # Delete individually validated leaf files; remove directories only if empty.
    $files = @(Get-ChildItem -LiteralPath $resolved -Recurse -File -Force)
    foreach ($file in $files) {
        if (-not $file.FullName.StartsWith($resolved + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe leaf path' }
        if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refuse linked leaf' }
        $script:freed += $file.Length
        if ($Apply) { Remove-Item -LiteralPath $file.FullName }
    }
    if ($Apply) {
        $dirs = @(Get-ChildItem -LiteralPath $resolved -Recurse -Directory | Sort-Object { $_.FullName.Length } -Descending)
        foreach ($dir in $dirs) {
            if (-not $dir.FullName.StartsWith($resolved + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe directory path' }
            if (-not @(Get-ChildItem -LiteralPath $dir.FullName -Force).Count) { Remove-Item -LiteralPath $dir.FullName }
        }
        if (-not @(Get-ChildItem -LiteralPath $resolved -Force).Count) { Remove-Item -LiteralPath $resolved }
    }
}

Clear-OwnedTree (Join-Path $workspace 'build\installer-lab\cache') $workspace
Clear-OwnedTree (Join-Path $workspace 'test\installer\upstream') $workspace
$lab = Join-Path $workspace 'build\installer-lab'
foreach ($file in @(Get-ChildItem -LiteralPath $lab -Recurse -File -Filter '*.cpio.gz')) {
    if (-not $file.FullName.StartsWith($lab + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe VM image path' }
    $script:freed += $file.Length
    if ($Apply) { Remove-Item -LiteralPath $file.FullName }
}
if (Test-Path -LiteralPath $taskTemp) {
    foreach ($name in @('com.coloros.scenemode-SceneMode','com.coloros.sceneservice-base',
        'com.oplus.pantanal.ums-base','com.oplus.smartengine-SmartEngine',
        'com.oplus.smartmediacontroller-SmartMediaController','com.oplus.systemui.plugins-SystemUIPlugin',
        'probe','session','systemui-plugin-src','publishing\python-deps')) {
        Clear-OwnedTree (Join-Path $taskTemp $name) $taskTemp
    }
    foreach ($file in @(Get-ChildItem -LiteralPath $taskTemp -File -Filter '*.apk')) {
        $script:freed += $file.Length
        if ($Apply) { Remove-Item -LiteralPath $file.FullName }
    }
}
$result = [PSCustomObject]@{applied=[bool]$Apply;removed_bytes=$script:freed;removed_mib=[math]::Round($script:freed/1MB,2);retained='source, reports, test results and compact evidence'}
$result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $workspace 'build\power-audit\cleanup.json') -Encoding utf8
$result | ConvertTo-Json
