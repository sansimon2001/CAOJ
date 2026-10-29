# 系统内录可行性验证

PC 端整个方案能不能成立，全看这一件事：Electron 里的 Chromium 在 Windows 上
有没有开放 WASAPI 回环采集。不同 Electron 版本对 `audio: 'loopback'` 的处理不一样，
不能凭文档假设，只能实测。

## 怎么测

```bash
cd pc
node tools/launch.js spike/main.js
# 或
npm run spike
```

程序会：

1. 用 `session.setDisplayMediaRequestHandler` 把 `audio: 'loopback'` 交给请求方
2. 页面调 `getDisplayMedia({ video: true, audio: true })`，拿到流后立刻停掉视频轨
3. **阶段 A**：不播任何声音，记录 1 kHz 附近的频谱能量（基线）
4. **阶段 B**：播一个低音量 1 kHz 正弦波（走系统默认输出），同时从回环录回来
5. 比较两阶段的 1 kHz 能量差，写进 `spike-result.json`

之所以要「自己播一段再录回来」，是因为「拿到了 audio track」根本不能说明问题 ——
静音设备也会给你一条轨道。只有能量显著抬升，才说明录到的确实是系统输出的声音。

## 实测结果（Electron 31 / Windows 11）

```
audioTrack   label="System audio"  state=live  muted=false
基线 1kHz    峰值 0
信号期 1kHz  峰值 255（满量程）  均值 239
抬升         255
```

干净通过。

## 录音后的二次确认

可行性测试只证明「能采到」，不证明「录下来的文件有内容」。所以另有两个脚本：

| 脚本 | 验证什么 |
|---|---|
| `tools/e2e-record.js` | 点开始 → 录 4 秒 → 点结束，记录是否落盘 |
| `tools/e2e-decode.js` | 把 webm 真解码，算 RMS 与峰值 |

解码结果（同一次运行里的两条录音）：

| 录音 | RMS | 峰值 | 判定 |
|---|---|---|---|
| 不播声音 | 0.000005 | 0 | 静音 |
| 播 1 kHz | 0.036292 | 0.2389 | 有内容 |

顺带排掉一个误区：Opus 在 `audioBitsPerSecond: 64000` 下是恒定码率，
静音录音 36,202 字节、有声音录音 36,438 字节 —— **光比文件大小根本判断不了有没有录到东西**，
必须解码。

## 踩过的坑

启动时报 `TypeError: Cannot read properties of undefined (reading 'commandLine')`：

某些宿主环境（比如 WorkBuddy 自己的 shell）会往子进程注入 `ELECTRON_RUN_AS_NODE=1`，
Electron 于是以纯 Node 模式启动，`require('electron')` 拿不到 `app` / `BrowserWindow`。
解决办法是在 spawn 之前把 `ELECTRON_RUN_AS_NODE` 与 `NODE_OPTIONS` 从环境里剔掉，
`tools/launch.js` 做的就是这件事。
