package com.kingzcheung.xime.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.XimeApplication
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.service.XimeInputMethodService
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.KeyboardHeightProfiles
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette
import com.kingzcheung.xime.ui.keyboard.legibleForTheme
import com.kingzcheung.xime.ui.keyboard.legibleOver
import com.kingzcheung.xime.ui.keyboard.glassFunctionKeyScale
import com.kingzcheung.xime.ui.keyboard.frostedKeyColor
import com.kingzcheung.xime.ui.theme.SamsungWindowBlur
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Exercises SurfaceFlinger composition of the real IME above a disposable Activity.
 * A Compose captureToImage of a keyboard cannot prove that another window shows through it.
 * Requires the standard AndroidJUnitRunner and explicit -e realIme true.
 * The hardware toolbar may be expanded and restored with Back; no text is entered,
 * no clipboard is read, and no user application receives input.
 */
@SdkSuppress(minSdkVersion = 31)
class RealGlassBackdropTest {
    @get:Rule val rule = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun glassTracksTheActualHostAndLeavesForegroundAndFloatingMarginsIntact() {
        assumeTrue("Opt in to real IME interaction", InstrumentationRegistry.getArguments().getString("realIme") == "true")
        val section = InstrumentationRegistry.getArguments().getString("realGlassSection") ?: "all"
        require(section in setOf("all", "backdrop", "opacity", "readability", "blur")) { "Unknown realGlassSection=$section" }
        fun runs(name: String) = section == "all" || section == name
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.javaClass.name == "androidx.test.runner.AndroidJUnitRunner") {
            "Use AndroidJUnitRunner with -PuiRenderingOnly=false, not the rendering-only application."
        }
        val context = instrumentation.targetContext
        check(context.applicationContext is XimeApplication && PluginManager.isInitialized)
        InstrumentationRegistry.getArguments().getString("expectedVersion")?.let { expected ->
            assertEquals("verify the package under test", expected,
                context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        }
        assertTrue(runBlocking { RimeConfigHelper.prepareEngine(context) })
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val ledger = PreferenceLedger(prefs)
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val imeId = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val wasEnabled = shell("ime list -s").lineSequence().any { it.trim() == imeId }
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val evidence = mutableListOf<String>()
        lateinit var originalActivity: ComponentActivity
        rule.scenario.onActivity { originalActivity = it }
        lateinit var editor: EditText
        lateinit var backdrop: TestBackdrop
        lateinit var host: FrameLayout
        var ready = false
        var service: XimeInputMethodService? = null
        var failure: Throwable? = null
        var expandedCompactToolbar = false
        try {
            shell("ime enable $imeId"); shell("ime set $imeId")
            runOnUiThread {
                // Keep the test background underneath the IME, as in a full-size editor.
                originalActivity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                originalActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                originalActivity.window.setDecorFitsSystemWindows(false)
                host = FrameLayout(originalActivity)
                backdrop = TestBackdrop(originalActivity)
                host.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
                editor = EditText(originalActivity).apply {
                    hint = "玻璃真实透底测试"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setBackgroundColor(Color.WHITE)
                }
                val editorHeight = (64 * originalActivity.resources.displayMetrics.density).roundToInt()
                host.addView(editor, FrameLayout.LayoutParams(-1, editorHeight).apply { topMargin = editorHeight })
                originalActivity.setContentView(host)
                ready = true
            }
            waitUntil(10_000) { ready && editor.hasWindowFocus() }
            runOnUiThread {
                editor.requestFocus()
                imm.restartInput(editor)
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            waitUntil(40_000) {
                service = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findService)
                service?.inputReadiness?.ticket() != null && service?.keyboardCallbacks != null
            }
            val actual = checkNotNull(service)
            evidence += "initial compact=${actual.uiState.value.isCompact}; floating=${actual.uiState.value.isFloatingMode}; pid=${Process.myPid()}; inputDevices=" +
                InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }.joinToString { "${it.name}(virtual=${it.isVirtual},keyboardType=${it.keyboardType})" }
            if (actual.uiState.value.isCompact) {
                waitUntil(10_000) { exists("hardware-toolbar-keyboard") }
                val button = screenRect("hardware-toolbar-keyboard")
                expandedCompactToolbar = true
                shell("input tap ${button.center.x.roundToInt()} ${button.center.y.roundToInt()}")
                waitUntil(10_000) { !actual.uiState.value.isCompact }
                evidence += "expanded compact toolbar via real keyboard button; compact=${actual.uiState.value.isCompact}"
            }
            val landscape = actual.resources.configuration.screenWidthDp > actual.resources.configuration.screenHeightDp
            val suffix = if (landscape) "_landscape" else ""
            val density = actual.resources.displayMetrics.density
            val screenWidthDp = actual.resources.configuration.screenWidthDp
            val platformBlur = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled
            val samsungBlur = SamsungWindowBlur.isAvailable
            val blurAvailable = platformBlur || samsungBlur
            val requireSystemBlur = InstrumentationRegistry.getArguments().getString("requireSystemBlur") != "false"
            evidence += "section=$section; crossWindowBlurEnabled=$platformBlur; samsungBlurApi=$samsungBlur; requireBlur=$requireSystemBlur; density=$density; orientation=${if (landscape) "landscape" else "portrait"}"
            evidence += "previousVisualStyle=${SettingsPreferences.getVisualStyle(context).id}"
            val height = 300.coerceIn(KeyboardHeightProfiles.fixedBounds(actual, landscape))
            val fixture = linkedMapOf<String, Any>(
                "dark_mode" to 1,
                // Exact alpha arithmetic excludes optional reflections/edge treatments.
                // The preference ledger restores the user's material after this fixture.
                SettingsPreferences.KEY_VISUAL_STYLE to "original",
                "fixed_width_dp$suffix" to (screenWidthDp - 16),
                "fixed_offset_x$suffix" to 0,
                "keyboard_height_dp$suffix" to height,
                "keyboard_bottom_padding_dp" to 0,
                "keyboard_opacity$suffix" to 1f,
                "floating_width_dp$suffix" to (screenWidthDp * .65f).roundToInt().coerceIn(200, 400),
                "floating_height_dp$suffix" to 260,
                "floating_offset_x$suffix" to 0,
                "floating_offset_y$suffix" to 96,
                "floating_mode$suffix" to false,
                // Material values below are saved through the public paired-appearance API.
                "frosted_glass_appearance_migrated" to true,
                FrostedGlassPreferences.KEY_ENABLED to false,
                "keyboard_theme" to "pure_black",
            )
            for (appearance in listOf("light", "dark")) {
                fixture["${FrostedGlassPreferences.KEY_BLUR_RADIUS}_$appearance"] = if (appearance == "dark") 14f else 8f
            }
            runOnUiThread {
                ledger.write(fixture)
                actual.restoreKeyboardDisplayMode(false, persist = false)
            }
            try {
                waitUntil(10_000) { !actual.uiState.value.isFloatingMode && exists("fixed-keyboard-card") && exists("space-key") }
            } catch (error: Throwable) {
                evidence += "fixture compact=${actual.uiState.value.isCompact}; floating=${actual.uiState.value.isFloatingMode}; fixed=${exists("fixed-keyboard-card")}; space=${exists("space-key")}; layout=${actual.keyboardViewModel.keyboardState.value}; page=${actual.keyboardViewModel.page.value}; pid=${Process.myPid()}"
                instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                    try { save(image, host, "fixture-not-ready") } finally { image.recycle() }
                }
                throw error
            }

