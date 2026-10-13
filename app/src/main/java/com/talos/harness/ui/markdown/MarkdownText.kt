package com.talos.harness.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talos.harness.ui.theme.TalosCodeTextStyle
import com.talos.harness.ui.theme.TalosColors
import com.talos.harness.ui.theme.TalosPalette
import java.util.Locale

// ---------------------------------------------------------------------------
// Public block model
// ---------------------------------------------------------------------------

/**
 * One structural block of parsed markdown.
 *
 * The model is deliberately tiny and plain-Kotlin (no Compose types) so
 * [parseMarkdown] is pure, testable and cheap to re-run on every token that
 * arrives from the model.
 */
sealed interface MdBlock {
    /** `# text` — [level] is 1..6. */
    data class Heading(val level: Int, val text: String) : MdBlock

    /** A run of prose lines. May carry inline markers, resolved at render time. */
    data class Paragraph(val text: String) : MdBlock

    /** `- item` / `* item` / `+ item` lines. */
    data class BulletList(val items: List<String>) : MdBlock

    /** `1. item` / `1) item` lines, preserving the numbers as written. */
    data class OrderedList(val items: List<OrderedItem>) : MdBlock

    /** A fenced block. [language] is null when the fence carried no info string. */
    data class CodeBlock(val language: String?, val code: String) : MdBlock

    /** A `>` blockquote, with the marker stripped from every line. */
    data class Quote(val text: String) : MdBlock

    /** `---`, `***` or `___` on its own line. */
    data object Rule : MdBlock

