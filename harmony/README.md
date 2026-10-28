# 草记 · 鸿蒙原生版（HarmonyOS NEXT）

Android 版（同仓库根目录 `app/`，Kotlin + XML）的鸿蒙原生重写：**ArkTS + ArkUI，Stage 模型，API 12（HarmonyOS 5.0）**。
不是壳、不是 WebView、不是跨平台框架，页面、录音、存储、加密、网络全部走鸿蒙原生能力。

---

## 一、怎么打开

1. DevEco Studio **5.0 及以上**（支持 API 12 / HarmonyOS NEXT）
2. `File → Open` 选择本目录（仓库里的 `harmony/`）
3. 首次打开会自动同步 hvigor 与依赖（需要网络）
4. `File → Project Structure → Signing Configs` 勾选 **Automatically generate signature**（否则装不上真机）
5. 运行设备选真机（模拟器没有麦克风和蓝牙，录音与耳机触发都测不了）

包名 `online.wokaka.caoji`，版本 `1.25.0 (versionCode 125)`。

> **要出可安装的 .hap**：见 [`docs/打包与安装.md`](docs/打包与安装.md)。
> 图形界面是一条最短路径（Sync → 自动签名 → Build Hap）；命令行则跑 `tools\build-hap.ps1`（自动找 SDK、编译、收产物到 `out\`），
> 需要离线签名时再用 `tools\sign-hap.ps1` + `tools\sign-config.json`。
> `.hap` 里的 `modules.abc` 与 `resources.index` 只能由 SDK 编译链生成，无法凭空造出来。

> **当前状态**：本工程已用 OpenHarmony 5.0.0 SDK（API 12）+ hvigor CLI **编译通过**（无需 DevEco、无需华为账号），
> 产出未签名 hap 约 1.06 MB，每次 `tools/ohos-build.sh` 约 16 秒。
> 该包**装不上真机**——签名必须用 DevEco Studio 配华为账号，这一步绕不过去。
> 命令行构建的环境搭建与踩坑记录见 [`tools/ohos-build.sh`](tools/ohos-build.sh) 与文末「构建」一节。

---

## 二、目录结构

```
harmony/                            （仓库子目录）
├── AppScope/                       应用级配置与图标
├── build-profile.json5             编译配置（compatibleSdkVersion 5.0.0(12)）
├── entry/src/main/
│   ├── module.json5                权限、Ability、分享接收（sendData）
│   ├── resources/rawfile/          免责声明全文（随包内置，离线可读）
│   └── ets/
│       ├── entryability/EntryAbility.ets     启动、初始化、接收外部分享
│       ├── pages/Index.ets                   四页容器 + 自绘底栏 + 录音器持有
│       ├── pages/SessionDetailPage.ets       记录详情（摘要/待办/原文/导出/推送）
│       ├── pages/LegalPage.ets               用户协议与免责声明
│       ├── view/TodayView.ets                今日：录音主控 + 记录流
│       ├── view/ProjectsView.ets             项目：聚合 + 项目洞见
│       ├── view/InsightView.ets              觉察：统计 / 待办 / 行为复盘
│       ├── view/SettingsView.ets             设置：5 张可折叠分组卡
│       ├── model/Models.ets                  领域模型（字段与 Android 版一一对应）
│       ├── data/Store.ets                    relationalStore 四张表
│       ├── data/Repo.ets                     偏好 + 默认服务商 + 敏感项加密
│       ├── service/Recorder.ets              AVRecorder 录音、分段、通话检测、音源
│       ├── service/BluetoothWatch.ets        耳机连接轮询
│       ├── service/HeadsetKeys.ets           耳机键接管（AVSession，三档）
│       ├── service/WatchLink.ets              手表遥控（局域网 UDP）
│       ├── service/AsrClient.ets             语音转写（multipart 手拼）
│       ├── service/LlmClient.ets             摘要/待办/问答/洞见/复盘
│       ├── service/Pipeline.ets              转写→摘要→原音处理→自动外发
│       ├── service/CollabBridge.ets          飞书/钉钉群机器人（含加签）+ WorkBuddy
│       ├── service/ExportService.ets         Markdown 导出、剪贴板
│       ├── util/SecureStore.ets              AES-256-CBC + 随机 IV 加密存密钥
│       ├── util/TranscriptParser.ets         srt/vtt/txt/md/json 解析
│       ├── util/Importer.ets                 文件选择器导入音频与文稿
│       ├── util/Legal.ets                    免责声明读取 + 反馈邮箱
│       └── util/TimeUtil.ets                 时间格式化与换算
├── hvigor/hvigor-config.json5      hvigor 构建器配置（命令行构建需要）
├── tools/
│   ├── build-hap.ps1               一键打包：找 SDK → 编译 → 产物收进 out\
│   ├── sign-hap.ps1                离线签名（hap-sign-tool localSign）
│   └── sign-config.sample.json     签名材料模板（复制成 sign-config.json 填）
├── docs/打包与安装.md              从源码到装机的完整步骤 + 报错对照表
└── README.md
```

代码量：**24 个 .ets，约 6 300 行**。

---

## 三、功能对照（与 Android 版）

| 能力 | 鸿蒙版 | 实现方式 |
|---|---|---|
| 连蓝牙耳机自动开始记录 | ✅ | `BluetoothWatch` 每 3 秒查 A2DP/HFP 连接状态，翻转即触发 |
| **耳机键接管**（播放键=开始/结束，下一首键=打重点） | ✅ | `HeadsetKeys`：注册 `audio` 类型 AVSession，录音时 `activate()` 把键抢过来，录完 `deactivate()` 还给音乐 App |
| **边听音乐边录音** | ✅ | 默认走手机麦，完全不碰蓝牙输出通路；选耳机麦（SCO）时通路独占、音乐会被系统暂停，设置里写明取舍 |
| **手表遥控**（安卓表，同一 Wi-Fi） | ✅ | `WatchLink`：UDP 47321 收 `start/stop/mark`，手表广播 `ping` 自动发现；录音与转写仍在手机 |
| 手动开始 / 结束 | ✅ | 今日页主按钮 |
| 音源切换（手机麦 / 耳机麦） | ✅ | `AudioRoutingManager.setPreferredInputDeviceForCapturerInfo`；切段不丢内容 |
| 30 分钟自动分段 | ✅ | 单段超时先收尾保存再开新段 |
| 通话打断续录 | ✅ | 轮询 `getAudioScene()`，连续 2 次命中才动作；通话结束自动开新段 |
| 重点标记 | ✅ | 记录毫秒偏移，写入 `marks` |
| 语音转写 | ✅ | OpenAI `/audio/transcriptions` 协议，手拼 multipart |
| 摘要 / 待办 / 项目归属 | ✅ | 同一套 prompt 与「三层治理」（分块抽要点 → 强约束 → 摘要回扣校验） |
| 项目洞见 / 行为复盘 | ✅ | 结果落 `project_insights` / 页面状态 |
| 待办勾选与到期 | ✅ | 逾期/今日到期统计 |
| 飞书 / 钉钉群机器人 | ✅ | 含两家不同的加签规则 + 错误码翻译 |
| WorkBuddy Webhook | ✅ | 结构化 JSON，Bearer 鉴权 |
| 导入外部录音 / 转写稿 | ✅ | 系统文件选择器，支持 m4a/mp3/wav… 与 srt/vtt/txt/md/json |
| 导出 Markdown | ✅ | DocumentViewPicker 保存，不需要存储权限 |
| 密钥加密存储 | ✅ | AES-256-CBC + 每次随机 IV，主密钥存应用沙箱（EL2） |
| 防截屏 | ✅ | `setWindowPrivacyMode` |
| 用户协议与免责声明 | ✅ | rawfile 内置全文，可复制可截图 |
| 反馈邮箱 | ✅ | `sansimon2001@163.com`，一键唤起邮件 / 复制 |

### 本版未包含（原因写清楚，不是漏了）

| 项目 | 原因 | 替代 |
|---|---|---|
| 长按说话（语音速记 / 即时问答） | 需要第二套短录音 + 问答 UI，本轮先保证主链路 | 可先用录音 + 详情页提问；下版补 |
| 环境声学分析（安静/会议/嘈杂） | AVRecorder 输出的是编码后的 AAC，拿不到 PCM；再开 AudioCapturer 会抢麦 | `envType` 统一记 `unknown`，不谎报 |
| 系统通话录音导入 | 鸿蒙没有 Android 那种通话录音目录约定，需按机型另适配 | 用文件选择器手动导入 |
| .docx 转写稿 | 需要 zip 解压 + XML 解析，鸿蒙侧还没有现成轻量方案 | 先在电脑上另存为 txt/srt 再导入 |
| 直接分享到某个 App | 对方必须声明可接收的 utd 类型，各家不一致，做出来经常空白 | 导出文件 / 复制 / 群机器人三条稳的路 |

---

## 四、几个关键实现，值得单独说

### 1. 为什么录音状态要放在 Index 里

录音器、蓝牙监听、权限申请都挂在 `Index`（页面容器），不放在子视图里。
放在 `TodayView` 的话，用户切到「设置」页时子组件销毁，录音跟着断——这是这类应用最常见的坑。

### 2. 通话检测为什么是「连续 2 次才动作」

`getAudioScene()` 在系统切换音频通路时会瞬时跳变（A2DP → SCO 的那一下）。
1 秒轮询、连续 2 次命中才算数，能过滤掉绝大多数误判；代价是最多 2 秒延迟。

### 3. 两家的加签规则真的不一样

| | 飞书 | 钉钉 |
|---|---|---|
| HMAC key | 空字符串 | secret 本身 |
| 待签内容 | `ts\nsecret` | `ts\nsecret` |
| sign 位置 | body 里的 `sign` 字段 | URL 参数（要 URL 编码） |

两家失败时**都返回 HTTP 200**，必须看 `code` / `errcode`，否则会「以为发出去了其实没发」。

### 4. 密钥怎么存

`SecureStore`：AES-256-CBC + 每次随机 16 字节 IV，密文 `iv:cipher` 存 preferences；
主密钥 32 字节随机生成，落在应用沙箱 `filesDir/caoji_master.key`，只在启动时读进内存。
鸿蒙沙箱位于设备加密分区（EL2），卸载随沙箱销毁。

要提升到「密钥不出 TEE」，把 `loadOrCreateKey` 换成 HUKS 的 `generateKeyItem` + 会话式加解密即可，上层接口不用动。

### 5. 耳机键为什么能「有时管音乐、有时管录音」

蓝牙耳机上只有一组播放/暂停键。系统不给任何 App 直接监听它的机会——它只把事件送给
**当前活跃的媒体会话**（AVSession）。

所以答案不是「监听按键」，而是「争当那个会话」：

| 时机 | 草记做什么 | 耳机上的键归谁 |
|---|---|---|
| 空闲 | 不激活会话（或激活但状态置 PAUSE，看档位） | 音乐 App |
| 按下开始记录 | `activate()` + 播放状态置 PLAY | 草记：按一下 = 结束 |
| 记录结束 | `deactivate()` | 立刻回到音乐 App |

设置 → 录音与音源 → 「耳机上的播放键归谁」三档：

- **只在录音中**（默认）：录音时接管，其余时间完全让给音乐
- **全程接管**：不录音时按播放键就开始记录。代价是听音乐时按它也会开始录音
- **不接管**：耳机键只给音乐，开始录音靠「连耳机自动开始」或今日页按钮

不用音量键那条路：音量键是系统保留键，第三方拿不到，而且它会改音量——
录音时按一下把手机调成静音，用户只会以为坏了。

「边听音乐边录音」是另一件事，靠音源解决而不是靠按键：默认用**手机麦克风**，
采集完全不碰蓝牙输出通路（A2DP），音乐照放。只有选了「用蓝牙耳机麦克风录音」才会切到
SCO——那条通路是独占的，系统会把音乐暂停。两个开关各管一件事，设置页里都写清了。

### 6. 手表遥控为什么用 UDP，而不是 Wear OS 那套

Wear OS 手表与 Android 手机之间，官方通道是 Google Play 服务里的 Wearable Data Layer
（`MessageClient` / `DataClient`）。**鸿蒙手机没有 GMS，这也装不了 Wear OS 配套应用** ——
那条路在这里不存在，不是没接，是接不上。

所以自建一条局域网 UDP（`service/WatchLink.ets`，默认端口 47321）：

- 手表先广播 `{"c":"ping"}` → 手机收到后**单播**回状态，手表记住回包来源地址
- 之后手表直接单播 `{"c":"start"|"stop"|"mark"}`，不需要输入 IP、不需要配对
- 状态只在**翻转时**回推；秒表由手表按本地时间自己走（每秒推一次会让手表多醒几百次）
- 开关在「设置 → 录音与音源 → 手表遥控」，手机端会把当前地址显示出来方便核对

手表端是单独的安卓包（同仓库 `watch/` 目录），不碰麦克风、不存内容、不连服务器，
所以功耗约等于一块表盘。已知局限：必须在同一个 Wi-Fi（手机开热点也行）。

---

## 五、首次运行检查清单

0. 全新安装启动 → 先出「隐私与使用须知」（五条摘要 + 完整声明入口）→ 点「同意并开始使用」后才弹麦克风授权。点「不同意」直接退出应用。
1. 启动 → 允许麦克风权限
2. 连上蓝牙耳机 → 应自动开始记录（若在设置里关了自动记录，点今日页按钮）
2.1 记录中按耳机播放键 → 应结束记录；不记录时按它 → 仍由音乐 App 接管（默认档）
2.2 设置里打开「手表遥控」→ 手表端应显示「已连 手机名」，按大圆钮能开始/结束
3. 设置 → AI 服务 → 填硅基流动 API Key（地址与模型已预填）→ 保存
4. 录一段话 → 结束 → 等 10~30 秒 → 记录里应出现摘要与待办
5. 设置 → 打通与分发 → 填飞书/钉钉 Webhook → 「保存并测试」→ 群里应收到测试消息
6. 记录详情 → 「导出 .md」→ 选目录保存 → 用文件管理器确认能打开
7. 设置 → 关于与法务 → 打开免责声明 → 应能滚动到底并看到反馈邮箱

---

## 六、上架前还需要做的

- **AppGallery Connect 建应用**：包名与 `AppScope/app.json5` 一致，补齐应用分类、截图、隐私政策 URL
- **隐私政策**：需写明「录音、转写文本仅在用户配置第三方服务时上传至该服务商」，并把 `docs/legal-disclaimer.md`（Android 项目里那份）的全文挂成可访问页面
- **权限说明文案**：`module.json5` 里三条 `reason` 已写，上架审核会逐条核对是否与实际用途一致
- **软著 / 商标**：应用名「草记」建议先查一下是否已被注册
- **测试机型**：蓝牙触发与后台长时录音在不同厂商 ROM 上表现差异明显，至少覆盖华为、荣耀、小米各一台

---

## 七、静态自检（本机无 DevEco，逐行核对过的部分）

本机没有安装 DevEco Studio 与 HarmonyOS SDK，工程未做真机编译。为避免第一次 Sync 就被一堆红波浪线劝退，用「对照官方 API 文档 + 跨文件符号核对」的方式自查了一遍，改掉的问题如下：

| # | 位置 | 问题 | 处理 |
|---|------|------|------|
| 1 | `service/BluetoothWatch.ets` | 写成 `bluetooth.connection.getProfileConnectionState(...)`、`bluetooth.ProfileId.*`，这两个命名空间不存在 | 改为官方口径 `import { connection, constant } from '@kit.ConnectivityKit'`，枚举用 `constant.ProfileId` / `constant.ProfileConnectionState` |
| 2 | 同上 | `resolveName()` 遍历已配对设备却查全局 profile 状态，多设备时会把 A 设备的连接态套到 B 设备名字上 | 仅在「已配对设备恰好一台」时用其名字，否则回落「蓝牙耳机」 |
| 3 | `service/Recorder.ets` | `getAudioScene()` 返回的是 `Promise<AudioScene>`，直接和枚举比较必然类型报错 | 改 `getAudioSceneSync()` |
| 4 | 同上 | `getPreferredInputDeviceForCapturerInfo()` 只返回「当前首选的那一个设备」，拿它去找蓝牙永远找不到 | 改 `getDevices(DeviceFlag.INPUT_DEVICES_FLAG)` 枚举全部输入设备再挑；SCO 优先、A2DP 兜底、手机麦用 `DeviceType.MIC` |
| 5 | 同上 | AVRecorder 只借用 fd，`release()` 后 fd 一直没关，每段漏一个句柄 | 加 `segFile` 字段 + `closeSegFile()`，在 `release()` 之后与失败路径统一关闭 |
| 6 | `entry/src/main/module.json5` | 缺 `backgroundModes: ["audioRecording"]`，后台长时任务申请会失败（表现为切后台 30 秒录音被掐） | 已补 |
| 7 | `util/Importer.ets` | `createAVMetadataExtractor()` 没设 `fdSrc` 就 `fetchMetadata()`，导入音频时长恒为 0 | 先 `openSync` 再 `fdSrc = { fd, offset: 0, length: stat.size }`，finally 里释放并关 fd |
| 8 | 同上 | `copyFileSync(pickerUri, dest)` 直接传 uri，部分版本不支持 | 先按 uri `openSync` 拿 fd，再 `copyFileSync(src.fd, dest)` |
| 9 | `data/Store.ets` | 无逾期待办时 `SUM(...)` 返回 NULL，`getLong()` 可能抛异常 | 加 `COALESCE(...,0)` |
| 10 | `view/TodayView.ets` | `session: Session = null!` 依赖非空断言 | 改用 `emptySession()` 初始化 |
| 11 | `service/AsrClient.ets` | `let audioBytes: ArrayBuffer;` 在 try 内赋值，提前 return 时编译器判「可能未赋值」 | 初始化为空 buffer |
| 12 | `pages/Index.ets` | `Keys.PRIVACY_ACK` 只定义、从没被读写——等于没有首次启动隐私同意门，华为审核会按「未同意即申请权限」驳回 | 增 `consentLayer()` 覆盖层：五条摘要 + 完整声明入口 + 同意/退出；未同意前不申请麦克风、不开蓝牙监听、不联网 |

核对过、确认无误的关键 API：`AVRecorder`（m4a/44.1k/64k 单声道）、`backgroundTaskManager.startBackgroundRunning(ctx, AUDIO_RECORDING, wantAgent)`、`wantAgent.OperationType/WantAgentFlags`、`relationalStore` 建表与 `querySql` 游标、`preferences.getSync`、`cryptoFramework` AES-256-CBC（`AES256|CBC|PKCS7` + `IvParamsSpec`）与 HMAC-SHA256、`http.createHttp().request`、`picker.DocumentViewPicker.save/select`、`pasteboard`、`window.setWindowPrivacyMode`。

> 说明：空 key 的 HMAC 用 `\u0000` 占位是刻意的——HMAC 会把短 key 右侧补零到 64 字节，`""` 与 `"\0"` 补齐后完全相同，所以飞书签名的计算结果与「空 key」一致，而 `convertKey({data: new Uint8Array(0)})` 在部分版本会被拒绝。

---

## 命令行构建（本机已验证可出 hap）

本机没有 DevEco Studio、也没有华为开发者账号，但用**公开的 OpenHarmony SDK + hvigor** 一样能真正编译一次——这比肉眼静态检查有价值得多：ArkTS 编译器会直接把类型错、SDK API 误用、不存在的导出名全部指出来。

### 本机工具链位置

| 组件 | 位置 | 版本 |
|------|------|------|
| OpenHarmony SDK | `C:/Users/simon/ohos-sdk/Sdk/12/ets` | 5.0.0.71 Release（API 12） |
| hvigor | `C:/Users/simon/ohos-sdk/hvigor-env/` | 5.19.8（华为 npm 源安装） |
| JDK | `C:/Users/simon/.workbuddy/binaries/jdk17/jdk-17.0.20.1+1` | Temurin 17.0.20.1 |

SDK 来源：`https://repo.huaweicloud.com/openharmony/os/5.0.0-Release/ohos-sdk-windows_linux-public.tar.gz`（免登录，sha256 已核对）。解压后要展成 `<sdk>/12/{ets,js,native,previewer,toolchains}` 布局——hvigor 是按 `<sdk.dir>/<api版本>/<组件>/oh-uni-package.json` 找组件的。

