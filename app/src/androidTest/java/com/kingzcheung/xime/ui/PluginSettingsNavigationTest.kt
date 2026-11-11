package com.kingzcheung.xime.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.settings.*
import com.kingzcheung.xime.ui.theme.XimeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PluginSettingsNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun pluginManagementHasNoStoreAndImportsFromFile() {
        rule.setContent { XimeTheme { SettingsScreen(initialRoute = "plugins") } }
        rule.onNodeWithText("插件管理").assertIsDisplayed()
        rule.onNodeWithText("安装插件").assertDoesNotExist()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        val picker = instrumentation.addMonitor(filter,
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            rule.onNodeWithContentDescription("从文件安装插件")
                .assertIsDisplayed().assertHasClickAction().performClick()
            rule.waitForIdle()
            assertEquals("file import must launch the system document picker", 1, picker.hits)
        } finally { instrumentation.removeMonitor(picker) }
    }

    @Test fun clipboardSetupIsReachableBeforeSyncIsEnabled() {
        val original = SettingsPreferences.isClipboardSyncEnabled(rule.activity)
        var manages = 0
        try {
            SettingsPreferences.setClipboardSyncEnabled(rule.activity, false)
            rule.setContent { XimeTheme { ClipboardSyncSettingsContent({}, { manages++ }) } }
            rule.onNodeWithTag("windows-scan-connect").assertIsDisplayed().assertIsEnabled()
            rule.onNodeWithText("安装插件").assertDoesNotExist()
            rule.onNodeWithText("备用：手动连接与其他服务").performScrollTo().performClick()
            rule.onNodeWithText("管理其他同步服务").performScrollTo().performClick()
            rule.onNodeWithText("同步服务").assertExists()
            assertEquals(1, manages)
        } finally { SettingsPreferences.setClipboardSyncEnabled(rule.activity, original) }
    }

    @Test fun backupIsLocalAndNeedsNoPlugin() {
        rule.setContent { XimeTheme { BackupSettingsContent(onBack = {}) } }
        rule.onNodeWithText("导出备份").assertIsDisplayed()
        rule.onNodeWithText("从备份恢复").assertIsDisplayed()
        rule.onNodeWithText("安装插件").assertDoesNotExist()
    }
    @Test fun onlineSpeechIsBuiltInApiWithoutPluginStore() {
        rule.setContent { XimeTheme { OnlineSpeechApiSection() } }
        rule.onNodeWithTag("speech-api-key").assertIsDisplayed()
        rule.onNodeWithTag("speech-api-test").assertIsDisplayed()
        rule.onNodeWithTag("speech-api-status").assertIsDisplayed()
        rule.onNodeWithText("安装插件").assertDoesNotExist()
    }

}
