package com.example.mindball.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AlertDialog
import com.example.mindball.R
import com.example.mindball.audio.Voiceprint
import com.example.mindball.ai.AsrClient
import com.example.mindball.ai.AsrProvider
import com.example.mindball.ai.ConnectivityTest
import com.example.mindball.ai.Providers
import com.example.mindball.ai.SpeechLangs
import com.example.mindball.audio.BluetoothSco
import com.example.mindball.audio.CallRecordingScanner
import com.example.mindball.audio.RecorderService
import com.example.mindball.data.Repo
import com.example.mindball.databinding.FragmentSettingsBinding
import com.example.mindball.reminder.TodoReminder
import com.example.mindball.util.CalendarBridge
import com.example.mindball.util.CollabBridge
import com.example.mindball.util.Contact
import com.example.mindball.util.Exporter
import com.example.mindball.util.Importer
import com.example.mindball.util.Privacy
import com.example.mindball.util.SecureStore
import com.example.mindball.util.TimeUtil
import com.example.mindball.util.WorkBuddyBridge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页
 * - 大模型 / 语音转写：选服务商自动带出地址与模型，只需填 Key
 *   （内置智谱 GLM 免费模型、通义千问免费额度、DeepSeek 低价、硅基流动、Kimi、OpenAI、自定义）
 * - 一键测试连接，避免 Key/地址填错后录了一堆才发现
 * - 说话人区分、待办提醒、记录行为、通话录音导入
 */
