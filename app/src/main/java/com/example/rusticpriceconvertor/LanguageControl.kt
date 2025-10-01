package com.example.rusticpriceconvertor

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.telephony.TelephonyManager
import android.view.View
import android.widget.AdapterView
import android.widget.Spinner
import androidx.core.content.edit
import java.util.Locale
class LanguageControl(
    private val activity: Activity,
    private val prefs: SharedPreferences
) {

    private val langs = listOf(
        LangItem("ru", "RU", R.string.language_ru),
        LangItem("uk", "UA", R.string.language_uk),
        LangItem("en", "EN", R.string.language_en),
        LangItem("ro", "RO", R.string.language_ro),
    )

    fun setupLanguageSpinner(spinner: Spinner) {
        spinner.adapter = LangAdapter(activity, langs)

        val savedLang = prefs.getString("app_lang", null)
            ?: detectDeviceLanguage()
            ?: detectLanguageByGeo(activity)
            ?: "en"

        spinner.setSelection(langs.indexOfFirst { it.persistCode == savedLang }.coerceAtLeast(0), false)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val selectedLangCode = langs[pos].persistCode
                val currentLang = prefs.getString("app_lang", null)
                if (selectedLangCode == currentLang) return

                prefs.edit { putString("app_lang", selectedLangCode) }
                LocaleUtil.applyLocale(activity, selectedLangCode)
                activity.recreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun detectDeviceLanguage(): String? {
        return Locale.getDefault().language.let { lang ->
            langs.firstOrNull { it.persistCode == lang }?.persistCode
        }
    }

    private fun detectLanguageByGeo(context: Context): String? {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val networkCountryIso = telephonyManager.networkCountryIso?.lowercase(Locale.getDefault())
            return when (networkCountryIso) {
                "ru" -> "ru"
                "ua" -> "uk"
                "ro", "md" -> "ro"
                else -> null
            }
        } catch (_: Exception) {
            return null
        }
    }

    companion object {
        const val PREFS_NAME = "language_prefs"
        const val KEY_APP_LANG = "app_lang"

        fun getSavedLanguage(prefs: SharedPreferences): String {
            return prefs.getString(KEY_APP_LANG, null)
                ?: detectDeviceLanguageGlobal()
                ?: "en"
        }

        private fun detectDeviceLanguageGlobal(): String? {
            return Locale.getDefault().language.let { lang ->
                listOf("ru", "uk", "en", "ro").firstOrNull { it == lang }
            }
        }
    }
}
