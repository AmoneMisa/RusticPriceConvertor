package com.example.rusticpriceconvertor

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleUtil {
    private fun buildLocale(lang: String): Locale {
        return when (lang) {
            "ru" -> Locale.Builder().setLanguage("ru").setRegion("RU").build()
            "uk" -> Locale.Builder().setLanguage("uk").setRegion("UA").build()
            "ro" -> Locale.Builder().setLanguage("ro").setRegion("RO").build()
            "kk" -> Locale.Builder().setLanguage("kk").setRegion("KZ").build()
            "uz" -> Locale.Builder().setLanguage("uz").setRegion("UZ").build()
            else -> Locale.Builder().setLanguage("en").setRegion("US").build()
        }
    }

    fun applyLocale(activity: Activity, languageCode: String) {
        val locale = buildLocale(languageCode)
        Locale.setDefault(locale)
        val resources = activity.resources
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    fun updateBaseContextLocale(context: Context): Context {
        val prefs = context.getSharedPreferences(LanguageControl.PREFS_NAME, Context.MODE_PRIVATE)
        val languageCode = LanguageControl.getSavedLanguage(prefs)
        val locale = buildLocale(languageCode)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }
}
