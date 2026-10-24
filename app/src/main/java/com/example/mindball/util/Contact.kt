package com.example.mindball.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 联系方式与版本信息
 *
 * 集中一处，避免邮箱在界面、免责声明、关于页各写一遍后对不上
 * （用户反馈时抄错一个字符就联系不上了）。
 */
object Contact {

    /** 作者反馈邮箱：对外唯一入口 */
    const val EMAIL = "sansimon2001@163.com"

    /** 邮件主题默认带上版本号，便于判断问题出在哪个版本 */
    fun mailIntent(ctx: Context, subject: String = "草记使用反馈"): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL")).apply {
            putExtra(Intent.EXTRA_SUBJECT, "$subject（v${versionName(ctx)}）")
            putExtra(
                Intent.EXTRA_TEXT,
                "\n\n---\n设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / " +
                    "Android ${android.os.Build.VERSION.RELEASE}\n应用版本：v${versionName(ctx)}\n" +
                    "（保留这几行有助于定位问题）"
            )
        }

    /** 唤起邮件客户端；没有邮件应用时退化为复制邮箱 + 提示 */
    fun sendMail(ctx: Context, subject: String = "草记使用反馈") {
        val ok = runCatching { ctx.startActivity(mailIntent(ctx, subject)) }.isSuccess
        if (!ok) {
            Exporter.copy(ctx, EMAIL)
            Toast.makeText(ctx, "没有找到邮件应用，邮箱已复制：$EMAIL", Toast.LENGTH_LONG).show()
        }
    }

    fun copyEmail(ctx: Context) {
        if (Exporter.copy(ctx, EMAIL)) {
            Toast.makeText(ctx, "邮箱已复制：$EMAIL", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, EMAIL, Toast.LENGTH_LONG).show()
        }
    }

    fun versionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
    }.getOrDefault("1.0")
}
