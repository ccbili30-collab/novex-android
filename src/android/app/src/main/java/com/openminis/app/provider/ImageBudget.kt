package com.openminis.app.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.logging.AppLogger
import java.io.ByteArrayOutputStream

/**
 * 内联图片字节预算器——对齐 iOS AIChatViewModel.swift 的 kPerImageMaxBytes /
 * kMessageImageMaxBytes（commit b830360；4.0 闭源收尾轮整体重组，常量、
 * 阶梯、占位文案与路径拼接为契约冻结面）。
 *
 * Anthropic 在请求内联图片总负载越界时回 HTTP 413（"Downloaded image
 * content cannot exceed 30MB"）。T-imgsize 之前只把附件按最长边 2000px、
 * JPEG q=85 重采样——那是**分辨率**预算，对 12MP 的 HEIC 源在字节上毫无
 * 作用。现在在两层强制真字节上限：
 *
 *   1. 编写层（ChatViewModel.prepareUserAttachments）——用户新附件逐个过
 *      [compressUnderBudget] 保证每件 ≤ MAX_PER_IMAGE_BYTES，再累计字节，
 *      超 MAX_TOTAL_BYTES 即掉尾并 Snackbar 告知；
 *   2. 供应商边界（适配器 encodeImage）——兜住绕过编写层的历史图片件
 *      （工具结果截图字节、恢复的会话、改后重试），超限件就地静默重编码。
 *
 * URL HEAD 预检（spec §2.b）刻意不做——Android 两家供应商永远 base64 内联
 * 图片字节（不转发远端 URL），与 iOS commit b830360 的结论一致。
 *
 * 本版组织（与前身直译版刻意不同）：压缩入口改 runCatching 管线；消息级
 * 预算与请求级规划各自收拢为「累计 + 单件计费」的单一循环形态；溢写
 * 落盘的失败路径统一经 [runSpilloverStep]。
 */
object ImageBudget {

    /** 单图字节上限，越过即触发重编码。 */
    const val MAX_PER_IMAGE_BYTES = 5L * 1024 * 1024

    /** 单条用户消息的内联图片累计字节。 */
    const val MAX_TOTAL_BYTES = 25L * 1024 * 1024

    /**
     * 单个请求体内**全部消息**的内联图片累计字节。与单消息上限同值，但
     * 施加在请求边界——长历史里多轮累积的图片（浏览器截图、附件、
     * read_image 结果）才不至于把请求顶过让 factory.pub / Anthropic 网关
     * 静默回 200 + 空 SSE 且 `finish_reason=stop` 的那条线。最先被抹除的
     * 是最老的图片（转文本占位）。
     */
    const val MAX_REQUEST_BYTES = 25L * 1024 * 1024

    /** 重编码默认目标最长边（像素）。 */
    const val MAX_EDGE_PX = 2000

    /** 重编码默认 JPEG 质量（0-100）。 */
    const val JPEG_QUALITY = 80

    private const val TAG = "ImageBudget"

    /**
     * 单图压缩器逐级试探的 (最长边, 质量) 候选，直到产出塞进
     * [MAX_PER_IMAGE_BYTES]。沿 iOS AIChatViewModel.swift
     * compressedImageDataUnderBudget(…) 的阶梯。
     */
    private val DOWNSIZE_LADDER: List<Pair<Int, Int>> = listOf(
        2000 to 80,
        1600 to 75,
        1280 to 70,
        1024 to 65,
        896 to 55,
        768 to 50,
        640 to 45,
    )

    /**
     * 把 [input] 重编码为最长边 [maxEdge]、质量 [q] 的 JPEG。解码/编码失败
     * 时原样返回——调用方已有的载荷永远比丢图安全。
     */
    fun compressBytes(input: ByteArray, maxEdge: Int = MAX_EDGE_PX, q: Int = JPEG_QUALITY): ByteArray =
        input.takeIf { it.isNotEmpty() }?.let { nonEmpty ->
            runCatching { reencodeSampled(nonEmpty, maxEdge, q) }
                .onFailure { t ->
                    AppLogger.warning(TAG, "compressBytes failed (${nonEmpty.size}B → keeping original): ${t.message}")
                }
                .getOrNull()
        } ?: input

    /**
     * 两遍解码（沿 PhotosOffloadHandler.copyResized 的做法）：先采边界定
     * 采样率，让内存位图与 maxEdge 成比例；解码后再缩放到精确目标。返回
     * (编码产物, 需要回收的位图)——缩放出的新位图与解码出的原位图都由
     * 调用方统一回收，防止大图解码内存滞留。
     */
    private fun reencodeSampled(input: ByteArray, maxEdge: Int, quality: Int): ByteArray? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null

        var downsample = 1
        while (probe.outWidth / downsample > maxEdge * 2 || probe.outHeight / downsample > maxEdge * 2) {
            downsample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            input, 0, input.size,
            BitmapFactory.Options().apply { inSampleSize = downsample },
        ) ?: return null

