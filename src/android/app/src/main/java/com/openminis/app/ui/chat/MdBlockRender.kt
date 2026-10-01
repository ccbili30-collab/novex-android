package com.openminis.app.ui.chat

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.openminis.app.R
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay
import novex.android.ui.Checkbox
import novex.android.ui.NovexIcons

// 块渲染器：RenderBlock 分派到各 MdBlock 种类的呈现 + KaTeX 行内/展示数学
// 渲染（经共享 WebView 池出位图）+ 破图占位。

// ─── Block renderers ────────────────────────────────────────────────────────

@Composable
internal fun RenderBlock(block: MdBlock) {
    val colors = currentMdColors()
    // [T-android-streaming-incremental-inline] The live streaming tail block
    // re-parses its growing paragraph every throttle tick; route it through the
    // incremental cache (frozen closed prefix + fresh suffix). Frozen/history
    // blocks (false) keep the plain per-block cache — no behavior change there.
    val liveIncremental = LocalLiveIncremental.current
    when (block) {
        is MdBlock.Paragraph -> {
            MdText(
                text = if (liveIncremental) MarkdownParseCaches.inlineIncremental(block.raw, colors)
                       else MarkdownParseCaches.inline(block.raw, colors),
                fontSize = BaseFontSize,
                lineHeight = BaseLineHeight,
                color = colors.text,
                modifier = Modifier.padding(bottom = 4.dp),
                inlineContent = rememberKatexInlineContent(
                    BaseFontSize,
                    if (liveIncremental) MarkdownParseCaches.mathLatexIncremental(block.raw)
                    else MarkdownParseCaches.mathLatex(block.raw),
                ),
            )
        }

        is MdBlock.Heading -> {
            val (size, weight) = when (block.level) {
                1 -> (BaseFontSize * 1.5f) to FontWeight.Bold
                2 -> (BaseFontSize * 1.3f) to FontWeight.Bold
                3 -> (BaseFontSize * 1.15f) to FontWeight.SemiBold
                4 -> BaseFontSize to FontWeight.SemiBold
                5 -> (BaseFontSize * 0.875f) to FontWeight.SemiBold
                else -> (BaseFontSize * 0.85f) to FontWeight.SemiBold
            }
            MdText(
                text = MarkdownParseCaches.inline(block.text, colors),
                fontSize = size,
                fontWeight = weight,
                lineHeight = size * 1.3f,
                color = colors.text,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                inlineContent = rememberKatexInlineContent(size, MarkdownParseCaches.mathLatex(block.text)),
            )
        }

        is MdBlock.CodeBlock -> {
            val clipboardManager = LocalClipboardManager.current
            var copied by remember { mutableStateOf(false) }
            if (copied) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1500)
                    copied = false
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.codeBg),
            ) {
                // Header row: language label + copy button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = block.language.ifEmpty { "code" },
                        fontSize = 11.sp,
                        color = MdCodeLangColor,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (copied) novex.android.ui.NovexIcons.Check else novex.android.ui.NovexIcons.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy code",
                        tint = if (copied) Color(0xFF34C759) else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(block.code))
                                copied = true
                            },
                    )
                }
                // iOS parity (SelectableMarkdownView.swift L971): cap visual
                // code-block height at ~400 pt and let an internal scroll
                // view handle overflow vertically, so a 200-line dump
                // doesn't push the rest of the message off the bottom of
                // the chat. Nest scrolls: inner Row owns horizontal scroll
                // (long lines), outer Box owns vertical scroll + height
                // cap (long blocks). Compose disallows two scroll modifiers
                // on the same node, hence the nesting.
                val vScroll = rememberScrollState()
                val hScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(vScroll)
                        .padding(bottom = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .horizontalScroll(hScroll)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = block.code,
                            fontSize = BaseFontSize * 0.85f,
                            fontFamily = colors.codeFont,
                            color = colors.codeText,
                            lineHeight = BaseLineHeight * 0.9f,
                        )
                    }
                }
            }
        }

        is MdBlock.BlockQuote -> {
            // T307: previous IntrinsicSize.Min approach crashes when inner
            // blocks contain SubcomposeLayout (tables, images, etc.) — Compose
            // refuses intrinsic measurement on those. Draw the orange rule
            // directly behind a single Column so layout never queries
            // intrinsics.
            val barColor = Color(0xFFFF9500)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .drawBehind {
                        drawRect(
                            color = barColor,
                            topLeft = Offset.Zero,
                            size = Size(3.dp.toPx(), size.height),
                        )
                    }
                    .padding(start = 15.dp),
            ) {
                block.innerBlocks.forEach { inner -> RenderBlock(inner) }
            }
        }

        is MdBlock.UnorderedList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEach { item ->
                    Row(modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)) {
                        Text("•  ", fontSize = BaseFontSize * 1.3f, color = colors.text)
                        MdText(
                            text = MarkdownParseCaches.inline(item.text, colors),
                            fontSize = BaseFontSize,
                            lineHeight = BaseLineHeight,
                            color = colors.text,
                            modifier = Modifier.weight(1f),
                            inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(item.text)),
                        )
                    }
                }
            }
        }

        is MdBlock.OrderedList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row(modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)) {
                        Text(
                            "${block.startNum + index}.  ",
                            fontSize = BaseFontSize,
                            color = colors.text,
                        )
                        MdText(
                            text = MarkdownParseCaches.inline(item.text, colors),
                            fontSize = BaseFontSize,
                            lineHeight = BaseLineHeight,
                            color = colors.text,
                            modifier = Modifier.weight(1f),
                            inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(item.text)),
                        )
                    }
                }
            }
        }

        is MdBlock.TaskList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEach { item ->
                    Row(
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = item.checked,
                            onCheckedChange = null,
                            modifier = Modifier.size(20.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.link,
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                        MdText(
                            text = MarkdownParseCaches.inline(item.text, colors),
                            fontSize = BaseFontSize,
                            lineHeight = BaseLineHeight,
                            color = if (item.checked) colors.text.copy(alpha = 0.5f) else colors.text,
                            modifier = Modifier.weight(1f),
                            inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(item.text)),
                        )
                    }
                }
            }
        }

        is MdBlock.HorizontalRule -> {
            HorizontalDivider(
                color = colors.divider,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        is MdBlock.Image -> {
            android.util.Log.d("MdStream", "render Image url=${block.url}")
            // [T-android-markdown-image-gallery-cross-message] Prefer the
            // image-specific handler when provided so the host can collect
            // every sibling image across the conversation and open a paged
            // gallery (mirrors iOS AIChatView.handleMarkdownImageTap). Fall
            // back to the generic URL handler — which routes a single-item
            // open via ChatLinkResolver — when the host hasn't supplied an
            // image handler (keeps the previous behaviour intact).
            val imageTapHandler = LocalMarkdownImageTapHandler.current
            val urlTapHandler = LocalMarkdownUrlClickHandler.current
            val ambientMessageId = LocalShardId.current?.messageId
            val onTap: (() -> Unit)? = when {
                imageTapHandler != null && ambientMessageId != null ->
                    { -> imageTapHandler(ambientMessageId, block.url) }
                urlTapHandler != null -> { -> urlTapHandler(block.url) }
                else -> null
            }
            val context = LocalContext.current
            val sessionId = LocalMarkdownSessionId.current
            // Resolve to a host File via the session-scoped resolver before
            // handing off to Coil. AsyncImage(model = "minis://...") routes
            // through MinisImageFetcher → ContentPaths.resolveHostPath, which
            // reads the *global* bindMounts map — last-writer-wins across
            // sessions. When another session booted its shell more recently,
            // that global lookup answers with the wrong session's path (or
            // null) and the image quietly renders as a 0-height placeholder.
            // The video/audio renderers already follow this pattern.
            val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
            // T146: 1dp hairline + 2dp soft shadow so a white-bg PNG (matplotlib
            // chart, screenshot…) reads as a discrete card against the chat
            // surface. Same ChatColors.thumbnailBorder / inputShadow recipe as
            // the attachment chip in T179 — keeps the visual rhythm consistent.
            // shadow → clip → border so the elevation paints behind the rounded
            // edge and the border stays crisp on top.
            val imageShape = RoundedCornerShape(8.dp)
            val imageBaseModifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .shadow(
                    elevation = 2.dp,
                    shape = imageShape,
                    clip = false,
                    ambientColor = ChatColors.inputShadow,
                    spotColor = ChatColors.inputShadow,
                )
                .clip(imageShape)
                .border(1.dp, ChatColors.thumbnailBorder, imageShape)
                .let { m -> if (onTap != null) m.clickable { onTap() } else m }
            // T148: SubcomposeAsyncImage so we can render a broken-image
            // placeholder when the underlying file is gone (deleted workspace
            // PNG, broken URL). Without this slot, Coil paints nothing and
            // the user sees a blank gap where a chart should be — easy to
            // mistake for a render bug.
            // [T-android-canvas-large-bitmap-crash] Cap the DECODE size.
            // Without an explicit request size, Coil sizes from the layout
            // constraints — but this column scrolls vertically, so the height
            // constraint is unbounded and Coil falls back to the image's
            // intrinsic size, decoding a very tall chart PNG at full
            // resolution. The resulting bitmap (215MB in the vivo/Android 16
            // report) exceeds RecordingCanvas's draw ceiling and crashes the
            // process from ThreadedRenderer.draw. Capping here means the
            // oversized bitmap is never allocated at all. FillWidth still
            // scales the (now bounded) bitmap to the column width, so normal
            // images render byte-identically to before.
            // Remembered per (file, url): this renderer recomposes on every
            // streaming token, and rebuilding the request each time would churn
            // allocations in a hot path.
            val imageRequest = remember(file, block.url) {
                ImageRequest.Builder(context)
                    .data(file ?: block.url)
                    .limitDisplaySize()
                    .build()
            }
            SubcomposeAsyncImage(
                model = imageRequest,
                contentDescription = block.alt,
                modifier = imageBaseModifier,
                contentScale = ContentScale.FillWidth,
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Error -> BrokenImagePlaceholder(alt = block.alt)
                    else -> SubcomposeAsyncImageContent()
                }
            }
        }

        is MdBlock.Video -> {
            android.util.Log.d("MdStream", "render Video url=${block.url}")
            RenderMdVideo(block)
        }

        is MdBlock.Audio -> {
            android.util.Log.d("MdStream", "render Audio url=${block.url}")
            RenderMdAudio(block)
        }

        is MdBlock.Table -> {
            RenderTable(block)
        }

        is MdBlock.MathDisplay -> {
            RenderMathDisplay(block.latex)
        }
    }
}

