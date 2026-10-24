# 发布到 GitHub

本文只讲「怎么把草记推上 GitHub、之后怎么发版」。仓库本身怎么用见 `README.md`。

---

## 0. 已经准备好的（在本地完成，不需要你再操作）

| 项 | 状态 |
|---|---|
| git 仓库 | 已初始化，默认分支 `main`，首个提交 143 个文件 |
| 签名密钥 | 已移出项目目录 → `~/keystores/mindball-release.jks` |
| 签名密码 | 在 `keystore.properties`（已被 `.gitignore` 忽略） |
| 构建 JAR / 密码 | 已从 `app/build.gradle.kts` 移除，改为读 `keystore.properties` |
| Gradle Wrapper | 已补 `gradlew` / `gradlew.bat` / `gradle-wrapper.jar`（原来只有 properties，别人 clone 下来根本构建不了） |
| `.gitignore` | 覆盖 `build/`、`.gradle/`、`local.properties`、`*.jks`、`*.keystore`、`keystore.properties` |
| `.gitattributes` | 仓库内统一 LF，`.bat`/`.ps1` 保留 CRLF，图片与 jar 标记为 binary |
| CI | `.github/workflows/build.yml`：push 构建、打 tag 自动发 Release |

> 提交作者当前是 `simon <sansimon2001@163.com>`。若想让提交关联到你的 GitHub 头像，
> 把下面两条换成你 GitHub 账号绑定的邮箱，然后改写首个提交的署名：
>
> ```bash
> git config user.email "你的GitHub邮箱"
> git config user.name "你的GitHub用户名"
> git commit --amend --reset-author --no-edit
> ```

---

## 1. 建仓库并推送

### 最快的方式：项目根目录的 `push-to-github.ps1`

在 `MindBall/push-to-github.ps1` 上**右键 → 使用 PowerShell 运行**，它会依次：

1. 检查 `gh`，没有就用 winget 装
2. 检查登录，没登录就开浏览器让你点授权
3. 执行 `gh repo create caoji --private --source=. --remote=origin --push`

脚本里 `$visibility` 改成 `"--public"` 就是公开仓库。跑完会自动打开仓库页面。

> 下面两条是手动路径，效果相同，供脚本跑不通时对照排查。

### 方式 A：GitHub CLI（手动，一条命令搞定）

```bash
# 安装（Windows，PowerShell）
winget install --id GitHub.cli

# 关掉当前终端重新打开，然后登录（浏览器里点一下授权）
gh auth login

cd ~/Desktop/MindBall
gh repo create caoji --private --source=. --remote=origin --push
```

`--private` 换成 `--public` 就是公开仓库。

### 方式 B：网页建仓库，手动推

1. 打开 <https://github.com/new>
2. 仓库名填 `caoji`；**不要**勾选 "Add a README file"、"Add .gitignore"、"Choose a license"
   （本地已经有，勾了会产生冲突，第一次推送就得先 pull --rebase）
3. 建好后在项目目录执行：

```bash
cd ~/Desktop/MindBall
git remote add origin https://github.com/<你的用户名>/caoji.git
git branch -M main
git push -u origin main
```

首次推送会要求认证。GitHub 早已不接受账号密码，用这两种之一：

- **HTTPS + Token**：Settings → Developer settings → Personal access tokens → Fine-grained tokens，
  勾选该仓库的 `Contents: Read and write`，推送时密码处粘贴 token
- **SSH**（一次配置长期免密）：`ssh-keygen -t ed25519` 后把 `~/.ssh/id_ed25519.pub`
  贴到 Settings → SSH and GPG keys，远端地址用 `git@github.com:<用户名>/caoji.git`

---

## 2. 之后每次发版

CI 会在你打 tag 时自动构建 APK 并挂到 Release 上，不用再本机打包。

```bash
# 1) 改版本号（versionCode 必须手工递增，商店只认它）
#    app/build.gradle.kts 里 versionCode 22 → 23

git add -A
git commit -m "v1.23：这一版改了什么"

# 2) 打 tag 并推送（tag 名带不带 v 都行，CI 会自动去掉前缀）
git tag v1.23
git push && git push origin v1.23
```

