package com.kingzcheung.xime.clipboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.StringWriter

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ClipboardExportTest {
    private val items = listOf(
        ClipboardItem(1, "旧的历史", timestamp = 100),
        ClipboardItem(2, "我的地址", code = "dz", timestamp = 50, isPinned = true),
        ClipboardItem(3, "新的历史 ```代码```", timestamp = 300),
        ClipboardItem(4, "旧快捷发送", timestamp = 400, isQuickSend = true),
    )

    private fun export(format: ClipboardExportFormat, pinned: Boolean = true) =
        StringWriter().also { ClipboardExport.write(ClipboardExport.order(items, pinned), format, it, 1000) }.toString()

    @Test fun fixedFirstThenNewestAndPinnedCanBeLeftOut() {
        assertEquals(listOf(2L, 3L, 1L), ClipboardExport.order(items, true).map { it.id })
        assertEquals(listOf(3L, 1L), ClipboardExport.order(items, false).map { it.id })
    }

    @Test fun textKeepsEachRecordVerbatimBetweenSeparators() {
        assertEquals("我的地址\n\n----------\n\n新的历史 ```代码```\n\n----------\n\n旧的历史", export(ClipboardExportFormat.TXT))
    }

    @Test fun markdownFencesAreLongerThanAnyBackticksInside() {
        val md = export(ClipboardExportFormat.MD)
        assertTrue(md.contains("## 固定内容"))
        assertTrue(md.contains("（编码 dz）"))
        assertTrue(md.contains("````\n新的历史 ```代码```\n````"))
        assertTrue(md.indexOf("## 固定内容") < md.indexOf("## 历史记录"))
    }

    @Test fun jsonHoldsTextPinnedCodeAndTime() {
        val json = JSONObject(export(ClipboardExportFormat.JSON))
        assertEquals(3, json.getInt("count"))
        val first = json.getJSONArray("items").getJSONObject(0)
        assertEquals("我的地址", first.getString("text"))
        assertTrue(first.getBoolean("pinned"))
        assertEquals("dz", first.getString("code"))
        assertEquals(50L, first.getLong("timestamp"))
    }

    @Test fun fileNameCarriesTheFormatExtension() {
        assertTrue(ClipboardExport.fileName(ClipboardExportFormat.MD, 0).endsWith(".md"))
        assertTrue(ClipboardExport.fileName(ClipboardExportFormat.JSON, 0).startsWith("Lime剪贴板-"))
    }
}
