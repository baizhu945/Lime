package com.kingzcheung.xime.ui.caption

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kingzcheung.xime.model.BuiltinModelCatalog
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.model.ModelStorage
import com.kingzcheung.xime.service.CaptionInferenceClient
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.SpeechModelCatalog
import com.kingzcheung.xime.speech.caption.CaptionRecorder
import com.kingzcheung.xime.speech.caption.CaptionSpectrum
import com.kingzcheung.xime.speech.caption.KnownVoices
import com.kingzcheung.xime.speech.caption.CaptionTranscript
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/** One speaker row on the caption page; ids come from the :asr labeler, names from the user. */
class SpeakerUi(val id: Int, val ordinal: Int) {
    var name by mutableStateOf(when (id) {
        CaptionController.REPLY_SPEAKER -> "我（写）"
        CaptionController.SELF_SPEAKER -> "我（拿手机的人）"
        CaptionController.SINGLE_SPEAKER -> "说话的人"
        else -> "第 $ordinal 个人"
    })
    var named by mutableStateOf(false)
    var ignored by mutableStateOf(false)
    val colorIndex: Int get() = when (id) {
        CaptionController.REPLY_SPEAKER, CaptionController.SELF_SPEAKER -> 7
        CaptionController.SINGLE_SPEAKER -> 1
        else -> (ordinal - 1).mod(7)
    }
}

/** [wallMs] is the clock time the line appeared; session-relative times restart with every session. */
class SegmentUi(val id: Long, val wallMs: Long) {
    /** How much of [text] the caption page has written out so far. */
    val reveal = Reveal()
    private var current by mutableStateOf("")
    var text: String
        get() = current
        set(value) { reveal.update(value, System.currentTimeMillis()); current = value }
    var confirmed by mutableStateOf(false)
    var speakerId by mutableStateOf<Int?>(null)
    var confident by mutableStateOf(true)
    var event by mutableStateOf("")
    /** Spoken length so far; the timeline draws the block this long. */
    var durationMs by mutableStateOf(0L)
    /** Shown in a lane once it has a speaker and something to read. */
    val placed: Boolean get() = speakerId != null && text.isNotEmpty()
}

/**
 * State and actions of the caption page. AIDL callbacks arrive on binder threads and are moved
 * to the main thread before touching Compose state; blocking service calls run on [io].
 */
