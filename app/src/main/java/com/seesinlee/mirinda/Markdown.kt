package com.seesinlee.mirinda

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

/* ============================================================
 *  轻量 Markdown 渲染器（自包含，无第三方依赖）
 *  支持：# 标题 / **粗体** / *斜体* / `行内代码` / ```代码块``` /
 *       - 无序列表 / 1. 有序列表 / > 引用 / --- 分割线 / [链接](url) / ~~删除线~~
 * ============================================================ */

/** Markdown 块级结构 */
private sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Code(val code: String, val lang: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Bullet(val text: String, val marker: String, val indent: Int) : MdBlock()
    object Divider : MdBlock()
}

/**
 * 渲染一段 Markdown 文本。
 * @param markdown 原始 markdown 文本
 * @param color    正文基础颜色（行内代码/链接会用各自的强调色）
 */
@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        3 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge
                    }
                    Text(
                        text = buildInline(block.text, color),
                        color = color,
                        style = style.copy(fontWeight = FontWeight.Bold)
                    )
                }

                is MdBlock.Code -> CodeBlock(block.code)

                is MdBlock.Quote -> {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0x225B9DFF),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = buildInline(block.text, ChatColors.TextSecondary),
                            color = ChatColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                is MdBlock.Bullet -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (block.indent.coerceAtMost(8) * 10).dp)
                    ) {
                        Text(
                            text = block.marker + " ",
                            color = color,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = buildInline(block.text, color),
                            color = color,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                MdBlock.Divider -> HorizontalDivider(
                    color = ChatColors.TextSecondary.copy(alpha = 0.3f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                is MdBlock.Paragraph -> Text(
                    text = buildInline(block.text, color),
                    color = color,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

/** 代码块：等宽字体 + 深色底 + 横向滚动 */
@Composable
private fun CodeBlock(code: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0A1428),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ChatColors.ReasoningBorder, RoundedCornerShape(8.dp))
    ) {
        Text(
            text = code,
            color = Color(0xFFCFE0FF),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

/* ---------- 解析：把文本切成块 ---------- */

private fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val blocks = mutableListOf<MdBlock>()
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) blocks.add(MdBlock.Paragraph(para.toString().trim()))
        para.setLength(0)
    }

    val headingRe = Regex("^(#{1,6})\\s+(.*)$")
    val olRe = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    val ulRe = Regex("^(\\s*)[-*+]\\s+(.*)$")

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // 代码块 ```
        if (trimmed.startsWith("```")) {
            flushPara()
            val lang = trimmed.removePrefix("```").trim()
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                sb.append(lines[i]).append("\n")
                i++
            }
            i++ // 跳过结束的 ```
            blocks.add(MdBlock.Code(sb.toString().trimEnd('\n'), lang))
            continue
        }

        if (trimmed.isEmpty()) { flushPara(); i++; continue }

        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushPara(); blocks.add(MdBlock.Divider); i++; continue
        }

        headingRe.find(trimmed)?.let { m ->
            flushPara()
            blocks.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
            i++; return@let
        }?.also { continue }

        if (trimmed.startsWith(">")) {
            flushPara(); blocks.add(MdBlock.Quote(trimmed.removePrefix(">").trim())); i++; continue
        }

        olRe.find(line)?.let { m ->
            flushPara()
            blocks.add(MdBlock.Bullet(m.groupValues[3], m.groupValues[2] + ".", m.groupValues[1].length))
            i++; return@let
        }?.also { continue }

        ulRe.find(line)?.let { m ->
            flushPara()
            blocks.add(MdBlock.Bullet(m.groupValues[2], "•", m.groupValues[1].length))
            i++; return@let
        }?.also { continue }

        if (para.isNotEmpty()) para.append("\n")
        para.append(trimmed)
        i++
    }
    flushPara()
    return blocks
}

/* ---------- 解析：行内样式 -> AnnotatedString ---------- */

private fun buildInline(src: String, base: Color): AnnotatedString = buildAnnotatedString {
    appendInline(src, base)
}

private fun AnnotatedString.Builder.appendInline(src: String, base: Color) {
    val n = src.length
    var i = 0

    fun matches(idx: Int, tok: String) =
        idx + tok.length <= n && src.regionMatches(idx, tok, 0, tok.length)

    while (i < n) {
        val c = src[i]
        when {
            // 行内代码 `code`
            c == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > i) {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x335B9DFF),
                            color = Color(0xFFCFE0FF)
                        )
                    )
                    append(src.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else { append(c); i++ }
            }

            // 粗体 **x** / __x__
            matches(i, "**") || matches(i, "__") -> {
                val tok = src.substring(i, i + 2)
                val end = src.indexOf(tok, i + 2)
                if (end > i) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    appendInline(src.substring(i + 2, end), base)
                    pop()
                    i = end + 2
                } else { append(c); i++ }
            }

            // 删除线 ~~x~~
            matches(i, "~~") -> {
                val end = src.indexOf("~~", i + 2)
                if (end > i) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    appendInline(src.substring(i + 2, end), base)
                    pop()
                    i = end + 2
                } else { append(c); i++ }
            }

            // 斜体 *x* / _x_
            (c == '*' || c == '_') -> {
                val end = src.indexOf(c, i + 1)
                if (end > i) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    appendInline(src.substring(i + 1, end), base)
                    pop()
                    i = end + 1
                } else { append(c); i++ }
            }

            // 链接 [text](url)
            c == '[' -> {
                val close = src.indexOf(']', i + 1)
                if (close > i && close + 1 < n && src[close + 1] == '(') {
                    val urlEnd = src.indexOf(')', close + 2)
                    if (urlEnd > close) {
                        val text = src.substring(i + 1, close)
                        val url = src.substring(close + 2, urlEnd)
                        withLink(
                            LinkAnnotation.Url(
                                url,
                                TextLinkStyles(
                                    SpanStyle(
                                        color = ChatColors.Accent,
                                        textDecoration = TextDecoration.Underline
                                    )
                                )
                            )
                        ) { append(text) }
                        i = urlEnd + 1
                    } else { append(c); i++ }
                } else { append(c); i++ }
            }

            else -> { append(c); i++ }
        }
    }
}