// ─── Math (KaTeX) ───────────────────────────────────────────────────────────

/**
 * T208-4 part 4: Per-latex InlineTextContent registry.
 *
 * Compose's Placeholder API requires a fixed size at construction — there
 * is no way to resize a placeholder after the inline text has been laid
 * out. The previous design used a single shared placeholder sized to
 * `fontSize * 6 × fontSize * 1.4` (≈ 96 × 22.5 dp at 16 sp); KaTeX
 * routinely produces ~26 dp tall bitmaps (subscript descenders), and any
 * formula wider than 96 dp simply did not fit. ContentScale.Fit then
 * shrank every formula to ~85 % to make it fit the slot, producing the
 * "everything looks shrunken" output the user reported in T208-4.
 *
 * The fix: each unique latex string registers its OWN InlineTextContent
 * with its own placeholder, sized via `estimateInlineMathSize` based on
 * the latex's character count and structural triggers. Compose draws the
 * KaTeX bitmap at its natural dp size centered inside that slot — no
 * shrink, no clip. Extra padding inside an over-estimated slot is
 * harmless; under-estimating would re-introduce the shrink, so the
 * estimator is intentionally generous.
 *
 * The list of latex strings comes from `collectInlineMathLatex`, which
 * runs the same delimiter scanner as `parseInline` over the raw text.
 */