    /** A pipe table. [header] may be empty; [rows] are already padded to match. */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

/** A single ordered-list entry, keeping the source number and delimiter. */
@Immutable
data class OrderedItem(val number: Int, val marker: String, val text: String)

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

private const val MAX_LINES = 20000
private const val MAX_BLOCKS = 4000

private val HeadingRegex = Regex(""" {0,3}(#{1,6})[ \t]+(.*)""")
private val BulletRegex = Regex("""^ {0,3}[-*+][ \t]+(.*)$""")
private val FenceRegex = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
private val RuleRegex = Regex("""^ {0,3}(?:-{3,}|\*{3,}|_{3,})[ \t]*$""")
private val TableDelimiterRegex = Regex("""^ {0,3}\|?[ \t]*:?-{1,}:?[ \t]*(\|[ \t]*:?-{1,}:?[ \t]*)*\|?[ \t]*$""")
private val SpecialTokenRegex = Regex("""\*\*|~~|`|\[|\*|_""")

/**
 * Parses [source] into renderable blocks without ever throwing and without ever
 * discarding input.
 *
 * The parser is written for *streaming* text: the model emits markdown token by
 * token, so most intermediate states are malformed — an unterminated ``` fence,
 * a lone `**`, a dangling `[label](`, a half-written table. Every such construct
 * degrades to plain text rather than an error, and it fails *closed*: if a
 * closing marker cannot be found, the opening marker is emitted literally.
 *
 * Total input is preserved in one of two ways. Block-level text that cannot be
 * interpreted (a malformed table, an empty quote) is re-emitted through
 * [plainBlocks], which always produces text; inline markers likewise always
 * fall back to their literal characters. The practical consequence is that
 * concatenating the text of the returned blocks reproduces the source, modulo
 * whitespace and blockquote/fence markers.
 *
 * Costs are bounded: at most [MAX_LINES] source lines and [MAX_BLOCKS] blocks
 * are produced, so a pathological input cannot wedge the composition.
 */
fun parseMarkdown(source: String): List<MdBlock> {
    if (source.isEmpty()) return emptyList()

    val rawLines = source.split('\n')
    val size = if (rawLines.size > MAX_LINES) MAX_LINES else rawLines.size
    val lines = ArrayList<String>(size)
    for (index in 0 until size) {
        lines.add(rawLines[index].removeSuffix("\r"))
    }

    val blocks = ArrayList<MdBlock>()
    var index = 0
    var openStart = -1
    var openFenceChar: Char? = null

    fun flushOpenParagraph() {
        if (openStart >= 0 && openStart < index) {
            val body = joinRange(lines, openStart, index).trim()
            if (body.isNotEmpty()) blocks.add(MdBlock.Paragraph(body))
        }
        openStart = -1
    }

    while (index < lines.size && blocks.size < MAX_BLOCKS) {
        val line = lines[index]
        val trimmed = line.trim()

        if (trimmed.isEmpty()) {
            flushOpenParagraph()
            index++
            continue
        }

        // Inside an unterminated fence everything stays code, so a streaming
        // code block never leaks raw markdown into the transcript.
        val fenceChar = openFenceChar
        if (fenceChar != null) {
            val opening = FenceRegex.find(line)
            if (opening != null && opening.groupValues[1][0] == fenceChar) {
                val code = joinRange(lines, openStart, index).trim('\n', '\r')
                val language = fenceLanguage(opening.groupValues[2])
                blocks.add(MdBlock.CodeBlock(language, code))
                openFenceChar = null
                openStart = -1
            }
            index++
            continue
        }

        val fence = FenceRegex.find(line)
        if (fence != null) {
            flushOpenParagraph()
            openStart = index + 1
            openFenceChar = fence.groupValues[1][0]
            index++
            continue
        }

        if (RuleRegex.matches(line)) {
            flushOpenParagraph()
            blocks.add(MdBlock.Rule)
            index++
            continue
        }

        val heading = HeadingRegex.find(line)
        if (heading != null) {
            flushOpenParagraph()
            val body = heading.groupValues[2].trim()
            if (body.isNotEmpty()) {
                blocks.add(MdBlock.Heading(heading.groupValues[1].length, body))
            }
            index++
            continue
        }

        val table = readTable(lines, index)
        if (table != null) {
            flushOpenParagraph()
            blocks.add(table.block)
            index = table.nextLine
            continue
        }

        if (trimmed.startsWith(">")) {
            flushOpenParagraph()
            val quoted = ArrayList<String>()
            var cursor = index
            while (cursor < lines.size && quoted.size < MAX_LINES) {
                val candidate = lines[cursor].trim()
                if (candidate.isEmpty()) break
                if (!candidate.startsWith(">")) break
                val content = candidate.substring(1).trim()
                if (content.isNotEmpty()) quoted.add(content)
                cursor++
            }
            if (quoted.isEmpty()) {
                blocks.addAll(plainBlocks(line))
                index++
            } else {
                val quote = MdBlock.Quote(quoted.joinToString("\n"))
                if (quote.text.isBlank()) blocks.addAll(plainBlocks(line)) else blocks.add(quote)
                index = cursor
            }
            continue
        }

        val bullet = BulletRegex.find(line)
        if (bullet != null) {
            flushOpenParagraph()
            val items = ArrayList<String>()
            var cursor = index
            while (cursor < lines.size && items.size < MAX_LINES) {
                val match = BulletRegex.find(lines[cursor]) ?: break
                items.add(match.groupValues[1].trim())
                cursor++
            }
            if (items.isEmpty()) {
                blocks.addAll(plainBlocks(line))
                index++
            } else {
                blocks.add(MdBlock.BulletList(items))
                index = cursor
            }
            continue
        }

        val ordered = parseOrderedListLine(line)
        if (ordered != null) {
            flushOpenParagraph()
            val items = ArrayList<OrderedItem>()
            var cursor = index
            while (cursor < lines.size && items.size < MAX_LINES) {
                val parsed = parseOrderedListLine(lines[cursor]) ?: break
                items.add(OrderedItem(parsed.number, parsed.delimiter.toString(), parsed.text.trim()))
                cursor++
            }
            if (items.isEmpty()) {
                blocks.addAll(plainBlocks(line))
                index++
            } else {
                blocks.add(MdBlock.OrderedList(items))
                index = cursor
            }
            continue
        }

        // Anything else joins the paragraph currently being accumulated, so
        // soft-wrapped prose renders as one block while still starting a new
        // block after any list, heading, quote or fence.
        if (openStart < 0) openStart = index
        index++
    }

    flushOpenParagraph()
    return blocks
}

private fun fenceLanguage(info: String): String? {
    val trimmed = info.trim()
    if (trimmed.isEmpty()) return null
    val token = trimmed.split(' ')[0].trim()
    return token.ifEmpty { null }
}

private fun joinRange(lines: List<String>, from: Int, to: Int): String {
    if (from < 0 || from >= to) return ""
    val builder = StringBuilder()
    for (i in from until to) {
        if (i > from) builder.append('\n')
        builder.append(lines[i])
    }
    return builder.toString()
}

/**
 * Last-resort conversion of a single line into a paragraph. Guarantees that no
 * source text is dropped when a block construct cannot be interpreted.
 */
private fun plainBlocks(text: String): List<MdBlock> {
    val body = text.trim()
    return if (body.isEmpty()) emptyList() else listOf(MdBlock.Paragraph(body))
}

private class TableRead(val block: MdBlock.Table, val nextLine: Int)

private fun readTable(lines: List<String>, start: Int): TableRead? {
    val headerLine = lines[start]
    if (!headerLine.contains('|')) return null
    if (start + 1 >= lines.size) return null

    val delimiterLine = lines[start + 1]
    if (delimiterLine.count { it == '-' } < 3) return null
    if (!TableDelimiterRegex.matches(delimiterLine)) return null

    val header = splitTableRow(headerLine)
    if (header.isEmpty()) return null

    val rows = ArrayList<List<String>>()
    var cursor = start + 2
    while (cursor < lines.size && rows.size < MAX_LINES) {
        val candidate = lines[cursor]
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) break
        if (!candidate.contains('|')) break
        if (FenceRegex.containsMatchIn(candidate) || RuleRegex.matches(candidate)) break
        rows.add(splitTableRow(candidate))
        cursor++
    }

