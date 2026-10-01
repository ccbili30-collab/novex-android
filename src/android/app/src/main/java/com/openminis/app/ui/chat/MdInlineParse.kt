package com.openminis.app.ui.chat

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit

// 行内 Markdown → AnnotatedString：转义/图片/粗斜体/删除线/行内码
// （含 U+2006 衬垫与行级背景注解）/链接/行内数学占位符 + KaTeX 尺寸估计
// 与 $…$/\(…\) 数学 latex 收集。

// ─── Inline markdown parser → AnnotatedString ───────────────────────────────

/**
 * [T-android-streaming-incremental-inline] Largest safe offset to split [text]
 * for incremental inline re-parse of a streaming tail. The result `p` satisfies:
 *   - `p` sits immediately AFTER a `\n` (so it lands on an inline-parse "reset"
 *     line boundary — inline code / `$…$` / `\(…\)` all stop at `\n`), and
 *   - `text[0, p)` has every multi-line-capable inline construct CLOSED, i.e.
 *     an even number of `**`, `__`, `~~`, `` ` `` runs and no dangling
 *     `[…](…` link, and no trailing `\` escape.
 *
 * This guarantees `parseInline(prefix) ++ parseInline(suffix) == parseInline(text)`
 * because no inline span crosses the split point. It's a single forward linear
 * scan (cheaper than the parse it saves). Returns 0 when no safe split exists
 * (caller then parses the whole thing) — conservative by construction: any
 * doubt about closure keeps the boundary earlier, never inside an open marker.
 *
 * We keep a [TAIL_MARGIN] of trailing chars unsplit so the still-growing tail
 * (where the model may still be mid-token, mid-`**`, mid-`$`) is always fully
 * re-scanned; only well-settled earlier content is frozen.
 */
private const val INCR_TAIL_MARGIN = 256

internal fun safeInlineSplitOffset(text: String): Int {
    // Track parity of the multi-line-capable delimiters. Single-line
    // constructs (inline code `…`, `$…$`, `\(…\)`, links) reset at every '\n'
    // (their close-scanners stop at newline), so at a line boundary they are
    // never "open" — we only need to prove the multi-line ones are balanced
    // AND that we're not sitting on a trailing escape.
    var boldStar = false   // ** run open  (also covers *** via two toggles)
    var boldUnder = false  // __ run open
    var strike = false     // ~~ run open
    // Link/image `[label](url` state: parseInline's [text](url) / ![alt](url)
    // use plain indexOf for `]`/`)` and thus CAN span newlines — a newline
    // inside an open link/image is NOT a safe split point.
    var inLabel = false    // seen unmatched `[` (or `![`)
    var inUrl = false      // seen `](`, awaiting `)`
    var lastSafeNewlineEnd = 0 // offset AFTER the last balanced '\n'
    val limit = text.length - INCR_TAIL_MARGIN
    if (limit <= 0) return 0

    var i = 0
    while (i < limit) {
        val c = text[i]
        when {
            // Escape — skip the escaped char so `\*`, `\[` etc. don't toggle.
            c == '\\' && i + 1 < text.length -> { i += 2; continue }
            text.startsWith("~~", i) -> { strike = !strike; i += 2; continue }
            text.startsWith("**", i) -> { boldStar = !boldStar; i += 2; continue }
            text.startsWith("__", i) -> { boldUnder = !boldUnder; i += 2; continue }
            // `](` transitions label -> url (only when a label is open).
            inLabel && text.startsWith("](", i) -> { inLabel = false; inUrl = true; i += 2; continue }
            c == '[' -> { inLabel = true; i++ }             // `![` also lands here on the `[`
            c == ']' && inLabel -> { inLabel = false; i++ } // `]` not followed by `(`
            c == ')' && inUrl -> { inUrl = false; i++ }
            c == '\n' -> {
                // Safe only when every newline-spanning construct is closed.
                // Inline code / `$…$` / `\(…\)` don't need tracking: their
                // close-scanners stop at '\n', so an unclosed one renders
                // literally on both sides of the split — identical either way.
                if (!boldStar && !boldUnder && !strike && !inLabel && !inUrl) {
                    lastSafeNewlineEnd = i + 1
                }
                i++
            }
            else -> i++
        }
    }
    return lastSafeNewlineEnd
}

