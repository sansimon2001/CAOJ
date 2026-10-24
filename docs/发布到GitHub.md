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

### 方式 B：网页建仓库，手动推（不装 gh，逐屏对照）

适用：不想装 GitHub CLI、`winget` 跑不通，或想弄清每一步在网页哪里点。

**动手前先确认本地状态。** 在 `MindBall` 目录执行：

```bash
git status -sb      # 应为 ## main，没有 ahead/behind、没有列表外的改动
git remote -v       # 应完全没有输出（还没有远端）
git log --oneline   # 应能看到提交列表，条数 = 网页右上角 N commits 的 N
```

如果 `git remote -v` 已经打印了 origin，说明脚本或方式 A 跑过一半，
直接跳到 **B-3**，用 `git push -u origin main` 补齐即可。

---

#### B-1 建仓库页：<https://github.com/new>

在浏览器登录后打开这个地址（或点右上角 `+` → `New repository`）。

| 页面位置 | 页面上的原文 | 怎么填 |
|---|---|---|
| 页面左上 | `Owner` 下拉 | 选你自己的账号。属于组织才需要切换 |
| 中上部 | `Repository name` | `caoji`。填完下方会出现 `Your new repository will be available at https://github.com/<你>/caoji`，这行就是最后要用的地址 |
| 名字下方 | `Description (optional)` | 选填：`语音速记 → 待办，本地优先` |
| 中间单选 | `Public` / `Private` | 见下方说明。GitHub 会记住你上次的选择，新建的账号默认 Private |
| 单选下方 | `Initialize this repository with:` 三个复选框 | **三个全部不要勾**（`Add a README file`、`Add .gitignore`、`Choose a license`） |
| 右下 | 绿色按钮 `Create repository` | 点它 |

公开还是私有，只有一处差别：

| | Private | Public |
|---|---|---|
| 谁能看 | 只有你 | 所有人 |
| Actions 额度 | Free 账号 2000 分钟/月，一次构建约 3–6 分钟 | 不限 |
| 提交里的邮箱 | 仅自己可见 | **公开可见**（见 B-9） |

那三个复选框为什么必须不勾：本地已经有 143 个文件、3 个提交，
网页再生成一个提交，两边历史就分叉了，第一次推送会被拒（`non-fast-forward`）。

---

#### B-2 建完停在 Quick setup 页

点 `Create repository` 后会跳到 `Quick setup — if you've done this kind of thing before`。
这一页**不要照抄它的命令**，只看两个信息：

1. 页面顶部 `HTTPS` / `SSH` 切换按钮 —— 决定下面用哪种地址，默认 HTTPS 就行；
2. 页面最下方第三段代码块 `…or push an existing repository from the command line`，
   内容正是 `git remote add origin …` 那三行，和 B-3 一致，可以整段复制。

页面上还有一段 `…or create a new repository on the command line`，里面带
`echo "# caoji" >> README.md`，那段**跳过**，照抄会多一个空提交。

---

#### B-3 本地三条命令

打开 Git Bash（或在 `MindBall` 目录右键 → `Open Git Bash here`）：

```bash
cd ~/Desktop/MindBall
git remote add origin https://github.com/<你的用户名>/caoji.git
git branch -M main
git push -u origin main
```

`<你的用户名>` 换成 B-1 那行地址里的名字。不确定就看网页右上角头像 → `Your profile`，
地址栏 `github.com/xxx` 里的 `xxx` 就是。

第二条执行完用 `git remote -v` 验证，应打印两行（fetch / push）。

`git branch -M main` 现在执行也不会有变化——本地分支本来就是 `main`，
留着是为了照抄网上教程时不会莫名其妙推到 `master` 上去。

---

#### B-4 认证：终端会拦你一下

`git push` 之后，GitHub 不接受账号密码（2021 年 8 月起已移除）。
两种过法，选一种：

**其一：浏览器授权（最省事，什么都没配过就选这个）**

Windows 装 Git 时自带 Git Credential Manager，push 后会弹出一个窗口，
标题大致是 `GitHub` / `Connect to GitHub`：

1. 点 `Sign in with your browser`；
2. 浏览器自动打开 GitHub 授权页，已登录的话直接点绿色的 `Authorize Git Credential Manager`；
3. 页面显示 `Authentication Succeeded`，回到终端，push 会自己继续跑完。

凭证存进 Windows 凭据管理器，以后 push 不再问。

**其二：个人访问令牌（PAT，弹窗没出来或授权失败时用）**

