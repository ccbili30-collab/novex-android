package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize

/**
 * MinisTextKit——自成一体、能在 LazyColumn 条目回收下存活的选择层（血统
 * 清剿 P3.7 就地真重写；类型形状、全部公开方法名与选择/命中/复制语义为
 * 消费方依赖面冻结）。
 *
 * Compose 内建的 [androidx.compose.foundation.text.selection.SelectionContainer]
 * 把每个文本节点配对一个注册在该 SelectionContainer 登记器上的
 * [androidx.compose.foundation.text.selection.Selectable]。LazyColumn 条目滚
 * 出视口时 Selectable dispose → 登记器丢锚 → 活动选区塌缩。
 *
 * MinisTextKit 把**权威选区态**整个提在 LazyColumn 之外绕开这一点。每个
 * 可见文本分片在组合期间向 [SelectionController] 注册一个 [TextShard]；
 * 滚出屏后注册消失，但按稳定 (messageId, shardId, charOffset) 键存的
 * [selection] 仍在。分片滚回视野时重新注册，选区高亮自动重画。
 *
 * 本文件只定义状态持有者与注册 ABI。命中测试、拖拽手势与高亮绘制在
 * [MinisMarkdownView] 实现，[StreamingMarkdownText] 经
 * [LocalMinisSelectionController] 消费。
 */

/**
 * 跨重组稳定的句柄，标识聊天消息里的一个承文本节点。(messageId, shardId)
 * 组合在聊天列表内唯一——messageId 圈定单条 ChatMessage，shardId 圈定该消
 * 息内的一个分片（一段/一个代码块/一个标题，对齐
 * splitMarkdownIntoBlockTexts）。
 */
data class TextShardId(
    val messageId: String,
    val shardId: String,
)

/**
 * 注册到 [SelectionController] 的按分片负载。控制器用 [textLayoutResult]
 * 做屏点→字符偏移的命中测试及其逆（偏移→包围盒）供高亮渲染。控制器**不**
 * 持有 LayoutCoordinates 引用；持有的是每趟布局经 [positionInWindow] 重算
 * 的「窗口内位置」。
 */
class TextShard(
    val id: TextShardId,
    /** 用户实际看到的纯文本——复制回落用。 */
    val plainText: String,
    /** 已渲染 Text 可组合件的 TextLayoutResult。 */
    val textLayoutResult: TextLayoutResult,
    /** 窗口坐标下的左上角，由分片属主每趟刷新。 */
    val positionInWindow: () -> Offset,
    /** 已布局文本的像素尺寸。 */
    val sizePx: () -> IntSize,
    /**
     * 可选：渲染后 AnnotatedString 字符偏移 → 原始 markdown 源偏移的映射。
     * null 时复制回落 [plainText.substring]；给了它，「复制 markdown」能返
     * 回原始 markdown（保住 `**粗体**`、链接等）。
     */
    val renderedToRawOffset: ((Int) -> Int)? = null,
    /** 本分片的原始 markdown 源。与 [renderedToRawOffset] 配合走「复制 markdown」路径。 */
    val rawMarkdown: String? = null,
    /**
     * true = 本分片是长按应整件选中的自足单元——现指表格单元格。
     *
     * 散文本该要 [SelectionController] 常规的句级扩展；表格单元格不该。
     * 单元格文本通常无标点（"Alice Smith"、"张三 项目经理"），句级扫描也
     * 会扫到两端、把整格收进来；但**带**标点的格（"1,200"、"v1.2, beta"）
     * 会只选中一截——长按表格的人永远不会想要那个。把格标为原子，格界就
     * 是规则、而不是内容的偶然。
     */
    val isAtomicUnit: Boolean = false,
)

/** 选区的一个端点。跨 LazyColumn 回收稳定：属主分片当前未组合也不妨碍控
 *  制器在它重新注册后把持久选区发还给用户。 */
data class TextPosition(
    val shard: TextShardId,
    /** 分片 [TextShard.plainText] 内的字符偏移。 */
    val charOffset: Int,
)

/** 一条活选区，可跨分片/跨消息。 */
data class TextSelection(
    val start: TextPosition,
    val end: TextPosition,
)

/**
 * 持有活动选区 + 当前已组合分片的登记表。每个逻辑「文本面」提升一个——
 * ChatScreen 就是整个 LazyColumn 一个，握在 LazyColumn 之上，寿命超过任
 * 何单条的条目。
 */
class SelectionController {
    /** 拖拽在调的是选区的哪个端点。 */
    enum class Handle { Start, End }

    /** 活动选区；未选中为 null。 */
    val selection = mutableStateOf<TextSelection?>(null)

    /**
     * 手势处理器发布的拖拽意图。用户正拖（长按二段或手柄拖动）时，
     * [DragIntent.point] 是最新指尖的窗口坐标、[DragIntent.handle] 指明更
     * 新哪个端点；抬指置 null。
     *
     * ChatScreen 里另有一个 effect 盯着它与 LazyColumn 滚动位——指尖停在
     * 边缘自动滚区内不动，选区也持续跟进（新分片滚进视野、选区伸进去）。
     */
    val dragIntent = mutableStateOf<DragIntent?>(null)

    data class DragIntent(val point: Offset, val handle: Handle)

    // ─── 选区状态操作 ───────────────────────────────────────────────────────

    /** 从 [pos] 起折一条新选区（长按起点）。 */
    fun beginSelection(pos: TextPosition) {
        selection.value = TextSelection(start = pos, end = pos)
    }

