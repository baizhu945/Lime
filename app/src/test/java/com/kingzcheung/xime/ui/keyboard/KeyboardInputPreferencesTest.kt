package com.kingzcheung.xime.ui.keyboard

import android.content.Context
import android.content.SharedPreferences
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.KeyEffectPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class KeyboardInputPreferencesTest {
    @Test fun `kana hints persist independently of symbol direction and cursor gestures`() {
        val before = KeyboardInputPreferences.read(context)
        assertEquals(false, before.kanaDirectionHints)
        for (enabled in listOf(true, false)) {
            before.copy(kanaDirectionHints = enabled).save(context)
            val after = KeyboardInputPreferences.read(context)
            assertEquals(enabled, after.kanaDirectionHints)
            assertEquals(before, after.copy(kanaDirectionHints = false))
        }
    }

    @Test fun `choosing voice for long-press space works even while space moves the cursor`() {
        val cursorOnSpace = KeyboardInputPreferences(cursorGesture = CursorGestureMode.SPACE)
        assertEquals(SpaceHoldAction.CURSOR, cursorOnSpace.effectiveSpaceHold)
        val voice = cursorOnSpace.withSpaceHold(SpaceHoldAction.VOICE_TOGGLE)
        assertEquals(SpaceHoldAction.VOICE_TOGGLE, voice.effectiveSpaceHold)
        assertEquals(CursorGestureMode.KEYBOARD, voice.cursorGesture)
        // 英文滑行锁着键盘滑动：移动光标关掉，不去占用滑行。
        val glide = cursorOnSpace.copy(glideTyping = true).withSpaceHold(SpaceHoldAction.REPEAT)
        assertEquals(SpaceHoldAction.REPEAT, glide.effectiveSpaceHold)
        assertEquals(CursorGestureMode.NONE, glide.cursorGesture)
        assertEquals(true, glide.glideTyping)
        // 没占着空格时只换空格功能，移动光标方式原样不动。
        for (mode in listOf(CursorGestureMode.KEYBOARD, CursorGestureMode.NONE)) {
            val other = KeyboardInputPreferences(spaceHold = SpaceHoldAction.REPEAT, cursorGesture = mode)
                .withSpaceHold(SpaceHoldAction.VOICE_TOGGLE)
            assertEquals(mode, other.cursorGesture)
            assertEquals(SpaceHoldAction.VOICE_TOGGLE, other.effectiveSpaceHold)
        }
    }

    @Test fun `uppercase key labels default off and persist without touching other settings`() {
        val before = KeyboardInputPreferences.read(context)
        assertEquals(false, before.uppercaseKeyLabels)
        assertTrue(KeyboardInputPreferences.UPPERCASE_KEY_LABELS_KEY in KeyboardInputPreferences.watchedKeys)
        for (enabled in listOf(true, false)) {
            before.copy(uppercaseKeyLabels = enabled).save(context)
            val after = KeyboardInputPreferences.read(context)
            assertEquals(enabled, after.uppercaseKeyLabels)
            assertEquals(before, after.copy(uppercaseKeyLabels = false))
        }
    }

    @Test fun `chinese uppercase key labels default off and persist without touching other settings`() {
        val before = KeyboardInputPreferences.read(context)
        assertEquals(false, before.chineseUppercaseKeyLabels)
        assertTrue(KeyboardInputPreferences.CHINESE_UPPERCASE_KEY_LABELS_KEY in KeyboardInputPreferences.watchedKeys)
        for (enabled in listOf(true, false)) {
            before.copy(chineseUppercaseKeyLabels = enabled).save(context)
            val after = KeyboardInputPreferences.read(context)
            assertEquals(enabled, after.chineseUppercaseKeyLabels)
            assertEquals(before, after.copy(chineseUppercaseKeyLabels = false))
        }
    }

    @Test fun `symbol direction defaults to pulling into key and persists either habit`() {
        assertEquals(false, KeyboardInputPreferences.read(context).reverseSymbolSwipe)
        for (reversed in listOf(true, false)) {
            val before = KeyboardInputPreferences.read(context)
            before.copy(reverseSymbolSwipe = reversed).save(context)
            val after = KeyboardInputPreferences.read(context)
            assertEquals(reversed, after.reverseSymbolSwipe)
            assertEquals(before, after.copy(reverseSymbolSwipe = before.reverseSymbolSwipe))
        }
    }
    @Test fun `fourteen swipe preferences reload without changing shared cursor or symbol settings`() {
        val previous = KeyboardInputPreferences.read(context)
        assertEquals(true, previous.fourteenLetterSwipe)
        assertEquals(24f, previous.fourteenLetterSwipeDp, 0f)
        previous.copy(fourteenLetterSwipe = false, fourteenLetterSwipeDp = 51f).save(context)
        val restored = KeyboardInputPreferences.read(context)
        assertEquals(false, restored.fourteenLetterSwipe)
        assertEquals(52f, restored.fourteenLetterSwipeDp, 0f)
        assertEquals(previous.cursorGesture, restored.cursorGesture)
        assertEquals(previous.symbolInputMode, restored.symbolInputMode)
        assertEquals(previous.neighborCorrection, restored.neighborCorrection)
    }

    @Test fun `glide typing is off by default and the old saved default does not turn it on`() {
        assertEquals(false, KeyboardInputPreferences.read(context).glideTyping)
        // 旧版 save() 会把旧默认 true 连同其他设置一起写进 glide_typing：不能因此打开。
        values["glide_typing"] = true
        assertEquals(false, KeyboardInputPreferences.read(context).glideTyping)
        // 用户在新版自己打开后保持打开。
        KeyboardInputPreferences.read(context).copy(glideTyping = true).save(context)
        assertEquals(true, KeyboardInputPreferences.read(context).glideTyping)
        assertEquals(listOf("glide_typing_v2", "glide_typing"), com.kingzcheung.xime.keyboard.ToolbarButton.GLIDE.gestureKeys())
    }

    @Test fun `chinese glide has its own switch and an english glide user does not get it`() {
        // 用户 10-31：老版本开着英文滑行，升级后中文 26 键不能跟着滑行。
        values["glide_typing_v2"] = true
        values["glide_typing"] = true
        val upgraded = KeyboardInputPreferences.read(context)
        assertEquals(true, upgraded.glideTyping)
        assertEquals(false, upgraded.chineseGlideTyping)
        upgraded.save(context)
        assertEquals(false, KeyboardInputPreferences.read(context).chineseGlideTyping)
        // 自己打开中文滑行后保持打开，英文滑行原样不动。
        KeyboardInputPreferences.read(context).copy(chineseGlideTyping = true).save(context)
        val chinese = KeyboardInputPreferences.read(context)
        assertEquals(true, chinese.chineseGlideTyping)
        assertEquals(true, chinese.glideTyping)
        // 只开中文滑行时同样锁住键盘滑动移光标。
        val onlyChinese = KeyboardInputPreferences(chineseGlideTyping = true)
        assertEquals("已被「中文滑行输入」锁定", InputGestureLocks.lockFor(onlyChinese,
            InputGestureLock.Option.CURSOR_GESTURE, CursorGestureMode.KEYBOARD)?.message)
        // 手势预设不管中文滑行：开着它，「滑动」预设照样认得出。
        assertEquals(InputGesturePreset.SWIPE, InputGesturePreset.of(InputGesturePreset.SWIPE.applyTo(onlyChinese)))
    }

    @Test fun `comma and period keys are both on by default and the old saved period default does not turn it off`() {
        val fresh = KeyboardInputPreferences.read(context)
        assertEquals(true, fresh.qwertyCommaKey)
        assertEquals(true, fresh.qwertyPeriodKey)
        // 旧版 save() 会把旧默认 false 连同其他设置一起写进 qwerty_period_key：不能因此关掉。
        values["qwerty_period_key"] = false
        assertEquals(true, KeyboardInputPreferences.read(context).qwertyPeriodKey)
        // 用户在新版自己关掉后保持关。
        KeyboardInputPreferences.read(context).copy(qwertyPeriodKey = false).save(context)
        assertEquals(false, KeyboardInputPreferences.read(context).qwertyPeriodKey)
    }

    @Test fun `time and date candidates are off by default and stay on once turned on`() {
        // 更新不改原有习惯（用户 10-31）：新功能默认关，在 语言与方案 › 中文输出 打开。
        assertEquals(false, KeyboardInputPreferences().datetimeCandidates)
        assertEquals(false, KeyboardInputPreferences.read(context).datetimeCandidates)
        KeyboardInputPreferences.read(context).copy(datetimeCandidates = false).save(context)
        assertEquals(false, values[KeyboardInputPreferences.DATETIME_CANDIDATES_KEY])
        assertEquals(false, KeyboardInputPreferences.read(context).datetimeCandidates)
        KeyboardInputPreferences.read(context).copy(datetimeCandidates = true).save(context)
        assertEquals(true, KeyboardInputPreferences.read(context).datetimeCandidates)
    }

    @Test fun `symbol name candidates are off by default and persist`() {
        // 别人请求的功能默认关，不改原有习惯；在 语言与方案 › 中文输出 打开。
        assertEquals(false, KeyboardInputPreferences().symbolNames)
        assertEquals(false, KeyboardInputPreferences.read(context).symbolNames)
        KeyboardInputPreferences.read(context).copy(symbolNames = true).save(context)
        assertEquals(true, values[KeyboardInputPreferences.SYMBOL_NAMES_KEY])
        assertEquals(true, KeyboardInputPreferences.read(context).symbolNames)
        KeyboardInputPreferences.read(context).copy(symbolNames = false).save(context)
        assertEquals(false, KeyboardInputPreferences.read(context).symbolNames)
    }

    @Test fun `bottom lift cursor is off by default and persists without touching the cursor gesture choice`() {
        val before = KeyboardInputPreferences.read(context)
        assertEquals(false, before.bottomLiftCursor)
        for (enabled in listOf(true, false)) {
            before.copy(bottomLiftCursor = enabled).save(context)
            val after = KeyboardInputPreferences.read(context)
            assertEquals(enabled, after.bottomLiftCursor)
            assertEquals(before, after.copy(bottomLiftCursor = false))
        }
        assertTrue(KeyboardInputPreferences.BOTTOM_LIFT_CURSOR_KEY in KeyboardInputPreferences.watchedKeys)
    }

    @Test fun `every saved setting refreshes the keyboard already on screen`() {
        // 设置页关掉滑行输入或九键拂动后，正在显示的键盘必须立即改用新值，不能等输入法重启。
        KeyboardInputPreferences().copy(glideTyping = false, t9FlickLetters = true).save(context)
        val unwatched = values.keys - KeyboardInputPreferences.watchedKeys
        assertTrue("save() 写入但键盘不监听：$unwatched", unwatched.isEmpty())
        assertTrue("glide_typing" in values && "t9_flick_letters" in values)
    }

    private val context = mock<Context>()
    private val prefs = mock<SharedPreferences>()
    private val editor = mock<SharedPreferences.Editor>()
    private val values = mutableMapOf<String, Any>()

    @Before
    fun setUp() {
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.edit()).thenReturn(editor)
        whenever(prefs.contains(any())).thenAnswer { values.containsKey(it.getArgument<String>(0)) }
        whenever(prefs.getInt(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] as? Int ?: it.getArgument<Int>(1)
        }
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer {
            values[it.getArgument<String>(0)] as? String ?: it.getArgument<String?>(1)
        }
        whenever(prefs.getBoolean(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] as? Boolean ?: it.getArgument<Boolean>(1)
        }
        whenever(prefs.getFloat(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] as? Float ?: it.getArgument<Float>(1)
        }
        whenever(editor.putString(any(), anyOrNull())).thenAnswer {
            val value = it.getArgument<String?>(1)
            if (value != null) values[it.getArgument(0)] = value
            editor
        }
        whenever(editor.putBoolean(any(), any())).thenAnswer {
            values[it.getArgument(0)] = it.getArgument<Boolean>(1)
            editor
        }
        whenever(editor.putFloat(any(), any())).thenAnswer {
            values[it.getArgument(0)] = it.getArgument<Float>(1)
            editor
        }
    }

    @Test
    fun `existing installs default to cursor hold and usable sensitivity`() {
        val settings = KeyboardInputPreferences.read(context)
        assertEquals(SpaceHoldAction.CURSOR, settings.spaceHold)
        assertEquals(0.2f, settings.cursorHoldSeconds, 0f)
        assertEquals(200L, settings.spaceHoldDelayMs)
        assertEquals(10f, settings.cursorStepDp, 0f)
        assertEquals(1.15f, settings.keyTextScale, 0f)
        assertEquals(0.5f, settings.handwritingPauseSeconds, 0f)
        assertNull(settings.symbols())
    }

    @Test fun `visual effects are opt in and explicit choices are respected`() {
        assertEquals(false, KeyboardInputPreferences.read(context).keyGlowEnabled)
        assertEquals(false, KeyboardInputPreferences.read(context).showPressBubble)
        values["key_glow_enabled"] = true
        values[SettingsPreferences.KEY_SHOW_PRESS_BUBBLE] = true
        assertEquals(true, KeyboardInputPreferences.read(context).keyGlowEnabled)
        assertEquals(true, KeyboardInputPreferences.read(context).showPressBubble)
    }

    @Test fun `frosted glass is opt in and survives unrelated input preference saves`() {
        assertEquals(FrostedGlassConfig(), KeyboardInputPreferences.read(context).frostedGlass)
        val effect = FrostedGlassConfig(true, 18f, 0.7f, 0.3f)
        FrostedGlassPreferences.save(context, effect)
        // 保存的是用户值；键盘实际使用的值只会为可读性加深，不会更透。
        fun assertKept() {
            assertEquals(effect, FrostedGlassPreferences.read(context))
            val shown = KeyboardInputPreferences.read(context).frostedGlass
            assertEquals(effect.blurRadiusDp, shown.blurRadiusDp, 0f)
            assertTrue(shown.enabled && shown.backgroundOpacity >= effect.backgroundOpacity &&
                shown.keyOpacity >= effect.keyOpacity)
        }
        assertKept()
        KeyboardInputPreferences(spaceHold = SpaceHoldAction.REPEAT).save(context)
        assertKept()
        assertEquals(false, KeyboardInputPreferences.read(context).keyGlowEnabled)
    }

    @Test fun `rendered appearance chooses its own glass controls despite saved display mode`() {
        values["dark_mode"] = 0
        val dark = FrostedGlassConfig(true, 15f, 0.77f, 0.21f)
        val light = FrostedGlassConfig(true, 11f, 0.24f, 0.84f)
        FrostedGlassPreferences.save(context, dark, true)
        FrostedGlassPreferences.save(context, light, false)
        val swappedDark = dark.copy(backgroundOpacity = light.keyOpacity, keyOpacity = light.backgroundOpacity)
        val theme = SettingsPreferences.getKeyboardTheme(context)
        val shownDark = swappedDark.legibleForTheme(theme, true)
        val shownLight = light.legibleForTheme(theme, false)
        // The two-dimensional legibility search changes rendering only, not the paired preferences.
        assertEquals(0.506f, shownLight.backgroundOpacity, 0.00001f)
        assertEquals(0.848f, shownLight.keyOpacity, 0.00001f)
        for ((stored, shown) in listOf(swappedDark to shownDark, light to shownLight)) {
            assertEquals(stored.enabled, shown.enabled)
            assertEquals(stored.blurRadiusDp, shown.blurRadiusDp, 0f)
            assertTrue(shown.backgroundOpacity >= stored.backgroundOpacity)
            assertTrue(shown.keyOpacity >= stored.keyOpacity)
        }
        fun assertStored() {
            assertEquals(swappedDark, FrostedGlassPreferences.read(context, true))
            assertEquals(light, FrostedGlassPreferences.read(context, false))
        }
        assertStored()
        assertEquals(shownDark, KeyboardInputPreferences.read(context, true).frostedGlass)
        assertEquals(shownLight, KeyboardInputPreferences.read(context, false).frostedGlass)
        assertEquals(shownLight, KeyboardInputPreferences.read(context).frostedGlass)
        values["dark_mode"] = 1
        assertEquals(shownLight, KeyboardInputPreferences.read(context, false).frostedGlass)
        assertEquals(shownDark, KeyboardInputPreferences.read(context, true).frostedGlass)
        assertEquals(shownDark, KeyboardInputPreferences.read(context).frostedGlass)
        assertStored()

        // Editing from the dark appearance swaps the pair in the other direction,
        // but must retain the light blur and the shared enabled flag.
        FrostedGlassPreferences.save(context, dark, true)
        val swappedLight = light.copy(backgroundOpacity = dark.keyOpacity, keyOpacity = dark.backgroundOpacity)
        assertEquals(dark, FrostedGlassPreferences.read(context, true))
        assertEquals(swappedLight, FrostedGlassPreferences.read(context, false))
        assertEquals(dark.legibleForTheme(theme, true), KeyboardInputPreferences.read(context, true).frostedGlass)
        assertEquals(swappedLight.legibleForTheme(theme, false), KeyboardInputPreferences.read(context, false).frostedGlass)
        assertEquals(dark, FrostedGlassPreferences.read(context, true))
        assertEquals(swappedLight, FrostedGlassPreferences.read(context, false))
    }

    @Test
    fun `saved input preferences can be read without restarting the app`() {
        val changed = KeyboardInputPreferences(spaceHold = SpaceHoldAction.REPEAT, cursorStepDp = 6f, keyTextScale = 1.4f, fixedSymbols = "…\n→")
        changed.save(context)
        assertEquals(changed, KeyboardInputPreferences.read(context))
        changed.copy(spaceHold = SpaceHoldAction.CURSOR, cursorStepDp = 24f).save(context)
        assertEquals(SpaceHoldAction.CURSOR, KeyboardInputPreferences.read(context).spaceHold)
        assertEquals(24f, KeyboardInputPreferences.read(context).cursorStepDp, 0f)
    }

    @Test fun `cursor hold delay persists tenths normalizes invalid values and leaves other actions unchanged`() {
        for (tenth in 1..10) {
            val seconds = tenth / 10f
            KeyboardInputPreferences(cursorHoldSeconds = seconds).save(context)
            val restored = KeyboardInputPreferences.read(context)
            assertEquals(seconds, restored.cursorHoldSeconds, 0f)
            assertEquals(tenth * 100L, restored.spaceHoldDelayMs)
            for (action in listOf(SpaceHoldAction.REPEAT, SpaceHoldAction.VOICE_TOGGLE)) {
                assertEquals("every space hold action uses the space hold delay", tenth * 100L,
                    restored.copy(spaceHold = action, cursorGesture = CursorGestureMode.NONE).spaceHoldDelayMs)
                assertEquals("Space cursor override uses its configured delay", tenth * 100L,
                    restored.copy(spaceHold = action, cursorGesture = CursorGestureMode.SPACE).spaceHoldDelayMs)
            }
        }
        val invalid = listOf(
            Float.NaN to 0.2f,
            Float.POSITIVE_INFINITY to 0.2f,
            Float.NEGATIVE_INFINITY to 0.2f,
            -1f to 0.1f,
            9f to 1f,
            0.26f to 0.3f,
        )
        for ((stored, expected) in invalid) {
            values["cursor_hold_seconds"] = stored
            assertEquals("Read normalizes $stored", expected, KeyboardInputPreferences.read(context).cursorHoldSeconds, 0f)
            KeyboardInputPreferences(cursorHoldSeconds = stored).save(context)
            assertEquals("Save normalizes $stored", expected, values["cursor_hold_seconds"] as Float, 0f)
            assertEquals(expected, KeyboardInputPreferences.read(context).cursorHoldSeconds, 0f)
        }
    }

    @Test
    fun `legacy voice hold migrates to cursor`() {
        values["space_hold_action"] = "VOICE"
        assertEquals(SpaceHoldAction.CURSOR, KeyboardInputPreferences.read(context).spaceHold)
    }

    @Test fun `symbol input keeps swipe default and persists an explicit hold choice`() {
        assertEquals(SymbolInputMode.SWIPE_UP, KeyboardInputPreferences.read(context).symbolInputMode)
        val hold = KeyboardInputPreferences(symbolInputMode = SymbolInputMode.LONG_PRESS,
            spaceHold = SpaceHoldAction.VOICE_TOGGLE, cursorGesture = CursorGestureMode.KEYBOARD)
        hold.save(context)
        assertEquals(hold, KeyboardInputPreferences.read(context))
        hold.copy(symbolInputMode = SymbolInputMode.SWIPE_UP).save(context)
        assertEquals(SymbolInputMode.SWIPE_UP, KeyboardInputPreferences.read(context).symbolInputMode)
        values["symbol_input_mode"] = "unknown-future-mode"
        assertEquals(SymbolInputMode.SWIPE_UP, KeyboardInputPreferences.read(context).symbolInputMode)
        assertEquals(SpaceHoldAction.VOICE_TOGGLE, KeyboardInputPreferences.read(context).effectiveSpaceHold)
    }

    @Test fun `explicit new voice toggle survives save while cursor override preserves it`() {
        val voice = KeyboardInputPreferences(spaceHold = SpaceHoldAction.VOICE_TOGGLE, cursorGesture = CursorGestureMode.KEYBOARD)
        voice.save(context)
        assertEquals(SpaceHoldAction.VOICE_TOGGLE, KeyboardInputPreferences.read(context).effectiveSpaceHold)
        voice.copy(cursorGesture = CursorGestureMode.SPACE).save(context)
        assertEquals(SpaceHoldAction.CURSOR, KeyboardInputPreferences.read(context).effectiveSpaceHold)
        assertEquals(SpaceHoldAction.VOICE_TOGGLE, KeyboardInputPreferences.read(context).spaceHold)
    }

    @Test
    fun `unknown modes and invalid numeric settings recover to usable defaults`() {
        values["space_hold_action"] = "REMOVED_MODE"
        values["cursor_step_dp"] = Float.NaN
        values["key_text_scale"] = Float.POSITIVE_INFINITY
        val settings = KeyboardInputPreferences.read(context)
        assertEquals(SpaceHoldAction.CURSOR, settings.spaceHold)
        assertEquals(10f, settings.cursorStepDp, 0f)
        assertEquals(1.15f, settings.keyTextScale, 0f)
    }

    @Test
    fun `fixed symbols preserve phrases and emoji while removing blank duplicate lines`() {
        val settings = KeyboardInputPreferences(fixedSymbols = " … \n\n👨‍👩‍👧‍👦\n自定义短语\n…\n")
        assertEquals(listOf("…", "👨‍👩‍👧‍👦", "自定义短语"), settings.symbols())
        assertNull(settings.copy(fixedSymbols = " \n \n").symbols())
    }
    @Test fun `handwriting pause persists and rejects invalid intervals`() {
        KeyboardInputPreferences(handwritingPauseSeconds = 1.8f).save(context)
        assertEquals(1.8f, KeyboardInputPreferences.read(context).handwritingPauseSeconds, 0f)
        for ((stored, expected) in listOf(Float.NaN to 0.5f, -1f to 0.1f, 9f to 2.5f)) {
            values["handwriting_pause_seconds_v2"] = stored
            assertEquals(expected, KeyboardInputPreferences.read(context).handwritingPauseSeconds, 0f)
        }
    }

    @Test fun `legacy one second default migrates but new explicit one second persists`() {
        values["handwriting_pause_seconds"] = 1f
        assertEquals(0.5f, KeyboardInputPreferences.read(context).handwritingPauseSeconds, 0f)
        KeyboardInputPreferences(handwritingPauseSeconds = 1f).save(context)
        assertEquals(1f, KeyboardInputPreferences.read(context).handwritingPauseSeconds, 0f)
    }
    @Test fun `handwriting delay saves tenths with a half second default`() {
        for (tenth in 1..25) {
            val value = tenth / 10f
            KeyboardInputPreferences(handwritingPauseSeconds = value).save(context)
            assertEquals(value, KeyboardInputPreferences.read(context).handwritingPauseSeconds, 0f)
        }
        KeyboardInputPreferences(handwritingPauseSeconds = 0.56f).save(context)
        assertEquals(0.6f, KeyboardInputPreferences.read(context).handwritingPauseSeconds, 0f)
    }

    @Test fun `split keyboard is opt in and persists independently of input settings`() {
        assertEquals(false, KeyboardInputPreferences.read(context).splitKeyboardEnabled)
        SettingsPreferences.setSplitKeyboardEnabled(context, true)
        assertEquals(true, KeyboardInputPreferences.read(context).splitKeyboardEnabled)
        KeyboardInputPreferences().save(context)
        assertEquals(true, SettingsPreferences.isSplitKeyboardEnabled(context))
        SettingsPreferences.setSplitKeyboardEnabled(context, false)
        assertEquals(false, KeyboardInputPreferences.read(context).splitKeyboardEnabled)
    }

    @Test fun `legacy combined preference preserves both effects until edited independently`() {
        for (old in listOf(false, true)) {
            values.clear()
            values[KeyEffectPreferences.GLOW] = old
            assertEquals(old, KeyboardInputPreferences.read(context).keyAnimationEnabled)
            KeyEffectPreferences.setGlowEnabled(prefs, !old)
            assertEquals(old, KeyboardInputPreferences.read(context).keyAnimationEnabled)
            assertEquals(!old, KeyboardInputPreferences.read(context).keyGlowEnabled)
            KeyEffectPreferences.setGlowEnabled(prefs, old)
            assertEquals(old, KeyboardInputPreferences.read(context).keyAnimationEnabled)
        }
    }

    @Test fun `all four combinations persist without one switch changing the other`() {
        for (glow in listOf(false, true)) for (animation in listOf(false, true)) {
            values.clear()
            KeyEffectPreferences.setAnimationEnabled(prefs, animation)
            KeyEffectPreferences.setGlowEnabled(prefs, glow)
            val effects = KeyboardInputPreferences.read(context)
            assertEquals(glow, effects.keyGlowEnabled)
            assertEquals(animation, effects.keyAnimationEnabled)
            KeyboardInputPreferences().save(context)
            assertEquals(glow, KeyboardInputPreferences.read(context).keyGlowEnabled)
            assertEquals(animation, KeyboardInputPreferences.read(context).keyAnimationEnabled)
            KeyEffectPreferences.setAnimationEnabled(prefs, !animation)
            assertEquals(glow, KeyboardInputPreferences.read(context).keyGlowEnabled)
        }
        values.clear()
        KeyEffectPreferences.setGlowEnabled(prefs, true)
        assertEquals(false, KeyboardInputPreferences.read(context).keyAnimationEnabled)
    }

    @Test fun `language key and space have separate hold delays with their own defaults`() {
        val defaults = KeyboardInputPreferences()
        assertEquals(0.15f, defaults.languageMenuHoldSeconds, 0f)
        assertEquals(200L, defaults.spaceHoldDelayMs)
        KeyboardInputPreferences(languageMenuHoldSeconds = 0.35f, cursorHoldSeconds = 0.6f).save(context)
        val restored = KeyboardInputPreferences.read(context)
        assertEquals(0.35f, restored.languageMenuHoldSeconds, 0f)
        assertEquals(600L, restored.spaceHoldDelayMs)
    }

    @Test fun `stored old language hold default migrates to new default but custom values stay`() {
        values["language_key_hold_seconds"] = 0.1f
        assertEquals(0.15f, KeyboardInputPreferences.read(context).languageMenuHoldSeconds, 0f)
        values["language_key_hold_seconds"] = 0.35f
        assertEquals(0.35f, KeyboardInputPreferences.read(context).languageMenuHoldSeconds, 0f)
        KeyboardInputPreferences(languageMenuHoldSeconds = 0.1f).save(context)
        assertEquals(0.1f, KeyboardInputPreferences.read(context).languageMenuHoldSeconds, 0f)
    }
}