            fun change(values: Map<String, Any>, floating: Boolean? = null) {
                assertFalse("test Activity must not be recreated", originalActivity.isDestroyed)
                runOnUiThread {
                    ledger.write(values)
                    if (floating != null) actual.restoreKeyboardDisplayMode(floating, persist = false)
                }
                instrumentation.waitForIdleSync()
            }
            fun colorFrame(color: Int, name: String): Bitmap {
                runOnUiThread { backdrop.pattern = false; backdrop.sceneColor = color; backdrop.invalidate() }
                return stableScreenshot(host).also { save(it, host, name) }
            }
            fun setMaterial(config: FrostedGlassConfig, isDark: Boolean) {
                runOnUiThread {
                    ledger.mutate(FrostedGlassPreferences.keys) { FrostedGlassPreferences.save(context, config, isDark) }
                }
                instrumentation.waitForIdleSync()
            }
            setMaterial(FrostedGlassConfig(false, 14f, .35f, .15f), isDark = true)
            if (runs("backdrop")) for (isDark in listOf(true, false)) for (theme in listOf("pure_black", "soft_blue", "lavender_purple")) {
                val appearance = if (isDark) "dark" else "light"
                val label = "$theme-$appearance"
                change(mapOf("keyboard_theme" to theme, "dark_mode" to if (isDark) 1 else 0,
                    FrostedGlassPreferences.KEY_ENABLED to false))
                waitUntil(10_000) { actual.uiState.value.themeId == theme &&
                    actual.uiState.value.darkMode == (if (isDark) 1 else 0) && !FrostedGlassPreferences.read(context).enabled }
                val plainRed = colorFrame(RED, "$label-fixed-plain-red")
                val plainBlue = colorFrame(BLUE, "$label-fixed-plain-blue")
                val card = screenRect("fixed-keyboard-card")
                val space = screenRect("space-key")
                try {
                    assertTrue("ordinary $theme must remain opaque when only host color changes",
                        meanDifference(plainRed, plainBlue, inset(card, density * 8)) < 4.0)
                    change(mapOf(FrostedGlassPreferences.KEY_ENABLED to true))
                    waitUntil(10_000) { FrostedGlassPreferences.read(context).enabled }
                    assertEquals("glass must retain selected colors", theme, SettingsPreferences.getKeyboardTheme(context))
                    assertEquals("real keyboard must read the swapped $appearance profile", if (isDark) .35f else .15f,
                        FrostedGlassPreferences.read(context).backgroundOpacity)
                    assertEquals("real keyboard must read the swapped $appearance key opacity", if (isDark) .15f else .35f,
                        FrostedGlassPreferences.read(context).keyOpacity)
                    val glassRed = colorFrame(RED, "$label-fixed-glass-red")
                    val glassBlue = colorFrame(BLUE, "$label-fixed-glass-blue")
                    try {
                        assertRectStable("glass must not change keyboard geometry", card, screenRect("fixed-keyboard-card"))
                        val raw = FrostedGlassPreferences.read(context, isDark)
                        val palette = resolveKeyboardPalette(theme, isDark)
                        val scale = glassFunctionKeyScale(palette.function, palette.key, palette.function)
                        val effective = raw.legibleForTheme(theme, isDark)
                        val unscaled = raw.legibleOver(palette.background, listOf(palette.key to palette.text, palette.function to palette.functionText))
                        evidence += "$label diagnostics: raw=$raw; effective=$effective; withoutFunctionScale=$unscaled; functionScale=$scale; palette=$palette; renderedKeyTint=${frostedKeyColor(palette.key, palette.text, effective)}; inputRed=${listOf(Color.red(RED), Color.green(RED), Color.blue(RED))}; inputBlue=${listOf(Color.red(BLUE), Color.green(BLUE), Color.blue(BLUE))}; space=$space; sampled=${inset(space, density * 6)}"
                        // SpaceKeyButton paints its unscaled key tint inside padding. Sample
                        // left of its centered label, and sample the padding separately.
                        val keyRegion = Rect(space.left + space.width * .12f, space.top + space.height * .18f, space.left + space.width * .28f, space.top + space.height * .28f)
                        val panelRegion = Rect(space.left, space.top + space.height * .35f, space.left + density, space.top + space.height * .65f)
                        runOnUiThread { imm.hideSoftInputFromWindow(editor.windowToken, 0) }
                        waitUntil(10_000) { !actual.isInputViewShown }
                        val hostRed = colorFrame(RED, "$label-host-red")
                        val hostBlue = colorFrame(BLUE, "$label-host-blue")
                        try {
                            assertTransmission("$label panel", glassRed, glassBlue, hostRed, hostBlue, panelRegion, 1f - effective.backgroundOpacity, evidence)
                            assertTransmission("$label space", glassRed, glassBlue, hostRed, hostBlue, keyRegion,
                                (1f - effective.backgroundOpacity) * (1f - effective.keyOpacity), evidence)
                        } finally { hostRed.recycle(); hostBlue.recycle() }
                        runOnUiThread { editor.requestFocus(); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
                        waitUntil(10_000) { !actual.uiState.value.isCompact && exists("fixed-keyboard-card") && exists("space-key") }
                        assertForegroundOpaque(theme, isDark, glassRed, glassBlue, space, evidence)
                        assertEquals("the disposable editor must remain untouched", "", editor.text.toString())
                    } finally { glassRed.recycle(); glassBlue.recycle() }
                    change(mapOf(FrostedGlassPreferences.KEY_ENABLED to false))
                    val restored = colorFrame(RED, "$label-fixed-glass-off")
                    try {
                        assertTrue("disabling glass must restore the original $theme surface",
                            meanDifference(plainRed, restored, inset(card, density * 8)) < 4.0)
                        assertEquals(theme, SettingsPreferences.getKeyboardTheme(context))
                    } finally { restored.recycle() }
                    evidence += "$label: opaque baseline stable, real backdrop followed, text retained, disabling restored theme"
                } finally { plainRed.recycle(); plainBlue.recycle() }
            }

            // The old test only wrote one opacity setting and could not detect coupled/dead controls.
            if (runs("opacity")) for (isDark in listOf(true, false)) {
                val appearance = if (isDark) "dark" else "light"
                change(mapOf("keyboard_theme" to "soft_blue", "dark_mode" to if (isDark) 1 else 0))
                setMaterial(FrostedGlassConfig(true, 0f, 0f, 0f), isDark)
                val spaceBounds = screenRect("space-key")
                // An interior patch to the left of the centered space-key label.
                val region = Rect(spaceBounds.left + spaceBounds.width * .12f, spaceBounds.top + spaceBounds.height * .18f,
                    spaceBounds.left + spaceBounds.width * .28f, spaceBounds.top + spaceBounds.height * .28f)
                // A 1dp-wide vertical gap can contain fewer than 101 pixels on a short
                // landscape row. Sample one actual pixel row along the space key's top
                // visual padding instead, bounded by its width and the body below the toolbar.
                val bodyBounds = screenRect("keyboard-underlay")
                val gapTop = ceil(maxOf(spaceBounds.top, bodyBounds.top)).toFloat()
                val gap = Rect(maxOf(spaceBounds.left + density * 7, bodyBounds.left), gapTop,
                    minOf(spaceBounds.right - density * 7, bodyBounds.right), gapTop + 1f)
                val gapPixels = (floor(gap.right) - ceil(gap.left)).toInt()
                evidence += "$appearance opacity geometry: space=$spaceBounds; body=$bodyBounds; keySample=$region; gap=$gap; gapPixels=$gapPixels"
                check(gap.top >= spaceBounds.top && gap.bottom <= minOf(spaceBounds.bottom, bodyBounds.bottom) &&
                    gapPixels > 100) { "No sufficiently wide real key gap: $gap ($gapPixels pixels)" }
                // Measure the actual host at the same coordinates without an IME window.
                runOnUiThread { imm.hideSoftInputFromWindow(editor.windowToken, 0) }
                waitUntil(10_000) { !actual.isInputViewShown }
                val hostImage = colorFrame(RED, "$appearance-opacity-host")
                val hostPanel: Int
                val hostKey: Int
                try {
                    hostPanel = medianColor(hostImage, gap)
                    hostKey = medianColor(hostImage, region)
                } finally { hostImage.recycle() }
                runOnUiThread { editor.requestFocus(); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
                waitUntil(10_000) { !actual.uiState.value.isCompact && exists("fixed-keyboard-card") && exists("space-key") }

                val stages = listOf(0f to 0f, 1f to 0f, 1f to 1f)
                var previousEffective: FrostedGlassConfig? = null
                var firstImage: Bitmap? = null
                try {
                    for ((index, pair) in stages.withIndex()) {
                        val requested = FrostedGlassConfig(true, 0f, pair.first, pair.second)
                        setMaterial(requested, isDark)
                        val raw = FrostedGlassPreferences.read(context, isDark)
                        assertEquals("$appearance stage $index saved configuration", requested, raw)
                        val opposite = FrostedGlassPreferences.read(context, !isDark)
                        assertEquals("$appearance stage $index opposite background is paired key", pair.second, opposite.backgroundOpacity, 0f)
                        assertEquals("$appearance stage $index opposite key is paired background", pair.first, opposite.keyOpacity, 0f)
                        assertEquals("$appearance current appearance reads saved controls", requested, FrostedGlassPreferences.read(context))
                        val theme = SettingsPreferences.getKeyboardTheme(context)
                        assertEquals("soft_blue", theme)
                        val palette = resolveKeyboardPalette(theme, isDark)
                        val effective = raw.legibleForTheme(theme, isDark)
                        assertTrue("effective background cannot be below saved background", effective.backgroundOpacity >= raw.backgroundOpacity)
                        assertTrue("effective key cannot be below saved key", effective.keyOpacity >= raw.keyOpacity)
                        previousEffective?.let { previous ->
                            if (index == 1) {
                                assertEquals("maximum background slider yields maximum effective background", 1f, effective.backgroundOpacity, 0f)
                                assertTrue("background slider must increase effective background", effective.backgroundOpacity > previous.backgroundOpacity)
                            } else {
                                assertEquals("maximum key slider yields maximum effective key", 1f, effective.keyOpacity, 0f)
                                assertTrue("key slider must increase effective key", effective.keyOpacity > previous.keyOpacity)
                                assertEquals("maximum effective background remains unchanged", previous.backgroundOpacity, effective.backgroundOpacity, 0f)
                            }
                        }
                        val tint = frostedKeyColor(palette.key, palette.text, effective)
                        val expectedPanel = blend(palette.background.toArgb(), hostPanel, effective.backgroundOpacity)
                        val expectedKeyPanel = blend(palette.background.toArgb(), hostKey, effective.backgroundOpacity)
                        val expectedKey = blend(tint.toArgb(), expectedKeyPanel, tint.alpha)
                        val image = colorFrame(RED, "$appearance-opacity-stage-$index")
                        try {
                            assertRectStable("opacity controls must keep key geometry", spaceBounds, screenRect("space-key"))
                            val actualPanel = medianColor(image, gap)
                            val actualKey = medianColor(image, region)
                            evidence += "$appearance stage=$index raw=$raw effective=$effective palette=$palette tint=$tint hostPanel=${Integer.toHexString(hostPanel)} hostKey=${Integer.toHexString(hostKey)} expectedPanel=${Integer.toHexString(expectedPanel)} actualPanel=${Integer.toHexString(actualPanel)} expectedKey=${Integer.toHexString(expectedKey)} actualKey=${Integer.toHexString(actualKey)}"
                            assertColor("$appearance stage $index panel composition", expectedPanel, actualPanel, 8)
                            assertColor("$appearance stage $index key composition", expectedKey, actualKey, 8)
                            assertEquals("rendering must not change saved controls", requested, FrostedGlassPreferences.read(context, isDark))
                            if (index == 0) firstImage = image.copy(image.config ?: Bitmap.Config.ARGB_8888, false)
                            if (index == 2) assertForegroundOpaque(theme, isDark, checkNotNull(firstImage), image, spaceBounds, evidence)
                        } finally { image.recycle() }
                        previousEffective = effective
                    }
                    evidence += "$appearance: saved controls and effective rendered composition verified independently"
                } finally { firstImage?.recycle() }
            }

            // Defaults must remain readable over both bright and dark app content, not merely keep text unchanged.
            if (runs("readability")) for (isDark in listOf(true, false)) for (theme in listOf("pure_black", "soft_blue", "lavender_purple")) {
                val appearance = if (isDark) "dark" else "light"
                val defaults = FrostedGlassConfig.defaults(isDark, enabled = true)
                change(mapOf("keyboard_theme" to theme, "dark_mode" to if (isDark) 1 else 0))
                setMaterial(defaults, isDark)
                for ((scene, name) in listOf(Color.BLACK to "black-host", Color.WHITE to "white-host")) {
                    val image = colorFrame(scene, "$theme-$appearance-defaults-$name")
                    try {
                        assertTextContrast(theme, isDark, image, inset(screenRect("space-key"), density * 7), evidence)
                    } finally { image.recycle() }
                }
            }

            fun verifyBlur(mode: String, floating: Boolean) {
                change(mapOf("floating_mode$suffix" to floating, "keyboard_theme" to "soft_blue", "dark_mode" to 1), floating = floating)
                setMaterial(FrostedGlassConfig(false, 0f, .25f, 0f), isDark = true)
                val tag = if (floating) "floating-keyboard-card" else "fixed-keyboard-card"
                waitUntil(10_000) { actual.uiState.value.isFloatingMode == floating && exists(tag) }
                runOnUiThread { backdrop.pattern = true; backdrop.invalidate() }
                val card = screenRect(tag)
                val frame = viewRect(host)
                val margin = if (floating) Rect(frame.left + 4 * density, card.top + card.height * .25f,
                    card.left - 6 * density, card.bottom - card.height * .25f) else null
                if (margin != null) assertTrue("fixture must expose a measurable outer margin", margin.width > 8 * density && margin.height > 16 * density)
                val plainPattern = stableScreenshot(host).also { save(it, host, "$mode-plain-pattern") }
                try {
                    setMaterial(FrostedGlassConfig(true, 0f, .25f, 0f), isDark = true)
                    val key = inset(screenRect("space-key"), density * 7)
                    val unblurred = sharpStripeScreenshot(host, key, mode, evidence)
                    setMaterial(FrostedGlassConfig(true, 14f, .25f, 0f), isDark = true)
                    val blurred = stableScreenshot(host).also { save(it, host, "$mode-glass-radius-14") }
                    try {
                        recordWindowGeometry(mode, evidence)
                        assertRectStable("glass must retain $mode size and location", card, screenRect(tag))
                        assertRectStable("blur must retain the $mode sample position", key, inset(screenRect("space-key"), density * 7))
                        if (blurAvailable) {
                            val blurBounds = checkNotNull(keyboardBlurWindowRect()) { "Missing visible $mode keyboard blur window" }
                            evidence += "$mode blur bounds=$blurBounds; keyboard card=$card"
                            assertRectStable("$mode blur window must exactly cover the keyboard card", card, blurBounds)
                        }
                        if (margin != null) {
                            // The radius-0 baseline was taken after transient overlays
                            // cleared; the earlier opaque preview may contain a toast.
                            val marginDelta = meanDifference(unblurred, blurred, margin)
                            evidence += "$mode margin=$margin; meanRgbDelta=$marginDelta"
                            assertTrue("transparent margins must preserve sharp host pixels; no full-window blur or tint", marginDelta < 4.0)
                        }
                        val sharpContrast = luminanceSpread(unblurred, key)
                        val blurredContrast = luminanceSpread(blurred, key)
                        evidence += "$mode key=$key; unblurredContrast=$sharpContrast; blurredContrast=$blurredContrast"
                        assertTrue("test must contain visible host stripe detail inside the key", sharpContrast > 35)
                        if (blurAvailable) {
                            assertTrue("$mode system blur must visibly soften the real host, not merely report an enabled API",
                                blurredContrast < sharpContrast * .65)
                        } else {
                            evidence += "$mode: SYSTEM_BLUR_UNAVAILABLE; transparency fallback is not proof of Gaussian blur"
                            assertFalse("Gaussian blur was not verified. Set requireSystemBlur=false only for an explicitly reported fallback test.", requireSystemBlur)
                        }
                    } finally { unblurred.recycle(); blurred.recycle() }
                } finally { plainPattern.recycle() }
            }
            if (runs("blur")) {
            verifyBlur("fixed", floating = false)
            verifyBlur("floating", floating = true)

            val floatingRed = colorFrame(RED, "floating-glass-red")
            val floatingBlue = colorFrame(BLUE, "floating-glass-blue")
            try {
                assertBackdropResponse("floating real glass", floatingRed, floatingBlue,
                    glassSurfaceSample("space-key", density, evidence), evidence)
                assertForegroundOpaque("soft_blue", true, floatingRed, floatingBlue, screenRect("space-key"), evidence)
            } finally { floatingRed.recycle(); floatingBlue.recycle() }
            }
            assertEquals("no test text must be entered", "", editor.text.toString())
        } catch (error: Throwable) {
            failure = error
            evidence += "FAILED: ${error.javaClass.simpleName}: ${error.message}"
            throw error
        } finally {
            val errors = mutableListOf<Throwable>()
            fun cleanup(block: () -> Unit) { runCatching(block).exceptionOrNull()?.let(errors::add) }
            cleanup { instrumentation.runOnMainSync {
                evidence += "preserved_external_preference_changes=${ledger.restore().joinToString(",")}"
                service?.takeIf { ledger.hasMutations }?.let { actual ->
                    val landscape = actual.resources.configuration.screenWidthDp > actual.resources.configuration.screenHeightDp
                    actual.restoreKeyboardDisplayMode(SettingsPreferences.isFloatingMode(actual, landscape), persist = false)
                }
            } }
            cleanup {
                if (expandedCompactToolbar && service?.uiState?.value?.isCompact == false) {
                    shell("input keyevent KEYCODE_BACK")
                    waitUntil(10_000) { service?.uiState?.value?.isCompact == true }
                }
                evidence += "final compact=${service?.uiState?.value?.isCompact}; restoredExpandedToolbar=$expandedCompactToolbar"
            }
            cleanup { instrumentation.runOnMainSync { if (ready) {
                imm.hideSoftInputFromWindow(editor.windowToken, 0)
                editor.clearFocus()
            } } }
            cleanup {
                if (Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == imeId && !previousIme.isNullOrBlank()) {
                    shell("ime set $previousIme")
                }
                if (!wasEnabled && previousIme != imeId &&
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) != imeId) shell("ime disable $imeId")
            }
            cleanup { File(directory(), "latest.txt").writeText(evidence.joinToString("\n")) }
            if (errors.isNotEmpty()) {
                val primary = failure ?: errors.first()
                errors.filter { it !== primary }.forEach(primary::addSuppressed)
                if (failure == null) throw primary
            }
        }
    }

    private fun assertTransmission(label: String, red: Bitmap, blue: Bitmap, hostRed: Bitmap, hostBlue: Bitmap,
        region: Rect, expected: Float, evidence: MutableList<String>) {
        val r = meanRgb(red, region); val b = meanRgb(blue, region)
        val hr = meanRgb(hostRed, region); val hb = meanRgb(hostBlue, region)
        evidence += "$label region=$region expectedTransmission=$expected; red=${r.toList()}; blue=${b.toList()}; hostRed=${hr.toList()}; hostBlue=${hb.toList()}"
        // The measured channel differences below reject an opaque result with a 10/255
        // margin over sampling noise; the formula check alone allows ±3.5 levels.
        assertTrue("$label red/blue host separation", hr[0] - hb[0] > 180 && hb[2] - hr[2] > 180)
        for (channel in 0..2) {
            val hostDelta = hr[channel] - hb[channel]
            val actualDelta = r[channel] - b[channel]
            assertEquals("$label channel=$channel transmission", hostDelta * expected, actualDelta, 3.5)
        }
        assertTrue("$label must actually transmit red", r[0] - b[0] > 10)
        assertTrue("$label must actually transmit blue", b[2] - r[2] > 10)
    }

    private fun assertBackdropResponse(label: String, red: Bitmap, blue: Bitmap, region: Rect, evidence: MutableList<String>) {
        val redMean = meanRgb(red, region)
        val blueMean = meanRgb(blue, region)
        evidence += "$label: region=$region; red=${redMean.toList()}; blue=${blueMean.toList()}"
        assertTrue("$label must expose the red host through its actual window", redMean[0] - redMean[2] > 45)
        assertTrue("$label must expose the blue host through its actual window", blueMean[2] - blueMean[0] > 45)
        assertTrue("$label must respond to the host, not display a static gradient", meanDifference(red, blue, region) > 45)
    }

    /** Measure the glass, not the separately verified opaque foreground. No pixel-value filtering. */
    private fun glassSurfaceSample(tag: String, density: Float, evidence: MutableList<String>): Rect {
        val foreground = mutableListOf<Rect>()
        var interior: Rect? = null
        runOnUiThread {
            val root = checkNotNull(imeNode(tag)) { "Missing real IME node: $tag" }
            fun rect(node: SemanticsNode) = Rect(node.positionOnScreen.x, node.positionOnScreen.y,
                node.positionOnScreen.x + node.size.width, node.positionOnScreen.y + node.size.height)
            interior = inset(rect(root), density * 6)
            fun visit(node: SemanticsNode) {
                if (node.config.contains(SemanticsActions.GetTextLayoutResult) ||
                    node.config.contains(SemanticsProperties.ContentDescription)) foreground += rect(node)
                node.children.forEach(::visit)
            }
            root.children.forEach(::visit)
        }
        check(foreground.isNotEmpty()) { "Missing actual keyboard foreground bounds: $tag" }
        val area = checkNotNull(interior)
        // Include antialiasing around the measured text/icon boxes, without examining their colors.
        val ink = Rect(foreground.minOf { it.left } - 2f, foreground.minOf { it.top } - 2f,
            foreground.maxOf { it.right } + 2f, foreground.maxOf { it.bottom } + 2f)
        val patch = listOf(Rect(area.left, area.top, minOf(area.right, ink.left), area.bottom),
            Rect(maxOf(area.left, ink.right), area.top, area.right, area.bottom),
            Rect(area.left, area.top, area.right, minOf(area.bottom, ink.top)),
            Rect(area.left, maxOf(area.top, ink.bottom), area.right, area.bottom))
            .filter { it.width > 0f && it.height > 0f }.maxByOrNull { it.width * it.height }
        checkNotNull(patch) { "No real glass surface outside foreground: area=$area foreground=$foreground" }
        val columns = (floor(patch.right).toInt() - ceil(patch.left).toInt() + 2) / 3
        val rows = (floor(patch.bottom).toInt() - ceil(patch.top).toInt() + 2) / 3
        check(columns * rows > 100) { "Insufficient real surface samples: patch=$patch count=${columns * rows}" }
        evidence += "glass-only geometry: area=$area; foreground=$foreground; patch=$patch; samples=${columns * rows}"
        return patch
    }

    private fun assertForegroundOpaque(theme: String, isDark: Boolean, red: Bitmap, blue: Bitmap, region: Rect, evidence: MutableList<String>) {
        val expected = resolveKeyboardPalette(theme, isDark).text.toArgb()
        var foreground = 0
        var retained = 0
        eachPixel(red, region, step = 1) { x, y ->
            if (distance(red.getPixel(x, y), expected) <= 12) {
                foreground++
                if (distance(blue.getPixel(x, y), expected) <= 12) retained++
            }
        }
        evidence += "$theme opaque text pixels=$foreground; retained=$retained"
        assertTrue("space-key label must contain opaque theme text", foreground >= 12)
        assertTrue("changing the backdrop must not fade or recolor foreground text", retained >= foreground * .9)
    }

    private fun assertTextContrast(theme: String, isDark: Boolean, image: Bitmap, region: Rect, evidence: MutableList<String>) {
        val expectedText = resolveKeyboardPalette(theme, isDark).text.toArgb()
        var textPixels = 0
        eachPixel(image, region, step = 1) { x, y ->
            if (distance(image.getPixel(x, y), expectedText) <= 12) textPixels++
        }
        assertTrue("$theme ${if (isDark) "dark" else "light"} must use its actual theme foreground", textPixels >= 12)
        val actualFill = medianColor(image, region)
        val foregroundLuminance = relativeLuminance(expectedText)
        val fillLuminance = relativeLuminance(actualFill)
        val contrast = (maxOf(foregroundLuminance, fillLuminance) + .05) / (minOf(foregroundLuminance, fillLuminance) + .05)
        evidence += "$theme ${if (isDark) "dark" else "light"}: text=${Integer.toHexString(expectedText)}; fill=${Integer.toHexString(actualFill)}; contrast=$contrast"
        assertTrue("default $theme ${if (isDark) "dark" else "light"} label must retain 4.5:1 contrast over actual app content; actual=$contrast",
            contrast >= 4.5)
    }

    private fun relativeLuminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val encoded = channel / 255.0
            return if (encoded <= .04045) encoded / 12.92 else ((encoded + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(Color.red(color)) + .7152 * linear(Color.green(color)) + .0722 * linear(Color.blue(color))
    }

    private fun assertColor(message: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$message: expected=${Integer.toHexString(expected)}, actual=${Integer.toHexString(actual)}",
            distance(expected, actual) <= tolerance)
    }

    private fun blend(foreground: Int, background: Int, opacity: Float): Int = Color.rgb(
        (Color.red(foreground) * opacity + Color.red(background) * (1f - opacity)).roundToInt(),
        (Color.green(foreground) * opacity + Color.green(background) * (1f - opacity)).roundToInt(),
        (Color.blue(foreground) * opacity + Color.blue(background) * (1f - opacity)).roundToInt(),
    )

    private fun medianColor(image: Bitmap, region: Rect): Int {
        val channels = Array(3) { mutableListOf<Int>() }
        eachPixel(image, region, step = 1) { x, y ->
            val pixel = image.getPixel(x, y)
            channels[0] += Color.red(pixel); channels[1] += Color.green(pixel); channels[2] += Color.blue(pixel)
        }
        check(channels[0].size > 100) { "sample must contain more than 100 real surface pixels: region=$region count=${channels[0].size}" }
        channels.forEach { it.sort() }
        return Color.rgb(channels[0][channels[0].size / 2], channels[1][channels[1].size / 2], channels[2][channels[2].size / 2])
    }

    // A live IME may predate this test. Let its own Choreographer run normally;
    // Compose test clocks/Espresso can otherwise re-enter its measure/layout pass.
    private fun runOnUiThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else instrumentation.runOnMainSync { block() }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Wait from the instrumentation thread" }
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (true) {
            var satisfied = false
            runOnUiThread { satisfied = condition() }
            if (satisfied) return
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0) throw AssertionError("Condition still not satisfied after $timeoutMillis ms")
            SystemClock.sleep(minOf(50L, remaining))
        }
    }

    private fun stableScreenshot(host: View): Bitmap {
        instrumentation.waitForIdleSync()
        val region = viewRect(host)
        var previous: Bitmap? = null
        try {
            repeat(20) {
                SystemClock.sleep(100)
                val current = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                val old = previous
                if (old != null && old.width == current.width && old.height == current.height && meanDifference(old, current, region) < .4) {
                    old.recycle(); previous = null
                    return current
                }
                old?.recycle()
                previous = current
            }
            error("real display did not settle in 2 seconds; do not compare different animation frames")
        } finally { previous?.recycle() }
    }

    private fun sharpStripeScreenshot(host: View, region: Rect, mode: String, evidence: MutableList<String>): Bitmap {
        val start = SystemClock.uptimeMillis()
        val deadline = start + 8_000
        val frame = viewRect(host)
        var previous: Bitmap? = null
        var contrast = 0
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(minOf(100L, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)))
                val current = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val old = previous
                contrast = luminanceSpread(current, region)
                val stable = old != null && old.width == current.width && old.height == current.height &&
                    meanDifference(old, current, frame) < .4
                old?.recycle()
                previous = current
                // A transient toast may cover the key. Wait for real stripe detail,
                // retaining the original contrast threshold and exact sample bounds.
                if (stable && contrast > 35) {
                    evidence += "$mode radius-0 baseline: key=$region; contrast=$contrast; waitedMs=${SystemClock.uptimeMillis() - start}"
                    save(current, host, "$mode-glass-radius-0")
                    previous = null
                    return current
                }
            }
            evidence += "$mode radius-0 baseline timed out: key=$region; contrast=$contrast"
            previous?.let { save(it, host, "$mode-glass-radius-0-not-ready") }
            throw AssertionError("$mode test must contain stable visible host stripe detail inside the key within 8 seconds; contrast=$contrast, required > 35")
        } finally { previous?.recycle() }
    }

    private fun keyboardBlurWindowRect(): Rect? {
        var rect: Rect? = null
        runOnUiThread {
            // Check the keyboard surface itself, excluding navigation-bar glass.
            val keyboard = WindowInspector.getGlobalWindowViews().filter { view ->
                val attrs = view.layoutParams as? WindowManager.LayoutParams
                attrs?.title?.toString() == "CyIME keyboard blur: keyboard" && view.isShown && view.width > 0 && view.height > 0
            }.maxByOrNull { it.width.toLong() * it.height }
            if (keyboard != null) rect = viewRect(keyboard)
        }
        return rect
    }

    private fun recordWindowGeometry(mode: String, evidence: MutableList<String>) {
        runOnUiThread {
            // WindowInspector enumerates only this process; never inspect view text.
            WindowInspector.getGlobalWindowViews().forEachIndexed { index, view ->
                val attrs = view.layoutParams as? WindowManager.LayoutParams
                val screen = IntArray(2)
                val window = IntArray(2)
                view.getLocationOnScreen(screen)
                view.getLocationInWindow(window)
                evidence += "$mode radius-14 root[$index]: title=${attrs?.title}; screen=${screen.contentToString()}; window=${window.contentToString()}; size=${view.width}x${view.height}; attrsXY=${attrs?.x},${attrs?.y}; attrsSize=${attrs?.width}x${attrs?.height}"
            }
        }
        val owner = Regex("\\bmOwnerUid=${Process.myUid()}\\b")
        val geometry = Regex("mAttrs=|mFrame=|mParentFrame=|mSurfaceInsets=|surfaceInsets=|\\bFrames:|\\bframe=|\\bparentFrame=")
        val windows = shell("dumpsys window windows").split(Regex("(?m)(?=^\\s*Window #\\d+ Window\\{)"))
        var matched = 0
        windows.forEach { block ->
            val header = block.lineSequence().firstOrNull { it.trimStart().startsWith("Window #") } ?: return@forEach
            val target = header.contains("CyIME keyboard blur") || header.contains(" InputMethod}")
            if (target && owner.containsMatchIn(block)) {
                matched++
                evidence += "$mode radius-14 ${header.trim()}"
                block.lineSequence().filter { geometry.containsMatchIn(it) }.forEach { evidence += it.trim() }
            }
        }
        if (matched == 0) evidence += "$mode radius-14: no matching current-process blur/InputMethod window geometry"
    }

    // Android may create the IME Compose root before the test registry starts. Read
    // that real window's existing semantics rather than rebuilding or replacing it.
    private fun imeNode(tag: String): SemanticsNode? {
        var found: SemanticsNode? = null
        runOnUiThread {
            fun visit(view: View) {
                if (found != null) return
                if (view is ViewRootForTest) {
                    found = view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).firstOrNull {
                        it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag
                    }
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            WindowInspector.getGlobalWindowViews().filter { findService(it) != null && it.isShown }.forEach(::visit)
        }
        return found
    }
    private fun exists(tag: String) = imeNode(tag) != null
    private fun screenRect(tag: String): Rect {
        var rect: Rect? = null
        runOnUiThread {
            val node = checkNotNull(imeNode(tag)) { "Missing real IME node: $tag" }
            rect = Rect(node.positionOnScreen.x, node.positionOnScreen.y,
                node.positionOnScreen.x + node.size.width, node.positionOnScreen.y + node.size.height)
        }
        return checkNotNull(rect)
    }
    private fun viewRect(view: View): Rect {
        val xy = IntArray(2)
        var width = 0
        var height = 0
        runOnUiThread { view.getLocationOnScreen(xy); width = view.width; height = view.height }
        return Rect(xy[0].toFloat(), xy[1].toFloat(), (xy[0] + width).toFloat(), (xy[1] + height).toFloat())
    }
    private fun assertRectStable(label: String, a: Rect, b: Rect) {
        assertEquals("$label left", a.left, b.left, 1f); assertEquals("$label top", a.top, b.top, 1f)
        assertEquals("$label right", a.right, b.right, 1f); assertEquals("$label bottom", a.bottom, b.bottom, 1f)
    }
    private fun inset(rect: Rect, amount: Float) = Rect(rect.left + amount, rect.top + amount, rect.right - amount, rect.bottom - amount)
    private fun distance(a: Int, b: Int) = maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
    private fun meanRgb(image: Bitmap, region: Rect): DoubleArray {
        val sum = DoubleArray(3)
        var count = 0
        eachPixel(image, region) { x, y ->
            val color = image.getPixel(x, y)
            sum[0] += Color.red(color); sum[1] += Color.green(color); sum[2] += Color.blue(color); count++
        }
        return DoubleArray(3) { sum[it] / count.coerceAtLeast(1) }
    }
    private fun meanDifference(a: Bitmap, b: Bitmap, region: Rect): Double {
        var sum = 0.0
        var count = 0
        eachPixel(a, region) { x, y ->
            val one = a.getPixel(x, y); val two = b.getPixel(x, y)
            sum += (abs(Color.red(one) - Color.red(two)) + abs(Color.green(one) - Color.green(two)) + abs(Color.blue(one) - Color.blue(two))) / 3.0
            count++
        }
        return sum / count.coerceAtLeast(1)
    }
    private fun luminanceSpread(image: Bitmap, region: Rect): Int {
        val samples = mutableListOf<Int>()
        eachPixel(image, region, step = 1) { x, y ->
            val color = image.getPixel(x, y)
            samples += (Color.red(color) * .2126 + Color.green(color) * .7152 + Color.blue(color) * .0722).roundToInt()
        }
        samples.sort()
        check(samples.size > 100)
        // Trim antialiased text/edge outliers rather than allowing one white glyph to hide blur.
        return samples[(samples.size * .85).toInt()] - samples[(samples.size * .15).toInt()]
    }
    private inline fun eachPixel(image: Bitmap, region: Rect, step: Int = 3, action: (Int, Int) -> Unit) {
        val left = ceil(region.left).toInt().coerceIn(0, image.width - 1)
        val top = ceil(region.top).toInt().coerceIn(0, image.height - 1)
        val right = floor(region.right).toInt().coerceIn(left + 1, image.width)
        val bottom = floor(region.bottom).toInt().coerceIn(top + 1, image.height)
        for (y in top until bottom step step) for (x in left until right step step) action(x, y)
    }
    private fun save(image: Bitmap, host: View, name: String) {
        val rect = viewRect(host)
        val left = floor(rect.left).toInt().coerceIn(0, image.width - 1)
        val top = floor(rect.top).toInt().coerceIn(0, image.height - 1)
        val right = ceil(rect.right).toInt().coerceIn(left + 1, image.width)
        val bottom = ceil(rect.bottom).toInt().coerceIn(top + 1, image.height)
        val crop = Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        try { File(directory(), "$name.png").outputStream().use { check(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { if (crop !== image) crop.recycle() }
    }
    private fun directory() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "real-glass-backdrop").apply { mkdirs() }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
    private fun findService(view: View): XimeInputMethodService? {
        var context: Context? = view.context
        while (context != null) {
            if (context is XimeInputMethodService) return context
            context = (context as? ContextWrapper)?.baseContext?.takeUnless { it === context }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) findService(view.getChildAt(index))?.let { return it }
        return null
    }

    private class TestBackdrop(context: Context) : View(context) {
        var sceneColor = RED
        var pattern = false
        private val paint = Paint().apply { isAntiAlias = false }
        override fun onDraw(canvas: Canvas) {
            if (!pattern) canvas.drawColor(sceneColor)
            else {
                val stripe = (4 * resources.displayMetrics.density).roundToInt().coerceAtLeast(3)
                var x = 0
                while (x < width) {
                    paint.color = if ((x / stripe) % 2 == 0) Color.rgb(20, 20, 20) else Color.rgb(235, 235, 235)
                    canvas.drawRect(x.toFloat(), 0f, (x + stripe).toFloat(), height.toFloat(), paint)
                    x += stripe
                }
            }
        }
    }

    /** Restore only keys whose current value still equals our last write; never restore a whole snapshot. */
    private class PreferenceLedger(private val prefs: SharedPreferences) {
        private val original = linkedMapOf<String, Any?>()
        private val written = linkedMapOf<String, Any?>()
        val hasMutations get() = written.isNotEmpty()
        fun mutate(keys: Set<String>, action: () -> Unit) {
            val before = prefs.all
            keys.forEach { key ->
                check(!written.containsKey(key) || before[key] == written[key]) { "Preference $key changed externally; stop instead of overwriting it" }
                if (!original.containsKey(key)) original[key] = before[key]
            }
            action()
            val after = prefs.all
            keys.filter { before[it] != after[it] }.forEach { written[it] = after[it] }
        }
        fun write(values: Map<String, Any>) {
            val current = prefs.all
            values.keys.forEach { key ->
                check(!written.containsKey(key) || current[key] == written[key]) { "Preference $key changed externally; stop instead of overwriting it" }
                if (!original.containsKey(key)) original[key] = current[key]
            }
            val edit = prefs.edit()
            values.forEach { (key, value) -> put(edit, key, value) }
            check(edit.commit()) { "Cannot save temporary glass fixture" }
            written.putAll(values)
        }
        fun restore(): List<String> {
            val preserved = mutableListOf<String>()
            val current = prefs.all
            val edit = prefs.edit()
            written.forEach { (key, value) -> if (current[key] == value) put(edit, key, original[key]) else preserved += key }
            check(edit.commit()) { "Cannot restore temporary glass fixture" }
            return preserved
        }
        private fun put(edit: SharedPreferences.Editor, key: String, value: Any?) {
            when (value) {
                null -> edit.remove(key)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                else -> error("Unsupported preference type: $key")
            }
        }
    }

    private companion object {
        val RED = Color.rgb(232, 24, 32)
        val BLUE = Color.rgb(24, 40, 232)
    }
}
