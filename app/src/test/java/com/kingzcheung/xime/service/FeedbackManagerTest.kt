package com.kingzcheung.xime.service

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import org.junit.Test
import org.mockito.kotlin.*

class FeedbackManagerTest {
    private fun manager(mode: HapticMode): FeedbackManager = FeedbackManager(mock<Context>()).also {
        FeedbackManager::class.java.getDeclaredField("hapticMode").apply { isAccessible = true }.set(it, mode)
    }

    @Test fun `cursor steps use text movement feedback and honor disabled mode`() {
        val view = mock<View>()
        manager(HapticMode.Enabled).hapticFeedback(view, type = KeyFeedbackType.CURSOR_STEP)
        verify(view).performHapticFeedback(eq(HapticFeedbackConstants.TEXT_HANDLE_MOVE), any())
        clearInvocations(view)
        manager(HapticMode.Disabled).hapticFeedback(view, type = KeyFeedbackType.CURSOR_STEP)
        verifyNoInteractions(view)
    }

    @Test fun `caret step vibration follows its own switch`() {
        val view = mock<View>()
        val m = manager(HapticMode.Enabled)
        m.cursorStepFeedback(view)
        verify(view).performHapticFeedback(eq(HapticFeedbackConstants.TEXT_HANDLE_MOVE), any())
        clearInvocations(view)
        FeedbackManager::class.java.getDeclaredField("cursorStepHaptic").apply { isAccessible = true }.set(m, false)
        m.cursorStepFeedback(view)
        verifyNoInteractions(view)
    }

    @Test fun `pen erasing plays its own sound with a light tick`() {
        org.junit.Assert.assertEquals("handwriting_erase", KeyFeedbackType.HANDWRITING_ERASE.sound)
        val view = mock<View>()
        manager(HapticMode.Enabled).hapticFeedback(view, type = KeyFeedbackType.HANDWRITING_ERASE)
        verify(view).performHapticFeedback(eq(HapticFeedbackConstants.TEXT_HANDLE_MOVE), any())
    }

    @Test fun `typing retains keyboard haptics independently of cursor ticks`() {
        val view = mock<View>()
        manager(HapticMode.Enabled).performKeyPressDownEffect("delete", view)
        verify(view).performHapticFeedback(eq(HapticFeedbackConstants.KEYBOARD_TAP), any())
    }
    @Test fun `physical keyboard typing keys share the soft keyboard sounds`() {
        fun sound(code: Int, repeat: Int = 0, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false) =
            HardwareKeySound.soundFor(code, repeat, ctrl, alt, meta)
        org.junit.Assert.assertEquals("standard", sound(android.view.KeyEvent.KEYCODE_A))
        org.junit.Assert.assertEquals("standard", sound(android.view.KeyEvent.KEYCODE_7))
        org.junit.Assert.assertEquals("standard", sound(android.view.KeyEvent.KEYCODE_SLASH))
        org.junit.Assert.assertEquals("standard", sound(android.view.KeyEvent.KEYCODE_NUMPAD_5))
        org.junit.Assert.assertEquals("space", sound(android.view.KeyEvent.KEYCODE_SPACE))
        org.junit.Assert.assertEquals("enter", sound(android.view.KeyEvent.KEYCODE_NUMPAD_ENTER))
        org.junit.Assert.assertEquals("delete", sound(android.view.KeyEvent.KEYCODE_DEL))
    }

    @Test fun `physical modifiers shortcuts and auto repeat stay silent`() {
        listOf(android.view.KeyEvent.KEYCODE_SHIFT_LEFT, android.view.KeyEvent.KEYCODE_CTRL_RIGHT,
            android.view.KeyEvent.KEYCODE_ALT_LEFT, android.view.KeyEvent.KEYCODE_META_LEFT,
            android.view.KeyEvent.KEYCODE_CAPS_LOCK, android.view.KeyEvent.KEYCODE_BACK,
            android.view.KeyEvent.KEYCODE_VOLUME_UP).forEach {
            org.junit.Assert.assertNull("$it", HardwareKeySound.soundFor(it, 0, false, false, false))
        }
        org.junit.Assert.assertNull(HardwareKeySound.soundFor(android.view.KeyEvent.KEYCODE_C, 0, true, false, false))
        org.junit.Assert.assertNull(HardwareKeySound.soundFor(android.view.KeyEvent.KEYCODE_TAB, 0, false, true, false))
        org.junit.Assert.assertNull(HardwareKeySound.soundFor(android.view.KeyEvent.KEYCODE_DEL, 3, false, false, false))
    }

    @Test fun `physical keyboard defaults to Rain75 while the screen keyboard keeps its sound`() {
        org.junit.Assert.assertEquals("mahjong", FeedbackManager.resolveKeySoundStyle(null, hardware = true))
        org.junit.Assert.assertEquals("default", FeedbackManager.resolveKeySoundStyle(null, hardware = false))
        org.junit.Assert.assertEquals("mahjong", FeedbackManager.resolveKeySoundStyle("mahjong", hardware = false))
    }

