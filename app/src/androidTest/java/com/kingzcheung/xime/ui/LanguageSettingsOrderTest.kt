package com.kingzcheung.xime.ui

import androidx.compose.ui.test.assertIsSelected
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.menubar.SchemaListView
import com.kingzcheung.xime.ui.settings.LanguageSettingsContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LanguageSettingsOrderTest {
    @get:Rule val rule = createComposeRule()

    private fun dragCardOver(fromTag: String, toTag: String) {
        rule.onNodeWithTag(toTag).performScrollTo()
        rule.waitForIdle()
        val targetBounds = rule.onNodeWithTag(toTag).fetchSemanticsNode().boundsInRoot
        val rootBounds = rule.onNodeWithTag("language-settings").fetchSemanticsNode().boundsInRoot
        assertTrue("$toTag must be laid out inside the panel: $targetBounds / $rootBounds",
            targetBounds.width > 0f && targetBounds.height > 0f &&
                targetBounds.top >= rootBounds.top - 1f && targetBounds.bottom <= rootBounds.bottom + 1f &&
                targetBounds.left >= rootBounds.left - 1f && targetBounds.right <= rootBounds.right + 1f)
        val source = rule.onNodeWithTag(fromTag).assertIsDisplayed()
        val from = source.fetchSemanticsNode().boundsInRoot
        val to = rule.onNodeWithTag(toTag).fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        try {
            source.performTouchInput { down(Offset(width * 0.25f, center.y)) }
            rule.mainClock.advanceTimeBy(1200)
            val delta = (to.center.y - from.center.y) / 5f
            repeat(5) {
                source.performTouchInput { moveBy(Offset(0f, delta)) }
                rule.mainClock.advanceTimeByFrame()
            }
            source.performTouchInput { up() }
        } finally { rule.mainClock.autoAdvance = true }
        rule.waitForIdle()
    }

    private fun isolatedContext(): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "language-order-audit-${System.nanoTime()}"
        return object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("$namespace-$name", mode)
        }
    }

    @Test fun completedSetupWithoutLanguageChoiceStartsWithChineseAndRequiredEnglish() {
        val context = isolatedContext()
        val prefs = SettingsPreferences.getPrefsPublic(context)
        SettingsPreferences.setSetupCompleted(context, true)
        assertEquals(setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH, InputLanguage.UNSPECIFIED),
            LanguagePreferences.enabled(context))
        LanguagePreferences.initialize(context)
        assertEquals(setOf("zh", "en"), prefs.getStringSet(LanguagePreferences.KEY, emptySet()))

        prefs.edit().putStringSet(LanguagePreferences.KEY, setOf("zh", "ja")).commit()
        assertEquals(setOf(InputLanguage.CHINESE, InputLanguage.JAPANESE, InputLanguage.ENGLISH,
            InputLanguage.UNSPECIFIED), LanguagePreferences.enabled(context))
        LanguagePreferences.initialize(context)
        assertEquals(setOf("zh", "ja", "en"), prefs.getStringSet(LanguagePreferences.KEY, emptySet()))
    }

    @Test fun oldJapaneseOnlyChoiceKeepsChineseOffAndAddsRequiredEnglish() {
        val context = isolatedContext()
        val prefs = SettingsPreferences.getPrefsPublic(context)
        prefs.edit().putStringSet(LanguagePreferences.KEY, setOf("ja")).commit()
        LanguagePreferences.initialize(context)
        assertEquals(setOf("ja", "en"), prefs.getStringSet(LanguagePreferences.KEY, emptySet()))
        assertEquals(setOf(InputLanguage.JAPANESE, InputLanguage.ENGLISH, InputLanguage.UNSPECIFIED),
            LanguagePreferences.enabled(context))
        assertEquals(listOf(InputLanguage.JAPANESE, InputLanguage.ENGLISH), InputModes.languageOrder(context))

        prefs.edit().putStringSet(LanguagePreferences.KEY, setOf("en")).commit()
        assertEquals(setOf(InputLanguage.ENGLISH, InputLanguage.UNSPECIFIED),
            LanguagePreferences.enabled(context))
        assertEquals(listOf(InputLanguage.ENGLISH), InputModes.languageOrder(context))
        val choices = InputModes.languageChoices(listOf(
            SchemaInfo("rime_ice", "中文26键", "", "", ""),
            SchemaInfo("japanese", "日语", "", "", "")),
            "rime_ice", emptyMap(), InputModes.languageOrder(context))
        assertEquals(listOf(InputModes.ENGLISH), choices.map { it.schemaId })
    }

    @Test fun englishCannotBeDisabledWhileChineseCan() {
        val context = isolatedContext()
        SettingsPreferences.getPrefsPublic(context).edit()
            .putStringSet(LanguagePreferences.KEY, setOf("en")).commit()
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { Box(Modifier.requiredSize(360.dp, 720.dp)) { LanguageSettingsContent {} } }
            }
        }
        rule.onNodeWithTag("language-toggle:en").assertIsNotEnabled()
        rule.onNodeWithTag("language-toggle:zh").assertIsEnabled()
        rule.onNodeWithTag("language-toggle:ja").assertIsEnabled()
    }

    @Test fun languageManagementOrderSurvivesOpeningSchemePanel() {
        val context = isolatedContext()
        SettingsPreferences.getPrefsPublic(context).edit()
            .putStringSet(LanguagePreferences.KEY, setOf("zh", "ja", "en")).commit()
        InputModes.saveLanguageOrder(context, listOf("en", "ja", "zh"))
        val showSettings = mutableStateOf(true)
        val schemas = listOf(SchemaInfo("rime_ice", "中文26键", "", "", ""))
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    if (showSettings.value) {
                        Box(Modifier.requiredSize(360.dp, 720.dp)) { LanguageSettingsContent {} }
                    } else {
                        SchemaListView(schemas, "rime_ice", Color.White, Color.Blue, Color.Black,
                            Color.LightGray, {}, modifier = Modifier.requiredSize(360.dp, 260.dp))
                    }
                }
            }
        }
        fun assertManagementOrder(ids: List<String>) {
            val tops = ids.map { rule.onNodeWithTag("language-$it").fetchSemanticsNode().boundsInRoot.top }
            assertTrue("语言管理排列应为 $ids: $tops", tops.zipWithNext().all { (a, b) -> a < b })
        }
        assertManagementOrder(listOf("en", "ja", "zh"))
        rule.onAllNodesWithText("顺序 1").assertCountEquals(0)
        rule.onAllNodesWithTag("language-order-up:zh").assertCountEquals(0)
        dragCardOver("language-drag:zh", "language-drag:ja")
        assertEquals(listOf("en", "zh", "ja"), InputModes.languageOrder(context).map { it.id })
        assertEquals(setOf(InputLanguage.CHINESE, InputLanguage.JAPANESE, InputLanguage.ENGLISH,
            InputLanguage.UNSPECIFIED), LanguagePreferences.enabled(context))

        rule.runOnIdle { showSettings.value = false }
        rule.onAllNodesWithTag("profile-language").assertCountEquals(0)
        rule.onNodeWithTag("panel-scheme:rime_ice").assertIsSelected()
        assertEquals(listOf("en", "zh", "ja"), InputModes.languageOrder(context).map { it.id })
        rule.runOnIdle { showSettings.value = true }
        assertManagementOrder(listOf("en", "zh", "ja"))
    }

    @Test fun narrowLargeTextKeepsWholeCardDragUsable() {
        val context = isolatedContext()
        SettingsPreferences.getPrefsPublic(context).edit()
            .putStringSet(LanguagePreferences.KEY, setOf("zh", "ja", "en")).commit()
        // The drag fixture uses a personal three-language order, not the Chinese/English defaults.
        InputModes.saveLanguageOrder(context, listOf("zh", "ja", "en"))
        val fontScale = mutableStateOf(1f)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context,
                LocalDensity provides Density(1f, fontScale.value)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(280.dp, 600.dp)) { LanguageSettingsContent {} }
                }
            }
        }
        rule.onNodeWithTag("language-expand:zh").performClick()
        rule.waitForIdle()
        for (font in listOf(1f, 1.3f, 2f)) {
            rule.runOnIdle { fontScale.value = font }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(200)
            rule.waitForIdle()
            val card = rule.onNodeWithTag("language-drag:zh").performScrollTo().assertIsDisplayed()
            val bounds = card.fetchSemanticsNode().boundsInRoot
            assertTrue("${font}x 语言卡片拖动区域高度不足: $bounds", bounds.height >= 71f)
            assertTrue("${font}x 语言卡片拖动区域宽度不足: $bounds", bounds.width >= 247f)
            dragCardOver("language-drag:zh", "language-drag:ja")
            assertEquals("font=$font", listOf("ja", "zh", "en"), InputModes.languageOrder(context).map { it.id })
            dragCardOver("language-drag:zh", "language-drag:ja")
            assertEquals(listOf("zh", "ja", "en"), InputModes.languageOrder(context).map { it.id })
            rule.assertGeometry("language-settings", "语言管理 280dp 字体 ${font}x")
        }
    }
}