    /**
     * 以 [pos] 处的**词**为界起选。对齐 iOS 长按语义：单指长按要给出一条
     * 真实、非零宽的选区——用户立刻有视觉反馈、可拖手柄外扩。分片未注册
     * 或字符不在词内时回落折叠光标。
     */
    fun beginSelectionWord(pos: TextPosition) {
        val shard = currentShards()[pos.shard]
        if (shard == null || shard.plainText.isEmpty()) {
            beginSelection(pos)
            return
        }
        val text = shard.plainText
        // 表格单元格整格选中——见 TextShard.isAtomicUnit。
        if (shard.isAtomicUnit) {
            val cellStart = text.indexOfFirst { !it.isWhitespace() }
            if (cellStart < 0) {
                beginSelection(pos)
                return
            }
            val cellEnd = text.indexOfLast { !it.isWhitespace() } + 1
            selection.value = TextSelection(
                start = TextPosition(pos.shard, cellStart),
                end = TextPosition(pos.shard, cellEnd),
            )
            return
        }
        val (lo, hi) = wordBoundsAt(text, pos.charOffset)
        if (hi <= lo) {
            beginSelection(pos)
            return
        }
        selection.value = TextSelection(
            start = TextPosition(pos.shard, lo),
            end = TextPosition(pos.shard, hi),
        )
    }

    /**
     * 计算 [text] 中包住 [offset] 的词区间——按**句级**扩展而非纯词切分：
     * [offset] 落在空白/标点上时向外找最近的词边界，段落末尾/两个 CJK 字
     * 形之间的长按仍能得到非折叠选区；实在找不到词回落「选这一个字符」，
     * 用户仍有可见选区可拖。
     */
    private fun wordBoundsAt(text: String, offset: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val len = text.length
        val clamped = offset.coerceIn(0, len)

        // 句级选择：长按要抓一段有意义的文本——拉丁取词样跑段，CJK 向两侧
        // 扩到标点/空白边界，用户拿到从句大小的选区（中文阅读器的惯例）。
        // 系统 TextView 的词迭代器对 CJK 太激进（单字选择），近乎折叠的高
        // 亮不可发现。「句停点」= 常见拉丁 + CJK 标点加换行的并集。
        val sentenceStops = setOf(
            '。', '？', '！', '；', '：', '，', '、',
            '.', '?', '!', ';', ':', ',',
            '\n', '\r',
        )
        // 向后扫 LO：最近前置句停点之后（无则 0）。
        var lo = clamped.coerceAtMost(len - 1).coerceAtLeast(0)
        while (lo > 0 && text[lo - 1] !in sentenceStops) lo--
        // 跳过句内前导空白，选区不从空格起头。
        while (lo < len && text[lo].isWhitespace()) lo++
        // 向前扫 HI：按点起第一个句停点（停点本身计入——标点被选上，复制
        // 时更自然）。
        var hi = clamped.coerceIn(0, len)
        while (hi < len && text[hi] !in sentenceStops) hi++
        if (hi < len) hi++ // 含停点字符
        // 修尾随空白。
        while (hi > lo && text[hi - 1].isWhitespace()) hi--
        if (hi <= lo) {
            // 退化——回落单字符，用户仍有可见选区可拖。
            val s = clamped.coerceIn(0, (len - 1).coerceAtLeast(0))
            return s to (s + 1).coerceAtMost(len)
        }
        return lo to hi
    }

    /** 把活动选区尾锚延到 [pos]（拖动更新）。 */
    fun extendSelectionTo(pos: TextPosition) {
        selection.value = selection.value?.copy(end = pos)
    }

    /** 换首锚（左手柄拖动）。 */
    fun replaceStart(pos: TextPosition) {
        val cur = selection.value ?: return
        if (cur.start == pos) return
        selection.value = cur.copy(start = pos)
    }

    /** 换尾锚（右手柄拖动）。 */
    fun replaceEnd(pos: TextPosition) {
        val cur = selection.value ?: return
        if (cur.end == pos) return
        selection.value = cur.copy(end = pos)
    }

    fun clearSelection() {
        selection.value = null
        messageMarkdownCache.clear()
    }

    /**
     * 活动选区两端共同所属的 messageId；跨消息（或无选区）为 null。浮动工
     * 具条用它决定「复制 Markdown / 复制富文本」按钮出不出现——那两个需
     * 要一个无歧义的源消息。
     */
    fun singleMessageId(): String? {
        val sel = selection.value ?: return null
        return sel.start.shard.messageId.takeIf { it == sel.end.shard.messageId }
    }

    // ─── 消息 markdown 快照（选区期间有效） ───────────────────────────────

    /**
     * 选区时刻抓下的消息级 markdown 快照——源分片滚出视口后（届时
     * MessageBoundsRegistry 已把它移除）「复制 Markdown / 复制富文本」仍可
     * 用。按 messageId 键控；分片注册时经 [rememberMessageMarkdown] 填入，
     * 选区存续期保留，[clearSelection] 清空。
     */
    // 快照态 map：一帧内 SideEffect 的写让下游读者（如工具条的
    // `md = resolveSelectionMarkdown()`）下一帧即失效——分片一公布源，
    // markdown 按钮立刻现身。
    private val messageMarkdownCache = mutableStateMapOf<String, String>()

    /** 分片属主调：公布父消息的拼接 markdown。 */
    fun rememberMessageMarkdown(messageId: String, markdown: String) {
        if (markdown.isEmpty()) return
        // 已正确则跳过——别每次重组都重发。
        if (messageMarkdownCache[messageId] == markdown) return
        messageMarkdownCache[messageId] = markdown
    }

    /** 活动选区父消息的 markdown。选区跨消息或缓存没见过时为 null。 */
    fun selectionMessageMarkdown(): String? {
        return messageMarkdownCache[singleMessageId() ?: return null]
    }

    // ─── 表格动作（供单一选区工具条） ──────────────────────────────────────

