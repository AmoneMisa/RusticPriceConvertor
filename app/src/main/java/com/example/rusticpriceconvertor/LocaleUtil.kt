package com.example.rusticpriceconvertor

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleUtil {
    fun applyLocale(base: Context, lang: String?): Context {
        val code = when (lang) {
            "ru","ua","en","ro" -> lang
            else -> return base // системный язык
        }
        val locale = when (code) {
            "ru" -> Locale("ru")
            "ua" -> Locale("ua")
            "en" -> Locale.ENGLISH
            "ro" -> Locale("ro")
            else -> Locale.getDefault()
        }
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }
}
