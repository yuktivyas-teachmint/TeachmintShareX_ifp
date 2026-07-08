package com.teachmint.sharex.language

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import java.util.Locale

/**
 * Persists the user-selected app language and applies it to a [Context].
 *
 * Same mechanism as the whiteboard app: the choice is stored in
 * SharedPreferences, every Context (Application + Activity) is wrapped via
 * [wrap] in attachBaseContext, and the activity is recreated after a change so
 * all string resources re-resolve in the new locale.
 */
object LocaleManager {
    private const val PREFS_NAME = "sharex_language"
    private const val KEY_LANG_ID = "app_lang_id"
    private const val KEY_LANG_COUNTRY = "app_lang_country"

    fun getAppLanguage(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AppLanguage.fromLangId(prefs.getString(KEY_LANG_ID, AppLanguage.ENGLISH.langId))
    }

    fun setAppLanguage(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG_ID, language.langId)
            .putString(KEY_LANG_COUNTRY, language.countryCode)
            .commit()
        Locale.setDefault(language.toLocale())
    }

    fun wrap(context: Context): Context {
        val locale = getAppLanguage(context).toLocale()
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    private fun AppLanguage.toLocale(): Locale =
        if (countryCode.isEmpty()) Locale(langId) else Locale(langId, countryCode)
}

/** Unwraps a Compose LocalContext back to its owning Activity (for recreate()). */
fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
