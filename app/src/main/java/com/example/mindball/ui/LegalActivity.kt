package com.example.mindball.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.mindball.databinding.ActivityLegalBinding
import com.example.mindball.util.Contact

/**
 * 用户协议与免责声明
 *
 * 全文放在 assets/legal_disclaimer.txt 而不是硬编码在布局里：
 * - 便于随版本修订（改文本不用动代码）；
 * - 不联网也能看，商店审核和首次使用都能直接打开。
 *
 * 这一页**不加** FLAG_SECURE：声明本身不敏感，允许截屏/转发反而有利于传播与留存证据。
 */
class LegalActivity : AppCompatActivity() {

    private lateinit var b: ActivityLegalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLegalBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvVersion.text = "v" + Contact.versionName(this)
        b.tvLegal.text = readLegalText()
        b.tvMail.text = Contact.EMAIL

        b.tvBack.setOnClickListener { finish() }
        b.btnMail.setOnClickListener { Contact.sendMail(this, "关于草记的协议与声明") }
        b.btnCopyMail.setOnClickListener { Contact.copyEmail(this) }
        b.tvMail.setOnClickListener { Contact.sendMail(this, "关于草记的协议与声明") }
    }

    private fun readLegalText(): String = runCatching {
        assets.open("legal_disclaimer.txt").bufferedReader().use { it.readText() }
    }.getOrElse {
        "免责声明文件读取失败。请联系 ${Contact.EMAIL} 获取完整文本。"
    }
}
