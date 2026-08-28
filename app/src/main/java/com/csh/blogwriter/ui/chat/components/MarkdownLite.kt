package com.csh.blogwriter.ui.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/**
 * 계획 패널에 쓰는 아주 작은 마크다운. 라이브러리를 들이지 않고 여기서 필요한 것만 읽는다:
 * `#`/`##`/`###` 제목, `-`/`*` 불릿, `1.` 번호, `**굵게**`, 빈 줄로 나뉘는 문단.
 * 그 밖의 문법(링크·표·코드)은 글자 그대로 둔다.
 */
data class MdSpan(val text: String, val bold: Boolean = false)

sealed interface MdBlock {
    val spans: List<MdSpan>
    data class Heading(val level: Int, override val spans: List<MdSpan>) : MdBlock
    data class Bullet(override val spans: List<MdSpan>) : MdBlock
    data class Numbered(val number: Int, override val spans: List<MdSpan>) : MdBlock
    data class Paragraph(override val spans: List<MdSpan>) : MdBlock
}

private val HEADING = Regex("^(#{1,3})\\s+(.*)$")
private val BULLET = Regex("^[-*]\\s+(.*)$")
private val NUMBERED = Regex("^(\\d+)\\.\\s+(.*)$")

fun parseMarkdownLite(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    // 이어지는 평범한 줄은 한 문단으로 모은다 — 빈 줄이나 다른 종류의 줄을 만나면 끊는다.
    val paragraph = mutableListOf<String>()
    fun flush() {
        if (paragraph.isEmpty()) return
        blocks += MdBlock.Paragraph(parseSpans(paragraph.joinToString("\n")))
        paragraph.clear()
    }
    text.lines().forEach { raw ->
        val line = raw.trim()
        val heading = HEADING.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        when {
            line.isEmpty() -> flush()
            heading != null -> { flush(); blocks += MdBlock.Heading(heading.groupValues[1].length, parseSpans(heading.groupValues[2])) }
            bullet != null -> { flush(); blocks += MdBlock.Bullet(parseSpans(bullet.groupValues[1])) }
            numbered != null -> { flush(); blocks += MdBlock.Numbered((numbered.groupValues[1].toIntOrNull() ?: 0), parseSpans(numbered.groupValues[2])) }
            else -> paragraph += line
        }
    }
    flush()
    return blocks
}

/** `**굵게**` 만 본다. 짝이 맞지 않는 `**` 는 글자 그대로 남긴다. */
private fun parseSpans(line: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    var rest = line
    while (true) {
        val open = rest.indexOf("**")
        if (open < 0) break
        val close = rest.indexOf("**", open + 2)
        if (close < 0) break
        if (open > 0) spans += MdSpan(rest.substring(0, open))
        val bold = rest.substring(open + 2, close)
        if (bold.isNotEmpty()) spans += MdSpan(bold, bold = true)
        rest = rest.substring(close + 2)
    }
    if (rest.isNotEmpty()) spans += MdSpan(rest)
    return spans
}

/**
 * 편집 칸에 넣을 한 줄. `**굵게**` 는 표시 그대로 되살려 둔다 — 고치다가 굵게가 사라지지 않게.
 */
fun spansToText(spans: List<MdSpan>): String =
    spans.joinToString("") { if (it.bold) "**${it.text}**" else it.text }

/** 종류는 그대로 두고 글자만 [text] 로 바꾼 블록. 편집 칸이 돌려준 글자를 담을 때 쓴다. */
fun MdBlock.withText(text: String): MdBlock = when (this) {
    is MdBlock.Heading -> copy(spans = listOf(MdSpan(text)))
    is MdBlock.Bullet -> copy(spans = listOf(MdSpan(text)))
    is MdBlock.Numbered -> copy(spans = listOf(MdSpan(text)))
    is MdBlock.Paragraph -> copy(spans = listOf(MdSpan(text)))
}

/** [parseMarkdownLite] 의 반대. 블록 사이는 빈 줄로 띄워 다시 읽어도 같은 블록이 나온다. */
fun blocksToMarkdown(blocks: List<MdBlock>): String = blocks.joinToString("\n\n") { block ->
    when (block) {
        is MdBlock.Heading -> "#".repeat(block.level.coerceIn(1, 3)) + " " + spansToText(block.spans)
        is MdBlock.Bullet -> "- " + spansToText(block.spans)
        is MdBlock.Numbered -> "${block.number}. " + spansToText(block.spans)
        is MdBlock.Paragraph -> spansToText(block.spans)
    }
}

@Composable
fun MarkdownLite(text: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val t = AppTheme.typography
    Column(modifier) {
        parseMarkdownLite(text).forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    annotated(block.spans),
                    style = when (block.level) { 1 -> t.title2; 2 -> t.title3; else -> t.body1 },
                    color = c.textPrimary,
                    modifier = Modifier.padding(top = AppSpacing.lg, bottom = AppSpacing.xs),
                )
                is MdBlock.Bullet -> MarkerLine("•", block.spans)
                is MdBlock.Numbered -> MarkerLine("${block.number}.", block.spans)
                is MdBlock.Paragraph -> Text(
                    annotated(block.spans),
                    style = t.body1, color = c.textSecondary,
                    modifier = Modifier.padding(vertical = AppSpacing.xs),
                )
            }
        }
    }
}

@Composable
private fun MarkerLine(marker: String, spans: List<MdSpan>) {
    val c = AppTheme.colors
    Row(Modifier.padding(vertical = AppSpacing.xs)) {
        Text(marker, style = AppTheme.typography.body1, color = c.textTertiary)
        Spacer(Modifier.width(AppSpacing.sm))
        Text(annotated(spans), style = AppTheme.typography.body1, color = c.textPrimary)
    }
}

private fun annotated(spans: List<MdSpan>): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        if (span.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) } else append(span.text)
    }
}
