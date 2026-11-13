# 同步源码到本地镜像（排除 target/node_modules），供 Maven/npm 构建。
# 用法: pwsh -File gitExclude\scripts\sync-build.ps1 [-Mirror <path>]
# 前提: mvn / node / npm 已在系统 PATH（用户已配置）。
param(
    [string]$Source = '\\170.240.110.32\playlet-generator',
    [string]$Mirror = "$env:TEMP\playlet-build"
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Source)) { throw "source not found: $Source" }
New-Item -ItemType Directory -Force -Path $Mirror | Out-Null

foreach ($part in 'backend', 'frontend', 'docs') {
    $src = Join-Path $Source $part
    if (Test-Path $src) {
        $dst = Join-Path $Mirror $part
        robocopy $src $dst /MIR /XD target node_modules dist .vite data /NFL /NDL /NJH /NJS /NP | Out-Null
        Write-Host "synced $part"
    }
}
Write-Host "mirror ready: $Mirror"
Write-Host "build:  mvn -B -f `"$Mirror\backend\pom.xml`" compile"
