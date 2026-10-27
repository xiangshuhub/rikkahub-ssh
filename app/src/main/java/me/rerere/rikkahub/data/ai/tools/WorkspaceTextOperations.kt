package me.rerere.rikkahub.data.ai.tools

internal data class WorkspaceTextEdit(val oldText: String, val newText: String)

/** All matches refer to the original snapshot; nothing is saved on validation failure. */
internal fun applyWorkspaceEdits(original: String, edits: List<WorkspaceTextEdit>): String {
    require(edits.isNotEmpty() && edits.size <= 100) { "Provide 1–100 edits" }
    val ranges = edits.map { edit ->
        require(edit.oldText.isNotEmpty()) { "old_text must not be empty" }
        val start = original.indexOf(edit.oldText)
        require(start >= 0) { "Exact text not found; read the file again. No changes saved." }
        require(original.indexOf(edit.oldText, start + 1) < 0) { "Text matches multiple locations; include unique context. No changes saved." }
        Triple(start, start + edit.oldText.length, edit.newText)
    }.sortedBy { it.first }
    ranges.zipWithNext().forEach { (left, right) ->
        require(left.second <= right.first) { "Edits overlap; merge overlapping edits. No changes saved." }
    }
    return buildString {
        var position = 0
        ranges.forEach { (start, end, replacement) ->
            append(original, position, start); append(replacement); position = end
        }
        append(original, position, original.length)
    }
}

internal data class WorkspaceTextPage(val text: String, val totalLines: Int, val nextOffset: Int?)

internal fun workspaceTextPage(text: String, offset: Int = 1, limit: Int = 2000): WorkspaceTextPage {
    require(offset >= 1 && limit in 1..2000) { "offset must be >= 1; limit must be 1–2000" }
    val lines = text.split('\n')
    require(offset <= lines.size) { "offset exceeds file length (${lines.size} lines)" }
    val selected = mutableListOf<String>()
    var bytes = 0
    for (index in offset - 1 until minOf(lines.size.toLong(), offset - 1L + limit).toInt()) {
        val line = lines[index]
        val size = line.toByteArray(Charsets.UTF_8).size + 1
        if (bytes + size > 48 * 1024) {
            require(selected.isNotEmpty()) { "Single line exceeds output limit; use shell to inspect a bounded substring." }
            break
        }
        selected += line
        bytes += size
    }
    val next = offset + selected.size
    return WorkspaceTextPage(selected.joinToString("\n"), lines.size, next.takeIf { it <= lines.size })
}