class CaptionController(context: Context) : CaptionInferenceClient.Listener {
    /** Held: the user tapped the keyboard mic; captions gave it the microphone and resume after dictation. */
    enum class Status { Idle, Starting, Listening, Paused, Held, Error }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "CyIME-CaptionCtl").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("caption_mode", Context.MODE_PRIVATE)
    private val client = CaptionInferenceClient(app)
    private val flipMemory = ReplyFlipMemory(prefs)
    /** Quick-reply card turned towards the other person; remembered across closing and restarts. */
    var replyFlipped by mutableStateOf(flipMemory.read()); private set
    /** What the captions listen for; remembered. Switching restarts the session with that language's models. */
    var language by mutableStateOf(CaptionLanguage.of(prefs.getString("language", null))); private set
    fun chooseLanguage(next: CaptionLanguage) {
        if (next == language || next !in CaptionLanguage.supported) return
        language = next
        prefs.edit().putString("language", next.id).apply()
        if (status == Status.Listening || status == Status.Starting) { pause(); start() }
        // An error (e.g. the previous language's model missing) must not keep the new language from starting.
        else if (status == Status.Error) start()
    }
    fun turnReplyCard(flipped: Boolean) { replyFlipped = flipped; flipMemory.write(flipped) }
    private val transcript = CaptionTranscript()
    private val recorder = CaptionRecorder(
        onChunk = { pcm -> client.push(pcm) },
        onLevel = { level -> if (level > .002f) lastLoudAt = SystemClock.elapsedRealtime(); main.post { amplitude = level } },
        onFailure = { message -> main.post { fail(message) } },
        onChunkRead = { peak -> chunks++; if (peak > peakSinceLog) peakSinceLog = peak },
        onSpectrum = { frames -> frames.forEachIndexed { k, bands -> main.postDelayed({ if (status == Status.Listening) spectrum = bands }, k * 33L) } })

    var status by mutableStateOf(Status.Idle); private set
    var message by mutableStateOf(""); private set
    var amplitude by mutableStateOf(0f); private set
    /** Voice-range loudness in [CaptionSpectrum.BANDS] bands, newest ~33 ms frame. */
    var spectrum by mutableStateOf(FloatArray(CaptionSpectrum.BANDS)); private set
    var micMuted by mutableStateOf(false); private set
    /**
     * Android gave the microphone to another app (a screen recorder with sound, a voice message, a
     * call app taking it back) and records silence for us. Seen on a TIM call 2026-10-18: captions
     * stopped for 35 s while the screen recorder captured the microphone.
     */
    var micTaken by mutableStateOf(false); private set
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private val recordingCallback = if (android.os.Build.VERSION.SDK_INT >= 29) object : android.media.AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
            val session = recorder.sessionId
            if (session == 0) return
            val mine = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
            val taken = mine.isClientSilenced
            if (taken != micTaken) {
                // Which other sources are recording (no package names are visible to us), for the log only.
                val others = configs.filter { it.clientAudioSessionId != session }.joinToString { "src=${it.clientAudioSource}" }
                FileLogger.i(TAG, "mic silenced=$taken others=[$others]")
            }
            micTaken = taken
        }
    } else null
    // Diagnostics without content: chunk count, audio level and when the last text arrived.
    @Volatile private var chunks = 0L
    @Volatile private var peakSinceLog = 0f
    private var lastTextAt = 0L
    private val diagnostics = object : Runnable {
        override fun run() {
            if (status == Status.Listening) FileLogger.i(TAG, "diag chunks=$chunks peak=${"%.3f".format(peakSinceLog)} " +
                "silenced=$micTaken sinceText=${if (lastTextAt == 0L) -1 else SystemClock.elapsedRealtime() - lastTextAt}ms lines=${segments.size}")
            peakSinceLog = 0f
            main.postDelayed(this, 5_000)
        }
    }
    val speakers = mutableStateListOf<SpeakerUi>()
    val segments = mutableStateListOf<SegmentUi>()
    var fontLevel by mutableStateOf(prefs.getInt("font", 1)); private set
    var darkTheme by mutableStateOf(prefs.getBoolean("dark", false)); private set
    /** true: one horizontal track per speaker on a time axis (default); false: vertical transcript. */
    var timeline by mutableStateOf(prefs.getBoolean("timeline", true)); private set
    var speakerModelReady by mutableStateOf(speakerModelFile() != null); private set
    var downloadState by mutableStateOf<ModelDownloadState>(ModelDownloadState.Idle); private set
    /** Wall-clock millis of session audio time zero; segment times are relative to it. */
    var sessionStartedAt by mutableStateOf(System.currentTimeMillis()); private set
    var resumeOnForeground = false

    @Volatile private var lastLoudAt = SystemClock.elapsedRealtime()
    @Volatile private var sessionEpoch = 0L
    private var nextOrdinal = 1
    private val firstPartialAt = HashMap<Long, Long>()
    private val muteCheck = object : Runnable {
        override fun run() {
            micMuted = status == Status.Listening && SystemClock.elapsedRealtime() - lastLoudAt > 15_000
            main.postDelayed(this, 5_000)
        }
    }


    private fun speakerModelFile(): File? =
        File(ModelStorage.getModelDir(app, BuiltinModelCatalog.SPEAKER_MODEL), BuiltinModelCatalog.SPEAKER_MODEL_FILE)
            .takeIf { it.isFile && it.length() > 0 }

    fun start() {
        if (status == Status.Starting || status == Status.Listening) return
        status = Status.Starting
        message = "正在加载语音模型…"
        micMuted = false
        lastLoudAt = SystemClock.elapsedRealtime()
        val epoch = ++sessionEpoch
        io.execute {
            val result = runCatching {
                val chosen = language
                val mode = captionMode(chosen)
                main.post {
                    // Chinese: SenseVoice corrects; English: it is the model; Japanese never uses it.
                    refinementReady = chosen == CaptionLanguage.JAPANESE || usesSenseVoice(mode)
                    // Only Chinese has a streaming model to offer; the others re-read the sentence instead.
                    streamingReady = chosen != CaptionLanguage.CHINESE || mode != SpeechModelCatalog.SENSEVOICE
                }
                val speakerPath = speakerModelFile()?.absolutePath.orEmpty()
                check(client.bind()) { "无法连接语音服务" }
                check(client.start(mode, speakerPath, sessionListener(epoch), chosen.id)) {
                    if (client.isHeld()) HELD_FOR_DICTATION else "语音模型启动失败" }
                // Closed or paused while the model loaded: the page already stopped the recorder, so
                // a microphone opened now would stay on with nobody to close it.
                check(startUnlessStale({ epoch == sessionEpoch }, recorder::start, recorder::stop)) { "麦克风启动失败" }
            }
            main.post {
                if (epoch != sessionEpoch) return@post
                result.onSuccess {
                    status = Status.Listening
                    message = ""
                    sessionStartedAt = System.currentTimeMillis()
                    main.removeCallbacks(muteCheck); main.postDelayed(muteCheck, 5_000)
                    main.removeCallbacks(diagnostics); main.postDelayed(diagnostics, 5_000)
                    recordingCallback?.let { callback -> audioManager.registerAudioRecordingCallback(callback, main) }
                    FileLogger.i(TAG, "session started speakerModel=${speakerModelReady}")
                }.onFailure { if (it.message == HELD_FOR_DICTATION) hold() else fail(it.message ?: "字幕启动失败") }
            }
        }
    }

    private fun fail(message: String) {
        if (status == Status.Idle) return
        recorder.stop()
        stopDiagnostics()
        io.execute { client.cancel() }
        status = Status.Error
        this.message = message
        main.removeCallbacks(muteCheck)
        FileLogger.w(TAG, "session failed: $message")
    }

    /**
     * Keyboard dictation has the engine: let go of the microphone and wait. Nothing is cancelled on
     * the service side, which already ended this session and will call [onHold] false when done.
     */
    private fun hold() {
        if (status == Status.Idle || status == Status.Paused) return
        ++sessionEpoch
        recorder.stop()
        stopDiagnostics()
        main.removeCallbacks(muteCheck)
        status = Status.Held
        message = "键盘语音在用，说完字幕自动继续"
        FileLogger.i(TAG, "held for dictation")
    }

    override fun onHold(held: Boolean) {
        main.post {
            if (held) hold()
            else if (status == Status.Held) { status = Status.Paused; start() }
        }
    }

    fun pause() {
        if (status == Status.Held) {
            // The user paused while waiting: stay paused after dictation instead of restarting.
            io.execute { client.cancel() }
            status = Status.Paused
            message = ""
            return
        }
        if (status != Status.Listening && status != Status.Starting) return
        ++sessionEpoch
        recorder.stop()
        stopDiagnostics()
        io.execute { client.stop() }
        status = Status.Paused
        message = ""
        main.removeCallbacks(muteCheck)
    }

    fun resume() = start()

    fun exit() {
        if (CaptionAutoSave.isOn(app)) saveTranscript()
        ++sessionEpoch
        recorder.stop()
        stopDiagnostics()
        main.removeCallbacks(muteCheck)
        status = Status.Idle
        io.execute { client.cancel(); client.resetSpeakers(); client.unbind() }
        scope.cancel()
    }

    /**
     * Every restart (pause, background, speaker-model download) numbers its segments from 1 again.
     * Lines are keyed by session too, so a new "segment 1" can never overwrite or re-label an old one.
     */
    private fun sessionListener(epoch: Long) = object : CaptionInferenceClient.Listener {
        private fun key(id: Long) = epoch * SESSION_STRIDE + id
        override fun onSegment(id: Long, text: String, startMs: Long, endMs: Long, boundary: Int, confirmed: Boolean, event: String) =
            this@CaptionController.onSegment(key(id), text, startMs, endMs, boundary, confirmed, event)
        override fun onSpeaker(id: Long, speakerId: Int, confident: Boolean) =
            this@CaptionController.onSpeaker(key(id), speakerId, confident)
        override fun onError(message: String) = this@CaptionController.onError(message)
        override fun onLost() = this@CaptionController.onLost()
        override fun onHold(held: Boolean) = this@CaptionController.onHold(held)
    }

    // ---- callbacks (binder threads) ----
    override fun onSegment(id: Long, text: String, startMs: Long, endMs: Long, boundary: Int, confirmed: Boolean, event: String) {
        val now = SystemClock.elapsedRealtime()
        main.post {
            val line = transcript.update(id, text, startMs, endMs, boundary, confirmed, event) ?: return@post
            // Placed by when it was said (session time), not when its text arrived: split pieces of an
            // earlier sentence come after SenseVoice and must not land behind the next sentence.
            val ui = segments.firstOrNull { it.id == id } ?: SegmentUi(id, sessionStartedAt + startMs).also {
                // The speaker label may arrive before the first text of a short sentence.
                line.speakerId?.let { speaker -> ensureSpeaker(speaker); it.speakerId = speaker; it.confident = line.confident }
                segments.add(insertionIndex(segments, it.wallMs), it)
            }
            if (text.isNotEmpty()) lastTextAt = now
            if (text.isNotEmpty() && firstPartialAt.put(id, now) == null) FileLogger.i(TAG, "seg=$id firstTextMs=${now - startAtElapsed()}")
            ui.text = displayText(line)
            ui.event = event
            ui.confirmed = confirmed
            if (endMs > startMs) ui.durationMs = endMs - startMs
            if (confirmed) {
                FileLogger.i(TAG, "seg=$id final len=${text.length} event=$event durMs=${endMs - startMs}")
                // Nothing to read: a short empty segment disappears, a long one shows the marker.
                if (ui.text.isEmpty()) { segments.remove(ui); transcript.remove(id) } else { scheduleSave(); alertIfNamed(ui) }
            }
            trimHistory()
        }
    }

    override fun onSpeaker(id: Long, speakerId: Int, confident: Boolean) {
        main.post {
            // A provisional label (sentence still being spoken) never overrides a final one.
            val known = transcript[id]
            if (!confident && known?.speakerId != null && known.confident) return@post
            transcript.assign(id, speakerId, confident)
            val ui = segments.firstOrNull { it.id == id } ?: return@post
            ensureSpeaker(speakerId)
            val before = ui.speakerId
            ui.speakerId = speakerId
            ui.confident = confident
            // A voice merged away by the re-check leaves no sentences: drop its empty lane.
            if (before != null && before != speakerId && before in 1 until SELF_SPEAKER && segments.none { it.speakerId == before })
                speakers.removeAll { it.id == before && !it.named }
            FileLogger.i(TAG, "seg=$id speaker=$speakerId confident=$confident")
        }
    }

    override fun onError(message: String) { main.post { this.message = message } }

    override fun onLost() { main.post { fail("语音服务中断了，点“重新开始”") } }

    private fun stopDiagnostics() {
        main.removeCallbacks(diagnostics)
        recordingCallback?.let { callback -> runCatching { audioManager.unregisterAudioRecordingCallback(callback) } }
        micTaken = false
    }

    private fun startAtElapsed(): Long = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - sessionStartedAt)

    private fun displayText(line: CaptionTranscript.Entry): String {
        if (line.text.isNotEmpty() && !isNoise(line.text)) return line.text
        if (!line.confirmed) return ""
        return when {
            line.event.equals("BGM", ignoreCase = true) -> "（电视声）"
            line.event.equals("Laughter", ignoreCase = true) -> "（笑）"
            line.endMs - line.startMs >= 1000 -> "（机器没听清）"
            else -> ""
        }
    }

    /** "I." / "嗯." style hallucinations on room noise: at most one letter and no CJK text. */
    private fun isNoise(text: String): Boolean {
        val letters = text.filter { it.isLetterOrDigit() }
        return letters.length <= 1 && letters.none { it.code > 0x2e80 }
    }

    /** Names of the people whose voices are remembered on this phone (written by the :asr process). */
    private val knownNames = HashMap<Int, String>().apply { KnownVoices.list(app).forEach { put(it.id, it.name) } }

    private fun ensureSpeaker(id: Int): SpeakerUi =
        speakers.firstOrNull { it.id == id } ?: SpeakerUi(id, if (id <= SINGLE_SPEAKER) 0 else nextOrdinal++).also { speaker ->
            knownNames[id]?.let { speaker.name = it; speaker.named = true }
            speakers.add(speaker)
        }

    fun isRemembered(speakerId: Int) = KnownVoices.isKnown(speakerId)

    private fun trimHistory() {
        while (segments.size > MAX_LINES) {
            val old = segments.removeAt(0); transcript.remove(old.id)
            lineOf(old)?.let { archived.append(it).append('\n') }
        }
    }

    // ---- user actions ----
    /**
     * Names a voice. With [remember] the name and voiceprint are kept on this phone, so later sessions
     * recognise the person by name from their first words; the row moves to the remembered id.
     */
    fun rename(speakerId: Int, name: String, remember: Boolean = false) {
        val speaker = ensureSpeaker(speakerId)
        speaker.name = name
        speaker.named = true
        speaker.ignored = false
        io.execute {
            client.setSpeakerNamed(speakerId, true); client.setSpeakerIgnored(speakerId, false)
            if (!remember) return@execute
            val id = client.rememberSpeaker(speakerId, name)
            main.post {
                if (id <= 0) { message = "没能记住这个声音：他说的话还太少，多说几句再试"; return@post }
                knownNames[id] = name
                if (id != speakerId) {
                    transcript.mergeSpeakers(speakerId, id)
                    for (segment in segments) if (segment.speakerId == speakerId) { segment.speakerId = id; segment.confident = true }
                    speakers.removeAll { it.id == speakerId }
                }
                ensureSpeaker(id).let { it.name = name; it.named = true }
            }
        }
    }

    /** Stops recognising a remembered person; their voiceprint is deleted from the phone. */
    fun forgetVoice(speakerId: Int) {
        if (!KnownVoices.isKnown(speakerId)) return
        knownNames.remove(speakerId)
        io.execute { client.forgetSpeaker(speakerId) }
    }

    // ---- this session's transcript file ----
    // One file per session (opening the page until 退出), rewritten in place as lines arrive, so
    // tapping 保存 again never adds another file (user 2026-10-27: every tap made a new one).
    private fun newSessionName() = "字幕_" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date()) + ".txt"
    private var sessionName = newSessionName()
    /** Lines that scrolled out of [segments] (kept to [MAX_LINES]), already written out. */
    private val archived = StringBuilder()
    private var savedUri: android.net.Uri? = null
    /** Where the file is, once written ("下载/CyIME字幕/…"). */
    var savedWhere by mutableStateOf<String?>(null); private set
    private val autoSave = Runnable { writeTranscript() }

    // Like Google Live Transcribe, saved captions are kept for 3 days and then removed (setting
    // 「字幕只保留 3 天」, on by default). Only this app's own files in 下载/CyIME字幕 are touched.
    init { if (CaptionAutoSave.keepsThreeDays(app)) io.execute { pruneOldCaptions() } }

    private fun pruneOldCaptions() {
        val cutoff = System.currentTimeMillis() - CaptionAutoSave.KEEP_MS
        var removed = 0
        for (old in savedCaptions().filter { it.modifiedMs < cutoff }) {
            val gone = runCatching {
                old.file?.delete() ?: (app.contentResolver.delete(checkNotNull(old.uri), null, null) > 0)
            }.getOrDefault(false)
            if (gone) removed++
        }
        if (removed > 0) FileLogger.i(TAG, "removed $removed caption files older than 3 days")
    }

    /**
     * Lines containing [query] in this session and in the saved captions, newest file first; each hit
     * names its file (null = this session). The current session's own file is not searched twice.
     */
    internal fun search(query: String, limit: Int = 200): List<Pair<SavedCaption?, String>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<SavedCaption?, String>>()
        for (line in transcriptText().lineSequence()) if (line.contains(q, ignoreCase = true)) hits += null to line
        for (saved in savedCaptions()) {
            if (saved.name == sessionName) continue
            for (line in readSaved(saved).lineSequence()) {
                if (line.contains(q, ignoreCase = true)) hits += saved to line
                if (hits.size >= limit) return hits
            }
        }
        return hits
    }

    private fun lineOf(line: SegmentUi): String? {
        if (line.text.isEmpty() || speakers.any { it.id == line.speakerId && it.ignored }) return null
        return transcriptLine(speakers.firstOrNull { it.id == line.speakerId }?.name, line.wallMs, line.text)
    }

    /** The whole session as text: speaker, time and words per line, oldest first. */
    fun transcriptText(): String = archived.toString() + segments.mapNotNull { lineOf(it)?.plus("\n") }.joinToString("")

    private fun scheduleSave() {
        if (!CaptionAutoSave.isOn(app)) return
        main.removeCallbacks(autoSave)
        main.postDelayed(autoSave, AUTO_SAVE_DELAY_MS)
    }

    /** Writes the file now (creating it the first time); returns where it is, or null when there is nothing yet. */
    fun saveTranscript(): String? {
        main.removeCallbacks(autoSave)
        return writeTranscript()
    }

    private fun writeTranscript(): String? {
        val text = transcriptText()
        if (text.isEmpty()) return null
        val bytes = text.toByteArray()
        fun write(): String = if (android.os.Build.VERSION.SDK_INT >= 29) {
            val uri = savedUri ?: checkNotNull(app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, sessionName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_FOLDER)
                })).also { savedUri = it }
            // "wt": replace the contents, the same file every time.
            checkNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
            "下载/$SAVE_FOLDER/$sessionName"
        } else {
            // Android 9 has no Downloads collection without storage permission: keep it in the app's own folder.
            val dir = java.io.File(checkNotNull(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)), SAVE_FOLDER).apply { mkdirs() }
            java.io.File(dir, sessionName).writeBytes(bytes)
            java.io.File(dir, sessionName).absolutePath
        }
        return runCatching { write() }
            // Deleted by the user meanwhile: start the file again under the same name.
            .recoverCatching { savedUri = null; write() }
            .onFailure { FileLogger.e(TAG, "save transcript failed", it) }.getOrNull()
            ?.also { savedWhere = it }
    }

    /**
     * 「开始新一段」 (a long pull on the captions): what was said so far is saved and cleared, and a new
     * file starts; listening goes on and the people stay known. The sentence still being spoken stays.
     */
    fun newSegment() {
        if (CaptionAutoSave.isOn(app)) saveTranscript()
        for (line in segments.filter { it.confirmed }) { segments.remove(line); transcript.remove(line.id) }
        archived.setLength(0)
        savedUri = null
        savedWhere = null
        sessionName = newSessionName()
    }

    /** Earlier caption files in 下载/CyIME字幕, newest first. */
    internal fun savedCaptions(): List<SavedCaption> = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val out = ArrayList<SavedCaption>()
            val columns = arrayOf(android.provider.MediaStore.Downloads._ID, android.provider.MediaStore.Downloads.DISPLAY_NAME,
                android.provider.MediaStore.Downloads.DATE_MODIFIED, android.provider.MediaStore.Downloads.SIZE)
            app.contentResolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, columns,
                "${android.provider.MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(android.os.Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_FOLDER + "/"),
                "${android.provider.MediaStore.Downloads.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                    out += SavedCaption(c.getString(1), c.getLong(2) * 1000, c.getLong(3), uri, null)
                }
            }
            out
        } else {
            val dir = java.io.File(checkNotNull(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)), SAVE_FOLDER)
            (dir.listFiles() ?: emptyArray()).sortedByDescending { it.lastModified() }
                .map { SavedCaption(it.name, it.lastModified(), it.length(), null, it) }
        }
    }.onFailure { FileLogger.e(TAG, "list saved captions failed", it) }.getOrDefault(emptyList())

    internal fun readSaved(saved: SavedCaption): String = runCatching {
        saved.file?.readText() ?: checkNotNull(app.contentResolver.openInputStream(checkNotNull(saved.uri))).use { it.readBytes().decodeToString() }
    }.getOrDefault("")

    /** Buzzes when a finished line from someone else calls the holder by name (「有人叫我名字时振动」). */
    private fun alertIfNamed(line: SegmentUi) {
        if (!CaptionNameAlert.isOn(app)) return
        if (line.speakerId == SELF_SPEAKER || line.speakerId == REPLY_SPEAKER) return
        if (!callsName(line.text, CaptionNameAlert.names(app))) return
        FileLogger.i(TAG, "name heard seg=${line.id}")
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31)
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") app.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26)
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
            else @Suppress("DEPRECATION") vibrator.vibrate(longArrayOf(0, 400, 200, 400, 200, 400), -1)
        }
    }

    /** Puts the whole session on the clipboard; false when there is nothing yet. */
    fun copyAll(): Boolean {
        val text = transcriptText()
        if (text.isEmpty()) return false
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("字幕", text))
        return true
    }

    /** "That voice is this person": the picked speaker keeps its name, the other row disappears. */
    fun mergeInto(from: Int, into: Int) {
        if (from == into) return
        transcript.mergeSpeakers(from, into)
        for (segment in segments) if (segment.speakerId == from) { segment.speakerId = into; segment.confident = true }
        speakers.removeAll { it.id == from }
        io.execute { client.mergeSpeakers(from, into) }
    }

    /** "这是我": the holder's voice. Remembered on this phone; call captions leave it out from now on. */
    fun markSelf(speakerId: Int) {
        if (speakerId != SELF_SPEAKER) {
            transcript.mergeSpeakers(speakerId, SELF_SPEAKER)
            for (segment in segments) if (segment.speakerId == speakerId) { segment.speakerId = SELF_SPEAKER; segment.confident = true }
            speakers.removeAll { it.id == speakerId }
        }
        ensureSpeaker(SELF_SPEAKER)
        io.execute { client.markSelf(speakerId) }
    }

    fun forgetSelf() {
        speakers.firstOrNull { it.id == SELF_SPEAKER }?.let { it.name = "第 ${nextOrdinal++} 个人" }
        io.execute { client.forgetSelf() }
    }

    fun markTelevision(speakerId: Int) {
        val speaker = ensureSpeaker(speakerId)
        speaker.ignored = true
        speaker.name = "电视"
        io.execute { client.setSpeakerIgnored(speakerId, true) }
    }

    fun reassign(segmentId: Long, speakerId: Int) {
        if (!transcript.reassign(segmentId, speakerId)) return
        segments.firstOrNull { it.id == segmentId }?.let { it.speakerId = speakerId; it.confident = true }
        ensureSpeaker(speakerId)
        // Only the running session's labeler knows this sentence's voiceprint.
        val raw = segmentId - sessionEpoch * SESSION_STRIDE
        if (raw in 1 until SESSION_STRIDE) io.execute { client.reassignSegment(raw, speakerId) }
    }

    fun addReply(text: String) {
        if (text.isBlank()) return
        ensureSpeaker(REPLY_SPEAKER)
        val id = -(System.currentTimeMillis())
        val line = SegmentUi(id, System.currentTimeMillis())
        line.text = text.trim(); line.confirmed = true; line.speakerId = REPLY_SPEAKER
        line.reveal.showAll()
        segments.add(line)
        trimHistory()
    }

    fun cycleFont() { fontLevel = (fontLevel + 1) % FONT_SP.size; prefs.edit().putInt("font", fontLevel).apply() }
    fun toggleLayout() { timeline = !timeline; prefs.edit().putBoolean("timeline", timeline).apply() }
    fun toggleTheme() { darkTheme = !darkTheme; prefs.edit().putBoolean("dark", darkTheme).apply() }

    /**
     * Captions always correct with SenseVoice when it is on disk (punctuation, CER 7.6 % vs 15 %),
     * whatever the keyboard's voice setting says; without it they fall back to the keyboard's own
     * model and the page offers the download.
     */
    private fun ready(mode: String) = runCatching { AsrModelManager(app).selection(mode, null).ready }.getOrDefault(false)
    private fun senseVoiceReady() = ready(SpeechModelCatalog.SENSEVOICE)

    /**
     * The model a caption language needs that is not on the phone yet, with its download size;
     * null when the language is ready. Japanese: ReazonSpeech int8 (the fp32 is used if that is what
     * the keyboard already downloaded); English: SenseVoice in English.
     */
    fun missingModel(language: CaptionLanguage): Pair<String, String>? = when (language) {
        CaptionLanguage.CHINESE -> if (ready(SpeechModelCatalog.TWO_PASS) || ready(SpeechModelCatalog.SENSEVOICE) ||
            ready(SpeechModelCatalog.PARAFORMER)) null else SpeechModelCatalog.PARAFORMER to "226 MB"
        CaptionLanguage.JAPANESE -> if (ready(SpeechModelCatalog.ZIPFORMER_JA_INT8) || ready(SpeechModelCatalog.ZIPFORMER_JA)) null
            else SpeechModelCatalog.ZIPFORMER_JA_INT8 to "153 MB"
        CaptionLanguage.ENGLISH -> if (senseVoiceReady()) null else SpeechModelCatalog.SENSEVOICE to "228 MB"
    }

    // One state per language: picking English while Japanese downloads must not show Japanese's
    // progress, or its "done", on the English banner.
    private val languageDownloads = mutableStateMapOf<CaptionLanguage, ModelDownloadState>()
    fun languageDownload(language: CaptionLanguage): ModelDownloadState = languageDownloads[language] ?: ModelDownloadState.Idle

    /** Downloads what [language] needs in the app (model management's own downloader), then listens in it. */
    fun downloadLanguage(language: CaptionLanguage) {
        val (modelId, _) = missingModel(language) ?: return
        if (languageDownload(language) is ModelDownloadState.Downloading) return
        languageDownloads[language] = ModelDownloadState.Downloading(0f, 0, -1)
        scope.launch {
            ModelManager.downloadModel(app, modelId) { state ->
                main.post {
                    languageDownloads[language] = state
                    if (state is ModelDownloadState.Complete && language == this@CaptionController.language) { pause(); start() }
                }
            }
        }
    }

    private fun captionMode(language: CaptionLanguage): String = when (language) {
        CaptionLanguage.JAPANESE -> listOf(SpeechModelCatalog.ZIPFORMER_JA_INT8, SpeechModelCatalog.ZIPFORMER_JA).firstOrNull { ready(it) }
            ?: error("日语字幕要先下载日语语音模型（153 MB）")
        CaptionLanguage.ENGLISH -> SpeechModelCatalog.SENSEVOICE.takeIf { ready(it) }
            ?: error("英语字幕要先下载 SenseVoice（228 MB）")
        CaptionLanguage.CHINESE -> chineseMode()
    }

    private fun chineseMode(): String {
        val manager = AsrModelManager(app)
        if (ready(SpeechModelCatalog.TWO_PASS)) return SpeechModelCatalog.TWO_PASS
        if (ready(SpeechModelCatalog.SENSEVOICE)) return SpeechModelCatalog.SENSEVOICE
        val keyboard = runCatching { manager.getSelectedModelId(InputLanguage.CHINESE) }.getOrNull()
        return listOfNotNull(keyboard, SpeechModelCatalog.PARAFORMER).firstOrNull { ready(it) }
            ?: error("还没有中文语音模型，请在 设置 → 语音转文本 里下载")
    }

    private fun usesSenseVoice(mode: String) = mode == SpeechModelCatalog.TWO_PASS || mode == SpeechModelCatalog.SENSEVOICE ||
        mode == SpeechModelCatalog.ZIPFORMER_TWO_PASS

    /** Whether the call-caption accessibility service is on; refreshed whenever the page comes back. */
    var callCaptionEnabled by mutableStateOf(true)
    fun refreshCallCaption() { callCaptionEnabled = CallCaptionService.isEnabled(app) }
    /**
     * The "去开启通话字幕" card was closed with "不用了": it stays closed. Call captions need an
     * accessibility service, which some bank apps refuse to run beside; the page itself never needs it.
     */
    var callCaptionCardDismissed by mutableStateOf(prefs.getBoolean(KEY_CALL_CARD_DISMISSED, false)); private set
    fun dismissCallCaptionCard() {
        callCaptionCardDismissed = true
        prefs.edit().putBoolean(KEY_CALL_CARD_DISMISSED, true).apply()
    }

    /** SenseVoice is on disk (or the page has not checked yet). */
    var refinementReady by mutableStateOf(true); private set
    var refinementDownload by mutableStateOf<ModelDownloadState>(ModelDownloadState.Idle); private set

    /**
     * Paraformer is on disk: words appear while the person is still speaking. SenseVoice alone
     * waits for each pause and then drops a whole sentence at once, too fast for an elder to read.
     */
    var streamingReady by mutableStateOf(true); private set
    var streamingDownload by mutableStateOf<ModelDownloadState>(ModelDownloadState.Idle); private set

    fun downloadStreaming() {
        if (streamingDownload is ModelDownloadState.Downloading) return
        streamingDownload = ModelDownloadState.Downloading(0f, 0, -1)
        scope.launch {
            ModelManager.downloadModel(app, SpeechModelCatalog.PARAFORMER) { state ->
                main.post {
                    streamingDownload = state
                    if (state is ModelDownloadState.Complete && (status == Status.Listening || status == Status.Starting)) { pause(); start() }
                }
            }
        }
    }

    fun downloadRefinement() {
        if (refinementDownload is ModelDownloadState.Downloading) return
        refinementDownload = ModelDownloadState.Downloading(0f, 0, -1)
        scope.launch {
            ModelManager.downloadModel(app, SpeechModelCatalog.SENSEVOICE) { state ->
                main.post {
                    refinementDownload = state
                    if (state is ModelDownloadState.Complete && (status == Status.Listening || status == Status.Starting)) { pause(); start() }
                }
            }
        }
    }

    fun downloadSpeakerModel() {
        if (downloadState is ModelDownloadState.Downloading) return
        downloadState = ModelDownloadState.Downloading(0f, 0, -1)
        scope.launch {
            ModelManager.downloadModel(app, BuiltinModelCatalog.SPEAKER_MODEL) { state ->
                main.post {
                    downloadState = state
                    if (state is ModelDownloadState.Complete) {
                        speakerModelReady = speakerModelFile() != null
                        // The running session has no labeler yet; a restart attaches it.
                        if (speakerModelReady && (status == Status.Listening || status == Status.Starting)) { pause(); start() }
                    }
                }
            }
        }
    }

    fun release() {
        exit()
        io.shutdown()
    }

    companion object {
        private const val TAG = "Caption"
        private const val KEY_CALL_CARD_DISMISSED = "call_caption_card_dismissed"
        /** Start refused because keyboard dictation is on; not an error. */
        private const val HELD_FOR_DICTATION = "held-for-dictation"
        const val REPLY_SPEAKER = -1
        /** The phone holder's remembered voice (same id as the :asr tracker). */
        const val SELF_SPEAKER = com.kingzcheung.xime.speech.caption.SpeakerTracker.SELF
        /** Without a speaker model every line goes to this single unnamed speaker. */
        const val SINGLE_SPEAKER = 0
        /** Above every id a session uses, including pieces split at a change of voice (SPLIT_BASE = 2^40). */
        const val SESSION_STRIDE = 1_000_000_000_000_000L
        const val MAX_LINES = 400
        private const val AUTO_SAVE_DELAY_MS = 3_000L
        const val SAVE_FOLDER = "Lime字幕"
        val FONT_SP = intArrayOf(24, 32, 44)
        val FONT_LABEL = arrayOf("大", "特大", "巨大")
        /** Okabe-Ito colour-blind safe palette: orange, sky blue, bluish green, blue, vermillion, reddish purple, yellow, grey. */
        val COLORS = longArrayOf(0xFFE69F00, 0xFF56B4E9, 0xFF009E73, 0xFF0072B2, 0xFFD55E00, 0xFFCC79A7, 0xFFF0E442, 0xFF999999)
        val PRESET_NAMES = listOf("这是我", "儿子", "女儿", "老伴", "孙子", "孙女", "医生", "邻居")
        /** Ready-made replies for a hard-of-hearing reader, by situation; one tap shows them to the other person. */
        val PHRASE_GROUPS: List<Pair<String, List<String>>> = listOf(
            "回答" to listOf("好的", "是", "不是", "对", "不对", "可以", "不要", "知道了", "谢谢", "没关系", "等一下", "我再想想"),
            "听不清" to listOf("请再说一遍", "说慢一点", "大声一点", "写下来给我看", "我听不见，请看手机", "请对着手机说", "我没听懂"),
            "生活" to listOf("我饿了，想吃饭", "我渴了，想喝水", "我想睡觉", "我要上厕所", "我冷", "我热", "我累了，想歇一会儿",
                "我想出去走走", "帮我开灯", "帮我关电视", "我想洗澡", "我想换衣服"),
            "家人" to listOf("我想见儿子", "我想见女儿", "我想见孙子", "我想见孙女", "我想孩子们了", "帮我给家里打个电话", "孩子什么时候来？", "你们吃饭了吗？", "路上小心"),
            "身体" to listOf("我不舒服", "我头疼", "我头晕", "胸口难受", "喘不上气", "我摔倒了", "我肚子疼", "该吃药了吗？", "帮我量一下血压"),
        )
        const val EMERGENCY_TEXT = "请马上帮我打120！"

    }
}

/**
 * Opens the microphone for a session that may have been closed while its model loaded. A session
 * that is already stale never opens it; one that goes stale during the open closes it again at
 * once, so the close that already ran on the main thread cannot be outrun.
 */
internal fun startUnlessStale(current: () -> Boolean, start: () -> Boolean, stop: () -> Unit): Boolean {
    if (!current()) return false
    val started = start()
    if (started && !current()) { stop(); return false }
    return started
}
