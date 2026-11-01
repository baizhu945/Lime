package com.kingzcheung.xime.clipboard

import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clipboard text records written out as a file the user saves. Fixed records come first, then
 * history, newest first. Images are not exported (they are files of their own). Markdown and JSON
 * also name each fixed record's group, so [ClipboardImport] can put it back.
 */
internal enum class ClipboardExportFormat(val label: String, val extension: String, val mimeType: String) {
    TXT("TXT", "txt", "text/plain"),
    MD("Markdown", "md", "text/markdown"),
    JSON("JSON", "json", "application/json"),
}

internal object ClipboardExport {
    private fun time(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    /** Fixed records first, then history; newest first within each. */
    fun order(items: List<ClipboardItem>, includePinned: Boolean): List<ClipboardItem> =
        items.filter { !it.isQuickSend && (includePinned || !it.isPinned) }
            .sortedWith(compareByDescending<ClipboardItem> { it.isPinned }.thenByDescending { it.timestamp })

    fun fileName(format: ClipboardExportFormat, now: Long): String =
        "Lime剪贴板-${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))}.${format.extension}"

    /** [groupOf]: the name of the clipboard group a record is in, null when it is in none. */
    fun write(items: List<ClipboardItem>, format: ClipboardExportFormat, out: Writer, now: Long,
        groupOf: (ClipboardItem) -> String? = { null }) {
        when (format) {
            ClipboardExportFormat.TXT -> items.forEachIndexed { i, item ->
                if (i > 0) out.write("\n\n----------\n\n")
                out.write(item.text)
            }
            ClipboardExportFormat.MD -> {
                out.write("# Lime 剪贴板（${time(now)} 导出，${items.size} 条）\n")
                val (pinned, history) = items.partition { it.isPinned }
                for ((title, group) in listOf("固定内容" to pinned, "历史记录" to history)) {
                    if (group.isEmpty()) continue
                    out.write("\n## $title\n")
                    for (item in group) {
                        val groupNote = groupOf(item)?.let { "（分组 $it）" }.orEmpty()
                        out.write("\n### ${time(item.timestamp)}${if (item.code.isNotEmpty()) "（编码 ${item.code}）" else ""}$groupNote\n\n")
                        // A fence longer than any backtick run inside keeps the text verbatim.
                        val fence = "`".repeat(maxOf(3, longestRun(item.text, '`') + 1))
                        out.write("$fence\n${item.text}\n$fence\n")
                    }
                }
            }
            ClipboardExportFormat.JSON -> {
                val array = JSONArray()
                for (item in items) array.put(JSONObject().apply {
                    put("text", item.text)
                    put("pinned", item.isPinned)
                    if (item.code.isNotEmpty()) put("code", item.code)
                    groupOf(item)?.let { put("group", it) }
                    put("time", time(item.timestamp))
                    put("timestamp", item.timestamp)
                })
                out.write(JSONObject().put("app", "CyIME").put("exported", time(now)).put("count", items.size)
                    .put("items", array).toString(2))
            }
        }
        out.flush()
    }

    private fun longestRun(text: String, c: Char): Int {
        var best = 0; var run = 0
        for (ch in text) { run = if (ch == c) run + 1 else 0; if (run > best) best = run }
        return best
    }
}
