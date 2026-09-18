package com.motion.browser.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal, dependency-free Markdown renderer for AI chat answers:
 * headings, bullet/numbered lists, fenced code blocks (with copy),
 * inline bold/italic/code, and links. Everything degrades gracefully
 * to plain text — never crashes on malformed input.
 */
private const val TAG_URL = "url"

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onOpenLink: (String) -> Unit = {},
) {
    val blocks = remember(markdown) { splitBlocks(markdown) }
    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlockView(block)
                is MdBlock.Heading -> Text(
                    block.text.trimStart('#', ' '),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                is MdBlock.Bullet -> Row(Modifier.padding(vertical = 2.dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    InlineText(block.text, Modifier.weight(1f), onOpenLink)
                }
                is MdBlock.Numbered -> Row(Modifier.padding(vertical = 2.dp)) {
                    Text("${block.n}.  ", style = MaterialTheme.typography.bodyMedium)
                    InlineText(block.text, Modifier.weight(1f), onOpenLink)
                }
                is MdBlock.Paragraph -> InlineText(
                    block.text,
                    Modifier.padding(vertical = 2.dp),
                    onOpenLink,
                )
            }
        }
    }
}

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Numbered(val n: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val lang: String, val code: String) : MdBlock()
}

private fun splitBlocks(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = md.lines()
    var i = 0
    val para = StringBuilder()
    fun flushParagraph() {
        if (para.isNotBlank()) blocks += MdBlock.Paragraph(para.toString().trim())
        para.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flushParagraph()
                val lang = line.trim().removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                blocks += MdBlock.Code(lang, code.toString().trimEnd())
            }
            line.startsWith("#") -> {
                flushParagraph()
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 3)
                blocks += MdBlock.Heading(level, line)
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                flushParagraph()
                blocks += MdBlock.Bullet(line.trimStart().drop(2))
            }
            Regex("^\\d+\\.\\s").containsMatchIn(line) -> {
                flushParagraph()
                val n = line.trim().substringBefore('.').toIntOrNull() ?: 0
                blocks += MdBlock.Numbered(n, line.trim().substringAfter(". "))
            }
            line.isBlank() -> flushParagraph()
            else -> para.appendLine(line)
        }
        i++
    }
    flushParagraph()
    return blocks
}

/** Inline markdown: **bold**, *italic*, `code`, [label](url), bare URLs. */
@Composable
private fun InlineText(
    text: String,
    modifier: Modifier = Modifier,
    onOpenLink: (String) -> Unit = {},
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val styled = remember(text, linkColor) { annotateInline(text, linkColor) }
    ClickableText(
        text = styled,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        modifier = modifier,
        onTextLayout = { },
        onClick = { offset ->
            styled.getStringAnnotations(TAG_URL, offset, offset).firstOrNull()?.let { ann ->
                onOpenLink(ann.item)
            }
        },
    )
}

private fun annotateInline(
    text: String,
    linkColor: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val close = text.indexOf(']', i)
                val openParen = text.indexOf('(', close + 1)
                val closeParen = text.indexOf(')', openParen + 1)
                if (close > i && openParen == close + 1 && closeParen > openParen) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(openParen + 1, closeParen)
                    pushStringAnnotation(TAG_URL, url)
                    pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    append(label)
                    pop(); pop()
                    i = closeParen + 1
                } else { append(text[i]); i++ }
            }
            text.startsWith("http://", i) || text.startsWith("https://", i) -> {
                val end = text.substring(i).takeWhile { !it.isWhitespace() && it !in ")]" }.length + i
                val url = text.substring(i, end)
                pushStringAnnotation(TAG_URL, url)
                pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                append(url)
                pop(); pop()
                i = end
            }
            else -> { append(text[i]); i++ }
        }
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.Code) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    block.lang.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                IconButton(onClick = { copyToClipboard(context, block.code) }) {
                    Icon(
                        Icons.Filled.ContentCopy, contentDescription = "Copy code",
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            Text(
                block.code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp),
            )
        }
    }
}

private fun copyToClipboard(context: Context?, text: String) {
    context ?: return
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Motion", text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}