        // 缩放比取「两个维度都塞进 maxEdge」的最小因子，且绝不放大。
        val widthScale = maxEdge.toFloat() / decoded.width
        val heightScale = maxEdge.toFloat() / decoded.height
        val shrink = minOf(widthScale, heightScale, 1f)
        val targetW = (decoded.width * shrink).toInt().coerceAtLeast(1)
        val targetH = (decoded.height * shrink).toInt().coerceAtLeast(1)
        val canvas = if (shrink < 1f) Bitmap.createScaledBitmap(decoded, targetW, targetH, true) else decoded
        val buffer = ByteArrayOutputStream()
        canvas.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), buffer)
        if (canvas !== decoded) canvas.recycle()
        decoded.recycle()
        return buffer.toByteArray()
    }

    /**
     * 逐级加压 (最长边, 质量) 直到重编码产物塞进 [targetMaxBytes]。全部候
     * 选都不达标时返回产出里最小的一版——绝不返回 null，「发出点什么」胜
     * 过「请求失败」。每一级都从原始字节重新编码，JPEG 伪影不叠加。
     */
    fun compressUnderBudget(input: ByteArray, targetMaxBytes: Long = MAX_PER_IMAGE_BYTES): ByteArray {
        if (input.size <= targetMaxBytes) return input
        var smallestSoFar = input
        for ((edge, quality) in DOWNSIZE_LADDER) {
            val candidate = compressBytes(input, edge, quality)
            if (candidate.size < smallestSoFar.size) smallestSoFar = candidate
            if (candidate.size > targetMaxBytes) continue
            return candidate.also {
                AppLogger.info(TAG, "compressUnderBudget hit: ${input.size}B → ${candidate.size}B (edge=$edge q=$quality)")
            }
        }
        AppLogger.warning(
            TAG,
            "compressUnderBudget exhausted ladder: ${input.size}B → ${smallestSoFar.size}B (target=${targetMaxBytes}B)",
        )
        return smallestSoFar
    }

    /** [applyMessageBudget] 的结果（字段语义：可发送字节原序 / 重编码件数 / 掉尾件数 / 最终总字节）。 */
    data class BudgetResult(
        val keptBytes: List<ByteArray>, val compressedCount: Int,
        val droppedCount: Int, val totalBytes: Long,
    ) {
        val mutated: Boolean get() = compressedCount > 0 || droppedCount > 0
    }

    /**
     * 走一遍 [bytesIn] 产出预算内输出：
     *  - 每个超限件先过 [compressUnderBudget]；
     *  - 再累计字节——累计将超 [MAX_TOTAL_BYTES] 时，其余尾件全部丢弃。
     */
    fun applyMessageBudget(bytesIn: List<ByteArray>): BudgetResult {
        val payload = ArrayList<ByteArray>(bytesIn.size)
        var recompressed = 0
        var tailDropped = 0
        var runningTotal = 0L
        for (raw in bytesIn) {
            val oversize = raw.size.toLong() > MAX_PER_IMAGE_BYTES
            val sized = if (oversize) compressUnderBudget(raw) else raw
            if (oversize && sized.size != raw.size) recompressed += 1
            val wouldExceed = runningTotal + sized.size.toLong() > MAX_TOTAL_BYTES
            if (wouldExceed) {
                tailDropped += 1
            } else {
                payload.add(sized)
                runningTotal += sized.size.toLong()
            }
        }
        if (tailDropped > 0 || recompressed > 0) {
            AppLogger.info(TAG, "applyMessageBudget: in=${bytesIn.size} kept=${payload.size} compressed=$recompressed dropped=$tailDropped total=${runningTotal}B")
        }
        return BudgetResult(payload, recompressed, tailDropped, runningTotal)
    }

    // ─── 请求级预算 ─────────────────────────────────────────────────────────

    /**
     * 出站请求负载里单个图片件的身份。供应商拿它查某件是否被
     * [planRequestBudget] 标记抹除。ByteArray 的 identity hash 足够——同一
     * 批字节在一个请求里只出现一次，不同字节的哈希也足够散；最坏碰撞不过
     * 「预算外多留一张图」（安全方向）。
     */
    @JvmInline
    value class ImagePartId(val identityHash: Int) {
        companion object {
            fun of(data: ByteArray): ImagePartId = ImagePartId(System.identityHashCode(data))
        }
    }

    /**
     * 预算规划用的图片件投影。让规划器对 `LLMMessage.ImagePart`（当轮用户
     * 附件）与 `AgentContentPart.ImageData` / `AgentContentPart.ToolResult.
     * imageData`（历史图片字节）保持泛化，不直接依赖任何一边。
     */
    data class BudgetImage(
        val data: ByteArray, val linuxPath: String?, val mimeType: String,
    )

    /**
     * [planRequestBudget] 的结果。字段语义：供应商**不得**内联的图片字节
     * 身份集 / 被抹除身份 → 原 linux 路径（无则 null）/ 保留总字节（按单
     * 图上限截断计）/ 被抹除总字节 / 被抹除图片数 / 纳入考虑总数。
     */
    data class RequestBudgetPlan(
        val droppedIds: Set<ImagePartId>, val droppedPaths: Map<ImagePartId, String?>,
        val keptBytes: Long, val elidedBytes: Long, val droppedCount: Int,
        val totalCount: Int,
    ) {
        val mutated: Boolean get() = droppedCount > 0
    }

    /**
     * 逆时间序（最新在前）走一遍候选图片，决定哪些塞得进 [maxBytes]。装不
     * 下的记进 [RequestBudgetPlan.droppedIds]，供应商改发文本占位而非
     * base64 字节。
     *
     * @param images 老 → 新排序。规划器内部反转——最新用户输入与最近的
     *   工具结果受保护、不被抹除。
     */
    fun planRequestBudget(images: List<BudgetImage>, maxBytes: Long = MAX_REQUEST_BYTES): RequestBudgetPlan {
        if (images.isEmpty()) return RequestBudgetPlan(emptySet(), emptyMap(), 0L, 0L, 0, 0)
        val elidedIds = HashSet<ImagePartId>()
        val elidedPaths = HashMap<ImagePartId, String?>()
        var keptTotal = 0L
        var elidedTotal = 0L
        // 最新 → 最老：最近的图先占预算。
        for (picture in images.asReversed()) {
            // 单图上限截断计——与真去调 [compressUnderBudget] 会得到的
            // 上限同一条线。
            val charge = minOf(picture.data.size.toLong(), MAX_PER_IMAGE_BYTES)
            val roomLeft = keptTotal + charge <= maxBytes
            if (roomLeft) {
                keptTotal += charge
                continue
            }
            val id = ImagePartId.of(picture.data)
            elidedIds.add(id)
            elidedPaths[id] = picture.linuxPath
            elidedTotal += charge
        }
        if (elidedIds.isNotEmpty()) {
            AppLogger.info(TAG, "planRequestBudget: in=${images.size} kept=${images.size - elidedIds.size} dropped=${elidedIds.size} keptBytes=${keptTotal}B elidedBytes=${elidedTotal}B cap=${maxBytes}B")
        }
        return RequestBudgetPlan(elidedIds, elidedPaths, keptTotal, elidedTotal, elidedIds.size, images.size)
    }

    /**
     * 供应商抹除图片后发的文本占位。给模型一个明确可行动的提示：这张图
     * 为塞进预算被丢掉了，且（若已知）字节仍可经 [read_image] 读取的
     * linux 路径。没有路径时模型只知道图被抹了，可以让用户重传。
     */
    fun elidedImagePlaceholder(linuxPath: String?): String =
        if (linuxPath != null) {
            "[image elided to fit 25MB request budget. Original at $linuxPath — re-fetch with `read_image $linuxPath` if you need to see it.]"
        } else {
            "[image elided to fit 25MB request budget. Original bytes no longer addressable; ask the user to re-attach if needed.]"
        }

    /**
     * 把 [data] 惰性落进会话级溢写目录 `attachments/spillover/<sha1>.<ext>`，
     * 让没有现成 linux 路径的被抹图片仍能从文本占位里被引用。该目录与
     * `attachments/uploads/` 同一挂载，沙箱内可见于
     * `/var/minis/attachments/spillover/`。写失败返回 null（占位回落无路径
     * 变体）。幂等：同字节已按 sha1 前缀在盘上时不重写、直接返回原路径。
     */
    fun ensureSpillover(sessionAttachmentsDir: java.io.File, data: ByteArray, mimeType: String): String? {
        if (data.isEmpty()) return null
        val extension = extensionFor(mimeType)
        val digest = sha1HexOrNull(data) ?: System.identityHashCode(data).toString(16)
        val spilloverDir = java.io.File(sessionAttachmentsDir, "spillover")
        if (!spilloverDir.isDirectory && !runSpilloverStep("mkdirs") { spilloverDir.mkdirs() }) return null
        val fileName = "$digest.$extension"
        val target = java.io.File(spilloverDir, fileName)
        if (!target.exists() && !runSpilloverStep("write") { target.writeBytes(data) }) return null
        // 对齐 uploads 挂载（见 ChatViewModel.prepareUserAttachments）。
        return "/var/minis/attachments/spillover/$fileName"
    }

    /** 溢写落盘单步执行：异常记日志并报失败（false），成功报 true。 */
    private fun runSpilloverStep(step: String, action: () -> Unit): Boolean = try {
        action()
        true
    } catch (e: Exception) {
        AppLogger.warning(TAG, "ensureSpillover $step failed: ${e.message}")
        false
    }

    /** MIME → 溢写文件扩展名（冻结面：映射行为即契约）。 */
    private val EXTENSION_BY_MIME = mapOf(
        "image/jpeg" to "jpg", "image/jpg" to "jpg",
        "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp",
        "image/heic" to "heic", "image/heif" to "heic",
    )

    private fun extensionFor(mimeType: String): String =
        EXTENSION_BY_MIME[mimeType.lowercase()] ?: "bin"

    /** SHA-1 平台必备；万一不可得，退 identity hash——这里的碰撞可容忍。 */
    private fun sha1HexOrNull(data: ByteArray): String? = try {
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(data)
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }
}
