package com.example.rusticpriceconvertor

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.content.edit

object CurrencyCache {
    private const val PREFS = "currency_cache"
    private const val KEY_SYMBOLS = "symbols_json"
    private const val KEY_RATES_PREFIX = "rates_json_" // + base

    private val gson = Gson()

    fun saveSymbols(ctx: Context, symbols: Map<String, String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_SYMBOLS, gson.toJson(symbols))
            }
    }

    fun loadSymbols(ctx: Context): Map<String, String> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SYMBOLS, null)
        if (s.isNullOrEmpty()) return emptyMap()
        val type = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson(s, type) ?: emptyMap()
    }

    fun saveRates(ctx: Context, base: String, rates: Map<String, Double>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_RATES_PREFIX + base, gson.toJson(rates))
            }
    }

    fun loadRates(ctx: Context, base: String): Map<String, Double> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RATES_PREFIX + base, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, Double>>() {}.type
        return gson.fromJson(s, type) ?: emptyMap()
    }
}
