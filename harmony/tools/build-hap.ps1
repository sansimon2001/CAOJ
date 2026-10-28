<#
  草记 · 鸿蒙版 一键打包脚本（Windows PowerShell）

  作用：定位 DevEco Studio / HarmonyOS SDK / Node → 调用 hvigor 编译 → 收集 .hap 到 out\

  用法：
      powershell -ExecutionPolicy Bypass -File tools\build-hap.ps1
      powershell -ExecutionPolicy Bypass -File tools\build-hap.ps1 -Release
      powershell -ExecutionPolicy Bypass -File tools\build-hap.ps1 -Clean -Release
      powershell -ExecutionPolicy Bypass -File tools\build-hap.ps1 -DevEco "D:\Huawei\DevEco Studio"

  前置条件（缺一不可）：
      1) 装过 DevEco Studio 5.0（自带 HarmonyOS SDK、hvigor、Node、JDK）
      2) 用 DevEco 打开过本工程并等 Sync 完成（会生成 hvigorw.bat 与 hvigor\hvigor-wrapper.js）
      3) File → Project Structure → Signing Configs 勾过「Automatically generate signature」
         没签名只能产出 unsigned hap，装不进真机
#>
param(
    [switch]$Release,
    [switch]$Clean,
    [string]$DevEco = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Step($m) { Write-Host ""; Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "    $m" -ForegroundColor Green }
function Warn($m) { Write-Host "    $m" -ForegroundColor Yellow }
function Die($m)  { Write-Host ""; Write-Host "!! $m" -ForegroundColor Red; exit 1 }

# ---------------- 1. 定位 DevEco Studio ----------------
Step "定位 DevEco Studio"
$cands = @()
if ($DevEco -ne "") { $cands += $DevEco }
if ($env:DEVECO_HOME) { $cands += $env:DEVECO_HOME }
$cands += "C:\Program Files\Huawei\DevEco Studio"
$cands += "D:\Program Files\Huawei\DevEco Studio"
$cands += "C:\Huawei\DevEco Studio"
$cands += "D:\Huawei\DevEco Studio"

$devecoHome = ""
foreach ($c in $cands) {
    if ($c -eq "") { continue }
    if (Test-Path (Join-Path $c "bin\devecostudio64.exe")) { $devecoHome = $c; break }
}
if ($devecoHome -eq "") {
    Warn "没找到 DevEco Studio 安装目录"
    Die "请先安装 DevEco Studio 5.0，或用 -DevEco `"你的安装路径`" 指定"
}
Ok $devecoHome

# ---------------- 2. 定位 SDK ----------------
Step "定位 HarmonyOS SDK"
$sdkCands = @()
if ($env:DEVECO_SDK_HOME) { $sdkCands += $env:DEVECO_SDK_HOME }
if (Test-Path (Join-Path $devecoHome "sdk")) { $sdkCands += (Join-Path $devecoHome "sdk") }
if ($env:LOCALAPPDATA) { $sdkCands += (Join-Path $env:LOCALAPPDATA "Huawei\Sdk") }
$sdkHome = ""
foreach ($s in $sdkCands) {
    if ($s -eq "") { continue }
    if (Test-Path $s) { $sdkHome = $s; break }
}
if ($sdkHome -eq "") {
    Warn "没找到 SDK（通常首次打开 DevEco 时自动下载，路径 %LOCALAPPDATA%\Huawei\Sdk）"
    Die "请打开 DevEco Studio → SDK Manager 装一个 API 12 的 SDK 后重试"
}
$env:DEVECO_SDK_HOME = $sdkHome
Ok $sdkHome

# ---------------- 3. 定位 Node ----------------
Step "定位 Node"
$nodeExe = Join-Path $devecoHome "tools\node\node.exe"
if (Test-Path $nodeExe) {
    $nodeDir = Split-Path -Parent $nodeExe
    $env:PATH = "$nodeDir;" + $env:PATH
    Ok "$nodeExe"
} else {
    $cmd = Get-Command node -ErrorAction SilentlyContinue
    if ($cmd -eq $null) { Die "找不到 node.exe（DevEco 的 tools\node 下应该有；或自行安装 Node 18+）" }
    Ok $cmd.Source
}

# ---------------- 4. 定位构建入口 ----------------
Step "定位 hvigor 构建入口"
$hvigorBat = Join-Path $root "hvigorw.bat"
$hvigorJs  = Join-Path $devecoHome "tools\hvigor\bin\hvigorw.js"
if (Test-Path $hvigorBat) {
    Ok $hvigorBat
} elseif (Test-Path $hvigorJs) {
    Ok "$hvigorJs（用 node 直接跑）"
} else {
    Warn "工程里没有 hvigorw.bat，DevEco 的 tools\hvigor\bin 下也没找到 hvigorw.js"
    Die "请先用 DevEco Studio 打开本工程并等右下角 Sync 完成（会生成 hvigorw.bat），再重跑本脚本"
}

# ---------------- 5. 构建 ----------------
$mode = "debug"
if ($Release) { $mode = "release" }
$commonArgs = "--mode module -p product=default -p module=entry@default -p buildMode=$mode"

if ($Clean) {
    Step "清理上次构建产物"
    if (Test-Path $hvigorBat) {
        cmd /c "`"$hvigorBat`" clean --no-daemon"
    } else {
        & $nodeExe $hvigorJs clean --no-daemon
    }
    Ok "clean 完成"
}

