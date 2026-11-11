package com.kingzcheung.xime.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import org.junit.Before

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.KeyboardRowsLayout
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class FourteenBottomRowGeometryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepFixtureVisible() {
        rule.runOnUiThread { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    private var previousRowsLayout: Any? = null
    private val rowsLayoutField = KeysConfigHelper::class.java.getDeclaredField("_zhRowsLayout").apply { isAccessible = true }

    @After fun restoreLayout() {
        previousRowsLayout?.let { rowsLayoutField.set(KeysConfigHelper, it) }
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
    }

    @Test fun bottomRowMatchesQwertyAcrossSchemasSizesFontsAndGlass() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        // Keep the real configured rows (including controls), but select the legacy
        // renderer for the 26-key reference so its two mode slots are comparable.
        // The schema-aware row API and default-size calculation still see production input.
        val originalLayout = rowsLayoutField.get(KeysConfigHelper) as KeyboardRowsLayout
        previousRowsLayout = originalLayout
        rowsLayoutField.set(KeysConfigHelper, originalLayout.copy(columns = 10))
        val vm = KeyboardViewModel(app)
        var schema by mutableStateOf("rime_ice")
        var width by mutableIntStateOf(360)
        var height by mutableIntStateOf(260)
        var fontScale by mutableFloatStateOf(1f)
        var floating by mutableStateOf(false)
        var glass by mutableStateOf(false)
        var targetPitch: Float? = null
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale),
                LocalKeyboardExplicitWidth provides true,
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(frostedGlass = FrostedGlassConfig(enabled = glass))) {
                MaterialTheme {
                    targetPitch = keyboardPhysicalMetrics(app, LocalView.current.display, 1f)
                        ?.dpFromMillimeters(CALIBRATED_QWERTY_PITCH_MM)
                    KeyboardLayout({}, vm, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                        KeyboardUiState(currentSchemaId = schema, isFloatingMode = floating), false,
                        Modifier.requiredSize(width.dp, height.dp).testTag("layout-body"))
                }
            }
        }
        val tags = listOf("mode-slot-1", "mode-slot-2", "qwerty-punctuation-key", "space-key", "qwerty-enter-key")
        fun bounds(): Map<String, Rect> {
            val body = rule.onNodeWithTag("layout-body", true).fetchSemanticsNode().boundsInRoot
            return tags.associateWith { tag ->
                val rect = rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot
                assertTrue("$schema $tag must fit $body: $rect", rect.left >= body.left - 1f && rect.right <= body.right + 1f &&
                    rect.top >= body.top - 1f && rect.bottom <= body.bottom + 1f && rect.height > 0f && rect.width > 0f)
                rect.translate(-body.topLeft)
            }
        }
        for ((size, bodyHeight, large) in listOf(Triple(280, 180, true), Triple(360, 260, false),
            Triple(800, 500, false), Triple(1000, 260, true))) {
            rule.runOnIdle {
                width = size; height = bodyHeight; fontScale = if (large) 1.5f else 1f; glass = large; floating = size == 280
                KeysConfigHelper.setActiveKeyboardSchema("rime_ice"); schema = "rime_ice"
            }
            rule.waitForIdle()
            val expected = bounds()
            val referenceRows = KeysConfigHelper.getKeyRows("rime_ice", false)
            assertEquals("reference must retain the configured control row", 4, referenceRows.size)
            assertTrue("reference must retain shift", "shift" in referenceRows[2])
            assertEquals("active-row API exposes only letters", 3, KeysConfigHelper.getKeyRows(false).size)
            val expectedLetterDefaults = letterKeyboardDefaults(
                KeyboardUiState(currentSchemaId = "rime_ice").inputProfile, 1000, false)
            assertNotNull("configured 26-key layout must use letter defaults", expectedLetterDefaults)
            for (target in listOf("pinyin_14jian", "double_pinyin_flypy_14jian", "pinyin_17jian",
                "pinyin_18jian", "pinyin_qwjrtk", "pinyin_cyletix10")) {
                rule.runOnIdle { KeysConfigHelper.setActiveKeyboardSchema(target); schema = target }
                rule.waitForIdle()
                val folder = File(app.getExternalFilesDir(null), "letter-family-geometry").apply { mkdirs() }
                File(folder, "$target-$size-$bodyHeight.png").outputStream().use {
                    rule.onNodeWithTag("layout-body", true).captureToImage().asAndroidBitmap()
                        .compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                assertEquals("$target must share default size calculation", expectedLetterDefaults,
                    letterKeyboardDefaults(KeyboardUiState(currentSchemaId = target).inputProfile, 1000, false))
                for ((tag, actual) in bounds()) {
                    val reference = expected.getValue(tag)
                    assertEquals("$target $size $tag left", reference.left, actual.left, 1f)
                    assertEquals("$target $size $tag top", reference.top, actual.top, 1f)
                    assertEquals("$target $size $tag width", reference.width, actual.width, 1f)
                    assertEquals("$target $size $tag height", reference.height, actual.height, 1f)
                }
                if (target == "pinyin_14jian" || target == "double_pinyin_flypy_14jian") {
                    fun key(id: String) = rule.onNodeWithTag("qwerty-key:$id", true).fetchSemanticsNode().boundsInRoot
                    val pitch = key("qw").width / 2f
                    val geometry = standardLetterRowGeometry(KeysConfigHelper.getKeyRows(false), pitch,
                        false, false, targetPitch)!!
                    // 双字母键与 26 键同一网格：每个合并键占两个字母的位置。
                    assertEquals("as cap must match df", key("df").width,
                        key("as").width - geometry.middleRowInsetDp, 1.1f)
                    assertEquals("df spans two letters", 2f * pitch, key("df").width, 1.1f)
                    // l 画到行尾，与上一行 op 右边对齐。
                    assertEquals("l reaches the row end like op", key("op").right, key("l").right, 1.1f)
                    for (id in listOf("cv", "bn")) {
                        assertEquals("$id cap must match zx", key("zx").width, key(id).width, 1f)
                        assertEquals("$id row height", key("qw").height, key(id).height, 1f)
                    }
                    // m 与 26 键同宽；只有错位让删除键比 Shift 宽时才分走富余的一半。
                    val borrowed = fourteenMBorrowedWeight(geometry.outerKeyWeight, geometry.deleteKeyWeight)
                    assertEquals("m borrows from delete", (1f + borrowed) * pitch, key("m").width, 1.1f)
                }
            }
            rule.runOnIdle { KeysConfigHelper.setActiveKeyboardSchema("rime_ice"); schema = "rime_ice" }
            rule.waitForIdle()
            assertEquals("restoring the reference schema must preserve its defaults", expectedLetterDefaults,
                letterKeyboardDefaults(KeyboardUiState(currentSchemaId = "rime_ice").inputProfile, 1000, false))
            assertEquals("restoring the reference schema must preserve its bottom row", expected, bounds())
        }
    }
}
