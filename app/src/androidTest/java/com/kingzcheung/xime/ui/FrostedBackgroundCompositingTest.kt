package com.kingzcheung.xime.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import org.junit.Before
import org.junit.After

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.SharedPreferencesTestRule
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.SpaceKeyButton
import com.kingzcheung.xime.ui.theme.KeyboardBackdropHost
import com.kingzcheung.xime.ui.theme.LocalMaterialPalette
import com.kingzcheung.xime.ui.theme.MaterialPalette
import com.kingzcheung.xime.ui.theme.VisualStyle
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.keyboardBackground
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Reproduces the real key/background update order without involving the IME window. */
class FrostedBackgroundCompositingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var originalStyle = VisualStyle.ORIGINAL

    @Before fun keepFixtureVisible() {
        rule.runOnUiThread {
            rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            originalStyle = VisualStyles.current
            VisualStyles.current = VisualStyle.ORIGINAL
        }
    }

    @After fun restoreMaterial() {
        rule.runOnUiThread { VisualStyles.current = originalStyle }
    }
    @get:Rule val preferences = SharedPreferencesTestRule()
    private val context by lazy {
        object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences.getSharedPreferences()
        }
    }

    @Test fun keyOpacityDoesNotRepaintBackgroundAndToolbarSharesTheSameGlass() {
        var dark by mutableStateOf(true)
        var shared by mutableStateOf(true)
        var glass by mutableStateOf(FrostedGlassConfig(true, 0f, 0f, 0f))
        val host = 0xFFE81820.toInt()
        rule.setContent {
            val panel = Color(if (dark) 0xFF191C22 else 0xFFF4F6FA)
            val cap = Color(if (dark) 0xFF2E323A else 0xFFFFFFFF)
            val text = Color(if (dark) 0xFFE9EDF5 else 0xFF20242C)
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f),
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(frostedGlass = glass),
                LocalMaterialPalette provides MaterialPalette(panel, text)) {
                MaterialTheme {
                    KeyboardBackdropHost(shared, null, dark, panel, glass, 204.dp,
                        Modifier.size(320.dp, 204.dp).background(Color(host)).testTag("compositing-panel")) {
                        Column(Modifier.fillMaxSize().keyboardBackground(null, dark, panel, glass)) {
                            CandidateBar(CandidateBarState.Idle,
                                visuals = CandidateBarVisuals(Color.Transparent, text, Color.Transparent,
                                    text, text, dark, cap), callbacks = CandidateBarCallbacks(onCandidateSelect = {}))
                            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                SpaceKeyButton({}, cap, text, schemaName = "A", modifier = Modifier.size(180.dp, 80.dp),
                                    shadowEnabled = false)
                            }
                        }
                    }
                }
            }
        }
        for (useShared in listOf(true, false)) for (useDark in listOf(true, false)) {
            val panel = (if (useDark) 0xFF191C22 else 0xFFF4F6FA).toInt()
            val cap = (if (useDark) 0xFF2E323A else 0xFFFFFFFF).toInt()
            val label = "${if (useShared) "shared" else "own"}-${if (useDark) "dark" else "light"}"
            rule.runOnIdle { shared = useShared; dark = useDark; glass = FrostedGlassConfig(true, 0f, 0f, 0f) }
            awaitPanel(host, host, "$label-transparent")
            rule.runOnIdle { glass = glass.copy(backgroundOpacity = 0.6f) }
            val background = blend(panel, host, 0.6f)
            awaitPanel(background, background, "$label-background")
            rule.runOnIdle { glass = glass.copy(keyOpacity = 0.6f) }
            // At 60% alpha the dark #2E323A cap keeps its elevation using
            // #4D5361 tint; light white caps remain white. Alpha stays 60%.
            val glassCap = if (useDark) 0xFF4D5361.toInt() else cap
            awaitPanel(background, blend(glassCap, background, 0.6f), "$label-key")
        }
        for (style in VisualStyle.entries.filter { it != VisualStyle.ORIGINAL }) for (useDark in listOf(true, false)) {
            val panel = (if (useDark) 0xFF191C22 else 0xFFF4F6FA).toInt()
            rule.runOnIdle {
                shared = true
                dark = useDark
                glass = FrostedGlassConfig(true, 0f, 0.2f, 0.8f)
                VisualStyles.current = style
            }
            // Keycaps keep their selected material. Only the rail's blank area must match the body.
            awaitPanel(blend(panel, host, 0.2f), null,
                "material-${style.id}-${if (useDark) "dark" else "light"}")
        }
    }

    private fun awaitPanel(expectedBackground: Int, expectedKey: Int?, name: String) {
        var last: Bitmap? = null
        try {
            rule.waitUntil(10_000) {
                last = rule.onNodeWithTag("compositing-panel").captureToImage().asAndroidBitmap()
                val frame = checkNotNull(last)
                difference(frame.getPixel(10, 124), expectedBackground) <= 3 &&
                    (expectedKey == null || difference(frame.getPixel(100, 124), expectedKey) <= 3) &&
                    difference(frame.getPixel(20, 20), frame.getPixel(10, 124)) <= 3
            }
        } finally {
            last?.let { frame ->
                val dir = File(context.getExternalFilesDir(null), "frosted-compositing").apply { mkdirs() }
                File(dir, "$name.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue("$name body gap expected=${expectedBackground.toUInt().toString(16)} actual=${frame.getPixel(10, 124).toUInt().toString(16)}",
                    difference(frame.getPixel(10, 124), expectedBackground) <= 3)
                if (expectedKey != null) assertTrue("$name key must be one cap over one background",
                    difference(frame.getPixel(100, 124), expectedKey) <= 3)
                // With no toolbar actions the leading logo occupies the centered slot:
                // (160, 20) hits the icon and its halo, not the rail's blank area.
                val toolbar = frame.getPixel(20, 20)
                val body = frame.getPixel(10, 124)
                assertTrue("$name toolbar (20,20)=${toolbar.toUInt().toString(16)} must match body (10,124)=${body.toUInt().toString(16)} material alpha",
                    difference(toolbar, body) <= 3)
            }
        }
    }

    private fun blend(foreground: Int, background: Int, alpha: Float): Int {
        fun c(shift: Int) = (((foreground ushr shift and 255) * alpha) +
            ((background ushr shift and 255) * (1f - alpha))).roundToInt()
        return android.graphics.Color.rgb(c(16), c(8), c(0))
    }
    private fun difference(a: Int, b: Int) = maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)),
        abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255)))
}
