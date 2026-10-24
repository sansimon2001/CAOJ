# 草记：一键发布到 GitHub
# 用法：在本文件上右键 → 使用 PowerShell 运行
# 前提：已安装 Git，且本目录已经 git init（这两步已经做完）
$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

$repo = "caoji"
$visibility = "--private"   # 想公开就改成 "--public"

Write-Host ""
Write-Host "== 1/4 检查 GitHub CLI ==" -ForegroundColor Cyan
$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) {
    Write-Host "未找到 gh，尝试用 winget 安装（约 10MB）……" -ForegroundColor Yellow
    winget install --id GitHub.cli --accept-package-agreements --accept-source-agreements
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
                [Environment]::GetEnvironmentVariable("Path", "User")
    $gh = Get-Command gh -ErrorAction SilentlyContinue
}
if (-not $gh) {
    Write-Host "仍找不到 gh：请关掉本窗口重新打开后再运行一次，或手动执行" -ForegroundColor Red
    Write-Host "  winget install --id GitHub.cli" -ForegroundColor Red
    Read-Host "按回车退出"
    exit 1
}
Write-Host ("gh 版本：" + (gh --version | Select-Object -First 1)) -ForegroundColor DarkGray

Write-Host ""
Write-Host "== 2/4 检查登录状态 ==" -ForegroundColor Cyan
gh auth status *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "尚未登录。接下来会打开浏览器，复制页面上的验证码并授权即可。" -ForegroundColor Yellow
    gh auth login --hostname github.com --git-protocol https --web
}

Write-Host ""
Write-Host "== 3/4 创建仓库并推送 ==" -ForegroundColor Cyan
$remote = git remote get-url origin 2> $null
if (-not $remote) {
    gh repo create $repo $visibility --source=. --remote=origin --push
} else {
    Write-Host ("远端已存在：" + $remote) -ForegroundColor DarkGray
    git push -u origin main
}

Write-Host ""
Write-Host "== 4/4 完成，正在打开仓库页面 ==" -ForegroundColor Green
gh repo view --web
Read-Host "按回车关闭窗口"