1. 网页右上头像 → `Settings`；
2. 左侧栏拉到最底 `Developer settings`；
3. `Personal access tokens` → `Tokens (classic)`；
4. 右上 `Generate new token` → `Generate new token (classic)`；
5. `Note` 填 `caoji push`；`Expiration` 选 90 days；
6. 权限只勾一个大项：`repo`（勾上会连带勾选下面几个子项，正常）；
7. 页面底部 `Generate token` → 复制那串 `ghp_` 开头的字符串。
   **只显示这一次**，关掉页面就看不到，只能重新生成。

然后回到终端，提示 `Username for 'https://github.com':` 填 GitHub 用户名，
提示 `Password for 'https://<用户名>@github.com':` 粘贴 token（输入时不回显，粘完直接回车）。

想用 fine-grained token 也行，但要选 `Only select repositories` → `caoji`，
并把 `Repository permissions` → `Contents` 设为 `Read and write`，否则 push 报 403。

> SSH 是第三选项：`ssh-keygen -t ed25519` 生成后把 `~/.ssh/id_ed25519.pub`
> 贴到 `Settings` → `SSH and GPG keys` → `New SSH key`，远端地址改用
> `git@github.com:<用户名>/caoji.git`。一次性配置，长期免密，但第一次比 token 更绕。

推送成功的标志，终端最后几行长这样：

```
 * [new branch]      main -> main
branch 'main' set up to track 'origin/main'.
```

---

#### B-5 回网页确认：Code 页

刷新 `https://github.com/<你的用户名>/caoji`：

| 该看到 | 说明 |
|---|---|
| 文件列表里有 `app/`、`gradlew`、`README.md` | 146 个文件都在（首提交 143 + 后续补的 workflow、脚本、本文档） |
| 顶部 `main` 下拉旁显示 `N commits` | N 等于本地 `git rev-list --count HEAD` 的输出 |
| 右栏 `About` 区域是空的 | 正常，`Description` 没填也可以之后点齿轮补 |
| 页面下方渲染出 README 内容 | 不是必需的，但能看出 `.gitattributes` 生效、`gradlew` 没被当二进制显示 |

顺手确认一件事：`gradlew` 那一行的右侧应显示 `Executable file`（或权限 `100755`）。
如果显示成普通文件，CI 会在 `./gradlew` 那步 `Permission denied`——
workflow 里已有 `chmod +x` 兜底，但本地这一位修好更干净。

---

#### B-6 第一次构建：Actions 页

推送完成后**不用做任何事**，`.github/workflows/build.yml` 已经在仓库里，
GitHub 会自动跑一次。

1. 仓库顶部标签栏点 `Actions`；
2. 首次进入若看到绿色按钮 `I understand my workflows, go ahead and enable them`，点它（新仓库偶发）；
3. 左侧 Workflows 列表出现 `Build`，点进去，列表里会有一条由你的 push 触发的运行记录；
4. 状态图标：黄色转圈 = 跑着，绿色对勾 = 通过，红色叉 = 失败。第一次约 3–6 分钟（要下 Gradle 依赖，偏慢）；
5. 点进该条记录 → 左侧 `build` → 展开各步骤看日志。

这一轮属于「验证编译能过」，产物在运行页底部 `Artifacts` 区，名字是 `apk-<运行编号>`，
下载下来是**未签名**的（因为还没配 Secrets，见 B-7）。

---

#### B-7 让 CI 出可安装的包：Settings → Secrets

1. 在 `MindBall` 目录先把 keystore 转成单行 base64。**用 Git Bash**：

   ```bash
   base64 -w0 ~/keystores/mindball-release.jks > jks.txt
   ```

   或者用 PowerShell（结果等价，注意是 `[Convert]` 那条，别用 `-wrap`）：

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\keystores\mindball-release.jks")) | Set-Content -NoNewline jks.txt
   ```

   用记事本打开 `jks.txt`，`Ctrl+A` `Ctrl+C`——它必须是**一整行**，中间不能有换行。
   这个 keystore 转出来约 3664 个字符，粘完可以对着长度大致判断有没有漏。
   复制完把这个明文文件删掉。

2. 网页操作路径（仓库页内，不是账号设置）：

   | 步骤 | 页面元素 |
   |---|---|
   | 1 | 仓库顶部标签栏最右 `Settings`（齿轮） |
   | 2 | 左侧栏找到 `Secrets and variables` 分组 → 点 `Actions` |
   | 3 | 右侧页面标题 `Actions secrets and variables`，两个标签 `Secrets` / `Variables`，停在 `Secrets` |
   | 4 | 右上绿色按钮 `New repository secret` |
   | 5 | 表单两栏：`Name` 填名字，`Secret` 粘值 → `Add secret` |

3. 加四个，名字**区分大小写**，必须完全一致：

   | Name | Secret |
   |---|---|
   | `KEYSTORE_BASE64` | 第 1 步那一整行 |
   | `KEYSTORE_PASSWORD` | keystore 密码（`keystore.properties` 里的 `storePassword`） |
   | `KEY_ALIAS` | `mindball` |
   | `KEY_PASSWORD` | key 密码（`keystore.properties` 里的 `keyPassword`） |

4. 加完页面只列名字和 `Updated on …`，值不能再查看，只能 `Update` 覆盖。
   少一个、拼错一个，CI 都会静默退回未签名包——它只在日志里留一句
   `未配置 KEYSTORE_BASE64，本次产出未签名包`。

---

#### B-8 发版：只差一个 tag

**命令行路子（推荐，稳）**

```bash
# 先把 versionCode 从 22 改成 23（app/build.gradle.kts），否则商店/系统认为没更新
git add -A && git commit -m "v1.23：这一版改了什么"
git push

