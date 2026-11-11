package com.kingzcheung.xime.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.*
import com.kingzcheung.xime.settings.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 在清空过应用数据的专用模拟器运行，不能依赖开发者个人启用列表。 */
class ChineseDefaultsImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun installedDefaultsOffer26KeysAndOptional14KeysCommitChinese(): Unit = runBlocking {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val engine = RimeEngine.getInstance()
        val imeComponent = ComponentName(context, "com.kingzcheung.xime.service.XimeInputMethodService")
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val previousSubtype = Settings.Secure.getInt(context.contentResolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, -1)
        val previouslyEnabled = imm.enabledInputMethodList.any { ComponentName.unflattenFromString(it.id) == imeComponent }
        val prefs = SettingsPreferences.getPrefsPublic(context)
        // Schema selection also remembers the Chinese mode/profile through the real panel.
        val ownedKeys = setOf("current_schema", "current_schema_dual", "last_input_mode_zh",
            "selected_input_profile_zh", "floating_mode", "reverse_symbol_swipe",
            "deployment_done", "deployment_hash")
        val savedPrefs = ownedKeys.associateWith { prefs.all[it] }
        lateinit var editor: EditText
        var editorCreated = false
        var initiallyEnabled: List<String>? = null
        // Preserve the live native state before stopping a previously running service.
        var oldSchema: String? = if (RimeEngine.isInitialized()) engine.getCurrentSchema().takeIf { it.isNotBlank() } else null
        var oldAscii: Boolean? = if (oldSchema != null) engine.isAsciiMode() else null
        var oldUserAscii: Boolean? = if (oldSchema != null) engine.getUserConfigBool("var/option/ascii_mode") else null
        try {
        // Dispose any service view that is still bound to a previous test's recomposer.
        stopKeyboardService(context, imeComponent, ime)
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        engine.initialize(user, shared)
        assertTrue(RimeConfigHelper.ensureDeployment(context))
        assertTrue(engine.ensureSession())
        initiallyEnabled = SchemaManager.getEnabledSchemas(context)
        val enabledAtStart = initiallyEnabled
        if (oldSchema == null) oldSchema = engine.getCurrentSchema()
        if (oldAscii == null) oldAscii = engine.isAsciiMode()
        if (oldUserAscii == null) oldUserAscii = engine.getUserConfigBool("var/option/ascii_mode")
        assertTrue(enabledAtStart.containsAll(CyimeInputDefaults.recommended))
        assertFalse(enabledAtStart.any { it in com.kingzcheung.xime.settings.BuiltinLetterLayouts.ids })
        val discovered = SchemaManager.discoverSchemas(context).associateBy { it.schemaId }
        assertEquals("中文26键", discovered["rime_ice"]?.name)
        assertEquals("中文14键", discovered["pinyin_14jian"]?.name)
        CyimeInputDefaults.recommended.forEach { assertTrue("$it 已编译", SchemaManager.isSchemaCompiled(context, it)) }
        // Start the real service in the Chinese mode under test, independent of
        // the emulator's previous language and floating-keyboard preferences.
        assertTrue(engine.switchSchema("rime_ice"))
        engine.setOption("ascii_mode", false)
        engine.setUserConfigBool("var/option/ascii_mode", false)
        SettingsPreferences.setCurrentSchema(context, "rime_ice")
        SettingsPreferences.setFloatingMode(context, false)
        // This test exercises physical upward swipes for the upper-corner digits.
        assertTrue(prefs.edit().putBoolean("reverse_symbol_swipe", true).commit())
        shell("ime enable ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
        shell("ime set ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
        rule.setContent { AndroidView(factory = { EditText(it).also { editor = it; editorCreated = true; it.hint = "全新安装中文输入验证" } }, modifier = Modifier.fillMaxWidth().height(120.dp)) }
        rule.waitUntil(10_000) {
            var ready = false
            rule.runOnUiThread { ready = editor.isAttachedToWindow && editor.hasWindowFocus() }
            ready
        }
        rule.runOnUiThread {
            assertTrue("测试输入框必须取得焦点", editor.requestFocus())
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        fun nodes(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
        // A physical keyboard can put the real service in compact toolbar mode. The
        // language control also exists there, so it is not evidence of expanded keys.
        rule.waitUntil(60_000) { nodes("hardware-toolbar-keyboard") > 0 || nodes("configured-mode-key") > 0 }
        if (nodes("hardware-toolbar-keyboard") > 0) {
            rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
        }
        rule.waitUntil(15_000) { nodes("configured-mode-key") > 0 }
        fun chooseMode(id: String) {
            rule.chooseModeThroughLanguageAndPanel(id)
        }
        fun typeAndCommit(labels: List<String>, screenshotName: String) {
            labels.forEach { rule.onNodeWithText(it, ignoreCase = true).performTouchInput { down(center); up() } }
            // The merged 14-key schema encodes its physical keys as uppercase letters.
            val expectedInput = if (screenshotName.contains("26")) "nihao" else "BUGAO"
            val candidate = hasText("你好", substring = true) and hasClickAction() and
                SemanticsMatcher("candidate bar entry") {
                    it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
                        ?.startsWith("bar-candidate:") == true
                }
            try {
                rule.waitUntil(10_000) { engine.getInput() == expectedInput }
                rule.waitUntil(10_000) { rule.onAllNodes(candidate).fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Throwable) {
                var editorText = ""
                rule.runOnUiThread { editorText = editor.text.toString() }
                val state = "DEFAULTS_IME_FAILURE case=$screenshotName labels=$labels expected=$expectedInput " +
                    "input=${engine.getInput()} schema=${engine.getCurrentSchema()} ascii=${engine.isAsciiMode()} " +
                    "candidates=${engine.getCandidates().contentToString()} " +
                    "composition=${engine.getComposition().let { "input=${it.input}, preedit=${it.preedit}, candidates=${it.candidates.contentToString()}, hasNext=${it.hasNextPage}, hasPrev=${it.hasPrevPage}" }} editor=$editorText " +
                    "compact=${nodes("hardware-toolbar-keyboard")} expanded=${nodes("configured-mode-key")}"
                android.util.Log.e("ChineseDefaultsImeTest", state, failure)
                println(state)
                val dir = File(context.getExternalFilesDir(null), "defaults").apply { mkdirs() }
                i.uiAutomation.takeScreenshot()?.also { bitmap ->
                    File(dir, "$screenshotName-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                rule.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
                    println("DEFAULTS_IME_ROOT $index " + rule.onAllNodes(isRoot()).get(index).printToString())
                }
                throw AssertionError(state, failure)
            }
            rule.waitForIdle()
            val dir = File(context.getExternalFilesDir(null), "defaults").apply { mkdirs() }
            i.uiAutomation.takeScreenshot().also { bitmap -> File(dir, "$screenshotName.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
            rule.onNode(candidate).performClick()
            rule.waitUntil(5000) { var text = ""; rule.runOnUiThread { text = editor.text.toString() }; text == "你好" }
            rule.runOnUiThread { editor.setText("") }
        }
        fun swipeDigit(label: String, digit: String) {
            try {
            rule.onNodeWithText(label, ignoreCase = true).performTouchInput {
                down(center)
                moveTo(Offset(center.x, center.y - 85f * context.resources.displayMetrics.density), delayMillis = 120)
                up()
            }
            rule.waitUntil(5000) {
                var text = ""
                rule.runOnUiThread { text = editor.text.toString() }
                text.startsWith(digit)
            }
            // Give the real service queue time to expose a late duplicate; do not clear it in the test.
            android.os.SystemClock.sleep(350)
            rule.waitForIdle()
            rule.runOnUiThread { assertEquals("上滑 $label 只能输入 $digit", digit, editor.text.toString()); editor.setText("") }
            assertEquals("上滑不能留下字母编码", "", engine.getInput())
            } catch (failure: Throwable) {
                var actual = "<unavailable>"
                runCatching { rule.runOnUiThread { actual = editor.text.toString() } }
                val state = "SWIPE_DIGIT_FAILURE label=$label digit=$digit editor=${actual.quoteForDiagnostic()} " +
                    "input=${engine.getInput().quoteForDiagnostic()} schema=${engine.getCurrentSchema()} " +
                    "layout=${KeysConfigHelper.mergedSectionForSchema(engine.getCurrentSchema())} " +
                    "ascii=${engine.isAsciiMode()} gesture=${KeysConfigHelper.getKeyGesture(label, engine.isAsciiMode())} " +
                    "prefs=${prefs.all.toSortedMap()}"
                android.util.Log.e("ChineseDefaultsImeTest", state, failure)
                println(state)
                throw AssertionError(state, failure)
            }
        }
        try {
            chooseMode("rime_ice")
            rule.waitUntil(5000) { rule.onAllNodesWithText("n", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(26, KeysConfigHelper.getKeyRows(false).flatten().count { it.length == 1 && it[0].isLetter() })
            typeAndCommit(listOf("n","i","h","a","o"), "chinese-26-default")
            SchemaManager.setEnabledSchemas(context, enabledAtStart + "pinyin_14jian")
            assertTrue(RimeConfigHelper.ensureDeployment(context))
            chooseMode("pinyin_14jian")
            rule.waitUntil(5000) { rule.onAllNodesWithText("bn", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("qwerty_14", KeysConfigHelper.mergedSectionForSchema("pinyin_14jian"))
            typeAndCommit(listOf("bn","ui","gh","as","op"), "chinese-14-default")
            listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l").forEachIndexed { index, label ->
                swipeDigit(label, ((index + 1) % 10).toString())
            }
            rule.onNodeWithText("qw", ignoreCase = true).performTouchInput { down(center); up() }
            rule.waitUntil(5000) { engine.getInput() == "Q" && engine.getCandidates().isNotEmpty() }
            val previousCandidates = engine.getCandidates().toList()
            // A literal ends composition as typed, not by selecting the first candidate.
            // For this incomplete merged key the decoded reading is q.
            val expectedText = "q2"
            try {
                rule.onNodeWithText("er", ignoreCase = true).performTouchInput {
                    down(center); moveTo(Offset(center.x, center.y - 85f * context.resources.displayMetrics.density), delayMillis = 120); up()
                }
                rule.waitUntil(5000) {
                    var text = ""
                    rule.runOnUiThread { text = editor.text.toString() }
                    text == expectedText && engine.getInput().isEmpty()
                }
            } catch (failure: Throwable) {
                var actual = "<unavailable>"
                runCatching { rule.runOnUiThread { actual = editor.text.toString() } }
                val state = "SWIPE_COMPOSING_FAILURE expected=${expectedText.quoteForDiagnostic()} " +
                    "editor=${actual.quoteForDiagnostic()} previousCandidates=$previousCandidates " +
                    "input=${engine.getInput().quoteForDiagnostic()} candidates=${engine.getCandidates().contentToString()} " +
                    "composition=${engine.getComposition().let { "input=${it.input}, preedit=${it.preedit}, candidates=${it.candidates.contentToString()}" }} " +
                    "schema=${engine.getCurrentSchema()} layout=${KeysConfigHelper.mergedSectionForSchema(engine.getCurrentSchema())} " +
                    "ascii=${engine.isAsciiMode()} gesture=${KeysConfigHelper.getKeyGesture("er", engine.isAsciiMode())} " +
                    "prefs=${prefs.all.toSortedMap()}"
                android.util.Log.e("ChineseDefaultsImeTest", state, failure)
                println(state)
                throw AssertionError(state, failure)
            }
            rule.onNodeWithText("ty", ignoreCase = true).performTouchInput { down(center); up() }
            rule.waitUntil(5000) { engine.getInput() == "T" }
            android.os.SystemClock.sleep(350)
            assertEquals("数字之后不得复活旧 Q 或附加上滑键的 E", "T", engine.getInput())
        } finally {
            engine.clearQueuedComposition()
        }
        } finally {
            runCatching { engine.clearComposition() }
            initiallyEnabled?.let { runCatching { SchemaManager.setEnabledSchemas(context, it) } }
            oldSchema?.let { runCatching { engine.switchSchema(it) } }
            oldAscii?.let { runCatching { engine.setOption("ascii_mode", it) } }
            oldUserAscii?.let { runCatching { engine.setUserConfigBool("var/option/ascii_mode", it) } }
            try {
                if (editorCreated) rule.runOnUiThread {
                    imm.hideSoftInputFromWindow(editor.windowToken, 0)
                    editor.clearFocus()
                }
                stopKeyboardService(context, imeComponent, ime)
            } finally {
                prefs.edit().also { edit ->
                    savedPrefs.forEach { (key, value) ->
                        when (value) {
                            null -> edit.remove(key)
                            is String -> edit.putString(key, value)
                            is Int -> edit.putInt(key, value)
                            is Float -> edit.putFloat(key, value)
                            is Long -> edit.putLong(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                }.commit()
                if (previouslyEnabled) shell("ime enable $ime")
                if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
                shell("settings put secure selected_input_method_subtype $previousSubtype")
            }
        }
    }

    private fun String.quoteForDiagnostic() = "\"" + replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\""

    @Suppress("DEPRECATION")
    private fun stopKeyboardService(context: Context, component: ComponentName, ime: String) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val waitForEngineDestroy = RimeEngine.isInitialized() &&
            activityManager.getRunningServices(Int.MAX_VALUE).any { it.service == component }
        shell("ime disable $ime")
        rule.waitUntil(10_000) {
            activityManager.getRunningServices(Int.MAX_VALUE).none { it.service == component } &&
                (!waitForEngineDestroy || !RimeEngine.isInitialized())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    private fun shell(cmd: String) = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }
}