    val width = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0, 1)
    return TableRead(
        MdBlock.Table(padCells(header, width), rows.map { padCells(it, width) }),
        cursor,
    )
}

private fun padCells(cells: List<String>, width: Int): List<String> {
    if (cells.size >= width) return cells
    val padded = ArrayList<String>(width)
    padded.addAll(cells)
    while (padded.size < width) padded.add("")
    return padded
}

private fun splitTableRow(line: String): List<String> {
    var body = line.trim()
    if (body.startsWith("|")) body = body.substring(1)
    if (body.endsWith("|")) body = body.substring(0, body.length - 1)
    return body.split('|').map { it.trim() }
}


// ---------------------------------------------------------------------------
// Inline parsing
// ---------------------------------------------------------------------------

/** One run of text plus the emphasis flags resolved for it. */
private class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: Boolean = false,
)

/**
 * Resolves inline markers into flat, non-overlapping spans.
 *
 * Unterminated markers emit their literal characters, so a mid-stream `**` or a
 * dangling `[label](` survives as visible text instead of disappearing. Emphasis
 * does not nest: markers inside an already-resolved span stay literal, which
 * keeps the output total and the scan bounded.
 */
private fun parseInline(text: String): List<MdSpan> {
    val out = ArrayList<MdSpan>()
    var index = 0
    var guard = 0
    val limit = text.length * 4 + 64

    while (index < text.length && guard++ < limit) {
        val rest = text.substring(index)
        val match = SpecialTokenRegex.find(rest)
        if (match == null) {
            out.add(MdSpan(rest))
            break
        }

        val at = match.range.first
        if (at > 0) {
            out.add(MdSpan(rest.substring(0, at)))
            index += at
            continue
        }

        val previous = if (index > 0) text[index - 1] else null
        val token = match.value
        var handled = false

        if (token == "**") {
            val close = findDelimiter(rest, 2, '*')
            if (close > 0) {
                out.add(MdSpan(rest.substring(2, close), bold = true))
                index += close + 2
                handled = true
            }
        } else if (token == "~~") {
            val close = findDelimiter(rest, 2, '~')
            if (close > 0) {
                out.add(MdSpan(rest.substring(2, close), strike = true))
                index += close + 2
                handled = true
            }
        } else if (token == "`") {
            val close = findDelimiter(rest, 1, '`')
            if (close > 0) {
                out.add(MdSpan(rest.substring(1, close), code = true))
                index += close + 1
                handled = true
            }
        } else if (token == "[") {
            val link = parseLink(rest)
            if (link != null) {
                out.add(MdSpan(link.first, link = true))
                index += link.second
                handled = true
            }
        } else if (token == "*") {
            if (rest.length > 1 && rest[1] != '*') {
                val close = findDelimiter(rest, 1, '*')
                if (close > 0) {
                    out.add(MdSpan(rest.substring(1, close), italic = true))
                    index += close + 1
                    handled = true
                }
            }
        } else if (token == "_") {
            // `snake_case` and `2_000` must not be read as emphasis. A preceding
            // marker is fine (`**_x_**`), but an alphanumeric means we are
            // inside a word, so the underscore stays literal.
            val leftOk = previous == null || (!previous.isLetterOrDigit() && previous != '*')
            if (leftOk) {
                val close = findDelimiter(rest, 1, '_')
                if (close > 0) {
                    out.add(MdSpan(rest.substring(1, close), italic = true))
                    index += close + 1
                    handled = true
                }
            }
        }

        if (!handled) {
            out.add(MdSpan(token))
            index += token.length
        }
    }

    return out
}

