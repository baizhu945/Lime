package com.kingzcheung.xime.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import org.junit.Test

/** 打印当前生效的键盘主题字段，用于和 Xime 原配色对照。 */
class KeyThemeDumpTest {
    @Test fun dumpEffectiveTheme() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = SettingsPreferences.getKeyboardTheme(context)
        val dark = SettingsPreferences.getDarkMode(context)
        val theme = KeyboardThemes.getThemeById(id)
        val colors = KeysConfigHelper.getKeyboardColors()
        val lines = buildList {
            add("themeId=$id darkMode=$dark resolved=${theme.id}")
            add("keyBgLight=${theme.keyBgLight} keyBgDark=${theme.keyBgDark}")
            add("specialLight=${theme.specialKeyLight} specialDark=${theme.specialKeyDark}")
            add("kbdBgLight=${theme.keyboardBgLight} kbdBgDark=${theme.keyboardBgDark}")
            add("useThemeColors=${theme.useThemeColors} isDynamic=${theme.isDynamic}")
            add("keyBackground=${theme.keyBackground?.type} ${theme.keyBackground?.colors}")
            add("globalKeyBg=${colors.keyBgColor} globalKeyBgDark=${colors.keyBgColorDark}")
            add("textLight=${theme.keyTextColorLight} textDark=${theme.keyTextColorDark}")
        }
        lines.forEach { println("KEYTHEME $it") }
    }
}