git tag v1.23
git push origin v1.23
```

第二条 `git push origin v1.23` 才是触发发版的那一下。CI 跑完，
左边 `Releases` 里会多一条，APK 直接挂在下面。

**网页路子（不想敲命令时）**

| 步骤 | 页面元素 |
|---|---|
| 1 | 仓库主页右栏 `Releases` → `Create a new release`（或直接开 `/releases/new`） |
| 2 | `Choose a tag` 输入框填 `v1.23`，下方出现 `Create new tag: v1.23 on publish`，点它 |
| 3 | `Target` 保持 `main` |
| 4 | `Release title` 填 `草记 v1.23` |
| 5 | `Describe this release` 写更新说明，或点上方 `Generate release notes` 让它自动整理 |
| 6 | 下方 `Attach binaries…` 可以把桌面上现成的 `草记-v1.22-正式签名.apk` 拖进来（可选） |
| 7 | 绿色 `Publish release` |

网页发布同样会创建 tag、触发 CI。如果 CI 又把 APK 挂到了同一个 Release 上，
说明它按 tag 找到了你刚建的那条，会追加而不是新建；万一出现两条同 tag 的，
删掉手建的那条即可，tag 本身不受影响。

---

#### B-9 公开仓库前（可选）：藏掉提交邮箱

当前所有提交的作者都是 `simon <sansimon2001@163.com>`。私有仓库无所谓，
公开仓库这串邮箱会出现在每条提交记录里。

要藏的话，**在推之前**做最省事（推完再改就得强推重写历史）：

1. 网页 `Settings` → `Emails` → 勾 `Keep my email addresses private`，
   页面会给出形如 `12345678+用户名@users.noreply.github.com` 的地址；
2. 本地改写三个提交的署名：

   ```bash
   git config user.email "12345678+用户名@users.noreply.github.com"
   git rebase --root --exec 'git commit --amend --no-edit --reset-author'
   git log --format='%h %an <%ae> %s'   # 核对三个提交都已换掉
   ```

`Contact.kt`、`docs/legal-disclaimer.md`、`preview.html` 里各有一处反馈邮箱，
那是 App「关于」页在用的联系方式，属于功能内容，不是提交元数据，改不改另说。

---

#### B-10 网页现象对照表

| 你看到的 | 原因 | 处理 |
|---|---|---|
| 终端：`remote: Support for password authentication was removed` | 密码处填了账号密码 | 改用 B-4 的浏览器授权或 PAT |
| 终端：`! [rejected] main -> main (fetch first)` / `non-fast-forward` | B-1 勾了 README / gitignore / license | `git pull --rebase origin main` 后重新 push |
| 终端：`remote: Permission to xxx/caoji.git denied` / 403 | token 权限不含 `Contents: write` | 重新生成 token，classic 勾 `repo`；fine-grained 选该仓库 + Contents 读写 |
| 网页 `This repository is empty` 但本地说推成功了 | 推到了别的仓库，或看的是另一账号的页面 | `git remote -v` 对一下地址里的人名 |
| Actions 红叉，日志：`base64: invalid input` | base64 带换行（PowerShell `-wrap` 默认换行） | 用 `base64 -w0` 或 `[Convert]::ToBase64String` 重新生成，确认单行 |
| Actions 绿勾，Release 里却是 `caoji-v1.23-unsigned.apk` | Secrets 名字拼错或漏配 | 回 B-7 逐个核对，注意大小写 |
| Actions 红叉，`./gradlew: Permission denied` | 缺可执行位 | `git update-index --chmod=+x gradlew` 后提交；workflow 内已有 `chmod +x` 兜底 |
| `git push` 一直卡住不动 | 代理环境变量指向失效端口 | `unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY` 后重试（只对当前窗口生效） |

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