/**
 * Returns the index of the closing delimiter within [text], or -1 when it is
 * missing — in which case the caller emits the opening marker literally.
 *
 * [open] is the length of the opening run; the closing run may be shorter but
 * never longer, so `**bold**` and `*italic*` resolve as written.
 */
private fun findDelimiter(text: String, open: Int, char: Char): Int {
    var i = open
    while (i < text.length) {
        if (text[i] == char) {
            var run = 0
            while (i + run < text.length && text[i + run] == char) run++
            if (i > open && closeRunOk(text, i, run, open, char)) return i
            i += run
        } else {
            i++
        }
    }
    return -1
}

private fun closeRunOk(text: String, at: Int, run: Int, open: Int, char: Char): Boolean {
    if (run > open) return false
    // The character after the closing run must not continue a word, otherwise
    // `a_b_c` would treat the middle underscore as a terminator.
    val after = at + run
    if (after < text.length && text[after].isLetterOrDigit()) return false
    if (char == '_' && after < text.length && text[after] == '_') return false
    return true
}

/**
 * Recognises `[label](url)`. Returns the label and the total consumed length, or
 * null when the construct is incomplete — a dangling `[` is a normal mid-stream
 * state, not an error.
 */
private fun parseLink(text: String): Pair<String, Int>? {
    val labelEnd = text.indexOf(']')
    if (labelEnd <= 1) return null
    if (labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') return null
    val urlEnd = text.indexOf(')', labelEnd + 2)
    if (urlEnd < 0) return null
    return Pair(text.substring(1, labelEnd), urlEnd + 1)
}

/** Materializes spans into an [AnnotatedString], applying one style per span. */
private fun styledText(
    spans: List<MdSpan>,
    base: TextStyle,
    codeStyle: TextStyle,
    palette: TalosPalette,
): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        if (span.text.isEmpty()) continue
        withStyle(spanStyleOf(span, base, codeStyle, palette)) {
            append(span.text)
        }
    }
}

