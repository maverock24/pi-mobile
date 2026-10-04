package com.maverock24.pimobile.ui

/**
 * Minimal markdown block parser for agent answers.
 *
 * pi answers are markdown, and rendering them as one flat string is why the
 * formatting read badly. This turns an answer into blocks that the Composable
 * layer can style independently: headings, paragraphs, bullet and numbered
 * lists, fenced code, diffs, tables and rules.
 *
 * It is deliberately forgiving: streaming answers arrive half-written, so an
 * unterminated code fence renders as code rather than being dropped.
 */
sealed interface AnswerBlock {
    data class Heading(val level: Int, val text: String) : AnswerBlock
    data class Paragraph(val text: String) : AnswerBlock
    data class Quote(val text: String) : AnswerBlock
    data class Bullets(val items: List<BulletItem>, val ordered: Boolean) : AnswerBlock
    data class Code(val language: String?, val code: String) : AnswerBlock
    data class Diff(val path: String?, val lines: List<DiffLine>) : AnswerBlock {
        val added: Int get() = lines.count { it.kind == DiffLine.Kind.ADD }
        val removed: Int get() = lines.count { it.kind == DiffLine.Kind.DEL }
    }
    data class Table(val header: List<String>, val rows: List<List<String>>) : AnswerBlock
    data object Rule : AnswerBlock
}

/** One list entry. [level] is nesting depth, [marker] marks a bullet inside an ordered list. */
data class BulletItem(val text: String, val level: Int = 0, val marker: Boolean = false)

data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind { ADD, DEL, CONTEXT }
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val orderedRegex = Regex("^(\\s*)\\d+[.)]\\s+(.*)$")
private val quoteRegex = Regex("^\\s*>\\s?(.*)$")
private val ruleRegex = Regex("^\\s*([-*_])\\1{2,}\\s*$")
private val tableRowRegex = Regex("^\\s*\\|(.+)\\|\\s*$")
private val tableDividerRegex = Regex("^\\s*\\|?[\\s:-]*-[\\s|:-]*$")

fun parseAnswerBlocks(text: String): List<AnswerBlock> {
    val blocks = ArrayList<AnswerBlock>()
    val lines = text.replace("\r\n", "\n").split('\n')
    val paragraph = StringBuilder()
    var index = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(AnswerBlock.Paragraph(paragraph.toString().trim()))
            paragraph.setLength(0)
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        // fenced code (or an unterminated one while streaming)
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val language = trimmed.removePrefix("```").trim().ifBlank { null }
            val body = ArrayList<String>()
            index++
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                body.add(lines[index])
                index++
            }
            index++ // past the closing fence
            blocks.add(codeBlock(language, body))
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            index++
            continue
        }

        val heading = headingRegex.find(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks.add(AnswerBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim()))
            index++
            continue
        }

        if (ruleRegex.matches(trimmed)) {
            flushParagraph()
            blocks.add(AnswerBlock.Rule)
            index++
            continue
        }

        if (tableRowRegex.matches(trimmed) && index + 1 < lines.size && tableDividerRegex.matches(lines[index + 1].trim())) {
            flushParagraph()
            val header = splitRow(trimmed)
            val rows = ArrayList<List<String>>()
            index += 2
            while (index < lines.size && tableRowRegex.matches(lines[index].trim())) {
                rows.add(splitRow(lines[index].trim()))
                index++
            }
            blocks.add(AnswerBlock.Table(header, rows))
            continue
        }

        if (quoteRegex.matches(line)) {
            flushParagraph()
            val quoted = ArrayList<String>()
            while (index < lines.size) {
                val match = quoteRegex.find(lines[index])
                if (match == null) break
                quoted.add(match.groupValues[1].trim())
                index++
            }
            blocks.add(AnswerBlock.Quote(quoted.joinToString(" ").trim()))
            continue
        }

        if (bulletRegex.matches(line) || orderedRegex.matches(line)) {
            flushParagraph()
            val ordered = orderedRegex.matches(line)
            val items = ArrayList<BulletItem>()
            while (index < lines.size) {
                val isOrdered = orderedRegex.matches(lines[index])
                val isBullet = bulletRegex.matches(lines[index])
                if (!isOrdered && !isBullet) break
                val match = (if (isOrdered) orderedRegex else bulletRegex).find(lines[index])!!
                val level = (match.groupValues[1].length / 2).coerceIn(0, 3)
                items.add(BulletItem(text = match.groupValues[2].trim(), level = level, marker = ordered && isBullet))
                index++
                // continuation lines belong to the current item
                while (index < lines.size) {
                    val next = lines[index]
                    if (next.isBlank()) break
                    val nextTrimmed = next.trim()
                    if (bulletRegex.matches(next) || orderedRegex.matches(next)) break
                    if (headingRegex.matches(nextTrimmed) || nextTrimmed.startsWith("```") || quoteRegex.matches(next)) break
                    val last = items.removeAt(items.size - 1)
                    items.add(last.copy(text = last.text + " " + nextTrimmed))
                    index++
                }
            }
            blocks.add(AnswerBlock.Bullets(items, ordered))
            continue
        }

        paragraph.append(trimmed).append(' ')
        index++
    }

    flushParagraph()
    return blocks
}

private fun codeBlock(language: String?, body: List<String>): AnswerBlock {
    val isDiff = language.equals("diff", ignoreCase = true) ||
        (language == null && body.count { it.trimStart().startsWith("+") || it.trimStart().startsWith("-") } >= 2)
    if (!isDiff) {
        return AnswerBlock.Code(language, body.joinToString("\n"))
    }
    val path = body.firstOrNull { it.contains("+++") }?.removePrefix("+++")?.trim()?.removePrefix("b/")
    val lines = body.filterNot { it.startsWith("+++") || it.startsWith("---") }.map { raw ->
        when {
            raw.startsWith("@@") -> DiffLine(DiffLine.Kind.CONTEXT, raw)
            raw.startsWith("+") -> DiffLine(DiffLine.Kind.ADD, raw)
            raw.startsWith("-") -> DiffLine(DiffLine.Kind.DEL, raw)
            else -> DiffLine(DiffLine.Kind.CONTEXT, raw)
        }
    }
    return AnswerBlock.Diff(path, lines)
}

private fun splitRow(row: String): List<String> =
    row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
