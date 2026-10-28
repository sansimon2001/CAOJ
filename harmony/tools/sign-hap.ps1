<#
  草记 · 鸿蒙版 离线签名工具（Windows PowerShell）

  用途：手上有 unsigned .hap，且已有签名材料时，本地签出可安装的 hap。
  不需要登录华为账号，也不需要打开 DevEco 的 GUI。

  签名材料从哪来（华为 AppGallery Connect）：
      keystore(.p12) + keyAlias  ← 自己用 DevEco 生成（Build → Generate Key and CSR）或 keytool 生成
      appCert(.cer)              ← AGC「证书、APP ID 和 Profile」里申请 .cer 时下载
      profile(.p7b)              ← AGC 里新建 Profile（类型选 release/debug）后下载

  用法：
      1) 复制 tools\sign-config.sample.json 为 tools\sign-config.json，填 4 个路径 + 2 个密码
      2) powershell -ExecutionPolicy Bypass -File tools\sign-hap.ps1
         powershell -ExecutionPolicy Bypass -File tools\sign-hap.ps1 -In "out\caoji-xxx-unsigned.hap"
#>
param(
    [string]$In = "",
    [string]$Out = "",
    [string]$Config = "",
    [string]$DevEco = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Step($m) { Write-Host ""; Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "    $m" -ForegroundColor Green }
function Warn($m) { Write-Host "    $m" -ForegroundColor Yellow }
function Die($m)  { Write-Host ""; Write-Host "!! $m" -ForegroundColor Red; exit 1 }

# ---------------- 1. 读配置 ----------------
Step "读取签名配置"
if ($Config -eq "") { $Config = Join-Path $root "tools\sign-config.json" }
if (-not (Test-Path $Config)) {
    Warn "没有 $Config"
    Warn "先复制 tools\sign-config.sample.json 为 tools\sign-config.json 并填好"
    exit 1
}
$cfg = Get-Content $Config -Raw -Encoding UTF8 | ConvertFrom-Json
Ok $Config

function Need($name, $val) {
    if ($val -eq $null -or $val -eq "") { Die "sign-config.json 缺字段：$name" }
    return $val
}

$keystore    = Need "keystore"    $cfg.keystore
$keyAlias    = Need "keyAlias"    $cfg.keyAlias
$keyPwd      = Need "keyPwd"      $cfg.keyPwd
$keystorePwd = Need "keystorePwd" $cfg.keystorePwd
$appCert     = Need "appCert"     $cfg.appCert
$profile     = Need "profile"     $cfg.profile

foreach ($p in @($keystore, $appCert, $profile)) {
    if (-not (Test-Path $p)) { Die "文件不存在：$p" }
}

# ---------------- 2. 待签文件 ----------------
Step "定位待签 hap"
if ($In -eq "") {
    $outDir = Join-Path $root "out"
    $found = @()
    if (Test-Path $outDir) {
        $found = Get-ChildItem -Path $outDir -Filter "*.hap" | Where-Object { $_.Name -notmatch "signed" }
    }
    if ($found.Count -eq 0) {
        Die "out\ 下没有 unsigned hap，先跑 tools\build-hap.ps1，或用 -In 指定"
    }
    $In = $found[0].FullName
}
if (-not (Test-Path $In)) { Die "文件不存在：$In" }
Ok $In

if ($Out -eq "") {
    $Out = $In -replace "\.hap$", "-signed.hap"
}
Ok ("输出：" + $Out)

# ---------------- 3. 定位 java 与签名工具 ----------------
Step "定位 java 与 hap-sign-tool"
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

$javaExe = ""
if ($devecoHome -ne "") {
    $j = Join-Path $devecoHome "jbr\bin\java.exe"
    if (Test-Path $j) { $javaExe = $j }
}
if ($javaExe -eq "") {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd -ne $null) { $javaExe = $cmd.Source }
}
if ($javaExe -eq "") { Die "找不到 java（DevEco 自带 jbr\bin\java.exe，或自行安装 JDK 11+）" }
Ok $javaExe

$jarCands = @()
if ($devecoHome -ne "") {
    $jarCands += (Join-Path $devecoHome "sdk\default\openharmony\toolchains\lib\hap-sign-tool.jar")
    $jarCands += (Join-Path $devecoHome "sdk\default\hms\toolchains\lib\hap-sign-tool.jar")
}
if ($env:LOCALAPPDATA) {
    $jarCands += (Join-Path $env:LOCALAPPDATA "Huawei\Sdk\default\openharmony\toolchains\lib\hap-sign-tool.jar")
}
$jar = ""
foreach ($j in $jarCands) {
    if (Test-Path $j) { $jar = $j; break }
}
if ($jar -eq "") {
    # 兜底：在 SDK 目录里搜一次
    $sdkRoot = $env:DEVECO_SDK_HOME
    if ($sdkRoot -eq "" -and $env:LOCALAPPDATA) { $sdkRoot = Join-Path $env:LOCALAPPDATA "Huawei\Sdk" }
    if ($sdkRoot -ne "" -and (Test-Path $sdkRoot)) {
        $hit = Get-ChildItem -Path $sdkRoot -Filter "hap-sign-tool.jar" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit -ne $null) { $jar = $hit.FullName }
    }
}
if ($jar -eq "") { Die "找不到 hap-sign-tool.jar（在 HarmonyOS SDK 的 toolchains\lib 下）" }
Ok $jar

# ---------------- 4. 签名 ----------------
Step "开始签名（localSign / SHA256withECDSA）"
$signArgs = @(
    "-jar", $jar, "sign-app",
    "-keyAlias", $keyAlias,
    "-signAlg", "SHA256withECDSA",
    "-mode", "localSign",
    "-appCertFile", $appCert,
    "-profileFile", $profile,
    "-inFile", $In,
    "-keystoreFile", $keystore,
    "-outFile", $Out,
    "-keyPwd", $keyPwd,
    "-keystorePwd", $keystorePwd
)
& $javaExe @signArgs
if ($LASTEXITCODE -ne 0) { Die "签名失败（退出码 $LASTEXITCODE）" }
if (-not (Test-Path $Out)) { Die "签名命令返回成功但没生成文件，检查 -outFile 路径" }

$sizeMb = [math]::Round((Get-Item $Out).Length / 1MB, 2)
$sha = (Get-FileHash $Out -Algorithm SHA256).Hash
Ok ("签名完成：" + $Out)
Write-Host ("       " + $sizeMb + " MB   sha256=" + $sha) -ForegroundColor DarkGray

Write-Host ""
Write-Host "安装：" -ForegroundColor Cyan
Write-Host ("  hdc install -r `"" + $Out + "`"")
Write-Host "  或 DevEco 里 Run（自动装到选中设备）"
Write-Host ""
Write-Host "注意：profile 里的 bundleName 必须与 AppScope\app.json5 的 online.wokaka.caoji 一致，" -ForegroundColor Yellow
Write-Host "      否则会报 9568289 / profile 与证书不匹配。调试用 Profile 只能装调试设备。" -ForegroundColor Yellow