### 三条打包路线，按手上有什么选

| 场景 | 用哪个 | 说明 |
|------|--------|------|
| 没装 DevEco（本机现状） | `tools/ohos-build.sh` | OpenHarmony SDK 命令行编译，只出 unsigned hap，用途是**让编译器验证代码** |
| 装了 DevEco 5.0+ | `tools/build-hap.ps1` | 自动定位 DevEco 自带的 HarmonyOS SDK / hvigor / JDK 编译 |
| 有签名材料 | `tools/sign-hap.ps1` | 离线签 hap，不需要账号也不需要 GUI，填 `sign-config.json` 即可 |

### 怎么构建

```bash
tools/ohos-build.sh          # 出 entry-default-unsigned.hap
```

脚本会自己配好 `JAVA_HOME` / `PATH` / `NODE_PATH` / `DEVECO_SDK_HOME`，把工程临时切成 OpenHarmony 配置，构建完**自动恢复 HarmonyOS 正本**（`trap` 兜底，中断也会恢复）。

### 两套配置为什么要切换

HarmonyOS 与 OpenHarmony 的 schema 不兼容，硬凑不进去：

| 项目 | HarmonyOS（正本） | OpenHarmony（构建时派生） |
|------|------------------|--------------------------|
| `runtimeOS` | `"HarmonyOS"` | `"OpenHarmony"` |
| `compatibleSdkVersion` | `"5.0.0(12)"`（字符串） | `12`（整数） |
| `compileSdkVersion` | 不需要 | 必须配 |
| `backgroundModes` | 支持（长时任务必需） | schema 不认此字段 |
| `deviceTypes` | `phone` / `tablet` | 只能 `default` |

