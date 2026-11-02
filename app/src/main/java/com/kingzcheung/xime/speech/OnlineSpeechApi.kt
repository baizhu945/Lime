package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.plugin.core.lua.ws.localCleartextOnly
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 内置在线语音：OpenAI 兼容的 `/audio/transcriptions` 接口（OpenAI、Groq、硅基流动等都支持）。
 * 地址、密钥、模型和最近一次测试结果单独存一个设置文件：不进备份，密钥只留在本机。
 */
object OnlineSpeechApi {
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "whisper-1"
    private const val PREFS = "online_speech_api"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_STATUS = "status"
    private const val SAMPLE_RATE = 16000

    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).localCleartextOnly().build()
    }

    /** 可空：测试里的假 Context 可能没有这个设置文件，此时按未填写处理。 */
    private fun prefs(context: Context): android.content.SharedPreferences? =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(context: Context): Config = prefs(context).let {
        Config(it?.getString(KEY_BASE_URL, null)?.takeIf { url -> url.isNotBlank() } ?: DEFAULT_BASE_URL,
            it?.getString(KEY_API_KEY, "").orEmpty(),
            it?.getString(KEY_MODEL, null)?.takeIf { model -> model.isNotBlank() } ?: DEFAULT_MODEL)
    }

    /** 改了配置就清掉旧的测试结果，状态不会停在“可用”误导人。 */
    fun save(context: Context, config: Config) {
        prefs(context)?.edit()?.putString(KEY_BASE_URL, config.baseUrl.trim())?.putString(KEY_API_KEY, config.apiKey.trim())
            ?.putString(KEY_MODEL, config.model.trim())?.remove(KEY_STATUS)?.apply()
    }

    fun isConfigured(context: Context): Boolean = config(context).apiKey.isNotBlank()

    /** 填好并且最近一次「测试」通过：只有这时才能选「优先使用在线接口」。 */
    fun isUsable(context: Context): Boolean = isConfigured(context) && prefs(context)?.getString(KEY_STATUS, null).orEmpty().startsWith("可用")

    fun status(context: Context): String = prefs(context)?.getString(KEY_STATUS, null)
        ?: if (isConfigured(context)) "未测试" else "未填写密钥"

    /** 用一秒静音真正调用一次转写接口：地址、密钥、模型都对才算可用。在后台线程调用。 */
    fun test(context: Context): String {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val status = if (!isConfigured(context)) "未填写密钥"
            else transcribe(context, ByteArray(SAMPLE_RATE * 2), InputLanguage.CHINESE)
                .fold({ "可用（$time 测试）" }, { "不可用：${it.message}（$time 测试）" })
        prefs(context)?.edit()?.putString(KEY_STATUS, status)?.apply()
        return status
    }

    /** [pcm] 为 16 kHz 单声道 16 位小端 PCM。在后台线程调用。 */
    fun transcribe(context: Context, pcm: ByteArray, language: InputLanguage): Result<String> = runCatching {
        val config = config(context)
        require(config.apiKey.isNotBlank()) { "未填写密钥" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", config.model)
            .addFormDataPart("response_format", "json")
            .apply { if (language != InputLanguage.UNSPECIFIED) addFormDataPart("language", language.id) }
            .addFormDataPart("file", "speech.wav", wav(pcm).toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder().url(config.baseUrl.trimEnd('/') + "/audio/transcriptions")
            .header("Authorization", "Bearer ${config.apiKey}").post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                when (response.code) {
                    401, 403 -> "密钥无效（HTTP ${response.code}）"
                    404 -> "地址或模型不对（HTTP 404）"
                    else -> "HTTP ${response.code}"
                }
            }
            org.json.JSONObject(text).optString("text").trim()
        }
    }.recoverCatching { error ->
        throw if (error is java.io.IOException) IllegalStateException("连不上服务器", error) else error
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        return ByteArrayOutputStream(44 + pcm.size).apply { write(header.array()); write(pcm) }.toByteArray()
    }
}

/**
 * 录完一段再整段上传；说话中不出预览。上传失败时这段录音改用本地模型识别（[localFallback]），
 * 并用 [onNotice] 在键盘上说明，不会无声无息地结束。
 */
class OnlineApiAsrBackend(
    private val context: Context,
    private val localFallback: () -> AsrBackend? = { null },
    private val onNotice: (String) -> Unit = {},
) : AsrBackend {
    override val name: String = "在线语音"
    override val finishTimeoutMs: Long = 30_000L

    private var onResult: ((String) -> Unit)? = null
    private var onState: ((RecognitionState) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private val audio = ByteArrayOutputStream()
    private var language = InputLanguage.CHINESE

    override fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)?,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        this.onResult = onResult; onState = onStateChange; this.onError = onError
    }

    override fun initialize(): Boolean = OnlineSpeechApi.isConfigured(context)
    override fun start(): Boolean = start(InputLanguage.CHINESE)

    override fun start(language: InputLanguage): Boolean {
        this.language = language
        synchronized(audio) { audio.reset() }
        onState?.invoke(RecognitionState.LISTENING)
        return true
    }

    override fun processAudioChunk(buffer: ByteArray) {
        synchronized(audio) { audio.write(buffer) }
    }

    override fun stop() {
        val pcm = synchronized(audio) { audio.toByteArray().also { audio.reset() } }
        if (pcm.isEmpty()) { onState?.invoke(RecognitionState.IDLE); return }
        onState?.invoke(RecognitionState.PROCESSING)
        OnlineSpeechApi.transcribe(context, pcm, language)
            .onSuccess { onResult?.invoke(it); onState?.invoke(RecognitionState.IDLE) }
            .onFailure { error ->
                if (recognizeLocally(pcm)) onNotice("在线接口不可用（${error.message}），这次已改用本地模型")
                else { onError?.invoke("在线语音：${error.message}；本地模型也没能识别"); onState?.invoke(RecognitionState.ERROR) }
            }
    }

    /** 把这段录音交给本地模型识别；本地也不可用时返回 false。 */
    private fun recognizeLocally(pcm: ByteArray): Boolean {
        val local = runCatching { localFallback() }.getOrNull() ?: return false
        return try {
            var text: String? = null
            local.setCallbacks(onResult = { text = (text.orEmpty() + it) }, onPartialResult = null,
                onStateChange = {}, onError = {})
            if (!local.initialize(language) || !local.start(language)) return false
            pcm.asList().chunked(3200).forEach { local.processAudioChunk(it.toByteArray()) }
            local.stop()
            onResult?.invoke(text.orEmpty()); onState?.invoke(RecognitionState.IDLE)
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { local.release() }
        }
    }

    override fun cancel() {
        synchronized(audio) { audio.reset() }
        onState?.invoke(RecognitionState.IDLE)
    }

    override fun release() = cancel()
    override fun isAvailable(): Boolean = OnlineSpeechApi.isConfigured(context)
}
