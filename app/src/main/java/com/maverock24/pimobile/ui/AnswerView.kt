package com.maverock24.pimobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Renders one answer: markdown blocks plus inline styling, with the tokens from
 * [AnswerStyle]. Tools and thinking are intentionally never rendered.
 */
@Composable
fun AnswerView(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseAnswerBlocks(text) }
    val scheme = MaterialTheme.colorScheme
    val linkColor = scheme.primary
    val chipColor = AnswerStyle.chipBackground(scheme.background.luminance() < 0.5f)
    val cursorColor = scheme.primary

    SelectionContainer {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(AnswerStyle.paragraphGap),
        ) {
            blocks.forEachIndexed { index, block ->
                val isLast = index == blocks.lastIndex
                when (block) {
                    is AnswerBlock.Heading -> Text(
                        text = inlineText(block.text, linkColor, chipColor),
                        fontWeight = FontWeight.Bold,
                        fontSize = AnswerStyle.headingSize,
                        lineHeight = AnswerStyle.headingSize * 1.3,
                        color = scheme.onBackground,
                        modifier = Modifier.padding(top = AnswerStyle.paragraphGap),
                    )

                    is AnswerBlock.Paragraph -> Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = inlineText(block.text, linkColor, chipColor),
                            fontSize = AnswerStyle.bodySize,
                            lineHeight = AnswerStyle.bodyLineHeight,
                            color = scheme.onBackground,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (streaming && isLast) {
                            PulsingDot(cursorColor)
                        }
                    }

                    is AnswerBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEachIndexed { itemIndex, item ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    text = if (block.ordered) "${itemIndex + 1}." else "•",
                                    color = if (block.ordered) linkColor else scheme.onSurfaceVariant,
                                    fontSize = AnswerStyle.bodySize,
                                    lineHeight = AnswerStyle.bodyLineHeight,
                                    modifier = Modifier.width(AnswerStyle.indent),
                                )
                                Text(
                                    text = inlineText(item, linkColor, chipColor),
                                    fontSize = AnswerStyle.bodySize,
                                    lineHeight = AnswerStyle.bodyLineHeight,
                                    color = scheme.onBackground,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                        }
                    }

                    is AnswerBlock.Code -> Surface(
                        shape = RoundedCornerShape(AnswerStyle.codeRadius),
                        color = scheme.surfaceVariant,
                        border = BorderStroke(1.dp, scheme.outline),
                    ) {
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = AnswerStyle.codeSize,
                            lineHeight = AnswerStyle.codeSize * 1.55,
                            color = scheme.onBackground,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(AnswerStyle.codePadding),
                        )
                    }

                    is AnswerBlock.Diff -> DiffBlock(block)

                    is AnswerBlock.Table -> TableBlock(block)

                    AnswerBlock.Rule -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(scheme.outline),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffBlock(block: AnswerBlock.Diff) {
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.path?.let { path ->
            Text(
                text = "$path  +${block.added} −${block.removed}",
                fontSize = AnswerStyle.codeSize,
                color = scheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
        ) {
            block.lines.forEach { line ->
                val background = when (line.kind) {
                    DiffLine.Kind.ADD -> AnswerStyle.diffAdd(isDark)
                    DiffLine.Kind.DEL -> AnswerStyle.diffDel(isDark)
                    DiffLine.Kind.CONTEXT -> Color.Transparent
                }
                Text(
                    text = line.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = AnswerStyle.codeSize,
                    lineHeight = AnswerStyle.codeSize * 1.5,
                    color = scheme.onBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(background)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun TableBlock(block: AnswerBlock.Table) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            block.header.forEach { cell ->
                Text(
                    text = cell,
                    fontSize = AnswerStyle.codeSize,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onBackground,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(scheme.outline))
        block.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                row.forEach { cell ->
                    Text(
                        text = cell,
                        fontSize = AnswerStyle.codeSize,
                        color = scheme.onBackground,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                }
            }
        }
    }
}

/** Pulsing dot marking text that is still arriving. */
@Composable
fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Spacer(
        modifier = Modifier
            .padding(start = 4.dp, top = 5.dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

/**
 * The only working indicator: an animated bar. The chosen design has no status
 * text, so this carries the "pi is busy" signal on its own.
 */
@Composable
fun WorkingShimmer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "shimmerProgress",
    )
    val scheme = MaterialTheme.colorScheme
    val travel = 120f
    val start = progress * travel - travel
    Box(
        modifier = modifier
            .width(64.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(scheme.surfaceVariant, scheme.primary, scheme.surfaceVariant),
                    startX = start,
                    endX = start + travel,
                ),
            ),
    )
}

private val inlineRegex = Regex(
    """(\*\*[^*\n]+\*\*|`[^`\n]+`|\[[^\]\n]+\]\([^)\s]+\)|https?://[^\s<>()\[\]{}"']+)""",
)

/** Bold, inline code chips, markdown links and bare URLs. */
private fun inlineText(raw: String, linkColor: Color, chipColor: Color): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        for (match in inlineRegex.findAll(raw)) {
            if (match.range.first > cursor) {
                append(raw.substring(cursor, match.range.first))
            }
            val token = match.value
            when {
                token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(token.removeSurrounding("**"))
                }

                token.startsWith("`") -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = AnswerStyle.bodySize * AnswerStyle.inlineCodeScale,
                        background = chipColor,
                    ),
                ) { append(token.removeSurrounding("`")) }

                token.startsWith("[") && token.contains("](") -> {
                    val label = token.substringAfter('[').substringBefore("](")
                    val url = token.substringAfter("](").removeSuffix(")")
                    appendLink(label, url, linkColor)
                }

                else -> {
                    val url = token.trimEnd('.', ',', ';', ':', '!', '?', ')')
                    val trailing = token.removePrefix(url)
                    appendLink(url, url, linkColor)
                    if (trailing.isNotEmpty()) append(trailing)
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) append(raw.substring(cursor))
    }

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendLink(
    label: String,
    url: String,
    linkColor: Color,
) {
    withLink(LinkAnnotation.Url(url)) {
        withStyle(
            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
        ) { append(label) }
    }
}
