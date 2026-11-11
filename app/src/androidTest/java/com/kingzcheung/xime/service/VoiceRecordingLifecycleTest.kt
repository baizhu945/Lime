package com.kingzcheung.xime.service

import android.content.Context
import android.media.AudioManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.speech.RecognitionState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 真正启动原市场 Zipformer 和 AudioRecord；模拟器静音，只验证录音生命周期。 */
class VoiceRecordingLifecycleTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val permission = GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    @Test fun stopRestartAndInputChangeReleaseTheRecorder() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val local = SettingsPreferences.isSttUseLocal(context)
        SettingsPreferences.setSttUseLocal(context, true)
        lateinit var editor: EditText
        lateinit var handler: VoiceRecognitionHandler
        val schema = SchemaInfo("voice_test", "voice test", "", "", "", language = InputLanguage.CHINESE)
        var state = InputUIState(inputSessionId = 1, currentSchemaId = schema.schemaId, schemas = listOf(schema))
        var completed = 0
        var initialized = false
        rule.setContent { AndroidView(factory = { EditText(it).also { view -> editor = view } }) }
        try {
            rule.runOnUiThread {
                editor.requestFocus()
                val connection = editor.onCreateInputConnection(EditorInfo())!!
                handler = VoiceRecognitionHandler(context, { state = it }, { state }, { connection },
                    onVoiceComplete = { completed++; state = state.copy(isVoiceMode = false, voiceSticky = false) })
                handler.initialize()
                initialized = true
            }
            repeat(3) { cycle ->
                rule.runOnUiThread {
                    state = state.copy(isVoiceMode = true, voiceSticky = true, voiceRecognitionState = RecognitionState.IDLE)
                    handler.startRecognition()
                }
                rule.waitUntil(20_000) { state.voiceRecognitionState == RecognitionState.LISTENING }
                rule.waitUntil(5000) { audio.activeRecordingConfigurations.isNotEmpty() }
                assertEquals("启动后不能提前结束第 $cycle 轮", cycle, completed)
                rule.runOnUiThread { handler.finishRecognition(); handler.finishRecognition() }
                rule.waitUntil(10_000) { completed == cycle + 1 }
                rule.waitUntil(5000) { audio.activeRecordingConfigurations.isEmpty() }
                assertFalse(state.isVoiceMode)
            }
            rule.runOnUiThread {
                state = state.copy(isVoiceMode = true, voiceSticky = true, voiceRecognitionState = RecognitionState.IDLE)
                handler.startRecognition()
            }
            rule.waitUntil(20_000) { state.voiceRecognitionState == RecognitionState.LISTENING }
            rule.runOnUiThread { handler.abandonSession(); handler.cancelPreStart(); handler.stopRecognition() }
            rule.waitUntil(5000) { audio.activeRecordingConfigurations.isEmpty() }
            rule.runOnUiThread { assertEquals("静音录音不应产生宿主文字", "", editor.text.toString()) }
        } finally {
            if (initialized) rule.runOnUiThread { handler.release() }
            SettingsPreferences.setSttUseLocal(context, local)
        }
    }
}
