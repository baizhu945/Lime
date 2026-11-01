package com.kingzcheung.xime.settings

import android.content.Context
import kotlinx.coroutines.launch

object LanguagePreferences {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    const val KEY = "enabled_input_languages"
    /** 整合版默认带日语：只能做一次，用户之后关掉了不再打开。 */
    private const val JAPANESE_DEFAULT_ADDED = "japanese_language_added_v1"
    fun enabled(context: Context): Set<InputLanguage> {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val stored = prefs.getStringSet(KEY, null)
        // Imported profiles without language metadata remain usable, without pretending to be Chinese.
        return (if (stored == null) setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH)
        else InputLanguage.entries.filterTo(mutableSetOf()) { it.id in stored } + InputLanguage.ENGLISH) + InputLanguage.UNSPECIFIED
    }
    fun initialize(context: Context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        // 默认启用日语，语言键才能按「中 → 英 → 日 → 中」循环；只补一次，尊重用户之后的关闭。
        if (!prefs.getBoolean(JAPANESE_DEFAULT_ADDED, false)) {
            val current = enabled(context)
            if (InputLanguage.JAPANESE !in current) {
                InputModes.moveLanguageLast(context, InputLanguage.JAPANESE)
                prefs.edit().putStringSet(KEY, (current + InputLanguage.JAPANESE).map { it.id }.toSet())
                    .putBoolean(JAPANESE_DEFAULT_ADDED, true).commit()
                runCatching {
                    val schemas = SchemaManager.getEnabledSchemas(context)
                    SchemaManager.setEnabledSchemas(context, (schemas + JapaneseSchemas.ids).distinct())
                }
            } else {
                prefs.edit().putBoolean(JAPANESE_DEFAULT_ADDED, true).commit()
            }
        }
        if (prefs.getStringSet(KEY, null)?.contains(InputLanguage.ENGLISH.id) != true) {
            prefs.edit().putStringSet(KEY, enabled(context).map { it.id }.toSet()).commit()
        }
    }
    /**
     * Turning a schema on in 方案管理 (or installing it from the market) also turns its language on: otherwise a
     * freshly deployed Japanese schema can never be reached, because the language key only cycles enabled
     * languages and switching to a schema of a disabled language is ignored (2026-10-19, tablet after 清除数据).
     */
    internal fun withSchemaLanguage(enabled: Set<InputLanguage>, schemaId: String): Set<InputLanguage> {
        val language = InputLanguage.forSchema(schemaId)
        return if (language in InputLanguage.supported && language !in enabled) enabled + language else enabled
    }

    /** Enable the schema's language without touching the schema list (the caller already changed it). */
    fun enableForSchema(context: Context, schemaId: String) {
        val current = enabled(context)
        val next = withSchemaLanguage(current, schemaId)
        if (next == current) return
        (next - current).forEach { InputModes.moveLanguageLast(context, it) }
        SettingsPreferences.getPrefsPublic(context).edit().putStringSet(KEY, next.map { it.id }.toSet()).commit()
    }

    /** The engine needs a real schema even when English is the only visible language. */
    internal fun nativeSchema(saved: String, available: List<String>, languages: Set<InputLanguage>): String =
        saved.takeIf { it in available && it != InputModes.ENGLISH && InputLanguage.forSchema(it) in languages }
            ?: available.firstOrNull { it.isNotBlank() && it != InputModes.ENGLISH && InputLanguage.forSchema(it) in languages }
            ?: available.firstOrNull { it.isNotBlank() && it != InputModes.ENGLISH }
            ?: "rime_ice"

    /** English always remains available; language switches never delete dictionaries. */
    fun save(context: Context, language: InputLanguage, enabled: Boolean) {
        require(language != InputLanguage.ENGLISH && language != InputLanguage.UNSPECIFIED)
        val before = this.enabled(context)
        val next = before.toMutableSet().apply { if (enabled) add(language) else remove(language) }
        if (enabled && language !in before) InputModes.moveLanguageLast(context, language)
        // Bundled languages bring their own schemas; others only show what is already installed.
        val bundled = when (language) {
            InputLanguage.JAPANESE -> JapaneseSchemas.ids
            InputLanguage.SPANISH -> SpanishSchemas.ids
            else -> null
        }
        if (bundled != null) {
            val schemas = SchemaManager.getEnabledSchemas(context)
            SchemaManager.setEnabledSchemas(context, if (enabled) (schemas + bundled).distinct()
                else schemas.filterNot { InputLanguage.forSchema(it) == language })
        }
        // The English entry is virtual. Keep a real Rime schema as the engine fallback.
        val current = SettingsPreferences.getCurrentSchema(context)
        if (current == InputModes.ENGLISH || InputLanguage.forSchema(current) !in next) {
            val fallback = nativeSchema(current, SchemaManager.getEnabledSchemas(context), next)
            SettingsPreferences.setCurrentSchema(context, fallback)
        }
        SettingsPreferences.setDeploymentDone(context, false)
        SettingsPreferences.getPrefsPublic(context).edit().putStringSet(KEY, next.map { it.id }.toSet()).commit()
        scope.launch { com.kingzcheung.xime.rime.RimeConfigHelper.prepareAutomatically(context.applicationContext) }
    }
}