    @Test fun `removed crisp style falls back to default`() {
        org.junit.Assert.assertEquals("default", FeedbackManager.resolveKeySoundStyle("crisp", hardware = false))
        org.junit.Assert.assertEquals("mahjong", FeedbackManager.resolveKeySoundStyle("crisp", hardware = true))
    }

    @Test fun `key sounds stay system sounds unless screen-recording capture is switched on`() {
        org.junit.Assert.assertEquals(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, FeedbackManager.soundUsage(false))
        org.junit.Assert.assertEquals(android.media.AudioAttributes.USAGE_GAME, FeedbackManager.soundUsage(true))
    }

    @Test fun `capturable key sounds stay quiet in silent and vibrate mode`() {
        org.junit.Assert.assertTrue(FeedbackManager.mutedByRinger(true, android.media.AudioManager.RINGER_MODE_SILENT))
        org.junit.Assert.assertTrue(FeedbackManager.mutedByRinger(true, android.media.AudioManager.RINGER_MODE_VIBRATE))
        org.junit.Assert.assertFalse(FeedbackManager.mutedByRinger(true, android.media.AudioManager.RINGER_MODE_NORMAL))
        // 默认的系统提示音由系统自己静音，这里不重复拦截。
        org.junit.Assert.assertFalse(FeedbackManager.mutedByRinger(false, android.media.AudioManager.RINGER_MODE_SILENT))
    }

    @Test fun `capturable key sounds follow do not disturb like system sounds`() {
        val all = android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        val priority = android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
        val none = android.app.NotificationManager.INTERRUPTION_FILTER_NONE
        val alarms = android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
        org.junit.Assert.assertFalse(FeedbackManager.mutedByDnd(true, all, systemSoundsAllowed = false))
        org.junit.Assert.assertTrue(FeedbackManager.mutedByDnd(true, priority, systemSoundsAllowed = false))
        org.junit.Assert.assertFalse(FeedbackManager.mutedByDnd(true, priority, systemSoundsAllowed = true))
        org.junit.Assert.assertTrue(FeedbackManager.mutedByDnd(true, none, systemSoundsAllowed = true))
        org.junit.Assert.assertTrue(FeedbackManager.mutedByDnd(true, alarms, systemSoundsAllowed = true))
        // 不可录屏时是系统提示音，勿扰由系统处理。
        org.junit.Assert.assertFalse(FeedbackManager.mutedByDnd(false, none, systemSoundsAllowed = false))
    }

    @Test fun `plain motors get pulses long enough to be felt`() {
        org.junit.Assert.assertEquals(45L, FeedbackManager.plainMotorDurationMs(longPress = false, light = false))
        org.junit.Assert.assertEquals(30L, FeedbackManager.plainMotorDurationMs(longPress = false, light = true))
        org.junit.Assert.assertEquals(70L, FeedbackManager.plainMotorDurationMs(longPress = true, light = false))
    }

    @Test fun `linear motors without click effects get short pulses instead of a buzz`() {
        org.junit.Assert.assertEquals(15L, FeedbackManager.fallbackPulseMs(samsungTuned = false, hasAmplitudeControl = true, longPress = false, light = false))
        org.junit.Assert.assertEquals(10L, FeedbackManager.fallbackPulseMs(samsungTuned = false, hasAmplitudeControl = true, longPress = false, light = true))
        org.junit.Assert.assertEquals(30L, FeedbackManager.fallbackPulseMs(samsungTuned = false, hasAmplitudeControl = true, longPress = true, light = false))
        // 三星（Tab S8 转子马达）和不能调振幅的马达保持原来的长度。
        org.junit.Assert.assertEquals(45L, FeedbackManager.fallbackPulseMs(samsungTuned = true, hasAmplitudeControl = true, longPress = false, light = false))
        org.junit.Assert.assertEquals(45L, FeedbackManager.fallbackPulseMs(samsungTuned = false, hasAmplitudeControl = false, longPress = false, light = false))
    }

    @Test fun `only samsung keeps its own tuned haptics`() {
        org.junit.Assert.assertTrue(FeedbackManager.isSamsungTuned("samsung"))
        org.junit.Assert.assertTrue(FeedbackManager.isSamsungTuned("Samsung"))
        org.junit.Assert.assertFalse(FeedbackManager.isSamsungTuned("Xiaomi"))
        org.junit.Assert.assertFalse(FeedbackManager.isSamsungTuned("Google"))
        org.junit.Assert.assertFalse(FeedbackManager.isSamsungTuned(null))
    }

