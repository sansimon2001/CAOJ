package com.example.mindball.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地敏感数据加密（API Key、声纹模板）
 *
 * 方案：Android Keystore 里的 AES-256 密钥 + AES/GCM/NoPadding。
 * - 密钥由系统 TEE/StrongBox 托管，**不会导出到应用进程之外**，也不会随备份带走
 * - 每次加密随机 IV（GCM 要求），IV 与密文一起 Base64 存储：`enc:v1:<base64(iv|cipher)>`
 * - 删除应用 / 清除数据后密钥随之销毁，残留的密文再也解不开（这正是我们想要的）
 *
 * 为什么不用 EncryptedSharedPreferences：要多引一个 androidx.security 依赖；本项目的
 * 配置本来就存在自建 SQLite 的 settings 表里，直接用 Keystore 更贴合、体积更小。
 *
 * 全部操作 runCatching：个别 ROM 的 Keystore 会抛异常（如密钥被系统清理），
 * 此时降级为不加密但**绝不崩溃**，并由上层在解密失败时把该项视为「未配置」，
 * 引导用户重新填写，而不是让 App 打不开。
 */
object SecureStore {

    private const val KEY_ALIAS = "mindball_local_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun isSealed(value: String): Boolean = value.startsWith(PREFIX)

    /** 加密；失败返回 null（调用方降级为明文存储） */
    fun seal(plain: String): String? {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }.getOrNull()
    }

    /** 解密；不是密文则原样返回，解密失败返回 null */
    fun open(value: String): String? {
        if (!isSealed(value)) return value
        return runCatching {
            val raw = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return@runCatching null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_LEN)),
            )
            String(cipher.doFinal(raw.copyOfRange(IV_LEN, raw.size)), Charsets.UTF_8)
        }.getOrNull()
    }

    /** 是否真的能加解密（设置页展示加密状态用，避免「写着加密其实没生效」） */
    fun isAvailable(): Boolean {
        val probe = seal("ping") ?: return false
        return open(probe) == "ping"
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