    /**
     * [T-android-markdown-table-copy-actions] 当前选中消息的表格动作——单一
     * 选区工具条可以把「复制表格 / 复制表格图像」排在 复制 / 复制 Markdown
     * 等旁边（而不是表格自己再弹第二个浮层）。[RenderTable] 在组合期间按
     * messageId 公布它的 [TableActions]；工具条为选中消息读取。
     *
     * [copyTableMarkdown] 返回表格的精确 markdown 源；[copyTableImage] 捕
     * 获渲染表格位图并进剪贴板。
     */
    class TableActions(
        val copyTableMarkdown: () -> Unit,
        val copyTableImage: () -> Unit,
    )

    private val tableActionsByMessage = mutableStateMapOf<String, TableActions>()

    /** 公布某消息的表格动作（组合期间调）。 */
    fun rememberTableActions(messageId: String, actions: TableActions) {
        tableActionsByMessage[messageId] = actions
    }

    /** 表格离开组合时撤下。 */
    fun forgetTableActions(messageId: String) {
        tableActionsByMessage.remove(messageId)
    }

    /** 单消息选区下该消息的表格动作；无选区/跨消息/无表格组合时 null。 */
    fun selectionTableActions(): TableActions? {
        return tableActionsByMessage[singleMessageId() ?: return null]
    }

    // ─── 分片登记表 ────────────────────────────────────────────────────────

    /**
     * 当前已组合的分片，按稳定 id 键控。分片进组合时注册
     * （DisposableEffect），销毁时注销。
     */
    private val shards = mutableStateMapOf<TextShardId, TextShard>()

    /** 当前已组合分片的只读快照（内部 + isShardBetween 扩展读）。 */
    internal fun currentShards(): Map<TextShardId, TextShard> = shards

    fun register(shard: TextShard) {
        shards[shard.id] = shard
    }

    fun unregister(id: TextShardId) {
        shards.remove(id)
    }

    /** 当前已组合分片的只读快照。 */
    private fun shardWindowRect(shard: TextShard): Rect {
        val origin = shard.positionInWindow()
        val size = shard.sizePx()
        return Rect(origin, Size(size.width.toFloat(), size.height.toFloat()))
    }

    // ─── 命中测试 ──────────────────────────────────────────────────────────

    /**
     * 对全部已组合分片做窗口坐标点命中。返回离该点最近的字符的
     * [TextPosition]；点不在任何注册分片上为 null。
     */
    fun hitTest(windowPoint: Offset): TextPosition? {
        val (shard, localPoint) = locateShard(windowPoint) ?: return null
        val charOffset = shard.textLayoutResult.getOffsetForPosition(localPoint)
            .coerceIn(0, shard.plainText.length)
        return TextPosition(shard.id, charOffset)
    }

    /**
     * 严格命中：点**直接落在**某注册分片矩形内才返回位置——不做最近分片回
     * 落。长按路径用：按在不可选区（如用户消息气泡——它刻意不注册
     * MinisTextKit 分片、好弹自己的长按菜单）不该吸附到恰好最近的某个助
     * 手分片上。
     */
    fun hitTestStrict(windowPoint: Offset): TextPosition? {
        for (shard in shards.values) {
            if (shard.positionInWindow() == Offset.Zero) continue
            if (!shardWindowRect(shard).contains(windowPoint)) continue
            val local = windowPoint - shard.positionInWindow()
            val charOffset = shard.textLayoutResult.getOffsetForPosition(local)
                .coerceIn(0, shard.plainText.length)
            return TextPosition(shard.id, charOffset)
        }
        return null
    }

    /** 找罩住 [windowPoint] 的注册分片及对应局部坐标点；无直接命中时给垂
     *  直最近的分片（拖出分片侧边仍能合理延伸）。 */
    private fun locateShard(windowPoint: Offset): Pair<TextShard, Offset>? {
        var nearest: Pair<TextShard, Offset>? = null
        for (shard in shards.values) {
            val origin = shard.positionInWindow()
            val rect = shardWindowRect(shard)
            if (rect.contains(windowPoint)) {
                return shard to (windowPoint - origin)
            }
            // 记住垂直最近的分片作回落。
            val clampedX = windowPoint.x.coerceIn(rect.left, rect.right.coerceAtLeast(rect.left))
            val clampedY = windowPoint.y.coerceIn(rect.top, rect.bottom.coerceAtLeast(rect.top))
            val candidate = shard to Offset(clampedX - origin.x, clampedY - origin.y)
            val incumbent = nearest
            if (incumbent == null) {
                nearest = candidate
            } else {
                val incumbentCenterY = incumbent.first.positionInWindow().y + incumbent.first.sizePx().height / 2f
                val candidateCenterY = origin.y + shard.sizePx().height / 2f
                if (kotlin.math.abs(candidateCenterY - windowPoint.y) <
                    kotlin.math.abs(incumbentCenterY - windowPoint.y)
                ) {
                    nearest = candidate
                }
            }
        }
        return nearest
    }

    // ─── 手柄锚点与几何 ────────────────────────────────────────────────────