private fun spanStyleOf(
    span: MdSpan,
    base: TextStyle,
    codeStyle: TextStyle,
    palette: TalosPalette,
): SpanStyle {
    val emphasis = TextStyle(
        color = when {
            span.code -> codeStyle.color
            span.link -> palette.brandInk
            else -> base.color
        },
        fontWeight = when {
            span.code -> codeStyle.fontWeight
            span.bold -> FontWeight.SemiBold
            else -> base.fontWeight
        },
        fontStyle = if (span.italic) FontStyle.Italic else base.fontStyle,
        textDecoration = when {
            span.strike && span.link -> TextDecoration.combine(
                listOf(TextDecoration.LineThrough, TextDecoration.Underline),
            )
            span.strike -> TextDecoration.LineThrough
            span.link -> TextDecoration.Underline
            else -> base.textDecoration
        },
    )
    return if (span.code) {
        emphasis.merge(codeStyle).toSpanStyle()
    } else {
        emphasis.toSpanStyle()
    }
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

/**
 * Renders streamed markdown as styled, selectable Compose content.
 *
 * Built for assistant output that arrives token by token: the whole string is
 * re-parsed whenever it changes, partial constructs render as their literal
 * characters and nothing is ever dropped, so text never flickers out mid-stream.
 *
 * @param text raw markdown; malformed or incomplete input is expected.
 * @param style base prose style; defaults to the ambient text style.
 * @param codeStyle style for fenced code blocks and inline code spans.
 * @param onLinkClick retained so the API stays stable. Links currently render as
 *   styled, non-interactive text; click handling is intentionally not wired.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    codeStyle: TextStyle = TalosCodeTextStyle,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    SelectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (block in blocks) {
                MarkdownBlockView(block, style, codeStyle)
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MdBlock, style: TextStyle, codeStyle: TextStyle) {
    when (block) {
        is MdBlock.Heading -> HeadingBlock(block, style, codeStyle)
        is MdBlock.Paragraph -> BlockText(block.text, style, codeStyle)
        is MdBlock.BulletList -> BulletListBlock(block, style, codeStyle)
        is MdBlock.OrderedList -> OrderedListBlock(block, style, codeStyle)
        is MdBlock.CodeBlock -> CodeBlockView(block, codeStyle)
        is MdBlock.Quote -> QuoteBlock(block.text, style, codeStyle)
        MdBlock.Rule -> RuleBlock()
        is MdBlock.Table -> TableBlock(block, style, codeStyle)
    }
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading, style: TextStyle, codeStyle: TextStyle) {
    val density = LocalDensity.current
    val palette = TalosColors.current
    val level = block.level.coerceIn(1, 6)
    val scale = when (level) {
        1 -> 1.5f
        2 -> 1.3f
        3 -> 1.14f
        4 -> 1.02f
        5 -> 0.96f
        else -> 0.9f
    }
    val headingStyle = remember(style, density, scale, level) {
        val size = style.fontSize.value * scale
        style.copy(
            fontWeight = FontWeight.Bold,
            fontSize = size.sp,
            lineHeight = with(density) { (size * 1.3f).sp },
            letterSpacing = 0.sp,
        )
    }
    Text(
        text = styledText(parseInline(block.text), headingStyle, codeStyle, palette),
        style = headingStyle,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (level <= 3) 8.dp else 4.dp),
    )
}

@Composable
private fun BlockText(
    text: String,
    style: TextStyle,
    codeStyle: TextStyle,
    quiet: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = TalosColors.current
    val resolved = if (quiet) style.copy(color = palette.textSecondary) else style
    Text(
        text = styledText(parseInline(text), resolved, codeStyle, palette),
        style = resolved,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun BulletListBlock(block: MdBlock.BulletList, style: TextStyle, codeStyle: TextStyle) {
    val density = LocalDensity.current
    val palette = TalosColors.current
    val markerWidth = remember(style, density) {
        with(density) { (style.fontSize.value * 1.2f).sp.toDp() }.coerceAtLeast(14.dp)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (item in block.items) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "\u2022",
                    style = style.copy(color = palette.textFaint),
                    modifier = Modifier.width(markerWidth),
                )
                Text(
                    text = styledText(parseInline(item), style, codeStyle, palette),
                    style = style,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OrderedListBlock(block: MdBlock.OrderedList, style: TextStyle, codeStyle: TextStyle) {
    val density = LocalDensity.current
    val palette = TalosColors.current
    val measurer = rememberTextMeasurer()
    val markerStyle = remember(style, palette) { style.copy(color = palette.textFaint) }

    val markers = remember(block.items) { block.items.map { "${it.number}${it.marker}" } }
    val markerWidth = remember(markers, markerStyle, density, measurer) {
        var widest = 0
        for (marker in markers) {
            val measured = measurer.measure(AnnotatedString(marker), markerStyle).size.width
            if (measured > widest) widest = measured
        }
        with(density) { (widest + 6).toFloat().toDp() }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((index, item) in block.items.withIndex()) {
            val marker = if (index < markers.size) markers[index] else "${item.number}${item.marker}"
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = marker,
                    style = markerStyle,
                    modifier = Modifier.width(markerWidth),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
                Text(
                    text = styledText(parseInline(item.text), style, codeStyle, palette),
                    style = style,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock, codeStyle: TextStyle) {
    val palette = TalosColors.current
    val scrollState = rememberScrollState()
    val language = block.language?.uppercase(Locale.ROOT)
    val code = if (block.code.isEmpty()) " " else block.code

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBackground)
            .border(1.dp, palette.codeBorder, RoundedCornerShape(12.dp))
            .heightIn(max = 460.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (language != null) {
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelSmall.copy(color = palette.textFaint),
                )
            }
            Text(
                text = code,
                style = codeStyle.copy(color = palette.codeText),
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
            )
        }
    }
}

@Composable
private fun QuoteBlock(text: String, style: TextStyle, codeStyle: TextStyle) {
    val palette = TalosColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(4.dp))
            .background(palette.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(palette.brandMuted),
        )
        BlockText(
            text = text,
            style = style,
            codeStyle = codeStyle,
            quiet = true,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun RuleBlock() {
    val palette = TalosColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(palette.divider),
    )
}

@Composable
private fun TableBlock(block: MdBlock.Table, style: TextStyle, codeStyle: TextStyle) {
    val palette = TalosColors.current
    val density = LocalDensity.current
    val cellStyle = remember(style, density) {
        val size = (style.fontSize.value * 0.88f).coerceAtLeast(11f)
        style.copy(fontSize = size.sp, lineHeight = with(density) { (size * 1.45f).sp })
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, palette.divider, RoundedCornerShape(12.dp))
            .padding(vertical = 2.dp),
    ) {
        if (block.header.isNotEmpty()) {
            TableRow(
                cells = block.header,
                cellStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold, color = palette.textPrimary),
                codeStyle = codeStyle,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(1.dp)
                    .background(palette.divider),
            )
        }
        for (row in block.rows) {
            TableRow(
                cells = row,
                cellStyle = cellStyle.copy(color = palette.textSecondary),
                codeStyle = codeStyle,
            )
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, cellStyle: TextStyle, codeStyle: TextStyle) {
    val palette = TalosColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        for ((column, cell) in cells.withIndex()) {
            if (column > 0) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(palette.divider),
                )
            }
            Text(
                text = styledText(parseInline(cell), cellStyle, codeStyle, palette),
                style = cellStyle,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Note: list bodies get their hanging indent from the layout, not from a
 * `TextIndent` — the marker sits in its own fixed-width gutter column, so
 * wrapped lines already align with the text column. Material3's `Text` in this
 * Compose version has no `indent` parameter.
 */
