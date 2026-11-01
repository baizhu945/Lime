package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

data class FrostedGlassConfig(
    val enabled: Boolean = false,
    val blurRadiusDp: Float = 10f,
    val backgroundOpacity: Float = 0.8f,
    val keyOpacity: Float = 0.2f,
) {
    fun normalized(defaults: FrostedGlassConfig = FrostedGlassConfig.defaults(true)): FrostedGlassConfig = copy(
        blurRadiusDp = (blurRadiusDp.takeIf { it.isFinite() } ?: defaults.blurRadiusDp).coerceIn(0f, 40f),
        backgroundOpacity = (backgroundOpacity.takeIf { it.isFinite() } ?: defaults.backgroundOpacity).coerceIn(0f, 1f),
        keyOpacity = (keyOpacity.takeIf { it.isFinite() } ?: defaults.keyOpacity).coerceIn(0f, 1f),
    )

    companion object {
        fun defaults(isDark: Boolean, enabled: Boolean = false) = FrostedGlassConfig(
            enabled = enabled, blurRadiusDp = 10f,
            backgroundOpacity = if (isDark) 0.8f else 0.2f,
            keyOpacity = if (isDark) 0.2f else 0.8f,
        )
    }
}

data class FrostedGlassProfiles(val light: FrostedGlassConfig, val dark: FrostedGlassConfig) {
    fun forAppearance(isDark: Boolean): FrostedGlassConfig = if (isDark) dark else light
    fun withEnabled(enabled: Boolean) = copy(light = light.copy(enabled = enabled), dark = dark.copy(enabled = enabled))

    /** The two opacity values are independent controls; their roles swap with appearance. */
    fun withAppearance(config: FrostedGlassConfig, isDark: Boolean): FrostedGlassProfiles {
        val current = config.normalized(FrostedGlassConfig.defaults(isDark))
        val opposite = forAppearance(!isDark).copy(enabled = current.enabled,
            backgroundOpacity = current.keyOpacity, keyOpacity = current.backgroundOpacity)
        return if (isDark) FrostedGlassProfiles(opposite, current) else FrostedGlassProfiles(current, opposite)
    }
}

/** One material switch, one opacity pair with swapped light/dark roles; blur remains per appearance. */
object FrostedGlassPreferences {
    const val KEY_ENABLED = "frosted_glass_enabled"
    const val KEY_BLUR_RADIUS = "frosted_glass_blur_radius_dp"
    const val KEY_BACKGROUND_OPACITY = "frosted_glass_background_opacity"
    const val KEY_KEY_OPACITY = "frosted_glass_key_opacity"
    private const val KEY_APPEARANCE_MIGRATED = "frosted_glass_appearance_migrated"
    private const val KEY_PAIRED_DARK_BACKGROUND = "frosted_glass_paired_dark_background_opacity"
    private const val KEY_PAIRED_DARK_KEY = "frosted_glass_paired_dark_key_opacity"
    private val parameterKeys = listOf(KEY_BLUR_RADIUS, KEY_BACKGROUND_OPACITY, KEY_KEY_OPACITY)
    private fun appearanceKey(key: String, dark: Boolean) = key + if (dark) "_dark" else "_light"

    val keys: Set<String> = (parameterKeys + parameterKeys.map { appearanceKey(it, true) } +
        parameterKeys.map { appearanceKey(it, false) } +
        listOf(KEY_ENABLED, KEY_APPEARANCE_MIGRATED, KEY_PAIRED_DARK_BACKGROUND, KEY_PAIRED_DARK_KEY,
            "keyboard_theme", "dark_mode")).toSet()

    fun isDark(context: Context): Boolean = when (SettingsPreferences.getEffectiveDarkMode(context)) {
        0 -> false
        2 -> runCatching {
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }.getOrDefault(false)
        else -> true
    }

    fun read(context: Context, isDark: Boolean = FrostedGlassPreferences.isDark(context)): FrostedGlassConfig =
        readProfiles(context).forAppearance(isDark)

    fun readProfiles(context: Context): FrostedGlassProfiles {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        migrateLegacy(context, prefs)
        migrateOpacityPair(context, prefs)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val darkDefaults = FrostedGlassConfig.defaults(true)
        val paired = FrostedGlassConfig(
            backgroundOpacity = prefs.getFloat(KEY_PAIRED_DARK_BACKGROUND, darkDefaults.backgroundOpacity),
            keyOpacity = prefs.getFloat(KEY_PAIRED_DARK_KEY, darkDefaults.keyOpacity),
        ).normalized(darkDefaults)
        fun readAppearance(dark: Boolean): FrostedGlassConfig {
            val defaults = FrostedGlassConfig.defaults(dark, enabled)
            return FrostedGlassConfig(
                enabled,
                prefs.getFloat(appearanceKey(KEY_BLUR_RADIUS, dark), defaults.blurRadiusDp),
                if (dark) paired.backgroundOpacity else paired.keyOpacity,
                if (dark) paired.keyOpacity else paired.backgroundOpacity,
            ).normalized(defaults)
        }
        return FrostedGlassProfiles(readAppearance(false), readAppearance(true))
    }

