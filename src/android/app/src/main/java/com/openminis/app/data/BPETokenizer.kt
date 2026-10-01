package com.openminis.app.data

import android.content.Context
import android.graphics.BitmapFactory
import kotlin.math.ceil
import kotlin.math.max

/**
 * 本地 token 估算器（血统清剿 P3.7 就地真重写；常量与估算公式为行为冻结
 * 面）。刻意保守：绝不是供应商 tokenizer，也不是计量 usage——文本跑段与
 * Unicode 字节分开数，中文卡片不会被当成「三字符一 token」。供应商
 * usage 一到就覆盖估算值。
 */
object BPETokenizer {
    const val TOKENS_PER_MESSAGE = 3
    const val TOKENS_PER_REPLY = 3
    private const val IMAGE_GRID = 32
    private const val IMAGE_MAX_EDGE = 2048
    private const val IMAGE_MIN_TOKENS = 85
    private const val IMAGE_FAILURE_FALLBACK = 1000

    /** 已装载的 cl100k_base 词表（字节序列 → rank）；未装载为 null。 */
    @Volatile
    private var vocabulary: Map<List<Byte>, Int>? = null

    /**
     * 数 [text] 的 token：词表在就用贪心 BPE 编码长度，否则走码点启发式。
     * 空输入返回 0。
     */
    fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val vocab = vocabulary ?: return heuristicTokenCount(text)
        return bpeEncode(text.toByteArray(Charsets.UTF_8), vocab).size
    }

    /**
     * 图片负载的近似 token 数。对齐 iOS 启发式：长边缩到 2048px、按 32×32
     * 格、下限 85；解码失败按 1000 计（当作一块昂贵的不可读团块）。
     */
    fun countImageTokens(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, probe)
        val width = probe.outWidth.toFloat()
        val height = probe.outHeight.toFloat()
        if (width <= 0f || height <= 0f) return IMAGE_FAILURE_FALLBACK

        // 长边越界才缩，否则原尺寸。
        val longEdge = max(width, height)
        val shrink = if (longEdge > IMAGE_MAX_EDGE) IMAGE_MAX_EDGE / longEdge else 1f
        val cells = ceil(width * shrink / IMAGE_GRID) * ceil(height * shrink / IMAGE_GRID)
        return max(IMAGE_MIN_TOKENS, cells.toInt())
    }

    /**
     * 可选：从 `assets/<fileName>` 装 cl100k_base 词表。文件须合 tiktoken
     * 格式——每行一条 `<base64字节> <rank>`。已装载或文件缺失时空转。
     */
    fun loadVocabularyFromAssets(context: Context, fileName: String = "cl100k_base.tiktoken"): Boolean {
        if (vocabulary != null) return true
        return try {
            val dict = HashMap<List<Byte>, Int>(100_300)
            context.assets.open(fileName).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    parseVocabLine(line)?.let { (tokenBytes, rank) ->
                        dict[tokenBytes] = rank
                    }
                }
            }
            if (dict.isNotEmpty()) {
                vocabulary = dict
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 一行 `<base64> <rank>` → (字节序列, rank)；畸形行 null。 */
    private fun parseVocabLine(line: String): Pair<List<Byte>, Int>? {
        val split = line.indexOf(' ')
        if (split <= 0) return null
        val tokenBytes = runCatching {
            android.util.Base64.decode(
                line.substring(0, split),
                android.util.Base64.DEFAULT or android.util.Base64.NO_WRAP,
            )
        }.getOrNull() ?: return null
        val rank = line.substring(split + 1).trim().toIntOrNull() ?: return null
        return tokenBytes.toList() to rank
    }

    /**
     * 无词表时的启发式：ASCII 连跑段按每 3 字符 1 token 折算；分隔符、CJK、
     * emoji 及任意外部文本不算英文散文——按码点计 1~2 token。
     */
    private fun heuristicTokenCount(text: String): Int {
        var tokens = 0L
        var asciiRunLength = 0
        fun settleAsciiRun() {
            tokens += (asciiRunLength.toLong() + 2) / 3
            asciiRunLength = 0
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val isWordChar = cp in 48..57 || cp in 65..90 || cp in 97..122
            if (isWordChar) {
                asciiRunLength++
            } else {
                settleAsciiRun()
                tokens += when {
                    cp < 128 -> 1
                    cp < 2048 -> 1
                    else -> 2
                }
            }
            i += Character.charCount(cp)
        }
        settleAsciiRun()
        return tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 贪心 BPE：每个字节各成一段，反复合并「仍在词表里的相邻对中 rank 最
     * 低者」，无可合并对即停。对齐 iOS `bytePairEncode`。
     */
    private fun bpeEncode(bytes: ByteArray, vocab: Map<List<Byte>, Int>): List<Int> {
        if (bytes.isEmpty()) return emptyList()
        val segments = ArrayList<IntArray>(bytes.size)
        for (i in bytes.indices) segments.add(intArrayOf(i, i + 1))
        while (segments.size > 1) {
            val mergeAt = lowestRankAdjacency(bytes, segments, vocab)
                ?: break // 没有任何相邻对还在词表里。
            segments[mergeAt] = intArrayOf(segments[mergeAt][0], segments[mergeAt + 1][1])
            segments.removeAt(mergeAt + 1)
        }
        return segments.mapNotNull { seg -> vocab[bytes.sliceArray(seg[0] until seg[1]).toList()] }
    }

    /** 找 rank 最小的可合并相邻段下标；无则 null。 */
    private fun lowestRankAdjacency(bytes: ByteArray, segments: List<IntArray>, vocab: Map<List<Byte>, Int>): Int? {
        var bestRank = Int.MAX_VALUE
        var bestIndex = -1
        for (i in 0 until segments.size - 1) {
            val merged = bytes.sliceArray(segments[i][0] until segments[i + 1][1]).toList()
            val rank = vocab[merged] ?: continue
            if (rank < bestRank) {
                bestRank = rank
                bestIndex = i
            }
        }
        return bestIndex.takeIf { it >= 0 }
    }
}