@Composable
internal fun rememberKatexInlineContent(
    fontSize: TextUnit,
    latexList: List<String>,
): Map<String, androidx.compose.foundation.text.InlineTextContent> {
    if (latexList.isEmpty()) return emptyMap()
    return remember(fontSize, latexList) {
        val map = HashMap<String, androidx.compose.foundation.text.InlineTextContent>(latexList.size)
        for (latex in latexList.toSet()) {
            val (w, h) = estimateInlineMathSize(latex, fontSize)
            map[katexInlineTagFor(latex)] = androidx.compose.foundation.text.InlineTextContent(
                placeholder = androidx.compose.ui.text.Placeholder(
                    width = w,
                    height = h,
                    // [T-android-math-baseline] TextCenter, was AboveBaseline.
                    // The slot is over-estimated AND the KaTeX bitmap carries
                    // its own top/bottom whitespace, so an above-baseline slot
                    // put the formula's optical center well ABOVE the line's
                    // ("N(100) 渲染偏上"). Centering the slot on the line and
                    // the bitmap in the slot (CenterStart below) aligns the
                    // two optical centers instead — robust against both the
                    // generous estimate and the bitmap padding.
                    placeholderVerticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter,
                ),
            ) { _ ->
                // Compose passes the alternative-text to the children lambda;
                // we already keyed the slot per-latex so we use the closure's
                // `latex` directly to avoid any tag/text mismatch.
                RenderInlineMath(latex = latex, fontSize = fontSize)
            }
        }
        map
    }
}