    @Test fun `other vendors follow the system key haptic unless the user tuned it`() {
        fun prefers(samsung: Boolean = false, custom: Boolean = false, duration: Long = 0L, amplitude: Int = 0,
                    primitives: Boolean = false, systemOn: Boolean = true) =
            FeedbackManager.prefersSystemHaptic(samsung, custom, duration, amplitude, primitives, systemOn)
        // 跟随系统：小米这类机型交给系统按键触感，有没有原语都一样。
        org.junit.Assert.assertTrue(prefers())
        org.junit.Assert.assertTrue(prefers(primitives = true))
        // 三星保持原来的原语 / 45 ms 路径。
        org.junit.Assert.assertFalse(prefers(samsung = true))
        // 「启用」且调了时长或振幅：按用户的设置自己震。
        org.junit.Assert.assertFalse(prefers(custom = true, amplitude = 120))
        org.junit.Assert.assertFalse(prefers(custom = true, duration = 20L))
        // 「启用」未调参数：支持原语时仍用原语，不支持时用系统触感；系统触感关着时自己震。
        org.junit.Assert.assertFalse(prefers(custom = true, primitives = true))
        org.junit.Assert.assertTrue(prefers(custom = true))
        org.junit.Assert.assertFalse(prefers(custom = true, systemOn = false))
    }

    @Test fun `held delete vibrates like a tap with pauses instead of a continuous buzz`() {
        val view = mock<View>()
        val m = manager(HapticMode.Enabled)
        // 单点：两下相隔 150 ms，每下都震。
        m.performKeyPressDownEffect("delete", view, nowUptime = 1_000L)
        m.performKeyPressDownEffect("delete", view, nowUptime = 1_150L)
        verify(view, times(2)).performHapticFeedback(eq(HapticFeedbackConstants.KEYBOARD_TAP), any())
        clearInvocations(view)
        // 按住：每 30 ms 连删一下，振动隔开到至少 100 ms 一下，每下仍是按键的 KEYBOARD_TAP。
        (1..10).forEach { m.performKeyPressDownEffect("delete", view, nowUptime = 1_150L + it * 30L) }
        verify(view, times(2)).performHapticFeedback(eq(HapticFeedbackConstants.KEYBOARD_TAP), any())
        // 其他键不受影响。
        clearInvocations(view)
        m.performKeyPressDownEffect("a", view, nowUptime = 2_000L)
        m.performKeyPressDownEffect("a", view, nowUptime = 2_010L)
        verify(view, times(2)).performHapticFeedback(eq(HapticFeedbackConstants.KEYBOARD_TAP), any())
    }

    @Test fun `delete repeat timing`() {
        org.junit.Assert.assertFalse(FeedbackManager.isDeleteRepeat(500L, 0L))
        org.junit.Assert.assertTrue(FeedbackManager.isDeleteRepeat(530L, 500L))
        org.junit.Assert.assertFalse(FeedbackManager.isDeleteRepeat(600L, 500L))
        org.junit.Assert.assertFalse(FeedbackManager.deleteRepeatHapticDue(560L, 500L))
        org.junit.Assert.assertTrue(FeedbackManager.deleteRepeatHapticDue(600L, 500L))
    }

    @Test fun `only repeated delete sounds cut off the previous one`() {
        org.junit.Assert.assertTrue(FeedbackManager.chokesPrevious("delete"))
        listOf("standard", "space", "enter", "voice_start").forEach {
            org.junit.Assert.assertFalse(it, FeedbackManager.chokesPrevious(it))
        }
    }

    @Test fun `feedback actions retain semantic types across keyboard layouts`() {
        mapOf(
            "a" to KeyFeedbackType.CHARACTER, "あ" to KeyFeedbackType.CHARACTER,
            "2" to KeyFeedbackType.NUMBER, "，" to KeyFeedbackType.SYMBOL,
            "space" to KeyFeedbackType.SPACE, "enter" to KeyFeedbackType.ENTER,
            "clear_all" to KeyFeedbackType.DELETE, "ime_switch" to KeyFeedbackType.MODE_SWITCH,
            // 底排 ?123 单键的“上次页面”也是切换键，声音和震动跟原来两个模式键一致。
            "mode_change_last" to KeyFeedbackType.MODE_SWITCH,
            "japanese_convert" to KeyFeedbackType.MODIFIER, "japanese_left" to KeyFeedbackType.NAVIGATION,
            "select_arrow_right" to KeyFeedbackType.NAVIGATION, "select_paragraph_start" to KeyFeedbackType.NAVIGATION,
            "copy" to KeyFeedbackType.EDITING, "toolbar" to KeyFeedbackType.TOOLBAR,
            "cursor_step" to KeyFeedbackType.CURSOR_STEP,
            "handwriting_erase" to KeyFeedbackType.HANDWRITING_ERASE,
        ).forEach { (action, expected) -> org.junit.Assert.assertEquals(action, expected, KeyFeedbackType.fromKey(action)) }
    }
}