    /**
     * 画某端点小「手柄」旋钮的窗口坐标点；该端点分片当前未组合时 null。
     * 点取端点字符偏移所在光标行的行底——首手柄在首个选中字符**左**侧、尾
     * 手柄在末个选中字符**右**侧（对齐 iOS / Android 原生选择手柄）。
     */
    fun handleAnchor(handle: Handle): Offset? {
        val sel = selection.value ?: return null
        val (first, last) = orderedEndpoints(sel) ?: return null
        val endpoint = when (handle) {
            Handle.Start -> first
            Handle.End -> last
        }
        val shard = shards[endpoint.shard] ?: return null
        // 分片已注册但 LayoutCoordinates 已分离（行滚出视野、可组合件还没
        // dispose）时 positionInWindow 回落 Offset.Zero——那会把手柄 Popup
        // 摔到窗口左上角。改回 null，宿主先藏手柄等分片重挂——对齐系统选
        // 择 UX。
        val origin = shard.positionInWindow().takeIf { it != Offset.Zero } ?: return null
        val tlr = shard.textLayoutResult
        val laidOutLen = tlr.layoutInput.text.length
        if (laidOutLen <= 0) return null
        val box = runCatching {
            tlr.getBoundingBox(anchorCharIndex(handle, endpoint.charOffset.coerceIn(0, laidOutLen), laidOutLen))
        }.getOrNull() ?: return null
        val anchorPoint = when (handle) {
            Handle.Start -> Offset(origin.x + box.left, origin.y + box.bottom)
            Handle.End -> Offset(origin.x + box.right, origin.y + box.bottom)
        }
        return anchorPoint
    }

    /** 手柄锚对应的字符下标：Start 取偏移处字符、End 取前一字符（右缘）。 */
    private fun anchorCharIndex(handle: Handle, clampedOffset: Int, laidOutLen: Int): Int =
        when (handle) {
            Handle.Start -> clampedOffset.coerceAtMost(laidOutLen - 1).coerceAtLeast(0)
            Handle.End -> (clampedOffset - 1).coerceIn(0, laidOutLen - 1)
        }

    /**
     * 窗口点 [p] 落在任一手柄旋钮的可视命中区内时，返回命中的手柄。旋钮
     * 挂在端点所在文本行**下方**（anchor.y 是光标行行底），故命中区取
     *   x ∈ [anchor.x − hitSlopPx, anchor.x + hitSlopPx]
     *   y ∈ [anchor.y − 4, anchor.y + hitSlopPx]
     * 不对称的竖向盒把「长按后点选区下方滚动」与「抓手柄」区分开——后者
     * 正是「一滚选区就漂移」缺陷的成因。
     */
    fun grabHandleAt(p: Offset, hitSlopPx: Float): Handle? {
        val startDist = distanceToHandleAnchor(handleAnchor(Handle.Start), p, hitSlopPx)
        val endDist = distanceToHandleAnchor(handleAnchor(Handle.End), p, hitSlopPx)
        // 慷慨的圆形命中区，圆心略低于各锚点（可见圆点挂在 anchor.y 报告的
        // 文字基线下约 hitSlopPx 处）。hitSlopPx 调用点默认 48 dp——拇指
        // 指腹量级的宽容。
        val nearest = if (startDist <= endDist) Handle.Start to startDist else Handle.End to endDist
        return if (nearest.second <= hitSlopPx * 1.5f) nearest.first else null
    }

