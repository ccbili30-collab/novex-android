package com.openminis.app.ui.noven

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class NovenProfile(
    val nickname: String,
    val bio: String,
    val avatarPath: String?,
)

/** 本地个人资料：SharedPreferences 存昵称/简介/头像文件路径，头像文件复制进 filesDir。 */
internal class NovenProfileStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("noven_profile", Context.MODE_PRIVATE)

    var profile by mutableStateOf(read())
        private set

    private fun read() = NovenProfile(
        nickname = prefs.getString("nickname", null)?.takeIf { it.isNotBlank() } ?: "我",
        bio = prefs.getString("bio", null).orEmpty(),
        avatarPath = prefs.getString("avatar_path", null)?.takeIf { File(it).exists() },
    )

    fun update(nickname: String, bio: String) {
        prefs.edit()
            .putString("nickname", nickname.ifBlank { "我" })
            .putString("bio", bio)
            .apply()
        profile = read()
    }

    /**
     * 把选中的图片复制到 filesDir/noven-profile/（IO 线程），返回持久路径；
     * 失败返回 null。文件名带时间戳保证唯一，并删除旧头像文件 —— Coil 按
     * 路径缓存，重名会命中旧图。
     */
    suspend fun saveAvatarFrom(source: java.io.InputStream?, extension: String): String? =
        withContext(Dispatchers.IO) {
            val input = source ?: return@withContext null
            runCatching {
                val dir = File(app.filesDir, "noven-profile").apply { mkdirs() }
                val old = prefs.getString("avatar_path", null)?.let(::File)
                val file = File(
                    dir,
                    "avatar-${System.currentTimeMillis()}.${extension.ifBlank { "img" }}",
                )
                input.use { stream -> file.outputStream().use { stream.copyTo(it) } }
                prefs.edit().putString("avatar_path", file.absolutePath).apply()
                if (old != null && old.absolutePath != file.absolutePath) old.delete()
                profile = read()
                file.absolutePath
            }.getOrNull()
        }

    companion object {
        @Volatile
        private var instance: NovenProfileStore? = null

        /** 进程内单例：各 tab 的状态被 SaveableStateHolder 保留，用同一个实例才能互通。 */
        fun get(context: Context): NovenProfileStore =
            instance ?: synchronized(this) {
                instance ?: NovenProfileStore(context).also { instance = it }
            }
    }
}
