package com.openminis.app.ui.chat

import android.net.Uri

/**
 * 输入框待发附件（血统清剿 P3.7 就地真重写；构造字段集为消费方依赖面
 * 冻结——id/文件名/URI/MIME/种类五参不得增删改名）。
 */
data class InputAttachment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val fileName: String,
    val uri: Uri,
    val mimeType: String,
    val kind: Kind,
) {
    /** 是否图片件（文档件走另一条预览/发送路径）。 */
    val isImage: Boolean
        get() = kind == Kind.IMAGE

    enum class Kind {
        IMAGE, DOCUMENT
    }
}
