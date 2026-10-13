package com.talos.harness.ui.markdown

/** One source line from a Markdown ordered list. */
data class OrderedListLine(val number: Int, val delimiter: Char, val text: String)

private val orderedListLineRegex = Regex("""^ {0,3}(\d{1,9})([.)])[ \t]+(.*)$""")

/** Parses an ordered-list line while keeping its short marker separate from its body. */
fun parseOrderedListLine(line: String): OrderedListLine? {
    val match = orderedListLineRegex.matchEntire(line) ?: return null
    val number = match.groupValues[1].toIntOrNull() ?: return null
    return OrderedListLine(number, match.groupValues[2].single(), match.groupValues[3])
}
