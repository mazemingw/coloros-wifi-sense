param([string]$Jdk = 'C:/Program Files/Java/jdk-17')
$ErrorActionPreference = 'Stop'
$out = Join-Path $PSScriptRoot 'build/tests'
New-Item -ItemType Directory -Path $out -Force | Out-Null
& (Join-Path $Jdk 'bin/javac.exe') -encoding UTF-8 --release 8 -d $out "$PSScriptRoot/src/WifiDisconnectPolicy.java" "$PSScriptRoot/src/WifiMonitor.java" "$PSScriptRoot/src/WifiBandPolicy.java" "$PSScriptRoot/src/WifiConnectionConfig.java" "$PSScriptRoot/src/WifiProbePolicy.java" "$PSScriptRoot/src/WifiSsidGuard.java" "$PSScriptRoot/test/WifiDisconnectPolicyTest.java" "$PSScriptRoot/test/WifiMonitorTest.java" "$PSScriptRoot/test/WifiBandPolicyTest.java" "$PSScriptRoot/test/WifiConnectionTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
foreach ($name in 'WifiDisconnectPolicyTest','WifiMonitorTest','WifiBandPolicyTest','WifiConnectionTest') {
    & (Join-Path $Jdk 'bin/java.exe') -cp $out $name
    if ($LASTEXITCODE -ne 0) { throw "$name failed" }
}
node "$PSScriptRoot/test/webui-test.cjs"
if ($LASTEXITCODE -ne 0) { throw 'WebUI tests failed' }
