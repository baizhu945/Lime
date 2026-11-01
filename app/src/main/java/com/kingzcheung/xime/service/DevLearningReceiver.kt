package com.kingzcheung.xime.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.BackupManager
import com.kingzcheung.xime.settings.SettingsPreferences
import java.io.File
import kotlin.concurrent.thread

/**
 * 开发者工具（只在“装到我的设备”包里起作用），给自动打字和测试脚本用，免得测试学进用户词库：
 * - `op=incognito --ez on true|false`：开关无痕，测试前打开、测试后恢复，Rime 和 CyLM 都不学。
 * - `op=unlearn`：读 Android/data/<包名>/files/unlearn.tsv（每行“词<TAB>次数”），从当前方案的用户词库里
 *   减去这么多次学习，减完的词回到词库原来的位置；用户自己别的学习不动。
 * - `op=learn`：读 Android/data/<包名>/files/learn.tsv（每行“词<TAB>拼 音<TAB>次数”），把用户自己在别处写过的词
 *   按选过这么多次学进当前方案的用户词库（他自己的聊天记录，只进他的设备）。
 * - `op=reset_cylm`：删掉 CyLM 在本机学到的那层（rime/cylm_learned.tsv），下次打开回到安装包带的用户层。
 *   之后要重启输入法进程，免得内存里的那层再写回去。
 * - `op=export`：把当前方案的用户词库（Rime 文本快照 .userdb.txt）和 CyLM 学习层复制到
 *   Android/data/<包名>/files/cyime-dev/<时间>/，供电脑 adb pull 分析。
 * - `op=snapshot`：自动打字前存一份快照（kime_settings 全部设置：无痕、全角、方案、布局等；自定义布局；
 *   rime/user.yaml；CyLM 学习层；当前方案用户词库），放在 Android/data/<包名>/files/cyime-dev/snapshot/。
 * - `op=restore`：打完后把上面这些原样放回（用户词库按快照逐条复原，期间新学的词删掉），再核对一遍。
 *   之后脚本要重启输入法进程，让内存里的设置和 CyLM 学习层重新读取，再发 `op=verify`。
 * - `op=verify`：只核对现状和快照是否完全一样，结果第一个词是 OK 或 DIFFERENT，后面列出哪里不一样。
 * 输入法正在部署时回 busy，稍后重试。每个 op 的结果写进广播结果（am broadcast 会打印 data=...）和 logcat 标签 DevLearning。
 * 用法：adb shell am broadcast -n com.kingzcheung.xime/com.kingzcheung.xime.service.DevLearningReceiver -a com.kingzcheung.xime.DEV_LEARNING --es op unlearn
 * 只有 adb（持有 DUMP 权限）能发，普通应用发不进来。
 */
class DevLearningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!BuildConfig.DEV_TOOLS) { report("dev tools off in this build"); return }
        val app = context.applicationContext
        when (intent.getStringExtra("op")) {
            "incognito" -> {
                SettingsPreferences.setIncognito(app, intent.getBooleanExtra("on", true))
                // `am broadcast` prints this, so a script can check the switch really changed.
                report("incognito=${SettingsPreferences.isIncognito(app)}")
            }
            "unlearn" -> {
                val file = File(app.getExternalFilesDir(null), "unlearn.tsv")
                if (!file.isFile) { report("unlearn: ${file.path} missing"); return }
                val rows = file.readText()
                background { "unlearn: ${RimeEngine.getInstance().unlearn(rows)} commits taken back" }
            }
            "learn" -> {
                val file = File(app.getExternalFilesDir(null), "learn.tsv")
                if (!file.isFile) { report("learn: ${file.path} missing"); return }
                val rows = file.readText()
                background { "learn: ${RimeEngine.getInstance().learn(rows)} words taught" }
            }
            "reset_cylm" -> {
                val layer = File(File(app.filesDir, "rime"), "cylm_learned.tsv")
                report("reset_cylm: ${if (layer.delete()) "removed" else "nothing to remove"}")
            }
            "export" -> {
                val dir = File(app.getExternalFilesDir(null), "cyime-dev/" +
                    java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()))
                background {
                    dir.mkdirs()
                    val rime = File(app.filesDir, "rime")
                    val dict = RimeEngine.getInstance().exportUserDict(File(dir, "user_dict.userdb.txt").path)
                    val cylm = File(rime, "cylm_learned.tsv").takeIf { it.isFile }?.copyTo(File(dir, "cylm_learned.tsv"), true) != null
                    "export: ${dir.path} userdb=$dict cylm_learned=$cylm"
                }
            }
            "snapshot" -> background {
                val dir = snapshotDir(app)
                dir.deleteRecursively()
                File(dir, "files").mkdirs()
                File(dir, SETTINGS_FILE).writeText(BackupManager.prefsToJson(settings(app)))
                SNAPSHOT_FILES.forEach { name ->
                    File(app.filesDir, name).takeIf { it.isFile }?.copyTo(File(dir, "files/$name"), true)
                }
                check(RimeEngine.getInstance().exportUserDict(File(dir, USERDB).path)) { "user dictionary export failed" }
                "snapshot: ${dir.path} settings=${settings(app).all.size} " +
                    "files=${SNAPSHOT_FILES.count { File(dir, "files/$it").isFile }} userdb=${dataLines(File(dir, USERDB)).size}"
            }
            "restore" -> background {
                val dir = snapshotDir(app)
                check(File(dir, SETTINGS_FILE).isFile && File(dir, USERDB).isFile) { "no snapshot in ${dir.path}" }
                BackupManager.restorePrefsJson(settings(app), File(dir, SETTINGS_FILE).readBytes(), replace = true)
                // Only files the run really changed are put back. Those are also held in memory (CyLM layer,
                // Rime options), so only then does the script have to restart the IME; a restart while the
                // user types looks like a crash to them.
                val putBack = SNAPSHOT_FILES.filter { name ->
                    val saved = File(dir, "files/$name")
                    val live = File(app.filesDir, name)
                    val a = saved.takeIf { it.isFile }?.let { comparable(name, it) }
                    val b = live.takeIf { it.isFile }?.let { comparable(name, it) }
                    if (a != b) { if (saved.isFile) saved.copyTo(live, true) else live.delete() }
                    a != b
                }
                val changed = RimeEngine.getInstance().restoreUserDict(File(dir, USERDB).path)
                check(changed >= 0) { "user dictionary restore failed" }
                "restore: userdb entries put back=$changed files put back=${putBack.joinToString(",")} " +
                    "restart=${if (putBack.isEmpty()) "no" else "yes"}; " + verify(app, dir)
            }
            "verify" -> background { verify(app, snapshotDir(app)) }
            else -> report("unknown op")
        }
    }

    private fun snapshotDir(app: Context) = File(app.getExternalFilesDir(null), "cyime-dev/snapshot")

    private fun settings(app: Context) = app.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

    /** Entry lines of a Rime .userdb.txt snapshot (header comments are left out). */
    private fun dataLines(file: File): Set<String> =
        file.readLines().filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    /** "OK", or "DIFFERENT" followed by every setting key, file and dictionary entry that doesn't match the snapshot. */
    private fun verify(app: Context, dir: File): String {
        check(File(dir, SETTINGS_FILE).isFile && File(dir, USERDB).isFile) { "no snapshot in ${dir.path}" }
        val problems = mutableListOf<String>()
        val saved = BackupManager.parsePrefs(File(dir, SETTINGS_FILE).readBytes())
        val live = BackupManager.parsePrefs(BackupManager.prefsToJson(settings(app)).toByteArray())
        val settingDiffs = (saved.keys + live.keys).toSortedSet().filter { it !in DEPLOY_STATE && saved[it] != live[it] }
        if (settingDiffs.isNotEmpty()) problems += "settings=" + settingDiffs.joinToString(",")
        SNAPSHOT_FILES.forEach { name ->
            val a = File(dir, "files/$name").takeIf { it.isFile }?.let { comparable(name, it) }
            val b = File(app.filesDir, name).takeIf { it.isFile }?.let { comparable(name, it) }
            if (a != b) problems += "file=$name"
        }
        val now = File(dir, "verify.userdb.txt")
        if (!RimeEngine.getInstance().exportUserDict(now.path)) problems += "userdb=export failed"
        else {
            val want = dataLines(File(dir, USERDB))
            val have = dataLines(now)
            val extra = have - want
            val missing = want - have
            if (extra.isNotEmpty() || missing.isNotEmpty())
                problems += "userdb=+${extra.size}/-${missing.size} " + (extra.take(5) + missing.take(5)).joinToString(" | ")
        }
        return if (problems.isEmpty()) "OK" else "DIFFERENT " + problems.joinToString("; ")
    }

    /**
     * File content as compared by [verify]. Rime rewrites the timestamps in user.yaml (last_build_time,
     * schema_access_time) whenever the IME restarts or a schema is opened, so those lines don't count;
     * the saved options and the selected schema do.
     */
    private fun comparable(name: String, file: File): String {
        if (!name.endsWith("user.yaml")) return file.readBytes().joinToString(",")
        var inAccessTimes = false
        return file.readLines().filter { line ->
            val trimmed = line.trim()
            if (inAccessTimes && line.startsWith("    ")) return@filter false
            inAccessTimes = trimmed == "schema_access_time:"
            !trimmed.startsWith("last_build_time:")
        }.joinToString("\n")
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        resultData = message
    }

    /**
     * Engine work off the main thread; the broadcast result carries its message.
     * A deploy holds the engine for minutes; waiting on it ran past the broadcast's time limit, and Android
     * killed the process in the middle of the deploy. So the engine is taken here with a short wait,
     * and a busy engine answers "busy" for the script to retry later.
     */
    private fun background(work: () -> String) {
        val pending = goAsync()
        thread(name = "dev-learning") {
            val lock = RimeEngine.rimeLock
            val message = if (!lock.tryLock(20, java.util.concurrent.TimeUnit.SECONDS)) "busy: engine is deploying, try again"
            else try { runCatching(work).getOrElse { "failed: ${it.message}" } } finally { lock.unlock() }
            Log.i(TAG, message)
            pending.resultData = message
            pending.finish()
        }
    }

    private companion object {
        const val ACTION = BuildConfig.APPLICATION_ID + ".DEV_LEARNING"
        const val TAG = "DevLearning"
        const val SETTINGS_PREFS = "kime_settings"
        const val SETTINGS_FILE = "kime_settings.json"
        const val USERDB = "user_dict.userdb.txt"
        /** Settings an app update rewrites on its own while redeploying Rime; not the run's doing. */
        val DEPLOY_STATE = setOf("deployment_done", "deployment_hash", "rime_assets_version", "builtin_schemas_merged")
        /** Files under filesDir an automated run may change: custom layouts, Rime saved options, CyLM learned layer. */
        val SNAPSHOT_FILES = listOf("custom-keyboard-layouts.json", "rime/user.yaml", "rime/cylm_learned.tsv")
    }
}
