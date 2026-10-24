# 草记 × WorkBuddy 对接说明（v1.17）

草记在手机上，WorkBuddy 在电脑上，两者之间没有直连通道。所以对接不做「魔法」，只做三条**标准、可逆、不依赖私有协议**的路：

| 方式 | 适合谁 | 需不需要配置 | 实时性 |
|---|---|---|---|
| ① 目录同步 | 电脑与手机有同步盘（OneDrive / 坚果云 / 群晖 / Syncthing）或习惯用数据线拷 | 绑一次目录 | 记录整理完成后自动写入 |
| ② 一键复制 | 想马上把内容丢给 WorkBuddy 处理一次 | 不用配置 | 手动 |
| ③ Webhook 推送 | 有自建服务 / 自动化平台，想把记录接进自己的流程 | 填一个 https 地址 | 手动或由服务端触发 |

---

## ① 目录同步（推荐）

### 手机端操作

1. 设置 → **接入录音设备与 WorkBuddy** → 「绑定 WorkBuddy 目录」
2. 选一个目录（例如 `Documents/Caoji`、或同步盘挂载的目录）
3. 打开「记录整理完成后自动写入该目录」

选目录用的是系统文件选择器（SAF），**不需要任何存储权限**，一次授权长期有效。

### 目录结构

```
<你选的目录>/
└── 草记/
    ├── caoji-index.json                 # 索引：一次读完全部记录元信息
    ├── caoji-20261022-00031.md          # 每条记录一份 Markdown
    ├── caoji-20261022-00032.md
    └── …
```

`caoji-index.json`：

```json
{
  "app": "草记",
  "schema": 1,
  "exportedAt": 1761098400000,
  "count": 12,
  "sessions": [
    {
      "id": 31,
      "file": "caoji-20261022-00031.md",
      "title": "虞懋府招商进度沟通",
      "date": "2026年10月22日",
      "time": "15:03",
      "durationMs": 1832000,
      "project": "虞懋府",
      "summary": "……",
      "source": "live",
      "status": "analyzed",
      "url": "caoji://session/31",
      "todos": [
        {"text": "确认 3 号楼租金递增条款", "done": false, "due": "2026-10-28"}
      ]
    }
  ]
}
```

字段口径：

| 字段 | 含义 |
|---|---|
| `source` | `live` 手机实时录音 / `call` 通话录音 / `voice` 长按速记 / `share` 外部分享文本 / `import` 外部录音导入 / `import_text` 第三方转写稿导入 |
| `status` | `recorded` 待转写 / `transcribed` 已转写 / `analyzed` 已出摘要 |
| `durationMs` | 0 表示没有音频（纯文本导入） |
| `todos[].due` | `YYYY-MM-DD`，空串表示没有截止日 |
| `url` | 占位深链，暂未注册 scheme，仅用于对方系统内标识 |

### 电脑端（WorkBuddy）怎么用

- 让 WorkBuddy 读这个目录即可。示例指令：

  > 读取 `~/Documents/Caoji/草记/caoji-index.json`，把最近 7 天、status=analyzed 的记录按项目分组，
  > 输出「进展 / 结论 / 待办（含截止日） / 风险」，待办去重后写到 `本周汇总.md`。

- 增量判断：比较 `exportedAt` 与 `sessions[].id`；文件名里的日期段也方便按天筛。
- 草记**不会删除**目录里已有的 `.md`（用户可能手改过），只会覆盖同名文件或新增。

---

## ② 一键复制

设置 → 「复制最近记录给 WorkBuddy」：把最近 10 条记录拼成一段 Markdown，**开头自带整理指令**（按项目合并、输出四段、待办格式），直接粘到 WorkBuddy 对话框即可。

适合「偶尔用一次、不想配目录」的场景。

---

## ③ Webhook 推送

设置里填地址与 Token（可选），点「推送最近记录到 Webhook」。请求：

```http
POST <你的地址>
Content-Type: application/json; charset=utf-8
Authorization: Bearer <Token>        # 填了才带
User-Agent: Caoji/<手机型号>
```

Body 与 `caoji-index.json` **同结构**（`app` / `schema` / `exportedAt` / `sessions[]`），所以服务端只写一套解析即可。

约束与行为：

- 只允许 `https`（明文 http 会被直接拒绝，避免内容在链路上裸奔）
- 单次最多 20 条记录，30 秒超时；失败会把对方返回的 HTTP 状态与响应前 200 字显示出来
- Token 与 API Key 同一套加密（Android Keystore AES-256-GCM）落库

一个最小可用的接收端（Node）：

```js
app.post('/caoji', (req, res) => {
  const { sessions } = req.body;
  for (const s of sessions) {
    db.upsert(s.id, { title: s.title, summary: s.summary, todos: s.todos, at: s.date + ' ' + s.time });
  }
  res.json({ ok: true, got: sessions.length });
});
```

---

## 和「钉钉闪记 / 飞书妙记」的关系

这两家的录音与转写稿留在自己的云端账号里，Android 不允许第三方应用读取它们的私有数据，也不提供「交给别的 App 转写」的开放接口。所以草记的适配方式是**双向的标准文件交换**：

| 方向 | 做法 |
|---|---|
| 进来 | ① 在钉钉闪记 / 飞书妙记里点「分享 → 草记」；② 先导出音频或文稿，再用「导入录音或转写稿」选进来 |
| 出去 | 详情页「转发」→ 选钉钉 / 飞书，把整理好的 Markdown 发给自己或群 |

支持的格式：

- 音频：`m4a / mp3 / wav / aac / amr / ogg / opus / flac / 3gp / mp4 / wma / mka / webm`
- 文稿：`txt / md / srt / vtt / json / docx`（docx 会解压 `word/document.xml` 后抽段落，无需联网）

导入文稿时会自动：

1. 去掉字幕序号与时间轴，按说话人**连读合并**（同一个人连续的 cue 合成一段）
2. 识别 `说话人1：` / `张三：` / `[00:12] 李四 ` 这几种前缀
3. 直接标记为「已转写」，跳过 ASR 只走大模型摘要（省一次转写费用）
4. 超过 20 万字截断并注明

---

## 隐私边界

- 目录同步、复制、Webhook 都是**用户显式触发或显式绑定**的，默认不往外发
- Webhook 只允许 https，Token 加密存储
- 目录写入走 SAF，授权范围就是用户选中的那一个目录，草记不会、也无法扫描其他位置