class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    private val llmProviders = Providers.LLM
    private val asrProviders = Providers.ASR

    /** 等系统文件选择器选完位置后写盘的文稿 */
    private var pendingExport: String? = null

    private val createDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val text = pendingExport
        pendingExport = null
        val ctx = context ?: return@registerForActivityResult
        if (uri == null || text == null) {
            if (_b != null) b.tvDataResult.text = ""
            return@registerForActivityResult
        }
        if (Exporter.saveText(ctx, uri, text)) {
            if (_b != null) {
                b.tvDataResult.text = "已导出：" + (uri.lastPathSegment?.substringAfterLast('/') ?: "文件")
            }
        } else if (_b != null) {
            b.tvDataResult.text = "导出失败，换个位置再试"
        }
    }

    private val calendarPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ctx = context ?: return@registerForActivityResult
        val granted = result[Manifest.permission.WRITE_CALENDAR] == true ||
            CalendarBridge.hasWritePermission(ctx)
        if (granted) {
            syncTodosToCalendar()
        } else if (_b != null) {
            b.tvDataResult.text = "未授予日历权限，无法写入系统日历"
        }
    }

    /** 扫描通话录音需要「音频/存储」权限（旧 API 的 requestPermissions 已废弃，统一走 launcher） */
    private val mediaPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!isAdded) return@registerForActivityResult
        if (granted) {
            scanCallRecordings()
        } else {
            Toast.makeText(requireContext(), "未授予音频权限，无法扫描通话录音", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 导入外部录音 / 转写稿（多选）
     *
     * 用 SAF 而不是申请存储权限：
     * - 不需要权限弹窗，也不会被当成「偷偷扫用户文件」；
     * - 用户选什么就读什么，想连钉钉/飞书导出的文件都行（它们在「下载」里）。
     */
    private val importFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val ctx = context?.applicationContext ?: return@registerForActivityResult
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        if (_b != null) b.tvWbState.text = "正在导入 ${uris.size} 个文件（音频会在后台转写）…"
        Thread {
            val summary = runCatching { Importer.importUris(ctx, uris) }.getOrNull()
            uiPost { if (_b != null) b.tvWbState.text = summary?.message() ?: "导入失败，请重试" }
        }.start()
    }

    /** 绑定 WorkBuddy 目录（SAF 树授权，持久化，不需要存储权限） */
    private val wbDirPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val ctx = context ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        if (WorkBuddyBridge.bindDir(ctx, uri)) {
            refreshWbState()
            runCatching { b.swWbAuto.isChecked = WorkBuddyBridge.autoEnabled(ctx) }
            Toast.makeText(ctx, "已绑定，之后的记录会自动写入该目录", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "绑定失败，请换一个目录再试", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()

        // ==================== 大模型回显 ====================
        val savedLlmBase = Repo.getStr(ctx, Repo.K_LLM_BASE)
        b.spLlmProvider.adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_dropdown_item, llmProviders.map { it.name }
        )
        // 没配过时直接落在默认服务商（硅基流动），而不是「自定义」
        val llmIdx = if (savedLlmBase.isBlank()) {
            llmProviders.indexOf(Providers.defaultLlm()).coerceAtLeast(0)
        } else {
            llmProviders.indexOfFirst { it.id == Providers.guessLlmId(savedLlmBase) }.coerceAtLeast(0)
        }
        b.spLlmProvider.setSelection(llmIdx)
        b.etLlmBase.setText(savedLlmBase)
        b.etLlmKey.setText(Repo.getStr(ctx, Repo.K_LLM_KEY))
        b.etLlmModel.setText(Repo.getStr(ctx, Repo.K_LLM_MODEL, llmProviders[llmIdx].models.firstOrNull() ?: ""))
        applyLlmProvider(llmIdx, overwriteFields = savedLlmBase.isBlank())

        // ==================== 转写回显 ====================
        val savedAsrUrl = Repo.getStr(ctx, Repo.K_ASR_URL)
        b.spAsrProvider.adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_dropdown_item, asrProviders.map { it.name }
        )
        // 没配过时直接落在默认转写服务（硅基流动 SenseVoice）
        val asrIdx = if (savedAsrUrl.isBlank()) {
            asrProviders.indexOf(Providers.defaultAsr()).coerceAtLeast(0)
        } else {
            asrProviders.indexOfFirst { it.id == Providers.guessAsrId(savedAsrUrl) }.coerceAtLeast(0)
        }
        b.spAsrProvider.setSelection(asrIdx)
        b.etAsrUrl.setText(savedAsrUrl)
        b.etAsrKey.setText(Repo.getStr(ctx, Repo.K_ASR_KEY))
        b.etAsrModel.setText(Repo.getStr(ctx, Repo.K_ASR_MODEL, asrProviders[asrIdx].model))

        // ==================== 说话人区分 ====================
        // 先把用户保存的值填上，再由 applyAsrProvider → applyAsrCapability
        // 按所选服务的真实能力校正（纯文本服务会把开关强制关掉并说明原因）
        b.swDiarize.isChecked = Repo.getBool(ctx, Repo.K_ASR_DIARIZE, true)
        val speakerOptions = (1..4).map { "说话人$it" }
        b.spMySpeaker.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, speakerOptions)
        b.spMySpeaker.setSelection(
            speakerOptions.indexOf(Repo.getStr(ctx, Repo.K_MY_SPEAKER, "说话人1")).coerceAtLeast(0)
        )

        applyAsrProvider(asrIdx, overwriteFields = savedAsrUrl.isBlank())

        // ==================== 识别语言 / 方言 ====================
        // 语言是「用户想识别什么」，能不能真的识别出方言取决于服务能力，
        // 所以这里先选语言，再按所选服务给出「是否支持 / 会降级成什么」的实话。
        b.spAsrLang.adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_dropdown_item, SpeechLangs.ALL.map { it.label }
        )
        b.spAsrLang.setSelection(
            SpeechLangs.indexOf(Repo.getStr(ctx, Repo.K_ASR_LANG, SpeechLangs.ID_ZH))
        )
        b.etAsrHotwords.setText(Repo.getStr(ctx, Repo.K_ASR_PROMPT))
        b.swDialectStd.isChecked = Repo.getBool(ctx, Repo.K_DIALECT_STD, true)

        b.spAsrLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Repo.setStr(ctx, Repo.K_ASR_LANG, SpeechLangs.ALL[position].id)
                refreshLangTip()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        b.swDialectStd.setOnCheckedChangeListener { _, c ->
            Repo.setBool(ctx, Repo.K_DIALECT_STD, c)
        }
        refreshLangTip()

        // ==================== 本人声纹 ====================
        b.swVoiceAuto.isChecked = Voiceprint.autoEnabled(ctx)
        refreshVoiceState()

        b.btnEnrollVoice.setOnClickListener {
            startActivity(Intent(ctx, EnrollVoiceActivity::class.java))
        }

        b.btnClearVoice.setOnClickListener {
            if (!Voiceprint.hasTemplate(ctx)) {
                Toast.makeText(ctx, "还没有录入声纹", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(ctx)
                .setTitle("清除声纹")
                .setMessage("清除后 App 不再自动识别「我」，会回到手动指定「我是说话人几」。")
                .setPositiveButton("清除") { _, _ ->
                    Voiceprint.clear(ctx)
                    refreshVoiceState()
                    Toast.makeText(ctx, "声纹已清除", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        b.swVoiceAuto.setOnCheckedChangeListener { _, c -> Repo.setBool(ctx, Repo.K_VOICEPRINT_AUTO, c) }

        // ==================== 待办提醒 ====================
        b.swRemind.isChecked = Repo.getBool(ctx, Repo.K_REMIND_ENABLE, true)
        b.spRemindHour.adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_dropdown_item, (0..23).map { "%02d:00".format(it) }
        )
        b.spRemindHour.setSelection(
            (Repo.getStr(ctx, Repo.K_REMIND_HOUR, "9").toIntOrNull() ?: 9).coerceIn(0, 23)
        )

        // ==================== 记录行为 ====================
        b.swAutoRecord.isChecked = Repo.getBool(ctx, Repo.K_AUTO_RECORD, true)
        b.swKeepAudio.isChecked = Repo.getBool(ctx, Repo.K_KEEP_AUDIO, false)

        // ==================== 隐私与安全 ====================
        b.swSecureWindow.isChecked = Repo.getBool(ctx, Repo.K_SECURE_WINDOW, true)
        b.swSecureWindow.setOnCheckedChangeListener { _, checked ->
            Repo.setBool(ctx, Repo.K_SECURE_WINDOW, checked)
            runCatching { Privacy.applySecureWindow(requireActivity()) }
            Toast.makeText(
                ctx,
                if (checked) "已开启截屏保护" else "已关闭截屏保护：记录内容可能出现在截图与最近任务里",
                Toast.LENGTH_SHORT
            ).show()
        }
        b.tvPrivacyState.text = if (SecureStore.isAvailable()) {
            "加密：已启用（API Key 与声纹模板用系统密钥库加密存储，密钥不随备份导出）"
        } else {
            "加密：⚠️ 本机密钥库不可用，敏感配置只能以明文保存——建议不要在此设备长期保存 API Key"
        }
        b.btnWipeData.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("清除本机全部数据")
                .setMessage(
                    "将删除：所有记录与摘要、待办、项目洞见、录音文件，以及接口配置（含 API Key 与声纹）。\n\n" +
                        "此操作不可撤销，建议先用「导出全部记录」备份。"
                )
                .setPositiveButton("清除") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { Repo.wipeAll(ctx) }
                        b.tvPrivacyState.text = "已清除本机全部数据（配置需重新填写）"
                        Toast.makeText(ctx, "已清除", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // ==================== 整理导出与系统打通 ====================
        b.btnExportToday.setOnClickListener { exportRecords(todayOnly = true) }
        b.btnExportAll.setOnClickListener { exportRecords(todayOnly = false) }
        b.btnExportTodos.setOnClickListener { exportTodos() }
        b.btnSyncCalendar.setOnClickListener {
            val c = context ?: return@setOnClickListener
            if (CalendarBridge.hasWritePermission(c)) {
                syncTodosToCalendar()
            } else {
                b.tvDataResult.text = "需要日历读写权限才能批量写入"
                calendarPerm.launch(CalendarBridge.permissions())
            }
        }

        // ==================== 接入录音设备 / WorkBuddy ====================
        setupInterop(ctx)

        // ==================== 飞书 / 钉钉群机器人（v1.18） ====================
        setupCollab(ctx)

        // ==================== 关于与法务 ====================
        setupAbout(ctx)

        // ==================== 分组折叠 + 状态概览 ====================
        setupGroups()
        refreshOverview()

        // ==================== 蓝牙耳机麦克风 ====================
        b.swBtMic.isChecked = Repo.getBool(ctx, Repo.K_BT_MIC, false)
        refreshBtMicHint()
        b.swBtMic.setOnCheckedChangeListener { _, c ->
            Repo.setBool(ctx, Repo.K_BT_MIC, c)
            refreshBtMicHint()
            // 正在录音时立即生效：服务会把当前段收尾保存，然后用新音源接着录
            if (RecorderService.isRecording) {
                runCatching {
                    ctx.startService(
                        Intent(ctx, RecorderService::class.java)
                            .setAction(RecorderService.ACTION_SWITCH_SOURCE)
                            .putExtra(RecorderService.EXTRA_SOURCE, RecorderService.sourceOf(c))
                    )
                }
                Toast.makeText(ctx, "正在录音，已按新音源接着录（前一段已单独保存）", Toast.LENGTH_LONG).show()
            }
        }

        // ==================== 服务商联动 ====================
        b.spLlmProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) =
                applyLlmProvider(position, overwriteFields = true)

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        b.spAsrProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) =
                applyAsrProvider(position, overwriteFields = true)

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // ==================== 测试连接 ====================
        b.btnTestLlm.setOnClickListener {
            val base = b.etLlmBase.text.toString().trim()
            val key = b.etLlmKey.text.toString().trim()
            val model = b.etLlmModel.text.toString().trim()
            if (base.isBlank() || key.isBlank() || model.isBlank()) {
                b.tvLlmTestResult.text = "请先填写接口地址、API Key 和模型名"
                b.tvLlmTestResult.setTextColor(0xFFE60012.toInt())
                return@setOnClickListener
            }
            b.tvLlmTestResult.text = "正在测试连接…"
            b.tvLlmTestResult.setTextColor(0xFF6B6B6B.toInt())
            viewLifecycleOwner.lifecycleScope.launch {
                val (ok, msg) = withContext(Dispatchers.IO) { ConnectivityTest.testLlm(base, key, model) }
                b.tvLlmTestResult.text = msg
                b.tvLlmTestResult.setTextColor(if (ok) 0xFF4A4A4A.toInt() else 0xFFE60012.toInt())
            }
        }

        b.btnOpenKeyPage.setOnClickListener {
            val provider = llmProviders.getOrNull(b.spLlmProvider.selectedItemPosition) ?: return@setOnClickListener
            val url = keyPageUrl(provider.id)
            if (url.isBlank()) {
                Toast.makeText(ctx, provider.keyHint, Toast.LENGTH_LONG).show()
            } else {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { Toast.makeText(ctx, provider.keyHint, Toast.LENGTH_LONG).show() }
            }
        }

        // ==================== 保存 ====================
        b.btnSaveConfig.setOnClickListener {
            Repo.setStr(ctx, Repo.K_LLM_BASE, b.etLlmBase.text.toString().trim())
            Repo.setStr(ctx, Repo.K_LLM_KEY, b.etLlmKey.text.toString().trim())
            Repo.setStr(ctx, Repo.K_LLM_MODEL, b.etLlmModel.text.toString().trim())
            Repo.setStr(ctx, Repo.K_LLM_PROVIDER, llmProviders[b.spLlmProvider.selectedItemPosition].id)

            Repo.setStr(ctx, Repo.K_ASR_URL, b.etAsrUrl.text.toString().trim())
            Repo.setStr(ctx, Repo.K_ASR_KEY, b.etAsrKey.text.toString().trim())
            Repo.setStr(ctx, Repo.K_ASR_MODEL, b.etAsrModel.text.toString().trim())
            Repo.setStr(ctx, Repo.K_ASR_PROVIDER, asrProviders[b.spAsrProvider.selectedItemPosition].id)
            Repo.setStr(ctx, Repo.K_ASR_LANG, SpeechLangs.ALL[b.spAsrLang.selectedItemPosition].id)
            Repo.setStr(ctx, Repo.K_ASR_PROMPT, b.etAsrHotwords.text.toString().trim())
            Repo.setBool(ctx, Repo.K_DIALECT_STD, b.swDialectStd.isChecked)

            Repo.setBool(ctx, Repo.K_ASR_DIARIZE, b.swDiarize.isChecked)
            Repo.setStr(ctx, Repo.K_MY_SPEAKER, b.spMySpeaker.selectedItem?.toString() ?: "说话人1")

            Repo.setBool(ctx, Repo.K_REMIND_ENABLE, b.swRemind.isChecked)
            Repo.setStr(ctx, Repo.K_REMIND_HOUR, b.spRemindHour.selectedItemPosition.toString())
            if (b.swRemind.isChecked) {
                TodoReminder.ensureScheduled(ctx)
                TodoReminder.checkAndNotify(ctx)
            } else {
                TodoReminder.cancel(ctx)
            }

            val asrOk = ConnectivityTest.checkAsrConfig(
                b.etAsrUrl.text.toString().trim(),
                b.etAsrKey.text.toString().trim(),
                b.etAsrModel.text.toString().trim(),
            ).first

            // 热词填了但这家的接口没有 prompt 参数 → 说清楚，别让用户以为在起作用
            val hotBlank = b.etAsrHotwords.text.toString().isBlank()
            val provider = asrProviders.getOrNull(b.spAsrProvider.selectedItemPosition)
            val hotIgnored = !hotBlank && provider != null && !provider.supportsPrompt

            Toast.makeText(
                ctx,
                when {
                    !asrOk -> "已保存，但转写配置不完整，录音会停留在待转写状态"
                    hotIgnored -> "配置已保存；注意：${provider!!.name} 不支持热词，热词表暂不生效"
                    else -> "配置已保存"
                },
                Toast.LENGTH_LONG
            ).show()

            // 概览卡跟着一起变，用户点完保存立刻能看到「已就绪」
            refreshOverview()
        }

        // ==================== 开关即时生效 ====================
        b.swAutoRecord.setOnCheckedChangeListener { _, c -> Repo.setBool(ctx, Repo.K_AUTO_RECORD, c) }
        b.swKeepAudio.setOnCheckedChangeListener { _, c -> Repo.setBool(ctx, Repo.K_KEEP_AUDIO, c) }
        b.swDiarize.setOnCheckedChangeListener { _, c -> Repo.setBool(ctx, Repo.K_ASR_DIARIZE, c) }
        b.swRemind.setOnCheckedChangeListener { _, c ->
            Repo.setBool(ctx, Repo.K_REMIND_ENABLE, c)
            if (c) TodoReminder.ensureScheduled(ctx) else TodoReminder.cancel(ctx)
        }

        b.btnScanCalls.setOnClickListener { scanCallRecordings() }
    }

    /**
     * 接入录音设备（钉钉闪记 / 飞书妙记 / 录音笔 …）与 WorkBuddy
     *
     * 这一块解决的是「草记不是孤岛」：别的产品里已经录好的东西要能进来，
     * 草记整理好的东西要能出去（尤其是到 WorkBuddy 那边接着用）。
     * 三个出口各管一段，用户按自己有的条件挑一个就行。
     */
    private fun setupInterop(ctx: Context) {
        b.btnImportFiles.setOnClickListener {
            // 音频与文稿一起放开：具体是录音还是转写稿，由 Importer 按扩展名/ MIME 判断
            runCatching {
                importFiles.launch(
                    arrayOf("audio/*", "text/*", "application/*", "application/octet-stream")
                )
            }.onFailure {
                Toast.makeText(ctx, "系统文件选择器不可用", Toast.LENGTH_SHORT).show()
            }
        }

        b.btnWbBind.setOnClickListener {
            runCatching { wbDirPicker.launch(null) }
                .onFailure { Toast.makeText(ctx, "无法打开目录选择器", Toast.LENGTH_SHORT).show() }
        }

        b.swWbAuto.isChecked = WorkBuddyBridge.autoEnabled(ctx)
        b.swWbAuto.setOnCheckedChangeListener { _, c ->
            WorkBuddyBridge.setAuto(ctx, c)
            refreshWbState()
        }

        b.btnWbSync.setOnClickListener {
            if (WorkBuddyBridge.boundDir(ctx) == null) {
                Toast.makeText(ctx, "先绑定一个目录（可选手机会话里的任意文件夹）", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            b.tvWbState.text = "正在同步…"
            Thread {
                val (_, msg) = runCatching { WorkBuddyBridge.syncAll(ctx) }
                    .getOrDefault(0 to "同步失败")
                uiPost {
                    if (_b != null) {
                        b.tvWbState.text = msg
                        refreshWbState()
                    }
                }
            }.start()
        }

        b.btnWbCopy.setOnClickListener {
            val md = WorkBuddyBridge.promptMarkdown(ctx)
            if (md.isBlank()) {
                Toast.makeText(ctx, "还没有可复制的记录", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (Exporter.copy(ctx, md)) {
                b.tvWbState.text = "已复制最近记录（含整理指令）。打开 WorkBuddy 对话，直接粘贴即可。"
                Toast.makeText(ctx, "已复制，去 WorkBuddy 里粘贴", Toast.LENGTH_SHORT).show()
            } else {
                b.tvWbState.text = "复制失败，请重试"
            }
        }

        b.btnWbPush.setOnClickListener {
            val url = b.etWbWebhook.text.toString().trim()
            val token = b.etWbToken.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(ctx, "请先填 Webhook 地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Repo.setStr(ctx, Repo.K_WB_WEBHOOK, url)
            Repo.setStr(ctx, Repo.K_WB_TOKEN, token)
            b.tvWbState.text = "正在推送…"
            Thread {
                val (_, msg) = WorkBuddyBridge.pushWebhook(ctx, url, token)
                uiPost { if (_b != null) b.tvWbState.text = msg }
            }.start()
        }

        refreshWbState()
    }

    /** WorkBuddy 区块的状态回显 */
    private fun refreshWbState() {
        val ctx = context ?: return
        if (_b == null) return
        b.swWbAuto.isChecked = WorkBuddyBridge.autoEnabled(ctx)
        b.tvWbState.text = if (WorkBuddyBridge.boundDir(ctx) == null) {
            "WorkBuddy：未绑定目录。绑定后每条记录会写成 " +
                "「草记/caoji-日期-编号.md」，电脑上的 WorkBuddy 直接读这个文件夹即可；" +
                "不想绑目录也可以用「复制最近记录」粘贴过去。"
        } else {
            "WorkBuddy：已绑定「${WorkBuddyBridge.bindLabel(ctx)}」，文件写在 " +
                "「草记」子目录（caoji-日期-编号.md + caoji-index.json）。"
        }
    }

    /**
     * 分组折叠
     *
     * 设置项从原来的「一路往下堆」改成卡片分组后，必须能收起：
     * 否则第一次进设置要滑五屏才看到底，比原来更难找东西。
     * 箭头方向跟着状态走，用户不用猜「现在点一下是展开还是收起」。
     */
    private fun setupGroups() {
        val ctx = context
        // 「AI 服务」默认是否展开，取决于有没有配好：
        //   首次使用（还没填 Key）→ 直接展开，省得用户找不到该填哪
        //   已经配好 → 收起，页面立刻短一半（需要改时点一下标题就行）
        if (ctx != null) {
            val needKey = Repo.getStr(ctx, Repo.K_LLM_KEY).isBlank() ||
                Repo.getStr(ctx, Repo.K_ASR_KEY).isBlank()
            b.grpAi.visibility = if (needKey) View.VISIBLE else View.GONE
        }

        val rows = listOf(
            Triple(b.hdrAi, b.grpAi, b.arwAi),
            Triple(b.hdrRecord, b.grpRecord, b.arwRecord),
            Triple(b.hdrBridge, b.grpBridge, b.arwBridge),
            Triple(b.hdrPrivacy, b.grpPrivacy, b.arwPrivacy),
            Triple(b.hdrAbout, b.grpAbout, b.arwAbout),
        )
        rows.forEach { (hdr, grp, arrow) ->
            syncArrow(grp, arrow)
            hdr.setOnClickListener {
                grp.visibility = if (grp.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                syncArrow(grp, arrow)
            }
        }
    }

    /** 展开时箭头朝上（点击收起），收起时朝下 */
    private fun syncArrow(grp: View, arrow: View) {
        val target = if (grp.visibility == View.VISIBLE) 180f else 0f
        arrow.animate().rotation(target).setDuration(160).start()
    }

    /**
     * 状态概览
     *
     * 设置页最容易出现的困惑是「我到底配好了没有」。
     * 这里直接按已保存的配置给结论，而不是让用户逐项去核对。
     */
    private fun refreshOverview() {
        val ctx = context ?: return
        if (_b == null) return

        val llmBase = Repo.getStr(ctx, Repo.K_LLM_BASE)
        val llmKey = Repo.getStr(ctx, Repo.K_LLM_KEY)
        val llmModel = Repo.getStr(ctx, Repo.K_LLM_MODEL)
        val llmName = llmProviders.firstOrNull { it.id == Providers.guessLlmId(llmBase) }?.name
            ?: "自定义"
        b.tvOverviewLlm.text = if (llmKey.isBlank()) {
            "大模型：未填 Key（摘要与待办会生成不了）"
        } else {
            "大模型：$llmName · ${llmModel.ifBlank { "默认模型" }}"
        }

        val asrUrl = Repo.getStr(ctx, Repo.K_ASR_URL)
        val asrKey = Repo.getStr(ctx, Repo.K_ASR_KEY)
        val asrName = asrProviders.firstOrNull { it.id == Providers.guessAsrId(asrUrl) }?.name
            ?: "自定义"
        b.tvOverviewAsr.text = if (asrKey.isBlank()) {
            "语音转写：未填 Key（录音只能停在「待转写」）"
        } else {
            "语音转写：$asrName" + if (AsrClient.diarizePossible(ctx)) " · 支持说话人分离" else " · 纯文本"
        }

        val bridge = buildList {
            val collab = CollabBridge.statusText(ctx)
            if (collab != "未接入") add(collab)
            if (WorkBuddyBridge.boundDir(ctx) != null) add("WorkBuddy 目录")
        }
        b.tvOverviewBridge.text =
            if (bridge.isEmpty()) "对外打通：未接入（可选，不影响录音与整理）"
            else "对外打通：${bridge.joinToString(" / ")}"

        b.tvOverviewHint.text = when {
            llmKey.isBlank() && asrKey.isBlank() -> "先把上面「AI 服务」里的两个 Key 填上，其余功能都能用。"
            asrKey.isBlank() -> "还差转写 Key：没有它，录音后拿不到文字。"
            llmKey.isBlank() -> "还差大模型 Key：没有它，出不了摘要与待办。"
            else -> "已就绪：录音结束后会自动转写、生成摘要与待办。"
        }
    }

    // ==================== 飞书 / 钉钉 ====================

    /**
     * 群机器人接入
     *
     * 「测试」按钮先保存再发消息：用户改完地址最想做的事就是验证，
     * 如果还要先点一次「保存配置」才能测，很容易测的是旧地址。
     */
    private fun setupCollab(ctx: Context) {
        b.etFsHook.setText(CollabBridge.hook(ctx, CollabBridge.Platform.FEISHU))
        b.etFsSecret.setText(CollabBridge.secret(ctx, CollabBridge.Platform.FEISHU))
        b.etDtHook.setText(CollabBridge.hook(ctx, CollabBridge.Platform.DINGTALK))
        b.etDtSecret.setText(CollabBridge.secret(ctx, CollabBridge.Platform.DINGTALK))

        b.btnFsTest.setOnClickListener { testCollab(CollabBridge.Platform.FEISHU) }
        b.btnDtTest.setOnClickListener { testCollab(CollabBridge.Platform.DINGTALK) }
        b.btnFsPush.setOnClickListener { pushCollab(CollabBridge.Platform.FEISHU) }
        b.btnDtPush.setOnClickListener { pushCollab(CollabBridge.Platform.DINGTALK) }

        refreshCollabState()
    }

    private fun collabInput(p: CollabBridge.Platform): Pair<String, String> = when (p) {
        CollabBridge.Platform.FEISHU ->
            b.etFsHook.text.toString().trim() to b.etFsSecret.text.toString().trim()

        CollabBridge.Platform.DINGTALK ->
            b.etDtHook.text.toString().trim() to b.etDtSecret.text.toString().trim()
    }

    private fun testCollab(p: CollabBridge.Platform) {
        val ctx = context ?: return
        val (hook, secret) = collabInput(p)
        if (!hook.startsWith("https://")) {
            b.tvCollabState.text = "请先填${p.label}机器人的 Webhook 地址（以 https:// 开头）"
            return
        }
        CollabBridge.save(ctx, p, hook, secret)
        b.tvCollabState.text = "正在向${p.label}发送测试消息…"
        viewLifecycleOwner.lifecycleScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) { CollabBridge.sendTest(ctx.applicationContext, p) }
            if (_b == null) return@launch
            b.tvCollabState.text =
                if (ok) "$msg。去群里确认一下有没有收到那条测试消息。" else msg
            refreshCollabState(keepMessage = true)
            refreshOverview()
        }
    }

    private fun pushCollab(p: CollabBridge.Platform) {
        val ctx = context ?: return
        val (hook, secret) = collabInput(p)
        if (!hook.startsWith("https://")) {
            b.tvCollabState.text = "请先填${p.label}机器人的 Webhook 地址"
            return
        }
        CollabBridge.save(ctx, p, hook, secret)
        b.tvCollabState.text = "正在推送最近记录到${p.label}…"
        viewLifecycleOwner.lifecycleScope.launch {
            val (_, msg) = withContext(Dispatchers.IO) {
                CollabBridge.pushRecent(ctx.applicationContext, p, 3)
            }
            if (_b == null) return@launch
            b.tvCollabState.text = msg
            refreshCollabState(keepMessage = true)
            refreshOverview()
        }
    }

    /** 接入状态回显；keepMessage=true 时保留刚返回的结果文案 */
    private fun refreshCollabState(keepMessage: Boolean = false) {
        val ctx = context ?: return
        if (_b == null) return
        val fs = CollabBridge.isReady(ctx, CollabBridge.Platform.FEISHU)
        val dt = CollabBridge.isReady(ctx, CollabBridge.Platform.DINGTALK)
        if (keepMessage) return
        b.tvCollabState.text = when {
            fs && dt -> "已接入：飞书 + 钉钉。点「推送最近记录」即可把摘要与待办同步到两个群。"
            fs -> "已接入：飞书。钉钉还没填地址。"
            dt -> "已接入：钉钉。飞书还没填地址。"
            else -> getString(R.string.collab_state_default)
        }
    }

    // ==================== 关于 / 法务 ====================

    /**
     * 关于与法务
     *
     * 免责声明入口必须显眼、可离线阅读：录音类工具最主要的法律风险不是代码问题，
     * 而是使用者以为「录了就能当证据」。
     */
    private fun setupAbout(ctx: Context) {
        b.tvAppVersion.text = "草记 v${Contact.versionName(ctx)} · 本地优先 · 无广告与埋点"
        b.tvFeedbackEmail.text = Contact.EMAIL

        b.tvFeedbackEmail.setOnClickListener { Contact.sendMail(ctx, "草记使用反馈") }
        b.tvFeedbackEmail.setOnLongClickListener { Contact.copyEmail(ctx); true }
        b.btnMailFeedback.setOnClickListener { Contact.sendMail(ctx, "草记使用反馈") }
        b.btnCopyEmail.setOnClickListener { Contact.copyEmail(ctx) }
        b.btnOpenLegal.setOnClickListener {
            runCatching { startActivity(Intent(ctx, LegalActivity::class.java)) }
                .onFailure { Toast.makeText(ctx, "打不开声明页，请重试", Toast.LENGTH_SHORT).show() }
        }
    }

    /** 视图已销毁就别再碰 UI */
    private fun uiPost(block: () -> Unit) {
        val v = view ?: return
        if (!isAdded) return
        v.post { if (isAdded && _b != null) block() }
    }

    /** 蓝牙麦克风开关的说明 + 当前生效状态 */
    private fun refreshBtMicHint() {
        val ctx = context ?: return
        val on = b.swBtMic.isChecked
        val available = BluetoothSco.isAvailable(ctx)
        b.tvBtMicHint.text = buildString {
            append(getString(R.string.bt_mic_hint))
            append("\n\n")
            append(if (on) "当前：耳机麦克风（SCO 通路）" else "当前：手机麦克风")
            if (!available) append(" · 未检测到已配对的蓝牙设备")
        }
    }
    /** 刷新声纹状态展示 */
    private fun refreshVoiceState() {
        val ctx = context ?: return
        val has = Voiceprint.hasTemplate(ctx)
        b.tvVoiceState.text = Voiceprint.describe(ctx)
        var hint = Voiceprint.thresholdHint() + "。声纹特征只存在本机数据库，不上传服务器。"
        if (!AsrClient.diarizePossible(ctx)) {
            hint += "\n当前转写服务不返回说话人分段，声纹暂时无法自动识别「我」（录好的声纹会保留，换服务后即可生效）。"
        }
        b.tvVoiceHint.text = hint
        b.btnEnrollVoice.text = ctx.getString(
            if (has) R.string.voiceprint_enroll_again else R.string.voiceprint_enroll
        )
        b.btnClearVoice.isEnabled = has
    }

    override fun onResume() {
        super.onResume()
        if (_b != null && !isHidden) {
            refreshVoiceState()
            syncMicSourceFromPref()
            refreshOverview()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 页面复用后不再走 onResume，切回设置页时再刷一次声纹状态
        if (!hidden && _b != null) {
            refreshVoiceState()
            syncMicSourceFromPref()
            refreshOverview()
        }
    }

    /** 「今日」页手动切过音源后，设置页的开关要跟着回显（两处写的是同一个偏好） */
    private fun syncMicSourceFromPref() {
        val ctx = context ?: return
        val on = Repo.getBool(ctx, Repo.K_BT_MIC, false)
        if (b.swBtMic.isChecked != on) b.swBtMic.isChecked = on
        refreshBtMicHint()
    }
    /** 应用大模型服务商预设：填地址、模型候选、成本提示 */
    private fun applyLlmProvider(index: Int, overwriteFields: Boolean) {
        val provider = llmProviders.getOrNull(index) ?: return
        val ctx = context ?: return

        b.tvLlmCostNote.text = if (provider.costNote.isBlank()) {
            "选择服务商后自动填入地址与模型，只需填 Key"
        } else {
            "${provider.costNote}｜${provider.keyHint}"
        }

        if (overwriteFields) {
            if (provider.base.isNotBlank()) {
                b.etLlmBase.setText(provider.base)
                b.etLlmModel.setText(provider.models.firstOrNull() ?: "")
            } else {
                b.etLlmBase.setText(Repo.getStr(ctx, Repo.K_LLM_BASE))
                b.etLlmModel.setText(Repo.getStr(ctx, Repo.K_LLM_MODEL))
            }
        }

        // 模型名：可下拉选，也可手输
        b.etLlmModel.setAdapter(
            ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, provider.models)
        )
    }

    /** 应用转写服务商预设 */
    private fun applyAsrProvider(index: Int, overwriteFields: Boolean) {
        val provider = asrProviders.getOrNull(index) ?: return
        val ctx = context ?: return

        b.tvAsrCostNote.text = provider.costNote.ifBlank { "转写按分钟计费" }

        if (overwriteFields) {
            if (provider.url.isNotBlank()) {
                b.etAsrUrl.setText(provider.url)
                b.etAsrModel.setText(provider.model)
            } else {
                b.etAsrUrl.setText(Repo.getStr(ctx, Repo.K_ASR_URL))
                b.etAsrModel.setText(Repo.getStr(ctx, Repo.K_ASR_MODEL, "whisper-1"))
            }
        }

        applyAsrCapability(provider)
    }

    /**
     * 按所选转写服务的真实能力校正「说话人区分」区块
     *
     * 只返回纯文本的服务（如硅基流动 SenseVoice）即便发再多 diarize 参数
     * 也拿不到说话人分段，开关留着只会让用户以为在起作用。
     */
    private fun applyAsrCapability(provider: AsrProvider) {
        val ctx = context ?: return
        val ok = provider.supportsDiarize

        b.swDiarize.isEnabled = ok
        b.spMySpeaker.isEnabled = ok
        if (!ok) b.swDiarize.isChecked = false

        b.tvSpeakerTip.text = if (ok) {
            ctx.getString(R.string.speaker_tip_supported)
        } else {
            ctx.getString(R.string.speaker_tip_plain, provider.name)
        }

        // 换了服务商，方言支持能力也跟着变（SenseVoice 支持粤语、Whisper 只吃 zh）
        refreshLangTip()
    }

    /**
     * 刷新「识别语言」的提示
     *
     * 这里必须说实话：用户选了上海话、服务端其实只按普通话认，
     * 不提前讲清楚，用户只会觉得「这 App 识别不准」。
     */
    private fun refreshLangTip() {
        val ctx = context ?: return
        val vb = _b ?: return
        if (vb.spAsrLang.adapter == null) return
        val lang = SpeechLangs.ALL.getOrNull(vb.spAsrLang.selectedItemPosition) ?: return
        val provider = asrProviders.getOrNull(vb.spAsrProvider.selectedItemPosition) ?: return
        val plan = SpeechLangs.plan(lang, provider)

        val sb = StringBuilder(lang.hint)
        when {
            plan.downgraded -> sb.append("\n\n⚠ ").append(plan.note)
            lang.isDialect -> sb.append("\n\n当前服务可直接按此方言识别（language=")
                .append(plan.requestValue ?: "auto").append("）。")
        }
        vb.tvLangTip.text = sb.toString()

        // 只有方言才需要「转普通话」这一步
        vb.swDialectStd.isEnabled = lang.isDialect
        vb.tvDialectStdTip.text = if (lang.isDialect) {
            ctx.getString(R.string.dialect_std_tip)
        } else {
            "当前语言不是方言，此项不生效。"
        }
    }

    // ==================== 整理导出 / 日历同步 ====================

    /**
     * 导出记录为 Markdown
     *
     * 生成文稿 + 查库都在 IO 线程（全部记录上千条、每条带完整转写，主线程做会明显卡），
     * 生成好再唤起系统文件选择器（SAF），由用户决定存到哪。
     */
    private fun exportRecords(todayOnly: Boolean) {
        val ctx = context ?: return
        b.tvDataResult.text = "正在整理…"
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val sessions = if (todayOnly) {
                    Repo.sessionsBetween(ctx, TimeUtil.todayStart(), TimeUtil.todayEnd())
                } else {
                    Repo.recentSessions(ctx, 1000)
                }
                if (sessions.isEmpty()) return@withContext null
                val items = sessions.map { it to Repo.todosOf(ctx, it.id) }
                val scopeName = if (todayOnly) "今日记录" else "全部记录"
                Exporter.documentMarkdown("草记 · $scopeName", items)
            }
            if (_b == null) return@launch
            if (text == null) {
                b.tvDataResult.text = if (todayOnly) "今天还没有记录" else "还没有任何记录"
                return@launch
            }
            pendingExport = text
            b.tvDataResult.text = ""
            createDoc.launch(Exporter.fileName(if (todayOnly) "今日记录" else "全部记录"))
        }
    }

    private fun exportTodos() {
        val ctx = context ?: return
        b.tvDataResult.text = "正在整理待办…"
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val todos = Repo.allTodos(ctx, 500)
                if (todos.isEmpty()) null else Exporter.todosMarkdown(todos)
            }
            if (_b == null) return@launch
            if (text == null) {
                b.tvDataResult.text = "还没有待办"
                return@launch
            }
            pendingExport = text
            b.tvDataResult.text = ""
            createDoc.launch(Exporter.fileName("待办清单"))
        }
    }

    /** 把带到期日的未完成待办批量写成系统日历的全天事件（需要读写日历权限） */
    private fun syncTodosToCalendar() {
        val ctx = context ?: return
        b.tvDataResult.text = "正在写入日历…"
        viewLifecycleOwner.lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                Repo.allTodos(ctx, 500).filter { !it.done && it.dueDate.length >= 10 }
            }
            if (_b == null) return@launch
            if (pending.isEmpty()) {
                b.tvDataResult.text = "没有「有到期日且未完成」的待办，无需同步"
                return@launch
            }
            val written = withContext(Dispatchers.IO) { CalendarBridge.writeTodos(ctx, pending) }
            if (_b == null) return@launch
            b.tvDataResult.text = when {
                written < 0 -> "没找到可写入的日历，请先在系统日历里建一个（并确认已授权）"
                written == 0 -> "${pending.size} 条待办都已在日历中，未重复添加"
                else -> "已写入 $written 条到系统日历（重复的自动跳过，共 ${pending.size} 条待办）"
            }
        }
    }

    private fun keyPageUrl(providerId: String): String = when (providerId) {
        "zhipu" -> "https://open.bigmodel.cn/usercenter/apikeys"
        "qwen" -> "https://bailian.console.aliyun.com/"
        "deepseek" -> "https://platform.deepseek.com/api_keys"
        "siliconflow" -> "https://cloud.siliconflow.cn/account/ak"
        "moonshot" -> "https://platform.moonshot.cn/console/api-keys"
        "openai" -> "https://platform.openai.com/api-keys"
        else -> ""
    }

    private fun scanCallRecordings() {
        val ctx = requireContext()
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(ctx, "请先授予「音频/存储」权限", Toast.LENGTH_SHORT).show()
            mediaPerm.launch(perm)
            return
        }

        b.tvScanResult.text = "正在扫描系统通话录音…"
        viewLifecycleOwner.lifecycleScope.launch {
            val since = Repo.getStr(ctx, Repo.K_LAST_CALL_SCAN).toLongOrNull() ?: 0L
            val result = withContext(Dispatchers.IO) {
                val found = CallRecordingScanner.scanNewCallRecordings(ctx, since)
                var imported = 0
                found.forEach { rec ->
                    val id = CallRecordingScanner.importAsSession(ctx, rec)
                    if (id > 0) {
                        imported++
                        com.example.mindball.ai.AnalysisPipeline.enqueue(ctx, id)
                    }
                }
                found.size to imported
            }
            Repo.setStr(ctx, Repo.K_LAST_CALL_SCAN, System.currentTimeMillis().toString())
            b.tvScanResult.text = "扫描完成：发现通话录音 ${result.first} 条，已导入 ${result.second} 条并进入转写队列"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