    /** 圆心向下偏 hitSlopPx/2（点在圆点可视位置上也算命中）。 */
    private fun distanceToHandleAnchor(anchor: Offset?, p: Offset, hitSlopPx: Float): Float {
        if (anchor == null) return Float.MAX_VALUE
        val dx = p.x - anchor.x
        val dy = p.y - (anchor.y + hitSlopPx / 2f)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * 两手柄之间的窗口横向中点——工具条用它把自己「居中在两柄之间」，而
     * 不是高亮几何中心上方（多行选区的中心落在段落中间）。两端都未注册
     * 时 null——调用方保自己的默认。
     */
    fun handlesCenterX(): Float? {
        val s = handleAnchor(Handle.Start)
        val e = handleAnchor(Handle.End)
        return when {
            s != null && e != null -> (s.x + e.x) / 2f
            s != null -> s.x
            e != null -> e.x
            else -> null
        }
    }

    // ─── 选区矩形 ──────────────────────────────────────────────────────────

    /**
     * 活动选区的联合包围矩形（窗口坐标），只走已注册分片。浮动工具条定位
     * 用。无选区、或选区分片全未组合时 null。
     */
    fun selectionWindowRect(): Rect? {
        val sel = selection.value ?: return null
        val (first, last) = orderedEndpoints(sel) ?: return null
        val firstShard = shards[first.shard] ?: return null
        val lastShard = shards[last.shard] ?: return null
        val firstBox = firstShard.textLayoutResult.getBoundingBox(
            first.charOffset.coerceIn(0, firstShard.textLayoutResult.layoutInput.text.length - 1)
                .coerceAtLeast(0),
        )
        val lastBox = lastShard.textLayoutResult.getBoundingBox(
            last.charOffset.coerceIn(0, lastShard.textLayoutResult.layoutInput.text.length - 1)
                .coerceAtLeast(0),
        )
        val topLeft = firstShard.positionInWindow() + Offset(firstBox.left, firstBox.top)
        val bottomRight = lastShard.positionInWindow() + Offset(lastBox.right, lastBox.bottom)
        return Rect(topLeft, bottomRight)
    }

    /**
     * 特定手柄端点所在文本行的窗口矩形——手柄拖动期间浮动工具条跟着用户
     * 在动的那只手柄走。手柄分片未注册（出屏）时 null。尺寸取端点字符的
     * 行高而非整分片——工具条贴住实际移动的那一行，不是整段。
     */
    fun draggedHandleLineRect(handle: Handle): Rect? {
        val sel = selection.value ?: return null
        // Handle 枚举对应选区按文档序的**可视首/尾**，不是 selection.start
        // vs selection.end（那两个取决于用户先抓哪边）。用有序对，「拖右手
        // 柄」永远跟右/下端点，无论它存在 selection.start 还是 end。
        val ordered = orderedEndpoints(sel) ?: return null
        val endpoint = when (handle) {
            Handle.Start -> ordered.first
            Handle.End -> ordered.second
        }
        val shard = shards[endpoint.shard] ?: return null
        val origin = shard.positionInWindow()
        if (origin == Offset.Zero) return null
        val tlr = shard.textLayoutResult
        val len = tlr.layoutInput.text.length
        if (len <= 0) return null
        val box = runCatching {
            tlr.getBoundingBox(anchorCharIndex(handle, endpoint.charOffset.coerceIn(0, len), len))
        }.getOrNull() ?: return null
        return windowRectOf(origin, box)
    }

    /**
     * 活动选区**末条可见行**的窗口矩形——含尾手柄的那行（尾手柄分片出屏
     * 时，取选区内最低仍可见分片的底行）。工具条锚在选区**末尾**上方——
     * 菜单与尾手柄扎堆，而不是飘到首手柄（可能隔着很多行）旁边。
     */
    fun visibleSelectionEndLineRect(): Rect? {
        val sel = selection.value ?: return null
        val (first, last) = orderedEndpoints(sel) ?: return null

        // 尾分片仍组合且在屏：直接取它的行。
        val lastShard = shards[last.shard]
        val lastOrigin = lastShard?.positionInWindow()
        if (lastShard != null && lastOrigin != Offset.Zero) {
            val tlr = lastShard.textLayoutResult
            val len = tlr.layoutInput.text.length
            val charIdx = (last.charOffset - 1).coerceIn(0, (len - 1).coerceAtLeast(0))
            val box = if (len > 0) runCatching { tlr.getBoundingBox(charIdx) }.getOrNull() else null
            if (box != null && lastOrigin != null) {
                return windowRectOf(lastOrigin, box)
            }
        }

        // 回落：尾手柄分片已出屏。找选区内仍在组合的最**底**分片——用户看
        // 到的高亮「尾巴」。
        var tail: TextShard? = null
        var tailBottom = Float.NEGATIVE_INFINITY
        for ((id, shard) in shards) {
            val origin = shard.positionInWindow()
            val visible = origin != Offset.Zero && shardParticipatesIn(id, first, last)
            if (!visible) continue
            val bottom = origin.y + shard.sizePx().height
            if (bottom <= tailBottom) continue
            tailBottom = bottom
            tail = shard
        }
        val tailShard = tail ?: return null
        val tlr = tailShard.textLayoutResult
        if (tlr.layoutInput.text.length <= 0) return null
        // 该分片**末字符**所在行作可视行。
        val lastLine = tlr.lineCount - 1
        if (lastLine < 0) return null
        val lineTop = runCatching { tlr.getLineTop(lastLine) }.getOrNull() ?: return null
        val lineBottom = runCatching { tlr.getLineBottom(lastLine) }.getOrNull() ?: return null
        val origin = tailShard.positionInWindow()
        // 用分片体宽近似该行的可视 x 范围。
        return Rect(
            left = origin.x,
            top = origin.y + lineTop,
            right = origin.x + tailShard.sizePx().width,
            bottom = origin.y + lineBottom,
        )
    }

    /**
     * 活动选区在全部已组合分片上**可见部分**的联合。返回此刻屏上实际画
     * 出来的包围矩形（窗口坐标）——任一端点出屏时它可能是完整逻辑选区的
     * 子集。工具条跟可见部分走，而不是端点一出视野就消失。无可绘制部分
     * （两端点与中间分片全出屏）时 null——调用方回落固定锚位。
     */
    fun visibleSelectionWindowRect(): Rect? {
        val sel = selection.value ?: return null
        val (first, last) = orderedEndpoints(sel) ?: return null
        var minL = Float.POSITIVE_INFINITY
        var minT = Float.POSITIVE_INFINITY
        var maxR = Float.NEGATIVE_INFINITY
        var maxB = Float.NEGATIVE_INFINITY
        for ((id, shard) in shards) {
            if (!shardParticipatesIn(id, first, last)) continue
            val tlr = shard.textLayoutResult
            val laidOutLen = tlr.layoutInput.text.length
            if (laidOutLen <= 0) continue
            val from = if (id == first.shard) first.charOffset.coerceIn(0, laidOutLen) else 0
            val to = if (id == last.shard) last.charOffset.coerceIn(0, laidOutLen) else laidOutLen
            val lo = minOf(from, to)
            val hi = maxOf(from, to)
            // positionInWindow 在 LayoutCoordinates 分离后回落
            // Offset.Zero——跳过那些，反正也画不出来。
            val origin = if (hi > lo) shard.positionInWindow() else Offset.Zero
            if (origin == Offset.Zero) continue
            val startBox = runCatching {
                tlr.getBoundingBox(lo.coerceAtMost(laidOutLen - 1).coerceAtLeast(0))
            }.getOrNull() ?: continue
            val endBox = runCatching {
                tlr.getBoundingBox((hi - 1).coerceIn(0, laidOutLen - 1))
            }.getOrNull() ?: continue
            // 两盒取并——盖住选区在本分片内跨多行的情形。
            val left = origin.x + minOf(startBox.left, endBox.left)
            val top = origin.y + minOf(startBox.top, endBox.top)
            val right = origin.x + maxOf(startBox.right, endBox.right)
            val bottom = origin.y + maxOf(startBox.bottom, endBox.bottom)
            if (left < minL) minL = left
            if (top < minT) minT = top
            if (right > maxR) maxR = right
            if (bottom > maxB) maxB = bottom
        }
        if (!minL.isFinite() || !maxR.isFinite() || maxR <= minL || maxB <= minT) return null
        return Rect(minL, minT, maxR, maxB)
    }

    /** 分片 id 是否参与该选区（是端点、或在两端点之间）。 */
    private fun shardParticipatesIn(id: TextShardId, first: TextPosition, last: TextPosition): Boolean {
        val isEndpoint = id == first.shard || id == last.shard
        return isEndpoint || isShardBetween(first.shard, last.shard, id)
    }

    private fun windowRectOf(origin: Offset, box: androidx.compose.ui.geometry.Rect): Rect = Rect(
        left = origin.x + box.left,
        top = origin.y + box.top,
        right = origin.x + box.right,
        bottom = origin.y + box.bottom,
    )

    // ─── 端点排序 ──────────────────────────────────────────────────────────

    /**
     * 按**可视**位置返回（首端点, 尾端点）——调用方无从得知用户先抓哪个手
     * 柄，也能左到右/上到下遍历。要求两端点分片都已注册（否则没有窗口 y
     * 可比）。
     */
    fun orderedEndpoints(sel: TextSelection): Pair<TextPosition, TextPosition>? {
        val a = sel.start
        val b = sel.end
        if (a.shard == b.shard) {
            return if (a.charOffset <= b.charOffset) a to b else b to a
        }
        // 两端点都有文档序键时优先用它——分片出屏/未注册也能比。键拿不到
        // 再回落 y 比较。
        val keyA = shardOrderKey(a.shard)
        val keyB = shardOrderKey(b.shard)
        if (keyA != null && keyB != null && a.shard.messageId == b.shard.messageId) {
            return if (keyA <= keyB) a to b else b to a
        }
        val shardA = shards[a.shard] ?: return null
        val shardB = shards[b.shard] ?: return null
        val yA = shardA.positionInWindow().y
        val yB = shardB.positionInWindow().y
        return if (yA <= yB) a to b else b to a
    }

    // ─── 选中文本提取 ──────────────────────────────────────────────────────

    /**
     * 构建活动选区覆盖的纯文本。未组合的分片经 [TextShard.plainText] 查阅
     * 仍计入——但跨消息选区只能含至少见过一次的分片（正常拖拽路径上，选
     * 区里的每个分片被拖过时都注册过）。
     */
    fun selectedPlainText(documentRegistry: Map<TextShardId, String> = emptyMap()): String {
        val sel = selection.value ?: return ""

        // [T-android-copy-selection-not-whole-message] orderedEndpoints() 仅在
        // 端点分片被回收出屏**且**无文档序键时（长拖）返回 null。此时回落
        // 为对仍注册分片的精确逐片走查——**不是**整条缓存消息。（此前的
        // [T-android-copy-long-reply-incomplete] 修复在这里和下面跨片分支
        // 返回了整条消息 markdown——多分片部分选择粘出整条回复正是那个
        // 原因。）走查也空（真退化的单分片回收）时保原折叠子串。
        val ordered = orderedEndpoints(sel) ?: run {
            val walked = crossShardSelectedText(sel.start, sel.end, documentRegistry)
            return walked.ifEmpty { collapsedFallback(sel) }
        }
        val (first, last) = ordered

        // 单分片选择——plainText 的精确子串。刻意**不走**消息缓存：单分片
        // 内的选择是普通的短选/半段复制，必须是精确子串。
        if (first.shard == last.shard) {
            val txt = shards[first.shard]?.plainText
                ?: documentRegistry[first.shard]
                ?: return ""
            val a = first.charOffset.coerceIn(0, txt.length)
            val b = last.charOffset.coerceIn(0, txt.length)
            return txt.substring(minOf(a, b), maxOf(a, b))
        }

        return crossShardSelectedText(first, last, documentRegistry)
    }

    /**
     * [T-android-copy-selection-not-whole-message] 构建跨分片选区覆盖的文
     * 本——以选区为界，绝不整条消息。
     *
     * 主路：对已注册 MdText 分片的逐片走查、两端切片。常见情形（选区跨段
     * 落/标题/列表项——全是分片）下精确。
     *
     * 走查唯一的盲区是非分片块（代码围栏、表格、展示数学）——它们经自己的
     * Text 可组合件渲染、不在分片登记表里。要不倾倒整条回复地找回它们，
     * 就把缓存消息 markdown 中严格介于首选中分片文本与末选中分片文本之间
     * 的片段拼接进来——且仅在真有此空档时。markdown 锚找不到就保留精确的
     * 走查结果，不回落整条消息。
     */
    private fun crossShardSelectedText(
        first: TextPosition,
        last: TextPosition,
        documentRegistry: Map<TextShardId, String>,
    ): String {
        val collected = StringBuilder()
        var started = false
        for (id in registeredShardsInOrder()) {
            val txt = shards[id]?.plainText ?: documentRegistry[id] ?: continue
            when (id) {
                first.shard -> {
                    collected.append(txt.substring(first.charOffset.coerceIn(0, txt.length), txt.length))
                    started = true
                }
                last.shard -> {
                    if (started) collected.append('\n')
                    collected.append(txt.substring(0, last.charOffset.coerceIn(0, txt.length)))
                    break
                }
                else -> if (started) collected.append('\n').append(txt)
            }
        }
        val walk = collected.toString()

        // 同消息选择：试着用缓存在选区内 markdown 的头尾锚切片找回非分片
        // 块（代码/表格）。跨消息没有单一源串，走查即终稿。
        if (first.shard.messageId == last.shard.messageId) {
            spliceNonShardSpan(first, last, walk, documentRegistry)?.let { return it }
        }
        return walk
    }

    /**
     * [T-android-copy-selection-not-whole-message] 同消息跨分片选区横跨非分
     * 片块（代码/表格/数学）时，返回以选区头尾锚为界的缓存消息 markdown 切
     * 片。无缓存、无可用锚、或选中段内没有超出走查文本的额外（非分片）内
     * 容时返回 null——调用方保留精确走查。
     */
    private fun spliceNonShardSpan(
        first: TextPosition,
        last: TextPosition,
        walk: String,
        documentRegistry: Map<TextShardId, String>,
    ): String? {
        val md = messageMarkdownCache[first.shard.messageId]?.takeIf { it.isNotEmpty() } ?: return null

        val firstText = shards[first.shard]?.plainText ?: documentRegistry[first.shard] ?: return null
        val lastText = shards[last.shard]?.plainText ?: documentRegistry[last.shard] ?: return null

        // 头锚：起始偏移之后一段选中文本；尾锚：结束偏移之前一段。
        val headSel = firstText.substring(first.charOffset.coerceIn(0, firstText.length))
        val tailSel = lastText.substring(0, last.charOffset.coerceIn(0, lastText.length))

        val headAnchor = distinctiveAnchor(headSel, fromStart = true)
        val tailAnchor = distinctiveAnchor(tailSel, fromStart = false)
        if (headAnchor.isEmpty() || tailAnchor.isEmpty()) return null

        val sliceStart = md.indexOf(headAnchor)
        if (sliceStart < 0) return null
        val anchorTail = md.indexOf(tailAnchor, sliceStart + headAnchor.length)
        if (anchorTail < 0) return null
        val sliceEnd = anchorTail + tailAnchor.length
        if (sliceEnd <= sliceStart) return null

        val slice = md.substring(sliceStart, sliceEnd)
        // 只有选中段确实**横跨非分片块**时才偏好 markdown 切片——围栏代码
        // （``` / ~~~）、表格（竖线行）或展示数学（$$）。那些经自己的 Text
        // 可组合件渲染（无 MinisTextKit 分片），逐片走查会丢掉它们，切片是
        // 唯一找回途径。纯段落/标题/列表项（全是分片）的连续段里走查已精
        // 确且无格式符——此时返回切片反而把用户没选的 `#`、`**` 塞回去。
        return if (sliceCrossesNonShardBlock(slice)) slice else null
    }

    /**
     * [T-android-copy-selection-not-whole-message] 启发式：原始 markdown 切
     * 片里有没有**不注册**为 MinisTextKit 文本分片的块（围栏代码、表格、
     * 展示数学）？用来判定 markdown 切片是否携带逐片走查会丢的内容。
     */
    private fun sliceCrossesNonShardBlock(slice: String): Boolean {
        if (slice.contains("```") || slice.contains("~~~")) return true
        if (slice.contains("$$")) return true
        // markdown 表格要表头行加分隔行（`|---|---|`）。认分隔行形态，散
        // 文里孤立的行内 `|` 不误报。
        val tableDelimiter = Regex("""(?m)^\s*\|?\s*:?-{3,}.*\|""")
        return tableDelimiter.containsMatchIn(slice)
    }

    /**
     * 从选中跑段的一端挑一个短而独特的锚子串，供在原始 markdown 中定位。
     * 修边空白、限长——匹配便宜、也对深处行内格式有韧性。无可用锚返回 ""。
     */
    private fun distinctiveAnchor(selected: String, fromStart: Boolean): String {
        val trimmed = selected.trim()
        if (trimmed.isEmpty()) return ""
        val cap = 24
        return when {
            trimmed.length <= cap -> trimmed
            fromStart -> trimmed.substring(0, cap)
            else -> trimmed.substring(trimmed.length - cap)
        }
    }

    private fun collapsedFallback(sel: TextSelection): String {
        val txt = shards[sel.start.shard]?.plainText ?: return ""
        val a = sel.start.charOffset.coerceIn(0, txt.length)
        val b = sel.end.charOffset.coerceIn(0, txt.length)
        return txt.substring(minOf(a, b), maxOf(a, b))
    }

    /** 当前已组合分片，按可视自上而下排序。 */
    private fun registeredShardsInOrder(): List<TextShardId> =
        shards.values
            .sortedBy { it.positionInWindow().y }
            .map { it.id }
}

/**
 * 向后代暴露活动 [SelectionController] 的 CompositionLocal。默认给个空控
 * 制器让调用方可以装作它总在——没挂真控制器的分片只是永不参与任何选择。
 * 在 ChatScreen 层用 [ProvideSelectionController] 包住。
 */
val LocalMinisSelectionController = compositionLocalOf<SelectionController?> { null }

@Composable
fun ProvideSelectionController(
    controller: SelectionController,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMinisSelectionController provides controller) {
        content()
    }
}

