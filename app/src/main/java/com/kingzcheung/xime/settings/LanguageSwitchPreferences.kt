package com.kingzcheung.xime.settings

import android.content.Context

internal enum class LanguageSwitchMode(val id: String) {
    CURRENT_ENGLISH("current_english"), CYCLE("cycle"), SPECIFIC_ENGLISH("specific_english")
}

internal data class LanguageSwitchOptions(
    val mode: LanguageSwitchMode = LanguageSwitchMode.CURRENT_ENGLISH,
    val language: InputLanguage? = null,
)

/** Explicit language-key settings. Ordinary language selection never modifies these preferences. */
internal object LanguageSwitchPreferences {
    const val MODE_KEY = "language_key_switch_mode"
    const val LANGUAGE_KEY = "language_key_specific_language"

    fun read(context: Context): LanguageSwitchOptions {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val stored = LanguageSwitchMode.entries.firstOrNull { it.id == prefs.getString(MODE_KEY, null) }
        val mode = stored ?: rememberDefaultMode(context)
        return LanguageSwitchOptions(
            mode,
            InputLanguage.fromId(prefs.getString(LANGUAGE_KEY, null).orEmpty())?.takeUnless { it == InputLanguage.ENGLISH },
        )
    }

    /**
     * 未显式设置时的默认行为：只启用中、英时保持原来的“中英互切”；
     * 启用了第二种非英语语言（例如日语）就改为在已启用语言间循环，否则地球键永远到不了日语。
     */
    private fun rememberDefaultMode(context: Context): LanguageSwitchMode {
        val native = LanguagePreferences.enabled(context)
            .count { it != InputLanguage.ENGLISH && it != InputLanguage.UNSPECIFIED }
        return if (native >= 2) LanguageSwitchMode.CYCLE else LanguageSwitchMode.CURRENT_ENGLISH
    }

    fun save(context: Context, options: LanguageSwitchOptions) {
        SettingsPreferences.getPrefsPublic(context).edit().putString(MODE_KEY, options.mode.id)
            .putString(LANGUAGE_KEY, options.language?.id).apply()
    }

    /**
     * 「指定语言与英文切换」列出的语言：每种已支持的非英文语言都列出（中文、日语、西班牙语……），按语言顺序，
     * 没在顺序里的（未启用的）排在后面。哪些此刻能选由设置页按启用和方案就绪另外判断，不在这里漏掉语言。
     */
    fun specificChoices(order: List<InputLanguage>): List<InputLanguage> = (order + InputLanguage.supported).distinct()
        .filter { it in InputLanguage.supported && it != InputLanguage.ENGLISH && it != InputLanguage.UNSPECIFIED }

    /** Hardware shortcuts have fixed meanings without changing the user's language-key choice. */
    fun forRequest(configured: LanguageSwitchOptions, overrideMode: LanguageSwitchMode?): LanguageSwitchOptions =
        overrideMode?.let { LanguageSwitchOptions(it) } ?: configured

    fun target(current: InputLanguage, nativeFallback: InputLanguage, options: LanguageSwitchOptions,
        languageOrder: List<InputLanguage>, available: Set<InputLanguage>): InputLanguage? {
        val ordered = (languageOrder + available).distinct().filter { it in available }
        return when (options.mode) {
            LanguageSwitchMode.CURRENT_ENGLISH ->
                (if (current == InputLanguage.ENGLISH) nativeFallback else InputLanguage.ENGLISH)
                    .takeIf { it in available && it != current }
            LanguageSwitchMode.CYCLE -> {
                if (ordered.isEmpty()) null else ordered[(ordered.indexOf(current) + 1) % ordered.size]
                    .takeUnless { it == current }
            }
            LanguageSwitchMode.SPECIFIC_ENGLISH -> {
                fun native(language: InputLanguage) = language != InputLanguage.ENGLISH && language in available
                val selected = options.language?.takeIf(::native) ?: nativeFallback.takeIf(::native)
                    ?: ordered.firstOrNull(::native)
                if (selected == null) null
                else (if (current == selected) InputLanguage.ENGLISH else selected).takeIf { it in available }
            }
        }
    }
}
