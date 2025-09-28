package com.example.rusticpriceconvertor

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.time.Duration
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.O)
object CurrencyApi {
    private const val TAG = "CurrencyApi"

    // --- Основные базы (npm-CDN + 2 зеркала GitHub) ---
    // npm CDN (самый стабильный для этого API)
    private const val NPM_BASE = "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api"
    // репо на GitHub (иногда отличается структурой, но в /v1/* совпадает)
    private const val GH_BASE  = "https://raw.githubusercontent.com/fawazahmed0/exchange-api/main"
    private const val STAT_BASE = "https://cdn.staticaly.com/gh/fawazahmed0/exchange-api@latest"

    private val gson = Gson()
    private val client: OkHttpClient by lazy {
        val log = HttpLoggingInterceptor { m -> Log.d(TAG, m) }
        log.level = HttpLoggingInterceptor.Level.BASIC
        OkHttpClient.Builder()
            .addInterceptor(log)
            .connectTimeout(Duration.ofSeconds(8))
            .readTimeout(Duration.ofSeconds(8))
            .retryOnConnectionFailure(true)
            .build()
    }

    // Сборка набора URL для запроса (с фолбэками)
    private fun urlsFor(pathV1: String, date: String = "latest"): List<String> = listOf(
        // npm (дата идёт сразу после пакета)
        "$NPM_BASE@$date/v1/$pathV1",
        // GitHub (в репо структура v1/latest/…)
        "$GH_BASE/v1/${
            if (date == "latest") "latest/$pathV1" else "$date/$pathV1"
        }",
        // Staticaly — то же, что GitHub latest
        "$STAT_BASE/v1/${
            if (date == "latest") "latest/$pathV1" else "$date/$pathV1"
        }"
    )

    private fun httpGetFirstOk(candidates: List<String>): String? {
        for (u in candidates) {
            Log.d(TAG, "GET $u")
            try {
                client.newCall(Request.Builder().url(u).build()).execute().use { r: Response ->
                    if (r.isSuccessful) {
                        val body = r.body?.string()
                        if (!body.isNullOrEmpty()) {
                            Log.i(TAG, "OK ${r.code} from $u")
                            return body
                        }
                        Log.w(TAG, "Empty body from $u")
                    } else {
                        Log.w(TAG, "HTTP ${r.code} from $u")
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Fail $u: ${e.message}")
            }
        }
        return null
    }

    /** Получить словарь код→человекочитаемое имя валюты */
    fun fetchSymbols(date: String = "latest"): Map<String, String> {
        val body = httpGetFirstOk(urlsFor("currencies.json", date)) ?: return emptyMap()
        val json = gson.fromJson(body, JsonObject::class.java)
        return json.entrySet().associate { (k, v) ->
            k.uppercase(Locale.US) to (v.asString ?: k.uppercase(Locale.US))
        }
    }

    /**
     * Получить курсы из base к targets.
     * date: "latest" или "YYYY-MM-DD" (есть дневные снапшоты).
     */
    fun fetchRates(base: String, targets: List<String>, date: String = "latest"): Map<String, Double> {
        if (base.isBlank()) return emptyMap()
        val b = base.lowercase(Locale.US)

        // форматы:
        // npm:   /v1/currencies/{base}.json  (дату кладём в версии пакета)
        // github: /v1/latest/currencies/{base}.json
        val body = httpGetFirstOk(urlsFor("currencies/$b.json", date)) ?: return emptyMap()

        val root = gson.fromJson(body, JsonObject::class.java)
        val obj = root.getAsJsonObject(b) ?: return emptyMap()

        val wanted = targets.map { it.lowercase(Locale.US) }.toSet().ifEmpty { obj.keySet() }
        val out = LinkedHashMap<String, Double>(wanted.size)
        for (code in wanted) {
            val el = obj.get(code) ?: continue
            out[code.uppercase(Locale.US)] = el.asDouble
        }
        return out
    }
}