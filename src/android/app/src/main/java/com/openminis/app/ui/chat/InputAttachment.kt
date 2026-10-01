package com.openminis.app.ui.chat

import android.net.Uri
import java.util.UUID

/**
 * 输入框待发附件（血统清剿 P3.7 就地真重写；字段集为消费方依赖面冻结）。
 */
data class InputAttachment(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val uri: Uri,
    val mimeType: String,
    val kind: Kind,
) {
    /** 是否图片件（文档件走另一条预览/发送路径）。 */
    val isImage: Boolean
        get() = kind == Kind.IMAGE

    enum class Kind { IMAGE, DOCUMENT }
}
