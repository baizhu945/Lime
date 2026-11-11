package com.kingzcheung.xime.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private fun titles(q: String) = searchSettings(q).map { it.title }

    @Test fun everydayWordsFindTheSwitch() {
        // 用户以为九键长按坏了：搜“长按”要能找到九键拂动。
        assertTrue("拂动选字母" in titles("长按"))
        assertTrue("数字框自动数字键盘" in titles("验证码"))
        assertTrue("按键效果" in titles("振动"))
    }

    @Test fun englishWordsFindTheGlossSetting() {
        // 用户 10-30：想在中文键盘里选英文单词，搜“单词”“英文”找不到要选「释义」的那一项。
        for (q in listOf("单词", "英文单词", "选英文", "下一项")) assertEquals(q, "候选注释内容", titles(q).first())
        assertTrue("候选注释内容" in titles("英文"))
    }

    @Test fun titleMatchesComeFirst() {
        assertEquals("拂动选字母", titles("拂动").first())
    }

    @Test fun allTermsMustMatchAndBlankFindsNothing() {
        assertEquals("假名方向提示", titles("假名 方向").first())
        assertTrue(titles("假名 方向").none { it == "按键效果" })
        assertTrue(titles("   ").isEmpty())
        assertTrue(titles("不存在的设置词").isEmpty())
    }

    @Test fun layoutSubPagesExistAndEveryPageIsSearchable() {
        val pages = LayoutDisplayPages.map { it.first }.toSet()
        val linked = SettingsSearchIndex.mapNotNull { it.target.substringAfter("?page=", "").ifEmpty { null } }.toSet()
        assertEquals(pages, linked)
    }

    /** 「时长与距离」默认收起：里面每一项搜到都要能让它自动展开（用户 10-31）。 */
    @Test fun foldedTimingItemsExpandOnSearch() {
        val folded = SettingsSearchIndex.filter { it.section == "时长与距离" }.map { it.title }
        assertEquals(5, folded.size)
        assertTrue("没登记自动展开：${folded - TIMING_SEARCH_KEYS}", TIMING_SEARCH_KEYS.containsAll(folded))
    }

    /** 新设置树：每项只出现一处，路径都从首页五组之一开始。 */
    @Test fun everySettingLivesInOnePlaceUnderTheNewGroups() {
        val dup = SettingsSearchIndex.groupBy { it.title }.filterValues { it.size > 1 }.keys
        assertTrue("重复：$dup", dup.isEmpty())
        val groups = setOf("配置选择", "输入", "外观", "智能", "系统")
        val stray = SettingsSearchIndex.filter { it.path.substringBefore(" › ") !in groups }.map { it.title }
        assertTrue("不在新分组下：$stray", stray.isEmpty())
    }

    /** 说明里的词、同义说法、语音输入常见的同音错字都能找到。 */
    @Test fun descriptionsSynonymsAndVoiceTypos() {
        assertEquals("触觉反馈模式", titles("触觉反馈").first())
        assertTrue("触觉反馈模式" in titles("震动"))
        assertTrue("按键振动振幅" in titles("震动强度"))
        assertTrue("底排标点" in titles("拳角"))
        assertTrue("底排标点" in titles("板角"))
        assertTrue("靠边停靠" in titles("扶窗"))
        assertTrue("显示模式" in titles("夜间模式"))
        assertTrue("玻璃效果" in titles("毛玻璃"))
        assertTrue("剪贴板卡片最多显示" in titles("剪切板"))
        assertTrue("性能与省电" in titles("卡顿"))
        assertTrue("我的短语" in titles("快捷短语"))
        assertTrue("Ctrl + 空格" in titles("ctrl"))
        assertTrue("Ctrl + 空格" in titles("ＣＴＲＬ"))
    }

    /** 开着「繁体输出」搜设置：繁体字按简体找（10-31 新用户模拟里搜“十四鍵”什么都没找到）。 */
    @Test fun traditionalCharactersFindSimplifiedSettings() {
        assertEquals(titles("繁体输出"), titles("繁體輸出"))
        assertTrue("繁体输出" in titles("繁體"))
        assertEquals(titles("14键"), titles("十四鍵"))
        assertTrue(titles("十四鍵").isNotEmpty())
        assertTrue("剪贴板卡片最多显示" in titles("剪貼板"))
    }

    /** 「时间日期候选」按说法能搜到（用户 10-31 要的功能）。 */
    @Test fun timeAndDateCandidatesAreSearchable() {
        assertTrue("时间日期候选" in titles("当前时间"))
        assertTrue("时间日期候选" in titles("日期"))
    }

    /** 「打符号名出符号」按符号名和说法能搜到。 */
    @Test fun symbolNameCandidatesAreSearchable() {
        assertTrue("打符号名出符号" in titles("加号"))
        assertTrue("打符号名出符号" in titles("符号名"))
    }

    /** 14 键默认没启用：搜“14键”要能找到启用它的「已安装的方案」，不只是 14 键的手势设置。 */
    @Test fun fourteenKeySearchFindsWhereToEnableIt() {
        assertTrue("已安装的方案" in titles("14键"))
        assertTrue("已安装的方案" in titles("启用14键"))
    }

    /** 词没有连着出现时按相邻两字近似匹配：“候选字号”“按键声音大小”这类自己组的说法。 */
    @Test fun nearMatchesFindSettingsWithoutExactWords() {
        assertEquals("候选字大小", titles("候选字号").first())
        assertTrue("音量大小" in titles("按键声音大小"))
        assertTrue("长按语言键弹出列表" in titles("语言键长按时间"))
        // 只沾一个字的不算：太宽的近似会把整张列表都翻出来。
        assertTrue(titles("键盘高度调节").size < 5)
    }

    /** 「数字行」：用户说法「数字栏」「平板一排数字」都能找到。 */
    @Test fun numberRowIsSearchable() {
        assertTrue("数字行" in titles("数字栏"))
        assertTrue("数字行" in titles("平板 一排数字"))
    }

    /** 定位的锚点必须真在页面源码里，改了行标题而没改索引会在这里失败。 */
    @Test fun everyAnchorExistsOnItsPage() {
        val dir = java.io.File("src/main/java/com/kingzcheung/xime/ui/settings")
        val source = dir.listFiles { f -> f.extension == "kt" && f.name != "SettingsSearch.kt" }!!
            .joinToString("\n") { it.readText() }
        val missing = SettingsSearchIndex.flatMap { listOfNotNull(it.anchor, it.section) }.distinct()
            .filter { "\"$it\"" !in source }
        assertTrue("页面上找不到：$missing", missing.isEmpty())
    }

    @Test fun focusPrefersTheRowAndFallsBackToItsGroup() {
        val entry = SettingsSearchIndex.first { it.title == "移动光标时振动" }
        // 行此刻不显示（滑动移动光标已关）：只有组在页面上，组亮。
        SettingsSearchFocus.open(entry)
        val first = SettingsSearchFocus.request!!
        SettingsSearchFocus.register("光标与空格")
        assertTrue(SettingsSearchFocus.claim("光标与空格", first))
        assertNull(SettingsSearchFocus.request)
        // 行和组都在：组让给行，行亮一次后请求清空。
        SettingsSearchFocus.open(entry)
        val second = SettingsSearchFocus.request!!
        SettingsSearchFocus.register("移动光标时振动")
        assertFalse(SettingsSearchFocus.claim("光标与空格", second))
        assertTrue(SettingsSearchFocus.claim("移动光标时振动", second))
        assertFalse(SettingsSearchFocus.claim("移动光标时振动", second))
        SettingsSearchFocus.unregister("光标与空格")
        SettingsSearchFocus.unregister("移动光标时振动")
        // 整页条目不带定位。
        SettingsSearchFocus.open(SettingsSearchIndex.first { it.title == "主题与外观" })
        assertNull(SettingsSearchFocus.request)
    }
}
