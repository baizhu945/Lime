package com.kingzcheung.xime.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.handwriting.*
import com.kingzcheung.xime.model.*
import com.kingzcheung.xime.runtime.adaptation.DeviceCapabilityProbe
import com.kingzcheung.xime.settings.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class OfflineEditionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun languageSettingsFitNarrowScreensAndLargeText() {
        val scale = mutableStateOf(1f)
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
            MaterialTheme { Box(Modifier.requiredSize(280.dp, 500.dp)) {
                com.kingzcheung.xime.ui.settings.LanguageSettingsContent {}
            } }
        } }
        for (font in listOf(1f, 1.3f, 2f)) {
            rule.runOnIdle { scale.value = font }
            rule.assertGeometry("language-settings", "language settings font=$font")
        }
    }

    @Test fun freshLanguageSettingsOfferChineseAndRequiredEnglish() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "language-test-${System.nanoTime()}").apply { mkdirs() }
        val fresh = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("language-test-$name", mode)
        }
        val prefs = SettingsPreferences.getPrefsPublic(fresh)
        prefs.edit().clear().commit()
        try {
            val hardware = DeviceCapabilityProbe.read(fresh)
            val profile = DeviceModelProfiles.choose(hardware)
            val glow = com.kingzcheung.xime.runtime.adaptation.DeviceKeyEffectDefaults.choose(hardware).glow
            DeviceDefaults.initialize(fresh)
            LanguagePreferences.initialize(fresh)
            assertTrue(SettingsPreferences.isSmartPredictionEnabled(fresh))
            assertTrue(SettingsPreferences.isSingleAssociationMode(fresh))
            assertEquals(profile.predictionModel, SettingsPreferences.getPredictionSelectedModel(fresh))
            assertTrue(SettingsPreferences.isSttEnabled(fresh))
            assertTrue(SettingsPreferences.isSttUseLocal(fresh))
            // 新装是「性能与省电 → 自动」：语音常驻与否跟本机档位那一组走。
            val tier = com.kingzcheung.xime.runtime.adaptation.PerformanceTiers.classify(hardware)
            assertEquals(com.kingzcheung.xime.settings.PerformanceProfile.presets.getValue(tier).speechEngineIdleMinutes == 0,
                SettingsPreferences.isSttKeepEngineAlive(fresh))
            DeviceDefaults.initialize(fresh)
            assertEquals(glow, prefs.getBoolean("key_glow_enabled", !glow))
            SettingsPreferences.setSmartPredictionEnabled(fresh, false)
            SettingsPreferences.setAssociationSingleMode(fresh, false)
            SettingsPreferences.setSttEnabled(fresh, false)
            SettingsPreferences.setSttUseLocal(fresh, false)
            prefs.edit().putBoolean("key_glow_enabled", !glow).commit()
            DeviceDefaults.initialize(fresh)
            assertFalse(SettingsPreferences.isSmartPredictionEnabled(fresh))
            assertFalse(SettingsPreferences.isSingleAssociationMode(fresh))
            assertFalse(SettingsPreferences.isSttEnabled(fresh))
            // 设备默认不改用户存的选择（是否真用在线还要看在线接口是否测试通过）。
            assertFalse(prefs.getBoolean(SettingsPreferences.KEY_STT_USE_LOCAL, true))
            assertEquals(!glow, prefs.getBoolean("key_glow_enabled", glow))
            assertEquals(setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH,
                InputLanguage.UNSPECIFIED), LanguagePreferences.enabled(fresh))
            val choices = InputModes.languageChoices(listOf(SchemaInfo("rime_ice", "中文26键", "", "", "")), "rime_ice",
                emptyMap(), InputModes.languageOrder(fresh))
            assertEquals(listOf(InputLanguage.CHINESE, InputLanguage.ENGLISH), choices.map { it.language })
        } finally {
            prefs.edit().clear().commit()
            directory.deleteRecursively()
        }
    }

    @Test fun whiteKeyLabelsStillProduceVisibleInkOnWhitePaper() {
        val ink = handwritingInk(Color.White, Color.White)
        assertEquals(Color.Black, ink)
        assertEquals(Color.White, handwritingInk(Color.Black, Color.Black))
        rule.setContent {
            Canvas(Modifier.size(160.dp).background(Color.White).testTag("paper")) {
                renderStrokes(listOf(listOf(StrokePoint(size.width * .2f, size.height * .5f),
                    StrokePoint(size.width * .8f, size.height * .5f))), emptyList(), ink)
            }
        }
        val pixels = rule.onNodeWithTag("paper").captureToImage().toPixelMap()
        assertTrue("真实笔迹应明显区别于白纸", pixels[pixels.width / 2, pixels.height / 2].red < .2f)
    }

    @Test fun handwritingPenHasBoundedWidthAndTransparentGradientWithoutDarkRim() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Canvas(Modifier.size(160.dp).background(Color.White).testTag("pen")) {
                    renderStrokes(listOf(listOf(StrokePoint(20f,80f),StrokePoint(140f,80f))),
                        emptyList(), Color(0xFF685191))
                }
            }
        }
        val pixels = rule.onNodeWithTag("pen").captureToImage().toPixelMap()
        fun changed(x: Int) = (0 until pixels.height).count { pixels[x,it].green < .99f }
        assertTrue("Pen including its translucent shoulder must stay below 6 pixels at density 1", changed(80) in 3..6)
        assertTrue("Start must not turn into a broad dot", changed(20) <= 6)
        assertTrue("Ink should fade toward its start", pixels[24,80].green > pixels[80,80].green + .08f)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            assertTrue("No opaque black rim", pixels[x,y].green >= .30f)
        }
    }

    @Test fun diagonalPenHasAntialiasedEdgesAtPhoneDensity() {
        val scale = 2.75f
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                Canvas(Modifier.size(160.dp).background(Color.White).testTag("diagonal-pen")) {
                    renderStrokes(listOf(listOf(StrokePoint(20.dp.toPx(),20.dp.toPx()),
                        StrokePoint(140.dp.toPx(),110.dp.toPx()))), emptyList(), Color.Black)
                }
            }
        }
        val pixels = rule.onNodeWithTag("diagonal-pen").captureToImage().toPixelMap()
        val edgeShades = mutableSetOf<Int>()
        // Sample the outer shoulder, away from the gradient-filled core and rounded caps.
        // A non-antialiased rasterizer leaves one flat shoulder shade, not coverage levels.
        for (x in 130 until 310) for (y in 70 until 310) {
            val distance = kotlin.math.abs(.6f*(x+.5f-20f*scale)-.8f*(y+.5f-20f*scale))
            if (distance in (2.1f*scale)..(2.7f*scale)) {
                val level=(pixels[x,y].green*255).toInt()
                if(level in 222..252) edgeShades += level
            }
        }
        assertTrue("Slanted edge should contain multiple subpixel coverage levels: $edgeShades", edgeShades.size >= 8)
    }

    @Test fun offlineEditionInstallsEveryModelAndDoesNotResetLaterChoices() = runBlocking {
        Assume.assumeTrue(BuildConfig.BUNDLED_MODELS)
        val app = ApplicationProvider.getApplicationContext<Context>()
        withTimeout(180_000) {
            while (!app.getSharedPreferences("bundled_models", 0).getBoolean("defaults_applied", false)) delay(200)
        }
        assertTrue(BundledModelInstaller.modelIds.all { ModelManager.isModelReady(app, it) })
        assertTrue(SettingsPreferences.isSmartPredictionEnabled(app))
        assertTrue(SettingsPreferences.isSttEnabled(app))
        assertTrue(SettingsPreferences.isSttUseLocal(app))
        try {
            SettingsPreferences.setSmartPredictionEnabled(app, false)
            withContext(Dispatchers.IO) { BundledModelInstaller.install(app) }
            assertFalse("完成引导后应尊重用户关闭", SettingsPreferences.isSmartPredictionEnabled(app))
        } finally { SettingsPreferences.setSmartPredictionEnabled(app, true) }
    }

    @Test fun freshInstallDoesNotSeedExperimentalLayouts() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "offline-layout-test-${System.nanoTime()}").apply { mkdirs() }
        val fresh = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        try {
            assertTrue(CustomKeyboardLayouts.load(fresh).isEmpty())
            assertEquals(listOf("rime_ice", "t9_pinyin"), CyimeInputDefaults.recommended)
        } finally { directory.deleteRecursively(); CustomKeyboardLayouts.load(app) }
    }

    @Test fun bundledDefaultsUseDeviceProfileWithoutResettingUserChoices() = runBlocking {
        Assume.assumeTrue(BuildConfig.BUNDLED_MODELS)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val used = mutableSetOf<String>()
        val fresh = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val testName = "bundle-profile-test-$name"
                used += testName
                return app.getSharedPreferences(testName, mode)
            }
        }
        try {
            val hardware = DeviceCapabilityProbe.read(fresh)
            val profile = DeviceModelProfiles.choose(hardware)
            val glow = com.kingzcheung.xime.runtime.adaptation.DeviceKeyEffectDefaults.choose(hardware).glow
            withContext(Dispatchers.IO) { BundledModelInstaller.install(fresh) }
            val high = profile.tier == DeviceModelTier.ENHANCED
            assertEquals(profile.predictionModel, SettingsPreferences.getPredictionSelectedModel(fresh))
            val asr = com.kingzcheung.xime.speech.AsrModelManager(fresh)
            assertEquals(com.kingzcheung.xime.speech.SpeechModelCatalog.ZIPFORMER, asr.getFirstPassModelId())
            assertEquals(high, asr.isRefinementEnabled())
            assertEquals(glow, SettingsPreferences.getPrefsPublic(fresh).getBoolean("key_glow_enabled", !glow))
            asr.setRefinementEnabled(!high)
            SettingsPreferences.setPredictionSelectedModel(fresh, DeviceModelProfiles.SMALL)
            SettingsPreferences.getPrefsPublic(fresh).edit().putBoolean("key_glow_enabled", !high).commit()
            withContext(Dispatchers.IO) { BundledModelInstaller.install(fresh) }
            assertEquals(DeviceModelProfiles.SMALL, SettingsPreferences.getPredictionSelectedModel(fresh))
            assertEquals(!high, asr.isRefinementEnabled())
            assertEquals(!high, SettingsPreferences.getPrefsPublic(fresh).getBoolean("key_glow_enabled", high))
        } finally { used.forEach { app.getSharedPreferences(it, 0).edit().clear().commit() } }
    }
}
