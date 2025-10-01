package com.example.rusticpriceconvertor

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleUtil {
    fun applyLocale(activity: Activity, languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val resources = activity.resources
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    fun updateBaseContextLocale(context: Context): Context {
        val prefs = context.getSharedPreferences(LanguageControl.PREFS_NAME, Context.MODE_PRIVATE)
        val languageCode = LanguageControl.getSavedLanguage(prefs)
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }
}