正本永远是 HarmonyOS 那一份（`.harmony.bak`），OpenHarmony 版每次从正本现算，所以 DevEco 打开时看到的一定是对的。

```bash
python tools/switch-target.py status        # 看当前是哪一套
python tools/switch-target.py harmony       # 手动恢复正本
```

### 编译器抓出来的真问题（12 处，原静态检查全漏了）

| 位置 | 问题 | 影响 |
|------|------|------|
| `HeadsetKeys.ets` | `avSession.AVSessionType.AUDIO` —— 该处 `AVSessionType` 是类型别名不是枚举 | 编译不过；`createAVSession` 直接传 `'audio'` |
| `HeadsetKeys.ets` | `AVMetadata.displayTags` 是 `number` 位掩码，写成 `string[]` | 编译不过；已删除该字段 |
| `WatchLink.ets` | `socket.SocketMessageInfo` 类型根本不存在（SDK 只有 `SocketRemoteInfo`） | 整条链被推断成 any，连带 4 个错误；改用自定 `UdpMessage` 接口 |
| `WatchLink.ets` | `@ohos.net.socket` 是默认导出，`{ socket }` 具名导入拿不到 | 必须 `import socket from '@ohos.net.socket'` |
| `WatchLink.ets` | `UDPSendOptions.address` 要 `NetAddress` 对象，不是字符串；且没有 `port` 字段 | 编译不过；改成 `connection.NetAddress` |
| `Recorder.ets` | `AudioManager.getDevicesSync` 不存在，它在 `AudioRoutingManager` 上 | 编译不过；改为 `getRoutingManager().getDevicesSync(...)` |
| `Recorder.ets` | `setPreferredInputDeviceForCapturerInfo` 在纯 OpenHarmony SDK 里没有导出 | 用 `InputDeviceSetter` 接口断言调用：HarmonyOS 真机上方法存在即生效，缺失则被 catch 接住并如实回退 |
| `BluetoothWatch.ets` | `ProfileId.PROFILE_HFP_SOURCE` 不存在 | 改 `PROFILE_HANDSFREE_AUDIO_GATEWAY`（AG = 手机侧） |
| `CollabBridge.ets` | `mac.doFinal({data})` 重载解析误挑 `doFinal(callback)` | 走同步链路 `updateSync` + `doFinalSync`，类型完全确定 |
| `LlmClient.ets` | 5 处裸对象字面量（`arkts-no-untyped-obj-literals`） | 抽 `ChatPayload` 接口 |
| `entry/src/main/module.json5` | 引用了 `$media:app_icon` 但 media 目录里只有 `startIcon.png` | 补 `app_icon.png` |