/**
 * 分片属主的助手：在调用点生命周期内把分片注册给环境控制器（若有），
 * dispose 时注销。
 *
 * 每次都传**当前最新**的 [TextShard]——助手按同 id 持有该引用、每次重组
 * 替换，窗口位置回调和 TextLayoutResult 始终反映最新测量。
 */
@Composable
fun RegisterSelectionShard(shard: TextShard?) {
    val controller = LocalMinisSelectionController.current ?: return
    if (shard == null) return
    DisposableEffect(controller, shard.id, shard) {
        controller.register(shard)
        onDispose { controller.unregister(shard.id) }
    }
}

/**
 * 在单个分片的 DrawScope 里画活动选区的高亮矩形。在以支撑该分片注册的
 * [TextLayoutResult] 的同一节点的 `Modifier.drawBehind` 里调——高亮画在分
 * 片局部坐标系，且只画落在本分片内的那部分选区。
 */
fun DrawScope.drawSelectionForShard(
    shardId: TextShardId,
    result: TextLayoutResult,
    selection: TextSelection,
    controller: SelectionController?,
    color: Color,
) {
    val laidOutText = result.layoutInput.text.text
    val maxOffset = laidOutText.length
    val lineCount = result.lineCount
    if (maxOffset == 0 || lineCount == 0) return

    val ordered = controller?.orderedEndpoints(selection) ?: run {
        val a = selection.start
        val b = selection.end
        if (a.shard == b.shard && a.charOffset <= b.charOffset) a to b else b to a
    }
    val (first, last) = ordered

    // 定本分片内的 [from, to] 字符区间。
    val isFirstHere = first.shard == shardId
    val isLastHere = last.shard == shardId
    if (!isFirstHere && !isLastHere) {
        // 本分片位于两端点**之间**——只有选区真的横穿我们才包含。经控制
        // 器的已注册分片窗口 y 快照判序。
        if (controller == null) return
        if (!controller.isShardBetween(first.shard, last.shard, shardId)) return
    }

    val from = if (isFirstHere) first.charOffset.coerceIn(0, maxOffset) else 0
    val to = if (isLastHere) last.charOffset.coerceIn(0, maxOffset) else maxOffset
    val lo = minOf(from, to)
    val hi = maxOf(from, to)
    if (hi <= lo) return

    val startLine = result.getLineForOffset(lo).coerceIn(0, lineCount - 1)
    val endLine = result.getLineForOffset((hi - 1).coerceAtLeast(0)).coerceIn(0, lineCount - 1)
    if (endLine < startLine) return

    for (line in startLine..endLine) {
        val lineStart = if (line == startLine) lo else result.getLineStart(line)
        val lineEndRaw = if (line == endLine) hi else result.getLineEnd(line)
        val lineEnd = lineEndRaw.coerceAtMost(maxOffset)
        if (lineEnd <= lineStart) continue
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        for (offset in lineStart until lineEnd) {
            val box = result.getBoundingBox(offset)
            if (box.width <= 0f) continue
            if (box.left < left) left = box.left
            if (box.right > right) right = box.right
        }
        if (!left.isFinite() || right <= left) continue
        // 若这是本分片内选区的末条可视行、而我们**不含**最终端点，把高亮
        // 延到行右缘——跨分片选择才有连续感。
        val finalLineHere = line == endLine && isLastHere
        val drawRight = if (!finalLineHere) maxOf(right, result.getLineRight(line)) else right
        val top = result.getLineTop(line)
        val bottom = result.getLineBottom(line)
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(drawRight - left, bottom - top),
        )
    }
}

