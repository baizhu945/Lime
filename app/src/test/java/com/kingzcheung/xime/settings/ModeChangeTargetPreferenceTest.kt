package com.kingzcheung.xime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.ModeChangeTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** ?123 单键「上次用的页面」：默认数字，选了常用符号以后重新读还是常用符号。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ModeChangeTargetPreferenceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = SettingsPreferences.getPrefsPublic(context)

    @After fun clear() {
        prefs.edit().remove("mode_change_target").commit()
    }

    @Test fun defaultsToNumberPage() {
        prefs.edit().remove("mode_change_target").commit()
        assertEquals(ModeChangeTarget.NUMBER, SettingsPreferences.modeChangeTarget(context))
    }

    @Test fun remembersThePageTheUserChose() {
        SettingsPreferences.setModeChangeTarget(context, ModeChangeTarget.COMMON_SYMBOL)
        assertEquals(ModeChangeTarget.COMMON_SYMBOL, SettingsPreferences.modeChangeTarget(context))
        SettingsPreferences.setModeChangeTarget(context, ModeChangeTarget.NUMBER)
        assertEquals(ModeChangeTarget.NUMBER, SettingsPreferences.modeChangeTarget(context))
    }

    @Test fun unknownStoredValueFallsBackToNumber() {
        prefs.edit().putString("mode_change_target", "common_symbols").commit()
        assertEquals(ModeChangeTarget.NUMBER, SettingsPreferences.modeChangeTarget(context))
    }
}
