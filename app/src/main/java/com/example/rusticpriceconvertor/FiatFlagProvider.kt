import com.example.rusticpriceconvertor.R
import java.util.Locale

object FiatFlagProvider {
    private val overrides = mapOf(
        "EUR" to R.drawable.flag_eu,
    )

    @Volatile
    private var currencyToFlag: Map<String, Int> = emptyMap()
    private const val TAG = "FiatFlags"

    fun buildIndex(debug: Boolean = false) {
        fun log(msg: String) {
            if (debug) android.util.Log.d(TAG, msg)
        }

        log("buildIndex() start")

        val countries = try {
            com.blongho.country_data.World.getAllCountries()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "World.getAllCountries() FAILED: ${t.message}", t)
            return
        }

        log("Countries count = ${countries.size}")
        if (countries.isEmpty()) return

        val out = HashMap<String, Int>(512)
        var withFlag = 0
        var withCurrency = 0
        var pairs = 0

        if (debug) {
            val c = countries.first()
            log("Sample class = ${c.javaClass.name}")
            log(
                "Sample methods = " + c.javaClass.methods.map { it.name }.distinct().sorted()
                    .joinToString()
            )
            log(
                "Sample fields  = " + c.javaClass.declaredFields.map { it.name }.distinct().sorted()
                    .joinToString()
            )
        }

        for (country in countries) {
            val flag = readFlagRes(country)
            if (flag != null && flag != 0) withFlag++

            val currencyCodes = readCurrencyCodes(country)
            if (currencyCodes.isNotEmpty()) withCurrency++

            if (flag == null || flag == 0 || currencyCodes.isEmpty()) continue

            for (cc in currencyCodes) {
                out[cc] = flag
                pairs++
            }
        }

        overrides.forEach { (k, v) -> out[k] = v }
        currencyToFlag = out

        log("Build done: out.size=${out.size}, pairs=$pairs, withFlag=$withFlag/${countries.size}, withCurrency=$withCurrency/${countries.size}")
        if (debug) log("Example keys: " + out.keys.take(20).joinToString())

        if (debug) {
            listOf("USD", "UAH", "MDL", "EUR").forEach { code ->
                log("flagRes($code) = ${flagRes(code)}")
            }
        }
    }

    fun flagRes(codeRaw: String): Int? {
        val code = codeRaw.uppercase(Locale.US)
        overrides[code]?.let { return it }
        return currencyToFlag[code]
    }

    private fun readFlagRes(country: Any): Int? {
        val v =
            country.callInt("getFlagResource")
                ?: country.callInt("getFlagRes")
                ?: country.callInt("getFlag")
                ?: country.readIntField("flagResource")
                ?: country.readIntField("flagRes")
                ?: country.readIntField("flag")
        return v
    }

    private fun readCurrencyCodes(country: Any): Set<String> {
        val out = LinkedHashSet<String>()

        country.callAny("getCurrency")?.let { curObj ->
            extractCurrencyCode(curObj)?.let { out.add(it) }
        }

        val list =
            country.callAny("getCurrencies")
                ?: country.readAnyField("currencies")

        when (list) {
            is Iterable<*> -> for (it in list) if (it != null) extractCurrencyCode(it)?.let(out::add)
        }

        (country.callAny("getCurrencyCode") as? String)?.let(out::add)
        (country.readAnyField("currencyCode") as? String)?.let(out::add)
        (country.readAnyField("currency") as? String)?.let(out::add)

        return out
            .filter { it.isNotBlank() }
            .map { it.uppercase(Locale.US) }
            .toSet()
    }

    private fun extractCurrencyCode(curObj: Any): String? {
        return (curObj.callAny("getCode") as? String)
            ?: (curObj.callAny("getCurrencyCode") as? String)
            ?: (curObj.readAnyField("code") as? String)
            ?: (curObj.readAnyField("currencyCode") as? String)
    }

    private fun Any.callAny(method: String): Any? =
        runCatching { this.javaClass.getMethod(method).invoke(this) }.getOrNull()

    private fun Any.callInt(method: String): Int? =
        (this.callAny(method) as? Number)?.toInt()

    private fun Any.readAnyField(name: String): Any? =
        runCatching {
            this.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
        }.getOrNull()

    private fun Any.readIntField(name: String): Int? =
        (this.readAnyField(name) as? Number)?.toInt()
}