/**
 * 尽力的文档序键：从 shardId 串尾的整数提取。不可解析为 null。用途：某个
 * 分片当前未注册（滚出屏、被 LazyColumn dispose）时也能排序。
 *
 * ChatScreen.kt 的约定，shard id 形如：
 *   "mdblock:<parentBlockId>:<blockIndex>"   ← splitMarkdownIntoBlockTexts
 *   "text:<blockId>"                          ← 单 AssistantText 块
 *   "legacy"                                  ← 整消息 legacy 路径
 * 今天只有 mdblock 需要排序；其余是单分片，比较永远不必消歧。
 */
private fun shardOrderKey(id: TextShardId): Int? {
    val s = id.shardId
    val lastColon = s.lastIndexOf(':')
    if (lastColon < 0) return null
    return s.substring(lastColon + 1).toIntOrNull()
}

/**
 * 分片 [middle] 是否（按可视自上而下序）位于 [a] 与 [b] 之间——按当前注册
 * 位置。高亮代码用它判定：没有选区端点在内的分片是否仍该填充（它处在多
 * 分片区间的中间）。
 */
internal fun SelectionController.isShardBetween(
    a: TextShardId,
    b: TextShardId,
    middle: TextShardId,
): Boolean {
    // 先试 shardId 串里烤进的文档序索引——mdblock:<parent>:<index> /
    // text:<blockId>。数字索引是稳定的文档位置，对 a/b 出屏免疫（出屏会使
    // 它们的 TextShard 未注册、下面的 y 比较摆不了位）。先比 messageId 轴，
    // 跨消息选择也摆得对：M1 的分片只有在两端点在已注册分片可视序上跨过
    // M1 时才「居中」。
    val orderA = shardOrderKey(a)
    val orderB = shardOrderKey(b)
    val orderM = shardOrderKey(middle)
    if (orderA != null && orderB != null && orderM != null &&
        a.messageId == b.messageId && middle.messageId == a.messageId
    ) {
        return orderM in minOf(orderA, orderB)..maxOf(orderA, orderB)
    }
    // 索引解析失败或选区跨消息：回落 y 比较。
    val registry = currentShards()
    val shardA = registry[a] ?: return false
    val shardB = registry[b] ?: return false
    val shardM = registry[middle] ?: return false
    val yA = shardA.positionInWindow().y
    val yB = shardB.positionInWindow().y
    val yM = shardM.positionInWindow().y
    return yM in minOf(yA, yB)..maxOf(yA, yB)
}

/**
 * 手里已有 `onGloballyPositioned` 修饰符给的 [LayoutCoordinates] 的调用方
 * 的便捷件——包成控制器要的 `positionInWindow` 与 `sizePx` 闭包。
 */
fun buildTextShard(
    id: TextShardId,
    plainText: String,
    layoutResult: TextLayoutResult,
    coordinatesProvider: () -> LayoutCoordinates?,
    rawMarkdown: String? = null,
    renderedToRawOffset: ((Int) -> Int)? = null,
    isAtomicUnit: Boolean = false,
): TextShard = TextShard(
    id = id,
    plainText = plainText,
    textLayoutResult = layoutResult,
    isAtomicUnit = isAtomicUnit,
    positionInWindow = {
        val coords = coordinatesProvider()
        if (coords != null && coords.isAttached) coords.positionInWindow() else Offset.Zero
    },
    sizePx = {
        val coords = coordinatesProvider()
        if (coords != null && coords.isAttached) {
            IntSize(coords.size.width, coords.size.height)
        } else {
            IntSize.Zero
        }
    },
    renderedToRawOffset = renderedToRawOffset,
    rawMarkdown = rawMarkdown,
)
