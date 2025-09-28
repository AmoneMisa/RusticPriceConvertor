package com.example.rusticpriceconvertor

import android.content.Context
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import java.util.Locale

data class LangItem(val persistCode: String, val displayCode: String, val labelRes: Int)

class LangAdapter(
    ctx: Context,
    private val items: List<LangItem>
) : ArrayAdapter<LangItem>(ctx, android.R.layout.simple_spinner_item, items) {

    init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = super.getView(position, convertView, parent) as TextView
        v.text = items[position].displayCode      // «RU / UA / EN / RO» в закрытом виде
        return v
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = super.getDropDownView(position, convertView, parent) as TextView
        v.text = context.getString(items[position].labelRes) // «Русский / Українська / …»
        return v
    }
}

object LanguageUtil {
    fun applyLocale(base: Context, lang: String): Context {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val cfg = base.resources.configuration
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }
}

public fun detectDeviceLanguage(): String? = when (Locale.getDefault().language.lowercase()) {
    "ru" -> "ru"
    "uk" -> "uk"           // украинский
    "ro" -> "ro"
    "en" -> "en"
    else -> null
}

public fun detectLanguageByGeo(ctx: Context): String {
    // «по стране» → «язык интерфейса»
    // простая карта, чтобы не тянуть базы:
    val country = try {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        (tm.networkCountryIso ?: tm.simCountryIso)?.uppercase(Locale.US)
            ?: Locale.getDefault().country
    } catch (_: Exception) {
        Locale.getDefault().country
    }

    return when (country) {
        "UA" -> "uk"
        "RU", "BY", "KZ", "KG" -> "ru"
        "RO", "MD" -> "ro"
        else -> "en"
    }
}