剩下 8 条 WARN 不阻塞：`decodeWithStream` 已废弃、`backgroundTaskManager` 非全机型支持、`setWindowPrivacyMode` 需 `PRIVACY_WINDOW` 权限。

### 版本对齐：鸿蒙版已追到安卓 v1.25

鸿蒙工程原本是从安卓 v1.18 转制的（最初那个源码包里写的就是
`versionCode 100 / versionName 1.18.0`），v1.19 之后的改动一行都没进来。
本次按安卓版逐轮补齐：

| 安卓版本 | 内容 | 鸿蒙落地位置 |
|---------|------|-------------|
| v1.19 | 方言识别（四级降级链）、繁简转换、热词表、事后方言检测与一键重转、方言口语转书面语（含长度畸变校验） | `service/SpeechLang.ets`、`util/ZhNormalizer.ets`、`util/TranscriptCleaner.ets`、`rawfile/t2s.txt`（OpenCC 一对一 3221 组）、设置页「识别语言 / 热词表 / 方言自动转普通话」、详情页降级提示与「按它重转」 |
| v1.20 | 项目档案（规范名 + 别名 + 线索词）、候选预筛 ≤8、强制抄原文证据并回原文比对、置信度 <0.6 标待确认、详情页可改并回写成线索词 | `service/ProjectMind.ets`、`project_profiles` 表、`LlmClient.analyze` 出口校验、详情页项目标签可点改 |
| v1.24 | 主录音码率 64 → 96 kbps、默认识别语言为普通话、新增 Groq whisper-large-v3 预设 | `Recorder.ets` 码率、`Providers.ets` 预设与能力字段（requestLangs / acceptsAuto / supportsPrompt / dialectNote）、`AsrClient` 语言换算 |
| v1.25 ① | 声纹不再要求百分比门槛 | **未实现**，原因见下 |
| v1.25 ② | 通知栏不显示录音状态 | **不需要移植**：鸿蒙长时任务通知由系统模板生成，只有应用名与运行状态，本来就没有计时器、话筒图标与操作按钮 |

