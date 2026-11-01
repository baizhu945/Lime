package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 键盘内各面板的同类问题一次查全（用户规则：同一件事用同一个组件、同一种表现；主题和毛玻璃处处生效）。
 * 读源码而不是逐个截图：新加面板或改动时，只要绕开共用做法就在这里失败，并写明哪个文件哪一行。
 */
class KeyboardSurfaceConsistencyTest {
    private val root = File("src/main/java/com/kingzcheung/xime")
    private fun source(path: String) = File(root, path).readText()
    private fun uiFiles() = listOf("ui/keyboard", "ui/menubar").flatMap { dir ->
        File(root, dir).listFiles { f -> f.extension == "kt" }.orEmpty().sortedBy { it.name }
    }

    /** 盖在键盘上的整块面板和覆盖层：底色都走 keyboardPanelBackground（主题底色、渐变/图片、毛玻璃），不用固定颜色。 */
    private val panels = listOf(
        "ui/keyboard/RejectedCommitOverlay.kt",
        "ui/keyboard/EmojiKeyboardLayout.kt", "ui/keyboard/SymbolKeyboardLayout.kt",
        "ui/keyboard/EditKeyboardLayout.kt", "ui/keyboard/SplitWordsView.kt", "ui/keyboard/InfoPanel.kt",
        "ui/keyboard/HandwritingKeyboardLayout.kt", "ui/keyboard/MenuBar.kt", "ui/keyboard/CandidatePage.kt",
        "ui/keyboard/QuickSendFormArea.kt", "ui/menubar/ClipboardBoardView.kt",
        "ui/menubar/SchemaListView.kt", "ui/menubar/ToolbarCustomizeView.kt",
    )

    @Test fun everyPanelPaintsItsBackgroundWithTheThemeAndGlass() {
        val missing = panels.filterNot { source(it).contains("keyboardPanelBackground(") || source(it).contains("keyboardOverlayBackground(") }
        assertEquals("这些面板没有用 keyboardPanelBackground，换主题或开毛玻璃时会和键盘不一致", emptyList<String>(), missing)
        val fixed = Regex("""\.background\(\s*Color\.(Black|White|Gray|DarkGray|LightGray)\b""")
        val scrims = panels.flatMap { path ->
            source(path).lines().mapIndexedNotNull { i, line -> if (fixed.containsMatchIn(line)) "$path:${i + 1}" else null }
        }
        assertEquals("面板底色写死了黑/白/灰，不跟主题", emptyList<String>(), scrims)
        // 玻璃下面板底是透的：盖在按键上的覆盖层要么用 keyboardOverlayBackground 挡住，要么把下面的按键压淡，
        // 否则按键和字叠在一起。光标调整走后一种（空格仍收拖动）。
        assertTrue(source("ui/keyboard/RejectedCommitOverlay.kt").contains("keyboardOverlayBackground("))
        assertTrue("光标调整时键盘内容没压淡",
            source("ui/keyboard/KeyboardView.kt").contains(".hiddenDuringCursorControl(cursorControlActive)"))
        // 光标调整不铺底：键盘自己的背景（主题、渐变、图片、玻璃）照常显示。铺不透明主题底色时深色主题整个键盘变黑。
        val cursorOverlay = source("ui/keyboard/CursorControlOverlay.kt")
        assertTrue("光标调整遮罩又铺了底色", listOf("keyboardPanelBackground(", "keyboardOverlayBackground(", ".background(")
            .none { cursorOverlay.contains(it) })
        assertTrue("光标调整时按键被整个藏掉", cursorOverlay.contains("CURSOR_CONTROL_KEY_ALPHA = 0.22f"))
    }

    @Test fun cardsAndDimsOverTheKeyboardShareTheKeyboardCardColors() {
        // 确认卡片用按键色、主题字色（keyboardCardColors），遮罩用同一层；Material 底在深色 iOS 上是白卡，黑遮罩不跟主题。
        val material = Regex("""containerColor\s*=\s*MaterialTheme\.colorScheme\.surface""")
        val fixedScrim = Regex("""\.background\(\s*Color\.(Black|White)\b""")
        // 调整尺寸时整块压暗是编辑态，不是内容面板。
        val allowed = setOf("KeyboardResizeOverlay.kt")
        val found = uiFiles().filterNot { it.name in allowed }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (material.containsMatchIn(line) || fixedScrim.containsMatchIn(line)) "${file.name}:${i + 1} ${line.trim()}" else null
            }
        }
        assertEquals(emptyList<String>(), found)
        assertTrue(source("ui/menubar/ClipboardView.kt").contains("keyboardCardColors()"))
        assertTrue(source("ui/keyboard/KeyboardView.kt").contains("keyboardCardColors()"))
    }

    @Test fun emptyRecentPagesShareOneHint() {
        // 表情、颜文字、符号的“最近”为空：同一个组件、中文提示。
        for (path in listOf("ui/keyboard/EmojiKeyboardLayout.kt", "ui/keyboard/KaomojiKeyboard.kt",
            "ui/keyboard/SymbolKeyboardLayout.kt")) {
            val text = source(path)
            assertTrue("$path 没用 PanelEmptyHint", text.contains("PanelEmptyHint(\"暂无"))
            assertTrue("$path 自己画了空页提示", Regex("""Text\(\s*(text\s*=\s*)?"暂无""").find(text) == null)
        }
    }

    @Test fun noJapaneseTextOutsideJapaneseKeyboards() {
        // 中文键盘的面板里出现日文提示（颜文字页曾是「まだ履歴がありません」）。日文键盘自己的文字和模式名除外。
        val kana = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]")
        val literal = Regex("\"([^\"\\n]*)\"")
        val allowed = setOf("あいう")
        val found = uiFiles().filterNot { it.name.startsWith("JapaneseKana") || it.name.startsWith("Kana") }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@mapIndexedNotNull null
                literal.findAll(line).map { it.groupValues[1] }
                    .firstOrNull { it !in allowed && kana.containsMatchIn(it) && !it.contains('ﾉ') }
                    ?.let { "${file.name}:${i + 1} $it" }
            }
        }
        assertEquals(emptyList<String>(), found)
    }

    @Test fun everyCompositionDisplayFollowsTheHighlightedCandidate() {
        // 屏上的编码一律取 shownPreedit（九键「下一项」换了高亮就是高亮候选的拼音）；直接读 preeditText 的只有下面几处。
        val allowed = mapOf(
            // 拼音编辑条编辑的是引擎自己的编码。
            "KeyboardView.kt" to listOf("preedit = candidateState.value.preeditText",
                // remember 的依赖项，不是显示。
                "cs.candidates, cs.candidateComments, cs.inputText, cs.preeditText"),
            // CandidateBarState 里的 preeditText 已经是 shownPreedit 的结果。
            "CandidateBar.kt" to listOf("it.preeditText.ifEmpty { it.inputText }"),
            // 候选栏与预编辑条被拆到独立组合作用域后，读候选状态的地方就在这两个 Host 里。
            "CandidateBarHost.kt" to listOf("cs.preeditText", "preedit = candidateState.value.preeditText"),
        )
        val raw = Regex("""\.preeditText\b""")
        val found = (uiFiles() + File(root, "service/XimeInputMethodService.kt")).flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (!raw.containsMatchIn(line) || allowed[file.name].orEmpty().any { line.contains(it) }) null
                else "${file.name}:${i + 1} ${line.trim()}"
            }
        }.filterNot { it.startsWith("XimeInputMethodService.kt") && !it.contains("preeditText =") }
        assertEquals(emptyList<String>(), found)
    }
}
