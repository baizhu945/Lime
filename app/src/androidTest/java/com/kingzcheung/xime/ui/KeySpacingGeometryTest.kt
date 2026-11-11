package com.kingzcheung.xime.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.keyboard.SpatialCosts
import com.kingzcheung.xime.keyboard.SpatialTaps
import com.kingzcheung.xime.keyboard.encodeLetterNeighbors
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.theme.VisualStyle
import com.kingzcheung.xime.ui.theme.KeyShape
import com.kingzcheung.xime.ui.theme.KeyShapes
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.floor
import java.io.File

class KeySpacingGeometryTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var sizeBeforeFixture: String? = null
    private var materialBeforeFixture: VisualStyle? = null
    // Stable, passive callback, created before setContent (not a probability input).
    private var probabilityGeometry: ProbabilityGeometrySnapshot? = null
    private val geometryObserver: (ProbabilityGeometrySnapshot) -> Unit = { probabilityGeometry = it }

    @Before fun before() {
        materialBeforeFixture = VisualStyles.current
        // G/B coverage integrates the undecorated cyan/white calibration cap. A material's
        // sheen changes that colour without changing the cap's physical padding.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            VisualStyles.current = VisualStyle.ORIGINAL
        }
        KeysConfigHelper.loadConfig(app)
        // The 1493dp layout fixture needs a window wider than the phone's 1080px,
        // but keep the override portrait so changing it does not rotate/recreate
        // the already-running Compose test activity. Restore it afterwards.
        sizeBeforeFixture = shell("wm size")
        shell("wm size 1600x2000")
    }

    @After fun after() {
        // The standalone UI collector does not feed the IME; do not leak its measured taps.
        SpatialTaps.clear()
        materialBeforeFixture?.let { previous ->
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                VisualStyles.current = previous
            }
        }
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
        val override = Regex("Override size: (\\d+x\\d+)")
            .find(sizeBeforeFixture.orEmpty())?.groupValues?.get(1)
        shell(if (override != null) "wm size $override" else "wm size reset")
        // wm commands return before the activity configuration is recomputed. The next
        // class runs immediately; wait until the display is portrait again so its fixed
        // 360x640dp pages are not laid out in the stale landscape window.
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val configuration = app.resources.configuration
            if (configuration.screenHeightDp > configuration.screenWidthDp) break
            SystemClock.sleep(50)
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    @Test fun paintedInsetsScaleEquallyForLettersTallEnterAndWideSpaceWithoutShrinkingTouchBounds() {
        val factor = mutableFloatStateOf(1f)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    KeyboardKeySpacingScope(Modifier.size((420 * factor.floatValue).dp,
                        (280 * factor.floatValue).dp).background(Color.Black).testTag("spacing-root"),
                        columns = 4f, rows = 2f, horizontalInset = 0.dp, verticalInset = 0.dp) { body ->
                        Row(body) {
                            Column(Modifier.weight(1f)) {
                                KeyButton("A", {}, Color.Cyan, Color.White, Modifier.weight(1f).testTag("letter"), shadowEnabled = false)
                                KeyButton("B", {}, Color.Cyan, Color.White, Modifier.weight(1f), shadowEnabled = false)
                            }
                            ActionKeyButton("回车", {}, Color.Cyan, Color.White,
                                Modifier.weight(1f).testTag("tall-enter"), shadowEnabled = false)
                            SpaceKeyButton({}, Color.Cyan, Color.White, "", modifier = Modifier.weight(2f).testTag("wide-space"), shadowEnabled = false)
                        }
                    }
                }
            }
        }
        var phoneInsets: Map<String, Pair<Float, Float>> = emptyMap()
        for (size in listOf(1f, 2f)) {
            rule.runOnIdle { factor.floatValue = size }
            rule.waitForIdle()
            val root = rule.onNodeWithTag("spacing-root", useUnmergedTree = true)
            val bitmap = root.captureToImage().asAndroidBitmap()
            val rootBounds = root.fetchSemanticsNode().boundsInRoot
            saveSpacingImage("spacing-insets-${size.toInt()}x", bitmap)
            val insets = listOf("letter", "tall-enter", "wide-space").associateWith { tag ->
                val bounds = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val expectedWidth = (if (tag == "wide-space") 210 else 105) * size
                assertEquals("$tag full touch width", expectedWidth, bounds.width, 1f)
                assertEquals("$tag full touch height", (if (tag == "letter") 140 else 280) * size, bounds.height, 1f)
                val relative = bounds.translate(-rootBounds.left, -rootBounds.top)
                val measured = paintedInsets(bitmap, relative)
                android.util.Log.i("KeySpacingGeometry", "size=$size tag=$tag root=$rootBounds key=$bounds relative=$relative " +
                    "bitmap=${bitmap.width}x${bitmap.height} measured=$measured " + edgePixels(bitmap, relative))
                measured
            }
            // 验证实际绘制采用布局度量，宽回车/空格不会按自身宽高二次放大间隙。
            val cellWidth = 420f * size / 4f
            val cellHeight = 280f * size / 2f
            val expected = cellMetrics(KeyVisualPolicy.Qwerty, cellWidth, cellHeight)
            insets.forEach { (tag, inset) ->
                assertEquals("$tag horizontal inset", expected.insetX!!, inset.first, 1f)
                assertEquals("$tag vertical inset", expected.insetY!!, inset.second, 1f)
            }
            if (size == 1f) phoneInsets = insets
        }
        // 这组宽键夹具已达到间距上限，只保留整数化误差。
        phoneInsets.forEach { (tag, inset) ->
            assertEquals("$tag capped horizontal ratio", 6f, inset.first, 1f)
            assertEquals("$tag capped vertical ratio", 5.5f, inset.second, 1f)
        }
    }

    @Test fun realLayoutsUseEqualPaintedGapsAndTheSameKeySizeRatio() {
        data class Mode(val schema: String, val ascii: Boolean = false, val split: Boolean = false)
        val modes = listOf(Mode("rime_ice", true), Mode("rime_ice"), Mode("pinyin_14jian"),
            Mode("t9_pinyin"), Mode("jaroomaji"), Mode("japanese_kana"), Mode("rime_ice", true, true))
        val mode = mutableStateOf(modes.first())
        val dimensions = mutableStateOf(420 to 280)
        val auditMode = mutableStateOf(false)
        val auditEpoch = mutableIntStateOf(0)
        val commits = mutableListOf<String>()
        var neighborEncoding: String? = null
        val collectKey: (String) -> Unit = { commits.add(it) }
        val usedMetrics = mutableStateOf<KeyVisualMetrics?>(null)
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 1400; screenHeightDp = 1000; orientation = Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(1f),
                LocalKeyboardExplicitWidth provides true,
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(splitKeyboardEnabled = mode.value.split),
                LocalProbabilityGeometryObserver provides geometryObserver,
                LocalKeyboardMetricsProbe provides { usedMetrics.value = it }) {
                MaterialTheme {
                    key(mode.value, auditMode.value, auditEpoch.intValue) {
                        val vm = remember { KeyboardViewModel(app) }
                        val ui = KeyboardUiState(currentSchemaId = mode.value.schema, isAsciiMode = mode.value.ascii)
                        val callbacks = KeyboardCallbacks(onKeyPress = { text, _ -> collectKey(text) }, onCandidateSelect = {},
                            onLetterNeighbors = { _, encoded -> neighborEncoding = encoded })
                        // Capture the untransformed black parent, never the rotated keyboard layer.
                        Box(Modifier.size(
                            (if (auditMode.value) 800 else dimensions.value.first).dp,
                            (if (auditMode.value) 800 else dimensions.value.second).dp)
                            .background(Color.Black).testTag("actual-grid"), contentAlignment = Alignment.Center) {
                            val body = if (auditMode.value) Modifier.size(360.dp, 280.dp).graphicsLayer {
                                rotationZ = 17f; scaleX = .85f; scaleY = 1.05f
                            } else Modifier.fillMaxSize()
                            when (mode.value.schema) {
                                "japanese_kana" -> JapaneseKanaKeyboardLayout({}, {}, Color.Cyan, Color.White, Color.Blue,
                                    modifier = body, shadowEnabled = false)
                                "t9_pinyin" -> T9KeyboardLayout({}, callbacks, ui, remember { T9InputController() },
                                    Color.Cyan, Color.White, Color.Blue, modifier = body, shadowEnabled = false, isFloatingMode = true)
                                else -> KeyboardLayout(collectKey, vm, callbacks, ui, mode.value.ascii, body)
                            }
                        }
                    }
                }
            }
        }
        val report = StringBuilder()
        for (current in modes) for (size in listOf(280 to 164, 360 to 232, 360 to 356,
            934 to 360, 934 to 627, 1493 to 814)) {
            rule.runOnIdle {
                KeysConfigHelper.setActiveKeyboardSchema(current.schema)
                usedMetrics.value = null
                probabilityGeometry = null
                neighborEncoding = null
                mode.value = current
                dimensions.value = size
            }
            rule.waitForIdle()
            val rows = KeysConfigHelper.getKeyRows(current.ascii)
            val labels = when (current.schema) {
                "japanese_kana" -> listOf("あ", "か", "た")
                "t9_pinyin" -> listOf("ABC", "DEF", "JKL")
                else -> listOf(rows[0][0], rows[0][1], rows[1][1]).map {
                    KeysConfigHelper.getKeyDisplayLabel(it, current.ascii, false)
                }
            }
            val root = rule.onNodeWithTag("actual-grid", true)
            assertEquals("fixture width cannot silently clamp", size.first,
                root.fetchSemanticsNode().size.width)
            assertEquals("fixture height cannot silently clamp", size.second,
                root.fetchSemanticsNode().size.height)
            val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
            val bitmap = root.captureToImage().asAndroidBitmap()
            val keypad = current.schema == "t9_pinyin" || current.schema == "japanese_kana"
            val policy = (if (keypad) KeyVisualPolicy.T9 else KeyVisualPolicy.Qwerty)
                .copy(maxKeyWidth = Float.MAX_VALUE)
            val columns = when (current.schema) {
                "t9_pinyin" -> 5f * 3f / 3.4f
                "japanese_kana" -> 5f
                else -> 10f
            }
            val spacing = if (keypad) null to null else KeysConfigHelper.getKeyboardKeyConfig().spacingFor("qwerty")

            // Measure the real key cell (semantic bounds), then read its four painted
            // insets. Adjacent cells share exact edges, so no text/background heuristics
            // or column-count assumptions are involved.
            val tags = when (current.schema) {
                "japanese_kana" -> listOf("kana-key:a", "kana-key:ka", "kana-key:ta")
                "t9_pinyin" -> labels.map { "t9-key:$it" }
                else -> listOf(rows[0][0], rows[0][1], rows[1][1]).map { "qwerty-key:$it" }
            }
            fun keyBounds(tag: String): Rect = rule.onNodeWithTag(tag, true)
                .fetchSemanticsNode().boundsInRoot.translate(-origin.x, -origin.y)
            val a = keyBounds(tags[0]); val b = keyBounds(tags[1]); val below = keyBounds(tags[2])
            val used = usedMetrics.value ?: error("layout did not report metrics")
            // Layout rounds each painted inset to whole pixels; mirror that before
            // summing the two sides, otherwise a 4.5dp side is measured as 5px.
            val expectedX = resolvedVisualGap(spacing.first, used.insetX!!.roundToInt().toFloat())
            val expectedY = resolvedVisualGap(spacing.second, used.insetY!!.roundToInt().toFloat()) + used.extraInsetY * 2f
            val cellHeight = used.cellHeightDp
            val ia = paintedEdgeInsets(bitmap, a)
            val ib = paintedEdgeInsets(bitmap, b)
            val ic = paintedEdgeInsets(bitmap, below)
            val gx = ia.right + ib.left; val gy = ia.bottom + ic.top
            val cellWidth = a.width
            val note = "${current.schema} ascii=${current.ascii} split=${current.split} $size gap=($gx,$gy) " +
                "expected=($expectedX,$expectedY) cell=($cellWidth,$cellHeight) actualCell=(${a.width},${a.height}) " +
                "rowGap=${below.top - a.bottom} spacing=$spacing insets=(${ia.left},${ia.top},${ia.right},${ia.bottom})/(${ic.left},${ic.top},${ic.right},${ic.bottom})"
            report.appendLine(note)
            assertEquals("$note gapX", expectedX, gx, 1.01f)
            assertEquals("$note gapY", expectedY, gy, 1.01f)
            assertEquals("$note same row top", a.top, b.top, 1f)
            assertEquals("$note same row bottom", a.bottom, b.bottom, 1f)
            assertEquals("$note first cap top", expectedY / 2f, a.top + ia.top, 1f)
            saveSpacingImage("grid-${current.schema}-${current.ascii}-${current.split}-${size.first}-${size.second}", bitmap)
            val geometry = rule.runOnIdle { probabilityGeometry }
            val required = current.ascii && !current.split
            if (required) assertNotNull("$note English probability geometry is mandatory", geometry)
            // Nonprobability/split layouts retain all their original geometry checks above.
            // Chinese/Japanese standard-26 layouts are audited whenever they publish geometry.
            if (!current.split && geometry != null) {
                if (!current.ascii) assertEquals("$note native neighbours share the exact local Gaussian geometry",
                    encodeLetterNeighbors(geometry.cells), rule.runOnIdle { neighborEncoding })
                val samples = auditProbabilityPaint(root, bitmap, geometry, note)
                if (required && size == (280 to 164)) {
                    assertPaintedTouches(root, samples.filterKeys { it in "qwertyuiop" }, commits, note)
                }
            }
        }
        rule.runOnIdle {
            KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
            probabilityGeometry = null
            mode.value = modes.first()
            dimensions.value = 360 to 280
            auditMode.value = true
        }
        rule.waitForIdle()
        val transformedRoot = rule.onNodeWithTag("actual-grid", true)
        val transformedBitmap = transformedRoot.captureToImage().asAndroidBitmap()
        val transformedGeometry = rule.runOnIdle { probabilityGeometry }
        assertNotNull("rotated English probability geometry is mandatory", transformedGeometry)
        val transformedSamples = auditProbabilityPaint(transformedRoot, transformedBitmap,
            transformedGeometry!!, "English rotation=17 scale=(.85,1.05)")
        assertPaintedTouches(transformedRoot, transformedSamples, commits, "transformed English")
        saveSpacingImage("grid-english-rotated-scaled", transformedBitmap)
        transformedBitmap.recycle()

        // Exercise the supported zero-gap counterexample with real parsed configuration, not
        // injected probabilities. Only this namespace's files/prefs are created or deleted.
        val namespace = "gaussian-cap-audit-${System.nanoTime()}"
        val directory = File(app.cacheDir, namespace)
        val config = File(directory, "rime/xime.custom.yaml")
        check(config.parentFile!!.mkdirs())
        val preferenceFiles = mutableSetOf<String>()
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, flags: Int) =
                app.getSharedPreferences("$namespace-$name".also { preferenceFiles += it }, flags)
        }
        val shapeBeforeAudit = KeyShapes.current
        try {
            for (corner in listOf(8, 0)) {
                config.writeText("""
                    keyboard:
                      key:
                        corner_radius: $corner
                        spacing_x: 0
                        spacing_y: 0
                      shadow:
                        enabled: false
                """.trimIndent())
                rule.runOnIdle {
                    KeyShapes.current = KeyShape.ROUNDED
                    KeysConfigHelper.loadConfig(isolated)
                    KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
                    probabilityGeometry = null
                    neighborEncoding = null
                    // Also exercise native correction's geometry publication under the transform.
                    mode.value = if (corner == 0) modes[1] else modes.first()
                    auditEpoch.intValue++
                }
                rule.waitForIdle()
                val declared = KeysConfigHelper.getKeyboardKeyConfig()
                assertEquals("actual parsed zero horizontal gap", 0f, declared.spacingFor("qwerty").first!!, 0f)
                assertEquals("actual parsed zero vertical gap", 0f, declared.spacingFor("qwerty").second!!, 0f)
                assertEquals("actual parsed corner radius", corner, declared.cornerRadius)
                val stressRoot = rule.onNodeWithTag("actual-grid", true)
                val stressBitmap = stressRoot.captureToImage().asAndroidBitmap()
                try {
                    val stressGeometry = rule.runOnIdle { probabilityGeometry }
                    assertNotNull("zero-gap radius=$corner Gaussian geometry is mandatory", stressGeometry)
                    if (corner == 0) assertEquals("rotated Chinese native neighbours share the Gaussian geometry",
                        encodeLetterNeighbors(stressGeometry!!.cells), rule.runOnIdle { neighborEncoding })
                    saveSpacingImage("grid-gaussian-zero-gap-radius-$corner", stressBitmap)
                    val samples = auditProbabilityPaint(stressRoot, stressBitmap, stressGeometry!!,
                        "zero gap radius=$corner rotation=17 scale=(.85,1.05)")
                    assertPaintedTouches(stressRoot, samples, commits, "zero gap radius=$corner")
                } finally { stressBitmap.recycle() }
            }
        } finally {
            rule.runOnIdle {
                KeysConfigHelper.loadConfig(app)
                KeyShapes.current = shapeBeforeAudit
                KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
            }
            preferenceFiles.forEach { app.deleteSharedPreferences(it) }
            check(directory.deleteRecursively())
        }
        File(app.getExternalFilesDir(null), "spacing-measurements.txt").writeText(report.toString())
    }

    /**
     * Independent raster oracle: ownership is the measured letter cell, visibility is any
     * nonblack screenshot pixel. Never consult GaussianKeyBoundary or discard a pixel because
     * its probability is inconvenient. A neighbour's leaking shadow is a failure too, not a
     * reason to colour-filter the sample. ORIGINAL material and the black fixture make this
     * possible: these cells contain letters only, with each cap confined to its own cell.
     */
    private fun auditProbabilityPaint(
        root: SemanticsNodeInteraction,
        bitmap: Bitmap,
        snapshot: ProbabilityGeometrySnapshot,
        note: String,
    ): Map<Char, Offset> {
        assertEquals("$note complete actual Gaussian geometry", ('a'..'z').toSet(), snapshot.cells.keys)
        val rootOrigin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val basis = rule.runOnIdle {
            assertTrue("$note frame attached", snapshot.coordinates.isAttached)
            listOf(snapshot.coordinates.localToRoot(Offset.Zero),
                snapshot.coordinates.localToRoot(Offset(1f, 0f)),
                snapshot.coordinates.localToRoot(Offset(0f, 1f)))
        }
        val origin = basis[0]
        val axisX = basis[1] - origin
        val axisY = basis[2] - origin
        val determinant = axisX.x * axisY.y - axisX.y * axisY.x
        assertTrue("$note invertible frame transform", determinant.isFinite() && kotlin.math.abs(determinant) > .001f)
        fun toBitmap(x: Float, y: Float) = origin + axisX * x + axisY * y - rootOrigin
        fun toLocal(pixel: Offset): Offset {
            val delta = pixel + rootOrigin - origin
            return Offset((delta.x * axisY.y - delta.y * axisY.x) / determinant,
                (axisX.x * delta.y - axisX.y * delta.x) / determinant)
        }
        val firstPaint = linkedMapOf<Char, Offset>()
        var checkedPixels = 0
        for ((letter, cell) in snapshot.cells) {
            val corners = listOf(toBitmap(cell.x, cell.y), toBitmap(cell.x + cell.width, cell.y),
                toBitmap(cell.x, cell.y + cell.height), toBitmap(cell.x + cell.width, cell.y + cell.height))
            val left = floor(corners.minOf { it.x }).toInt().coerceAtLeast(0)
            val right = kotlin.math.ceil(corners.maxOf { it.x }).toInt().coerceAtMost(bitmap.width - 1)
            val top = floor(corners.minOf { it.y }).toInt().coerceAtLeast(0)
            val bottom = kotlin.math.ceil(corners.maxOf { it.y }).toInt().coerceAtMost(bitmap.height - 1)
            var paintedSamples = 0
            // Fixed root-pixel lattice plus dense measured-cell edge/corner strips. Sampling
            // decisions depend ONLY on geometry, never on density or the production clip.
            for (y in top..bottom) for (x in left..right) {
                val pixel = Offset(x + .5f, y + .5f)
                val local = toLocal(pixel)
                val dx = local.x - cell.x
                val dy = local.y - cell.y
                if (dx < 0f || dx >= cell.width || dy < 0f || dy >= cell.height) continue
                val edge = dx < 2f || dy < 2f || cell.width - dx < 2f || cell.height - dy < 2f
                if (!edge && (x % 2 != 0 || y % 2 != 0)) continue
                if ((bitmap.getPixel(x, y) and 0x00ffffff) == 0) continue
                val picked = SpatialCosts.pick(local.x, local.y, snapshot.cells)
                assertEquals("$note painted $letter pixel=($x,$y) local=$local (including shadows)", letter, picked)
                paintedSamples++
                checkedPixels++
                // Top-to-bottom, left-to-right first visible pixel, not a probability search.
                if (letter !in firstPaint) firstPaint[letter] = pixel
            }
            assertTrue("$note $letter must retain visible paint, not clip everything away", paintedSamples > 0)
        }
        android.util.Log.i("GaussianCapAudit", "$note letters=${snapshot.cells.size} checkedPaintedPixels=$checkedPixels")
        return firstPaint
    }

    private fun assertPaintedTouches(
        root: SemanticsNodeInteraction,
        samples: Map<Char, Offset>,
        commits: MutableList<String>,
        note: String,
    ) {
        assertTrue("$note real touch samples", samples.isNotEmpty())
        for ((letter, pixel) in samples) {
            rule.runOnIdle { commits.clear() }
            root.performTouchInput { click(pixel) }
            rule.runOnIdle {
                assertEquals("$note actual onKeyPress at first painted edge pixel for $letter",
                    listOf(letter.toString()), commits.toList())
            }
        }
    }

    @Test fun handwritingAndEditingAuxiliaryKeysUseTheSameBodyScale() {
        val mode = mutableStateOf("edit")
        val factor = mutableFloatStateOf(1f)
        val usedMetrics = mutableStateOf<KeyVisualMetrics?>(null)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f),
                LocalKeyboardMetricsProbe provides { usedMetrics.value = it }) {
                MaterialTheme {
                    val body = Modifier.size((420 * factor.floatValue).dp, (280 * factor.floatValue).dp)
                        .background(Color.Black).testTag("aux-spacing-root")
                    if (mode.value == "edit") EditKeyboardLayout({}, {}, Color.Black, Color.White, Color.Blue,
                        Color.Cyan, modifier = body, shadowEnabled = false)
                    else HandwritingKeyboardLayout(keyBackgroundColor = Color.Cyan,
                        specialKeyBackgroundColor = Color.Cyan, keyTextColor = Color.White,
                        modifier = body, bottomPaddingDp = 0)
                }
            }
        }
        for (panel in listOf("edit", "handwriting")) for (size in listOf(1f, 2f)) {
            rule.runOnIdle { usedMetrics.value = null; mode.value = panel; factor.floatValue = size }
            rule.waitForIdle()
            val root = rule.onNodeWithTag("aux-spacing-root", useUnmergedTree = true)
            val bitmap = root.captureToImage().asAndroidBitmap()
            val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
            val key = if (panel == "edit") rule.onNodeWithContentDescription("删除", useUnmergedTree = true)
                else rule.onNodeWithTag("handwriting-key:delete", useUnmergedTree = true)
            val keyRect = key.fetchSemanticsNode().boundsInRoot.translate(-origin.x, -origin.y)
            // Edit's corner keys are cut out around the centre pad; the left edge is the
            // cutout, not the key spacing. Measure the untouched outer edge instead.
            val insets = if (panel == "edit") paintedEdgeInsets(bitmap, keyRect).let { it.right to it.top }
                else paintedInsets(bitmap, keyRect)
            // 辅助面板仍按自身键格计算，不叠乘父键盘倍率。
            // Edit is a 5-column pad; handwriting uses the default 10-column scope.
            val cellWidth = 420f * size / if (panel == "edit") 5f else 10f
            val cellHeight = if (panel == "edit") 280f * size / 3f else (280f * size - 8f) / 4f
            val spacing = KeysConfigHelper.getKeyboardKeyConfig().spacingFor("qwerty")
            val expectedX: Float
            val expectedY: Float
            if (panel == "edit") {
                val expected = cellMetrics(KeyVisualPolicy.Qwerty, cellWidth, cellHeight)
                // The edit pad intentionally widens its gap to 15% of the short cell edge
                // once the cell is larger than a phone key; use the same documented policy.
                val proportional = EDITOR_GAP_FRACTION / 2f * minOf(cellWidth, cellHeight)
                expectedX = maxOf(expected.insetX!!, proportional)
                expectedY = maxOf(expected.insetY!!, proportional)
            } else {
                // Handwriting uses the automatic growth spacing, including extraRow; read
                // the metrics actually used instead of duplicating that formula.
                val used = usedMetrics.value ?: error("handwriting layout did not report metrics")
                // paintedInsets() measures one side, while resolvedVisualGap() is
                // the gap between two keys (two insets).
                expectedX = resolvedVisualGap(spacing.first, used.insetX!!.roundToInt().toFloat()) / 2f
                expectedY = resolvedVisualGap(spacing.second, used.insetY!!.roundToInt().toFloat()) / 2f + used.extraInsetY
            }
            assertEquals("$panel horizontal inset size=$size", expectedX, insets.first, 1f)
            assertEquals("$panel vertical inset size=$size", expectedY, insets.second, 1f)
        }
    }

    @Test fun smallNestedCardUsesItsOwnBoundsAndNeverMultipliesTheParentScale() {
        var nested = KeyVisualMetrics.Unspecified
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                KeyboardKeySpacingScope(Modifier.size(840.dp, 560.dp)) {
                    KeyboardKeySpacingScope(Modifier.size(320.dp, 240.dp)) { _ ->
                        val metrics = LocalKeyboardKeyVisualMetrics.current
                        SideEffect { nested = metrics }
                    }
                }
            }
        }
        // 320×240、默认 10 列 4 行：格 32×58dp → 缩放取 1.0（不缩小），缝用策略目标值
        rule.runOnIdle {
            assertEquals(1f, nested.scale, 0.001f)
            assertEquals(KeyVisualPolicy.Qwerty.gapX / 2f, nested.insetX!!, 0.01f)
            assertEquals(KeyVisualPolicy.Qwerty.gapY / 2f, nested.insetY!!, 0.01f)
        }
    }

    private fun cellMetrics(policy: KeyVisualPolicy, width: Float, height: Float, allowShrink: Boolean = false) =
        keyVisualMetrics(policy.copy(maxKeyWidth = Float.MAX_VALUE, minGutter = 0f),
            width, height, columns = 1f, rows = 1f, verticalInsetDp = 0f, allowShrink = allowShrink)

    private fun saveSpacingImage(name: String, bitmap: Bitmap) {
        val directory = File(app.getExternalFilesDir(null), "cyime").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun edgePixels(bitmap: Bitmap, rect: Rect): String {
        val x = rect.left.roundToInt().coerceAtLeast(0)
        val y = rect.top.roundToInt().coerceAtLeast(0)
        val cx = rect.center.x.roundToInt().coerceIn(0, bitmap.width - 1)
        val cy = rect.center.y.roundToInt().coerceIn(0, bitmap.height - 1)
        val left = (x until minOf(x + 12, bitmap.width)).joinToString { Integer.toHexString(bitmap.getPixel(it, cy)) }
        val top = (y until minOf(y + 16, bitmap.height)).joinToString { Integer.toHexString(bitmap.getPixel(cx, it)) }
        return "left=[$left] top=[$top]"
    }

    private data class EdgeInsets(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** All four sub-pixel painted insets inside a key cell. White/cyan keys on black gaps. */
    private fun paintedEdgeInsets(bitmap: Bitmap, rect: Rect): EdgeInsets {
        val left = floor(rect.left).toInt().coerceAtLeast(0)
        val top = floor(rect.top).toInt().coerceAtLeast(0)
        val right = rect.right.roundToInt().coerceAtMost(bitmap.width)
        val bottom = rect.bottom.roundToInt().coerceAtMost(bitmap.height)
        val centerX = rect.center.x.roundToInt().coerceIn(left, right - 1)
        val centerY = rect.center.y.roundToInt().coerceIn(top, bottom - 1)
        fun coverage(pixel: Int): Float =
            minOf(android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel)) / 255f
        fun inset(from: Int, to: Int, step: Int, pixel: (Int) -> Int): Float {
            var position = from
            var sum = 0f
            while (position != to && coverage(pixel(position)) < 1f) {
                sum += 1f - coverage(pixel(position))
                position += step
            }
            return sum
        }
        return EdgeInsets(
            left = inset(left, right, 1) { bitmap.getPixel(it, centerY) },
            right = inset(right - 1, left - 1, -1) { bitmap.getPixel(it, centerY) },
            top = inset(top, bottom, 1) { bitmap.getPixel(centerX, it) },
            bottom = inset(bottom - 1, top - 1, -1) { bitmap.getPixel(centerX, it) },
        )
    }

    private fun paintedInsets(bitmap: Bitmap, rect: Rect): Pair<Float, Float> {
        val left = floor(rect.left).toInt().coerceAtLeast(0)
        val top = floor(rect.top).toInt().coerceAtLeast(0)
        val centerX = rect.center.x.roundToInt().coerceIn(0, bitmap.width - 1)
        val centerY = rect.center.y.roundToInt().coerceIn(0, bitmap.height - 1)
        // Fixtures paint cyan over black. G/B therefore measure the key's pixel coverage.
        // Summing uncovered fractions includes antialiased boundary pixels instead of rounding
        // the phone's first fully opaque pixel up and then doubling that rounding error.
        fun coverage(pixel: Int): Float = minOf(android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel)) / 255f
        val firstX = (left until rect.right.roundToInt()).first { coverage(bitmap.getPixel(it, centerY)) == 1f }
        val firstY = (top until rect.bottom.roundToInt()).first { coverage(bitmap.getPixel(centerX, it)) == 1f }
        val horizontal = (left until firstX).sumOf { (1f - coverage(bitmap.getPixel(it, centerY))).toDouble() }.toFloat()
        val vertical = (top until firstY).sumOf { (1f - coverage(bitmap.getPixel(centerX, it))).toDouble() }.toFloat()
        return (horizontal - (rect.left - left)) to (vertical - (rect.top - top))
    }
}