### v1.25 的声纹为什么在鸿蒙上没做

安卓的完整链路是：录音 m4a → MediaCodec 解码 PCM → MFCC（25 维，含 delta 与 F0，
16 kHz 重采样、25 ms 帧 / 10 ms 跳）→ 中心化余弦比对 → pairwise logistic 换算出 0..1 匹配度，
两人场景温度 T=0.006、自动采用门槛 0.72。

鸿蒙要走到同一步，缺两样东西：

1. **拿不到 PCM**。录音走 AVRecorder 直接出 AAC，要分析得再接 AVDemuxer + AVCodec 解码；
   或改用 AudioCapturer 采 PCM 自己封 m4a（AVMuxer）。两条路都是几百行，且本机没有真机
   音频可用，写完也无法验证对错。
2. **没有系统级声纹能力**。SDK 里没有 voiceprint / speakerVerify / speakerRecognition
   这类 API（已逐个确认）。

所以这不是移植时漏了，而是一个需要真机与真实录音反复调试的独立工程。
当前设置页如实写着「鸿蒙版暂不做本地声纹比对，所以不会自动标出「我」」——
宁可让人知道没有，也不要放一个百分比让人以为它在算。

### 还没做的

- **unsigned hap 装不上真机**。签名材料（`.p12` / `.cer` / `.p7b`）要华为账号从 AGC 拿，但**拿到之后不需要 DevEco** —— `tools/sign-hap.ps1` 可以离线签，按 `sign-config.sample.json` 填 4 个路径 + 2 个密码即可。
- **`backgroundModes` 在 OpenHarmony 构建里被剥离**，所以那份 hap 的录音没有长时任务保护。真要装真机，用 DevEco + HarmonyOS SDK 出包。
- 手表端 UDP 协议只在本机用 `tools/probe-watch.py` 跑通过，手机真机与手表真机的联调仍未做。

---

首次用 DevEco Studio 5.0 打开时：

1. 等待 Sync 完成（首次会下载 hvigor 与 SDK 依赖，需要网络）
2. `File → Project Structure → Signing Configs` 勾自动签名（否则装不上真机）
3. 若提示 `compatibleSdkVersion` 不匹配，把 `build-profile.json5` 里的 `5.0.0(12)` 改成你本地已装的 SDK 版本
4. 报错集中在 `@kit.*` 的导出名差异时，按 IDE 的快速修复换成实际导出名即可，逻辑层不用动

---

反馈邮箱：**sansimon2001@163.com**