    private fun migrateLegacy(context: Context, prefs: SharedPreferences) {
        if (prefs.getBoolean(KEY_APPEARANCE_MIGRATED, false) || parameterKeys.none(prefs::contains)) return
        // Keep the original keys as a recoverable record. A known dark preset must
        // never seed the light appearance, even when first read from a light screen.
        val legacy = FrostedGlassConfig(
            blurRadiusDp = prefs.getFloat(KEY_BLUR_RADIUS, 24f),
            backgroundOpacity = prefs.getFloat(KEY_BACKGROUND_OPACITY, 0.55f),
            keyOpacity = prefs.getFloat(KEY_KEY_OPACITY, 0.22f),
        )
        val targetDark = legacy == FrostedGlassConfig.defaults(true) || isDark(context)
        val editor = prefs.edit().putBoolean(KEY_APPEARANCE_MIGRATED, true)
        if (parameterKeys.none { prefs.contains(appearanceKey(it, targetDark)) }) {
            writeAppearance(editor, legacy.normalized(FrostedGlassConfig.defaults(targetDark)), targetDark)
        }
        editor.apply()
    }

    private fun migrateOpacityPair(context: Context, prefs: SharedPreferences) {
        if (prefs.contains(KEY_PAIRED_DARK_BACKGROUND) && prefs.contains(KEY_PAIRED_DARK_KEY)) return
        fun hasOpacity(dark: Boolean) = prefs.contains(appearanceKey(KEY_BACKGROUND_OPACITY, dark)) ||
            prefs.contains(appearanceKey(KEY_KEY_OPACITY, dark))
        if (!hasOpacity(true) && !hasOpacity(false)) return
        // Preserve the currently rendered old pair. If it was never customized,
        // use the other appearance's saved pair; keep all old keys as a recovery record.
        val currentDark = isDark(context)
        val sourceDark = if (hasOpacity(currentDark)) currentDark else !currentDark
        val defaults = FrostedGlassConfig.defaults(sourceDark)
        val source = FrostedGlassConfig(
            backgroundOpacity = prefs.getFloat(appearanceKey(KEY_BACKGROUND_OPACITY, sourceDark), defaults.backgroundOpacity),
            keyOpacity = prefs.getFloat(appearanceKey(KEY_KEY_OPACITY, sourceDark), defaults.keyOpacity),
        ).normalized(defaults)
        val editor = prefs.edit()
        if (!prefs.contains(KEY_PAIRED_DARK_BACKGROUND)) editor.putFloat(KEY_PAIRED_DARK_BACKGROUND,
            if (sourceDark) source.backgroundOpacity else source.keyOpacity)
        if (!prefs.contains(KEY_PAIRED_DARK_KEY)) editor.putFloat(KEY_PAIRED_DARK_KEY,
            if (sourceDark) source.keyOpacity else source.backgroundOpacity)
        editor.apply()
    }

    fun save(context: Context, config: FrostedGlassConfig, isDark: Boolean = FrostedGlassPreferences.isDark(context)) {
        val previous = readProfiles(context)
        saveProfiles(context, previous.withAppearance(config, isDark))
    }

    fun saveProfiles(context: Context, profiles: FrostedGlassProfiles) {
        val enabled = profiles.dark.enabled
        val editor = SettingsPreferences.getPrefsPublic(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean(KEY_APPEARANCE_MIGRATED, true)
        val normalized = profiles.withAppearance(profiles.dark, true)
        editor.putFloat(KEY_PAIRED_DARK_BACKGROUND, normalized.dark.backgroundOpacity)
            .putFloat(KEY_PAIRED_DARK_KEY, normalized.dark.keyOpacity)
        writeAppearance(editor, normalized.dark, true)
        writeAppearance(editor, normalized.light.normalized(FrostedGlassConfig.defaults(false)), false)
        editor.apply()
    }

    private fun writeAppearance(editor: SharedPreferences.Editor, config: FrostedGlassConfig, dark: Boolean) {
        editor.putFloat(appearanceKey(KEY_BLUR_RADIUS, dark), config.blurRadiusDp)
            .putFloat(appearanceKey(KEY_BACKGROUND_OPACITY, dark), config.backgroundOpacity)
            .putFloat(appearanceKey(KEY_KEY_OPACITY, dark), config.keyOpacity)
    }
}
