import com.example.rusticpriceconvertor.R
import java.util.Locale

object CurrencyIconProvider {
    sealed class IconRef {
        data class Res(val resId: Int) : IconRef()
        data class Url(val url: String) : IconRef()
        data class Text(val text: String) : IconRef()
    }

    @Volatile private var fiatCodes: Set<String> = emptySet()
    @Volatile private var cryptoIconUrls: Map<String, String> = emptyMap()

    fun updateFiatCodes(symbols: Map<String, String>) {
        fiatCodes = symbols.keys.map { it.uppercase(Locale.US) }.toSet()
    }

    fun updateCryptoIconUrls(map: Map<String, String>) {
        cryptoIconUrls = map.mapKeys { it.key.uppercase(Locale.US) }
    }

    fun resolve(codeRaw: String): IconRef {
        val code = codeRaw.uppercase(Locale.US)

        if (code == "EUR") return IconRef.Res(R.drawable.flag_eu)

        if (fiatCodes.contains(code)) {
            FiatFlagProvider.flagRes(code)?.let { return IconRef.Res(it) }
            return IconRef.Text(code)
        }

        cryptoIconUrls[code]?.let { return IconRef.Url(it) }

        return IconRef.Text(code)
    }
}