Step "开始构建（buildMode=$mode）"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
if (Test-Path $hvigorBat) {
    cmd /c "`"$hvigorBat`" $commonArgs assembleHap --no-daemon"
} else {
    cmd /c "`"$nodeExe`" `"$hvigorJs`" $commonArgs assembleHap --no-daemon"
}
$code = $LASTEXITCODE
$sw.Stop()

if ($code -ne 0) {
    Write-Host ""
    Warn "hvigor 退出码 $code，构建失败"
    Warn "常见原因："
    Warn "  · Signing Configs 没配（Project Structure → Signing Configs 勾自动签名）"
    Warn "  · compatibleSdkVersion 与本地已装 SDK 不一致（改 build-profile.json5）"
    Warn "  · @kit.* 导出名差异（按 IDE 提示快速修复）"
    exit $code
}
Ok ("构建完成，用时 " + [math]::Round($sw.Elapsed.TotalSeconds, 1) + " 秒")

# ---------------- 6. 收集产物 ----------------
Step "收集 hap 产物"
$outDir = Join-Path $root "out"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$haps = @()
if (Test-Path (Join-Path $root "entry\build")) {
    $haps = Get-ChildItem -Path (Join-Path $root "entry\build") -Filter "*.hap" -Recurse -ErrorAction SilentlyContinue
}
if ($haps.Count -eq 0) {
    Warn "构建成功但没找到 .hap，检查 entry\build\default\outputs\ 目录"
    exit 1
}

$version = "1.18.0"
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
foreach ($h in $haps) {
    $signed = "unsigned"
    if ($h.Name -match "signed") { $signed = "signed" }
    $dest = Join-Path $outDir ("caoji-" + $version + "-" + $mode + "-" + $signed + "-" + $stamp + ".hap")
    Copy-Item $h.FullName $dest -Force
    $sizeMb = [math]::Round($h.Length / 1MB, 2)
    $sha = (Get-FileHash $dest -Algorithm SHA256).Hash
    Ok ($dest)
    Write-Host ("       " + $sizeMb + " MB   sha256=" + $sha.Substring(0, 16) + "...") -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "下一步：" -ForegroundColor Cyan
Write-Host "  · 产物在 out\ 目录"
Write-Host "  · 如果文件名带 unsigned：还没签名，装不进真机"
Write-Host "      用 DevEco 的自动签名重跑本脚本，或配置 tools\sign-config.json 后跑 tools\sign-hap.ps1"
Write-Host "  · 安装：hdc install -r out\xxx.hap"
Write-Host "  · 或者直接在 DevEco 里点绿色三角 Run，会自动装到选中设备"
