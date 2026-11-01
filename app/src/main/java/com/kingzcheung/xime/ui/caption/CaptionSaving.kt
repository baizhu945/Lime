package com.kingzcheung.xime.ui.caption

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「自动保存字幕」: each session's transcript kept as one file in 下载/Lime字幕, updated as it goes. On by default. */
internal object CaptionAutoSave {
    private const val PREFS = "caption_mode"
    private const val KEY = "auto_save"
    fun isOn(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    private const val KEY_KEEP = "keep_three_days"
    const val KEEP_MS = 3L * 24 * 60 * 60 * 1000
    fun keepsThreeDays(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP, true)
    fun setKeepsThreeDays(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP, on).apply()
    }
}

/** One transcript line: speaker, clock time, words (the format of the saved file and of 复制全部). */
internal fun transcriptLine(speaker: String?, wallMs: Long, text: String): String =
    "${speaker ?: "说话的人"}  ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(wallMs))}  $text"

/**
 * 「有人叫我名字时振动」: names the holder answers to (several, separated by spaces or commas).
 * A finished caption line from someone else that contains one buzzes the phone.
 */
internal object CaptionNameAlert {
    private const val PREFS = "caption_mode"
    private const val KEY_ON = "name_alert"
    private const val KEY_NAMES = "name_alert_names"
    fun isOn(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }
    fun names(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAMES, "").orEmpty()
    fun setNames(context: Context, names: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NAMES, names).apply()
    }
}

/** Whether [text] calls one of [names] (split on spaces, commas, 、; each at least one character). */
internal fun callsName(text: String, names: String): Boolean =
    names.split(' ', ',', '，', '、', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }.any { text.contains(it, ignoreCase = true) }

/** One earlier caption file in 下载/Lime字幕. */
internal data class SavedCaption(val name: String, val modifiedMs: Long, val sizeBytes: Long, val uri: android.net.Uri?, val file: java.io.File?)