@Composable
internal fun RenderInlineMath(latex: String, fontSize: TextUnit) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    // T208-5: pass the sp value as CSS px so the rendered glyph height
    // matches the surrounding body text. KaTeX's HTML sets
    // `el.style.fontSize = fontSize + 'px'` and the WebView viewport runs
    // at initial-scale=1.0, so 1 CSS px = 1 dp. Passing 16 here makes the
    // formula glyphs 16 dp tall — same as the 16-sp Compose body text.
    // Earlier code passed sp.toPx() (= sp × density = 42 on a density-2.625
    // device), which produced a bitmap ~2.6× too large; combined with the
    // CSS-vs-physical-px snapshot bug it accidentally landed near correct
    // size, but with the snapshot bug fixed the inflation showed through.
    //
    // [T-android-math-fontscale] ×fontScale: 16 sp of TEXT draws at
    // 16 × fontScale dp when the SYSTEM font size setting isn't 100%, and
    // the inline Placeholder (TextUnit sp) scales with it — but the CSS-px
    // bitmap did NOT. On a small-font device (fontScale < 1) the bitmap
    // came out LARGER than the shrunken slot, and Compose clips inline
    // content to the placeholder bounds — the field report's "N(10(" (a
    // clipped N(100)) and formulas visibly oversized next to their own
    // paragraph text. fontScale > 1 gave the inverse: formulas too small.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val fontSizeCssPx = (fontSize.value * fontScale).toInt().coerceAtLeast(12)
    val palette = currentMdColors()
    val result by androidx.compose.runtime.produceState<KatexRenderResult?>(
        initialValue = null,
        key1 = latex,
        key2 = isDark,
        key3 = fontSizeCssPx,
    ) {
        value = KatexWebViewPool.render(
            context = context,
            latex = latex,
            displayMode = false,
            isDark = isDark,
            fontSizePx = fontSizeCssPx,
        )
    }
    val rendered = result
    if (rendered != null) {
        // T208-4 part 4: the slot was sized by `estimateInlineMathSize`
        // generously enough for this latex, so draw the bitmap at its
        // natural dp size (no scaling, no shrinking). ContentScale.Fit
        // is the defensive fallback if the estimator ever under-shoots.
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val naturalWidthDp = (rendered.bitmap.width / density).dp
        val naturalHeightDp = (rendered.bitmap.height / density).dp
        // [T-android-math-fontscale] Defensive de-clip: the slot was sized by
        // estimateInlineMathSize, but any residual estimator drift (or a
        // future slot/bitmap unit mismatch) used to CLIP the formula — inline
        // content never exceeds its placeholder bounds. Measure the slot and
        // scale the bitmap DOWN to fit when needed: a slightly shrunken
        // formula is readable, a clipped one ("N(10(") is not.
        //
        // [T-android-math-baseline] BOTTOM-align the bitmap. The placeholder
        // uses AboveBaseline (slot bottom sits ON the text baseline), but its
        // height is deliberately over-estimated — with the default TopStart
        // alignment the formula rode at the TOP of the too-tall slot,
        // floating visibly above its own line ("N(100) 渲染偏上"). Anchoring
        // to the slot's bottom puts the formula on the baseline regardless of
        // how generous the height estimate is.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val fit = minOf(
                1f,
                if (naturalWidthDp > maxWidth) maxWidth / naturalWidthDp else 1f,
                if (naturalHeightDp > maxHeight) maxHeight / naturalHeightDp else 1f,
            )
            androidx.compose.foundation.Image(
                bitmap = rendered.bitmap.asImageBitmap(),
                contentDescription = "math: $latex",
                modifier = Modifier.size(naturalWidthDp * fit, naturalHeightDp * fit),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
    } else {
        // Fallback while loading or on error: show the raw latex so the
        // user is never staring at an empty rectangle.
        Text(
            text = latex,
            fontSize = fontSize * 0.9f,
            fontFamily = palette.codeFont,
            color = palette.text,
        )
    }
}

