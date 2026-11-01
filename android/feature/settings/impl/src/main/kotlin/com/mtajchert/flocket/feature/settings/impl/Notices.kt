package com.mtajchert.flocket.feature.settings.impl

import androidx.compose.runtime.Immutable

@Immutable
data class NoticeRow(
    val library: String,
    val license: String,
    val use: String,
)

@Immutable
data class Notices(
    val intro: List<String>,
    val rows: List<NoticeRow>,
    val footnotes: List<String>,
)

private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")

private fun String.plain(): String =
    LINK
        .replace(this, "$1")
        .replace("`", "")
        .replace("**", "")
        .trim()

/** Reads `THIRD_PARTY_NOTICES.md`: a heading, paragraphs and one `| Library | License | Use |` table. */
fun parseNotices(markdown: String): Notices {
    val intro = mutableListOf<String>()
    val rows = mutableListOf<NoticeRow>()
    val foot = mutableListOf<String>()
    var seenTable = false
    for (line in markdown.lines()) {
        val t = line.trim()
        when {
            t.isEmpty() || t.startsWith("#") -> {
                Unit
            }

            t.startsWith("|") -> {
                seenTable = true
                val cells = t.trim('|').split('|').map { it.trim() }
                val header = cells.firstOrNull() == "Library"
                val separator = cells.all { c -> c.all { it == '-' || it == ':' } }
                if (!header && !separator && cells.size >= 3) {
                    rows += NoticeRow(cells[0].plain(), cells[1].plain(), cells.drop(2).joinToString(" | ").plain())
                }
            }

            else -> {
                (if (seenTable) foot else intro) += t.plain()
            }
        }
    }
    return Notices(intro, rows, foot)
}
