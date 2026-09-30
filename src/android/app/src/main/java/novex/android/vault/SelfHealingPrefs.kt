package novex.android.vault

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

/**
 * 带自愈的加密 SharedPreferences 工厂（P3.5c 自 util/EncryptedPrefsFactory
 * 真重写）。
 *
 * 要解决的问题：AndroidKeystore 主键解不开 Tink keyset 时（三星 One UI /
 * Android 16 上备份恢复或重录生物特征后可复现），默认创建流程直接抛
 * AEADBadTagException，懒初始化把异常带到主线程，冷启动反复命中同一处
 * 就成了重启死循环。
 *
 * 三级阶梯（冻结面）：
 *  1. 正常创建；
 *  2. 失败则清掉加密 XML、Tink keyset 伴生文件与 Keystore 主键别名后重建
 *     ——用户丢已存凭据（需重贴 key / 重登 OAuth），但应用能起来；
 *  3. 再失败落到明文 prefs 兜底，文件名带 `_plain_fallback` 后缀以示区别，
 *     且永不升格回加密槽。
 */
object SelfHealingPrefs {
    private const val TAG = "SelfHealingPrefs"

    /** Tink keyset 的伴生 prefs 文件名（按 SP 文件名挂账的那份）。 */
    private const val KEYSET_COMPANION = "__androidx_security_crypto_encrypted_prefs__"

    fun safeCreate(context: Context, fileName: String): SharedPreferences =
        attempt(context, fileName, stage = "first create")
            ?: rebuildAfterWipe(context, fileName)
            ?: plainFallback(context, fileName)

    private fun attempt(context: Context, fileName: String, stage: String) =
        runCatching { build(context, fileName) }
            .onFailure { Log.w(TAG, "$stage($fileName) threw: ${it.message}") }.getOrNull()

    private fun rebuildAfterWipe(context: Context, fileName: String): SharedPreferences? {
        wipeCorruptState(context, fileName)
        val rebuilt = attempt(context, fileName, stage = "rebuild")
        if (rebuilt == null) {
            Log.e(TAG, "rebuild($fileName) after wipe failed", )
        }
        return rebuilt
    }

    private fun plainFallback(context: Context, fileName: String): SharedPreferences {
        Log.w(TAG, "crypto unavailable for $fileName — using plain fallback store (credentials lost)")
        return context.getSharedPreferences("${fileName}_plain_fallback", Context.MODE_PRIVATE)
    }

    private fun build(context: Context, fileName: String): SharedPreferences {
        val key = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(context, fileName, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    /** 加密 XML + keyset 伴生文件 + Keystore 主键别名三件全清，让 create() 重生成。 */
    private fun wipeCorruptState(context: Context, fileName: String) {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        for (victim in listOf("$fileName.xml", "$KEYSET_COMPANION.xml")) {
            runCatching { File(prefsDir, victim).delete() }
                .onFailure { Log.w(TAG, "wipe $victim failed: ${it.message}") }
        }
        runCatching {
            with(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }) {
                if (containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }.onFailure { Log.w(TAG, "wipe keystore master alias failed: ${it.message}") }
    }
}