推送 tag 后：Actions 跑起来 → 产物出现在仓库 **Releases** 页，带自动生成的更新说明。

普通 `git push`（不打卡 tag）只跑编译检查，产物留在 Actions 的 artifact 里，不发 Release。

---

## 3. 让 CI 产出**可安装**的包（配置签名 Secrets）

不配也能跑，但产出的是 `-unsigned.apk`，手机装不上。
要出正式签名包，把密钥塞进 GitHub Secrets（加密存储，日志里不显示）：

1. 把 keystore 转成 base64 一行文本：

   ```bash
   base64 -w0 ~/keystores/mindball-release.jks > jks.txt
   # 打开 jks.txt 全选复制；复制完记得删掉这个明文文件
   ```

2. 仓库 → Settings → Secrets and variables → Actions → New repository secret，建 4 个：

   | Name | Value |
   |---|---|
   | `KEYSTORE_BASE64` | 上一步那一长串 |
   | `KEYSTORE_PASSWORD` | keystore 密码 |
   | `KEY_ALIAS` | `mindball` |
   | `KEY_PASSWORD` | key 密码 |

3. 再推一次 tag，产物就是签名过的 `caoji-v1.23.apk`，CI 日志里会打印证书 SHA-256
   供你核对（应恒为 `34abf277…c34ed`）。

---

## 4. 公开仓库前需要知道的两件事

**一、`.jks` 必须离线备份。**
丢了就再也签不出同签名的包，老用户只能卸载重装（本地数据会清空）。
网盘、U 盘各放一份，别只放在这台电脑上。

**二、仓库里会公开这些内容：**

| 内容 | 位置 |
|---|---|
| 反馈邮箱 `sansimon2001@163.com` | `util/Contact.kt`、`docs/legal-disclaimer.md`、`preview.html` |
| 本机绝对路径（含用户名 `simon`） | `README.md` 的构建命令 |
| 包名建议 `online.wokaka.caoji` | `docs/store-readiness.md` |

不想公开就走 `--private`，或用 `git filter-repo` 清掉再推。这两项我都没有擅自改动，
因为它们同时也是 App 里「关于」页和上架材料在用的信息。

---

## 5. 常见问题

**连不上 github.com（`curl` 返回 502 / 连接超时 / `InternetOpenUrl() failed`）**
本机如果开了代理软件，先确认它没挂；命令行里 `echo %HTTPS_PROXY%` 若指向一个已经失效的端口，
`git` 和 `winget` 都会跟着失败。清掉环境变量再试：

```bash
# Git Bash 临时清掉（只对当前窗口有效）
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
curl -I https://github.com
```

另外注意：**在这台机器上由 AI 助手执行的命令走的是沙箱代理**，即使你自己的终端能上 GitHub，
助手侧也可能连不上。所以 `gh` 安装和 `git push` 请在你自己开的终端 / PowerShell 里跑。

**推送被拒：`rejected — non-fast-forward`**
网页建仓时勾了 README/LICENSE，远端已有本地没有的提交。先 `git pull --rebase origin main` 再推。

**CI 报 `Permission denied` 跑 `./gradlew`**
Windows 提交的文件没有可执行位。本仓库已用 `git update-index --chmod=+x gradlew` 记录为 755，
workflow 里也加了 `chmod +x` 兜底。若仍失败，检查是否有别的脚本缺执行位。

**CI 构建失败但本机能过**
多为 Gradle 缓存或 SDK 版本差异。看 Actions 日志里 `assembleRelease` 的第一条 `e:` 错误；
本机用的是 `--offline`，CI 是联网拉依赖，偶尔会因为新版本依赖变动而暴露问题。

**手机提示「应用未安装：签名不一致」**
装过更早的 debug 签名版本，必须先卸载。卸载会清空本地 SQLite 里的记录，先导出备份。