/**
 * T155: Display-mode math rendered via the shared KaTeX WebView pool.
 * Shows the bitmap snapshot once KaTeX returns; falls back to monospace
 * raw LaTeX while loading or on render error so the user always sees
 * *something* meaningful even before / instead of the rendered formula.
 *
 * iOS parity: KaTeXRenderer.swift (single offscreen WKWebView, snapshot,
 * cached). The render call is suspending — Compose drives it via
 * `produceState` keyed by (latex, isDark, fontSize) so flipping themes
 * or scrolling back-and-forth never re-renders the same formula twice.
 */
@Composable
internal fun RenderMathDisplay(latex: String) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    // T208-5: render at sp.value (CSS px = dp) so glyph height matches the
    // surrounding 16-sp body text. See RenderInlineMath comment for the
    // full reasoning. [T-android-math-fontscale] ×fontScale so display math
    // tracks the SYSTEM font size setting the way the surrounding sp text
    // does (the inline path had the same gap — see RenderInlineMath).
    val displayFontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val fontSizeCssPx = (BaseFontSize.value * displayFontScale).toInt().coerceAtLeast(12)
    val palette = currentMdColors()

    val result by androidx.compose.runtime.produceState<KatexRenderResult?>(
        initialValue = null,
        key1 = latex,
        key2 = isDark,
        key3 = fontSizeCssPx,
    ) {
        value = KatexWebViewPool.render(
            context = context,
            latex = latex,
            displayMode = true,
            isDark = isDark,
            fontSizePx = fontSizeCssPx,
        )
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val rendered = result
        if (rendered != null) {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            val naturalWidthDp = (rendered.bitmap.width / density).dp
            val naturalHeightDp = (rendered.bitmap.height / density).dp
            val scale = if (naturalWidthDp > maxWidth) maxWidth / naturalWidthDp else 1f
            androidx.compose.foundation.Image(
                bitmap = rendered.bitmap.asImageBitmap(),
                contentDescription = "math: $latex",
                modifier = Modifier.size(naturalWidthDp * scale, naturalHeightDp * scale),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        } else {
            // Fallback: raw latex in monospace inside a faint surface.
            Text(
                text = latex,
                fontSize = BaseFontSize * 0.95f,
                fontFamily = palette.codeFont,
                color = palette.text,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ─── Broken image placeholder ───────────────────────────────────────────────

/**
 * T148: Visible fallback for `SubcomposeAsyncImage` error state inside
 * markdown — rendered when Coil can't load the source (file deleted,
 * 404, IO error). Without this the slot paints nothing and the user
 * can't tell whether the image is missing or whether the renderer is
 * broken. The outer `imageBaseModifier` already supplies the T146
 * border/shadow/rounded-corner frame; here we just fill the inside
 * with a subtle tool-bg, a broken-image glyph, and the alt text.
 */
@Composable
private fun BrokenImagePlaceholder(alt: String?) {
    val palette = com.openminis.app.ui.theme.LocalChatPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(palette.toolBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = novex.android.ui.NovexIcons.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = palette.secondaryText,
            )
            Text(
                text = alt?.takeIf { it.isNotBlank() } ?: "Image not available",
                fontSize = 12.sp,
                color = palette.secondaryText,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