internal fun parseInline(text: String, colors: MdColors): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // [T-latex-inline] `\( … \)` inline math must be matched BEFORE the
                // generic `\`-escape branch below — otherwise `\(` is consumed as
                // an escaped `(` and the math delimiter never fires (latent dead
                // code before this reorder). `\[ … \]` display math stays block-
                // level; here we only handle the inline `\( … \)` form.
                text.startsWith("\\(", i) -> {
                    val end = text.indexOf("\\)", i + 2)
                    if (end != -1) {
                        appendInlineContent(katexInlineTagFor(text.substring(i + 2, end)), text.substring(i + 2, end))
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                // Escape: \* \_ \` etc.
                text[i] == '\\' && i + 1 < text.length -> {
                    append(text[i + 1]); i += 2
                }
                // Image: ![alt](url) — render as [alt] link
                text.startsWith("![", i) -> {
                    val cb = text.indexOf(']', i + 2)
                    if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                        val cp = text.indexOf(')', cb + 2)
                        if (cp != -1) {
                            val alt = text.substring(i + 2, cb).ifEmpty { "image" }
                            withStyle(SpanStyle(color = colors.link)) { append("[$alt]") }
                            i = cp + 1
                        } else { append(text[i]); i++ }
                    } else { append(text[i]); i++ }
                }
                // Bold + italic: ***text***
                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            appendRecursive(text.substring(i + 3, end), colors)
                        }
                        i = end + 3
                    } else { append(text[i]); i++ }
                }
                // Bold: **text** or __text__
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendRecursive(text.substring(i + 2, end), colors)
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                text.startsWith("__", i) -> {
                    val end = text.indexOf("__", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendRecursive(text.substring(i + 2, end), colors)
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                // Strikethrough: ~~text~~
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            appendRecursive(text.substring(i + 2, end), colors)
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                // Triple backtick (fenced code fence leaked into inline) — skip as literal
                text.startsWith("```", i) -> {
                    append("```"); i += 3
                }
                // T155: inline math $ ... $ (skip $$ which is display-math, handled at block level)
                text[i] == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ' -> {
                    val end = findInlineMathClose(text, i + 1)
                    if (end != -1) {
                        val latex = text.substring(i + 1, end)
                        if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) {
                            appendInlineContent(katexInlineTagFor(latex), latex)
                            i = end + 1
                        } else { append(text[i]); i++ }
                    } else { append(text[i]); i++ }
                }
                // Inline code: `text`
                text[i] == '`' -> {
                    val end = findInlineCodeClose(text, i + 1)
                    if (end != -1) {
                        val codeStyle = SpanStyle(
                            fontFamily = colors.codeFont,
                            color = colors.inlineCodeText,
                        )
                        withStyle(codeStyle) { append("\u2006") }
                        // Annotation excludes the U+2006 pads on either side.
                        // Compose treats U+2006 as a break opportunity, so on
                        // wrap the leading pad sits at the prior line's tail \u2014
                        // including it in the annotation made drawBehind paint
                        // background back onto that prior line (T223).
                        val annStart = length
                        withStyle(codeStyle) { append(text.substring(i + 1, end)) }
                        val annEnd = length
                        withStyle(codeStyle) { append("\u2006") }
                        addStringAnnotation("inline_code", "", annStart, annEnd)
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                // Link: [text](url)
                text[i] == '[' && !text.startsWith("![", i - 1.coerceAtLeast(0)) -> {
                    val cb = text.indexOf(']', i + 1)
                    if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                        val cp = text.indexOf(')', cb + 2)
                        if (cp != -1) {
                            val url = text.substring(cb + 2, cp).trim()
                            val linkStart = length
                            withStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)) {
                                append(text.substring(i + 1, cb))
                            }
                            addStringAnnotation("url", url, linkStart, length)
                            i = cp + 1
                        } else { append(text[i]); i++ }
                    } else { append(text[i]); i++ }
                }
                // Italic: *text* or _text_ (single delimiter, not followed by same)
                (text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != text[i] && text[i + 1] != ' ' -> {
                    val delim = text[i]
                    val end = text.indexOf(delim, i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            appendRecursive(text.substring(i + 1, end), colors)
                        }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                // Line break: two trailing spaces or \n
                text[i] == '\n' -> {
                    append('\n'); i++
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}

/** Recursively parse inline markdown within a styled span. */
private fun AnnotatedString.Builder.appendRecursive(text: String, colors: MdColors) {
    var i = 0
    while (i < text.length) {
        when {
            // [T-latex-inline] Inline math must be handled INSIDE emphasis too —
            // `**$\approx$**`, `*$x$*`, `~~$a$~~` all appear in AI replies. Without
            // these branches emphasis content fell through to the literal `else`
            // and the `$…$` / `\(…\)` rendered as raw dollar text. The KaTeX
            // InlineTextContent slots are pre-registered by collectInlineMathLatex
            // (which scans the whole raw line, emphasis markers included), so
            // emitting the tag here resolves to the same rendered math. Placed
            // before the `\` escape branch so `\(` is treated as math, not an
            // escaped `(`.
            text.startsWith("\\(", i) -> {
                val end = text.indexOf("\\)", i + 2)
                if (end != -1) {
                    appendInlineContent(katexInlineTagFor(text.substring(i + 2, end)), text.substring(i + 2, end))
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ' -> {
                val end = findInlineMathClose(text, i + 1)
                if (end != -1) {
                    val latex = text.substring(i + 1, end)
                    if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) {
                        appendInlineContent(katexInlineTagFor(latex), latex)
                        i = end + 1
                    } else { append(text[i]); i++ }
                } else { append(text[i]); i++ }
            }
            text[i] == '\\' && i + 1 < text.length -> { append(text[i + 1]); i += 2 }
            text.startsWith("```", i) -> { append("```"); i += 3 }
            text[i] == '`' -> {
                val end = findInlineCodeClose(text, i + 1)
                if (end != -1) {
                    val codeStyle = SpanStyle(fontFamily = colors.codeFont, color = colors.inlineCodeText)
                    withStyle(codeStyle) { append("\u2006") }
                    // See T223 in the top-level inline-code branch \u2014 annotation
                    // excludes the U+2006 pads to keep wrap-line background
                    // from overshooting onto the prior line.
                    val annStart = length
                    withStyle(codeStyle) { append(text.substring(i + 1, end)) }
                    val annEnd = length
                    withStyle(codeStyle) { append("\u2006") }
                    addStringAnnotation("inline_code", "", annStart, annEnd)
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val cb = text.indexOf(']', i + 1)
                if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                    val cp = text.indexOf(')', cb + 2)
                    if (cp != -1) {
                        val url = text.substring(cb + 2, cp).trim()
                        val linkStart = length
                        withStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)) { append(text.substring(i + 1, cb)) }
                        addStringAnnotation("url", url, linkStart, length)
                        i = cp + 1
                    } else { append(text[i]); i++ }
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

/**
 * T156: find the closing backtick for an inline-code span starting at
 * [from]. Streaming chunks can deliver an odd backtick ahead of its
 * real partner; if a naive `indexOf` walks past a hard line break to
 * pair it with a backtick on a later line, the intervening prose gets
 * highlighted as code (the user-reported "first half of the sentence turns into code" bug). The
 * CommonMark spec already disallows newlines inside inline code, so
 * stopping at `\n` matches the canonical parser AND defends against
 * mid-stream pairings — the orphan backtick falls back to a literal
 * character until the real closer streams in.
 *
 * Returns -1 when no close is available before the next newline,
 * mirroring `indexOf` so the call site falls into the existing
 * "literal backtick" branch.
 */
private fun findInlineCodeClose(text: String, from: Int): Int {
    var k = from
    while (k < text.length) {
        val c = text[k]
        if (c == '`') return k
        if (c == '\n') return -1
        k++
    }
    return -1
}

// ─── T155: inline math helpers ──────────────────────────────────────────────

/** Compose annotation tag for inline KaTeX placeholders. */
/**
 * T208-4 part 4: per-latex inline-content tag. Each unique latex span gets
 * its own InlineTextContent slot so the placeholder can be sized to the
 * formula's predicted dimensions instead of one fixed-size slot for all
 * formulas (which forced ContentScale.Fit to shrink every taller-than-slot
 * formula to ~85% scale and clipped wider-than-slot ones).
 */
internal const val KATEX_INLINE_TAG_PREFIX = "katex_inline:"

internal fun katexInlineTagFor(latex: String): String = KATEX_INLINE_TAG_PREFIX + latex

/**
 * T208-4 part 4: estimate the on-screen dp size of an inline KaTeX render
 * BEFORE it has actually rendered, so we can size the InlineTextContent
 * placeholder appropriately. Compose's Placeholder API requires a size at
 * construction time and the inline Text layout reserves exactly that
 * amount of space — so we have to predict.
 *
 * Heuristics calibrated against the T208-DBG logs collected from a real
 * device: KaTeX produces ~`fontSize * 1.6` dp wide per visible character
 * for ordinary glyphs at 16 sp / density 2.625, and ~`fontSize * 1.65` dp
 * tall for one-line formulas (descenders + sub/superscript whitespace).
 * Stacked constructs (\frac, \begin, \sqrt with fraction inside) need
 * 2-3× the height. These numbers are intentionally generous — Compose
 * will draw the bitmap at its natural dp size centered inside the slot,
 * so an over-sized slot just produces extra whitespace, but an
 * under-sized slot triggers shrink/clip.
 */
internal fun estimateInlineMathSize(latex: String, fontSize: TextUnit): Pair<TextUnit, TextUnit> {
    val visibleCharCount = run {
        var c = 0
        var i = 0
        while (i < latex.length) {
            val ch = latex[i]
            if (ch == '\\' && i + 1 < latex.length) {
                // Skip a TeX command name; count the command as ~1.5 visible chars.
                i++
                while (i < latex.length && latex[i].isLetter()) i++
                c += 1
                continue
            }
            if (ch == '{' || ch == '}' || ch == ' ') { i++; continue }
            c++
            i++
        }
        c.coerceAtLeast(1)
    }

    // Width: ~0.95 em per visible char for typical math glyphs. KaTeX's
    // measured widths run ~0.95 em/char for ordinary symbols and >1 em/char
    // when `\text{...}` switches to a proportional sans/serif body face;
    // tuning down to 0.65 underestimated formulas like `W_c^{\text{non-private}}`
    // (244 dp natural wide vs 166 dp slot → Image got clipped/shrunk).
    // Cap at a generous upper bound — wide-math splitter has already
    // promoted truly wide formulas (length>30 OR `\begin{...}` etc.) to
    // display blocks, so anything reaching this estimator is short-ish
    // inline math; the cap is just defensive against pathological input.
    val charWidthEm = 0.95f
    val widthEm = (visibleCharCount * charWidthEm).coerceIn(1.5f, 22f)

    // Height: ~1.7 em base (matches measured ~26 dp for 16 sp).
    // Stacked constructs need vertical room for numerator+bar+denominator.
    var heightEm = 1.7f
    if (latex.contains("\\frac") || latex.contains("\\binom") ||
        latex.contains("\\sum") || latex.contains("\\int") ||
        latex.contains("\\prod") || latex.contains("\\sqrt[")
    ) heightEm = 3.2f
    if (latex.contains("\\begin{") || latex.contains("\\\\")) heightEm = 4.5f

    return (fontSize * widthEm) to (fontSize * heightEm)
}

/**
 * T208-4 part 4: scan a markdown line for inline-math spans (same delimiter
 * logic as parseInline) so we can pre-register a sized placeholder for
 * each unique latex BEFORE the AnnotatedString is laid out.
 */
internal fun collectInlineMathLatex(text: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length && text[i + 1] != '(' && text[i + 1] != '[') {
            i += 2; continue
        }
        if (c == '\\' && i + 1 < text.length && text[i + 1] == '(') {
            val end = text.indexOf("\\)", i + 2)
            if (end != -1) {
                out.add(text.substring(i + 2, end))
                i = end + 2; continue
            }
        }
        if (c == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ') {
            val end = findInlineMathClose(text, i + 1)
            if (end != -1) {
                val latex = text.substring(i + 1, end)
                if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) {
                    out.add(latex)
                    // Consumed a real span — jump past its closing `$`.
                    i = end + 1; continue
                }
                // [T-latex-inline] Rejected (currency / artifact): DON'T skip past
                // the closing `$`. Advancing to end+1 here would swallow the `$`
                // that legitimately OPENS the next span (`cost $5, and $x+y$` lost
                // `x+y` because the `$` before `x` got consumed as the first span's
                // close). Fall through to i++ so that `$` stays available. Matches
                // parseInline, which appends the char and advances by 1 on reject.
            }
        }
        i++
    }
    return out
}

/**
 * Find the closing `$` for an inline-math span starting at [from].
 * Mirrors [findInlineCodeClose]: stop at a newline so streaming chunks
 * never pair a stray `$` with the next dollar that arrives later, and
 * skip `\$` (escaped) and `$$` (which would be display math).
 */
internal fun findInlineMathClose(text: String, from: Int): Int {
    var k = from
    while (k < text.length) {
        val c = text[k]
        if (c == '\n') return -1
        if (c == '\\' && k + 1 < text.length) { k += 2; continue }
        if (c == '$') {
            // `$$` here is the start of display math, not a single-dollar close.
            if (k + 1 < text.length && text[k + 1] == '$') return -1
            return k
        }
        k++
    }
    return -1
}

/**
 * Crude heuristic to skip plain currency like `$5`, `$1,000` and avoid
 * turning prose dollar signs into KaTeX renders. Real LaTeX math nearly
 * always carries a backslash command, a brace, a math operator, or a
 * superscript/subscript marker. iOS uses the same idea
 * (MinisMarkdownParser.looksLikeMath).
 */
internal fun looksLikeMath(latex: String): Boolean {
    if (latex.isBlank()) return false
    if (latex.contains('\\')) return true
    if (latex.contains('{') || latex.contains('}')) return true
    if (latex.contains('^') || latex.contains('_')) return true
    val mathChars = "=+-*/<>≤≥≠∑∫∏√∞αβγθπφλμωΔΩ"
    if (latex.any { it in mathChars }) return true
    // [T-latex-inline] Bare short spans like `$x$`, `$pi$`, `$abc$` carry no
    // LaTeX glyph but ARE math. Mirror iOS MinisMarkdownParser.looksLikeMath,
    // which accepts `count > 2`, and additionally accept a single alphanumeric
    // token (`$x$`, `$n$`) — the strict "needs a math char" rule was the drift
    // that made single-variable inline math leak as literal `$…$`. Currency
    // (`$5`, `$1,000`) is filtered by the leading-digit guard, and `$$`/space
    // openers never reach here (gated by the caller).
    if (latex[0].isDigit()) return false            // currency: `$5`, `$10.99`
    if (latex.first().isWhitespace() || latex.last().isWhitespace()) return false
    if (latex.length <= 30 && latex.all { it.isLetterOrDigit() }) return true
    return latex.length > 2
}

/**
 * [T-latex-inline] True when a candidate inline-math span is really a markdown
 * table-cell artifact (a `$` that paired across `|` column separators) rather
 * than a formula. Mirrors iOS MinisMarkdownParser.isTablePipeArtifact so a row
 * like `| 月付 | $20|$ **3** |` doesn't capture `20|` as fake math (which would
 * eat the bold `**3**`). Two signals a real formula avoids: an unescaped pipe
 * with whitespace on a side (the ` | ` column separator), or an ODD number of
 * unescaped pipes (abs-value / norm bars always come in balanced pairs).
 * Escaped `\|` (LaTeX norm) is never counted.
 */
private fun isTablePipeArtifact(content: String): Boolean {
    var bareCount = 0
    for (idx in content.indices) {
        if (content[idx] != '|') continue
        if (idx > 0 && content[idx - 1] == '\\') continue      // escaped norm bar
        bareCount++
        val prevIsSpace = idx > 0 && content[idx - 1].isWhitespace()
        val nextIsSpace = idx + 1 < content.length && content[idx + 1].isWhitespace()
        if (prevIsSpace || nextIsSpace) return true
    }
    return bareCount % 2 == 1
}
