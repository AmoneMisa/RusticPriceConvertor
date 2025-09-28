package com.example.rusticpriceconvertor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // --------- UI ----------
    private lateinit var priceInput: EditText
    private lateinit var quantityInput: EditText
    private lateinit var quantityTypeSpinner: Spinner

    private lateinit var baseCurrencySpinner: Spinner
    private lateinit var selectCurrenciesButton: Button

    private lateinit var priceUnitRow: LinearLayout
    private lateinit var priceUnitSpinner: Spinner

    // Штучно
    private lateinit var modePiece: LinearLayout
    private lateinit var piecePriceLabel: TextView
    private lateinit var pieceCountLabel: TextView
    private lateinit var pieceConvertedPerItem: TextView
    private lateinit var pieceConvertedTotal: TextView

    // Вес/объём
    private lateinit var modeWeight: LinearLayout
    private lateinit var pricePerUnitLabel: TextView
    private lateinit var takenAmountLabel: TextView
    private lateinit var costPerBaseUnitLabel: TextView
    private lateinit var convertedPerUnitLabel: TextView
    private lateinit var convertedPerBaseUnitLabel: TextView
    private lateinit var convertedTotalLabel: TextView

    // --------- Данные валют ----------
    private var symbolNames: Map<String, String> = emptyMap()  // code -> name
    private var allSymbols: List<String> = emptyList()
    private var selectedSymbols: MutableList<String> = mutableListOf("USD", "EUR")
    private var rates: Map<String, Double> = emptyMap()

    // единые prefs (без дублей)
    private val prefs by lazy { getSharedPreferences("currency_prefs", MODE_PRIVATE) }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()

        // пока не загрузим список валют — вырубим элементы
        baseCurrencySpinner.isEnabled = false
        selectCurrenciesButton.isEnabled = false

        setupUnitSpinner()
        attachRecalcListeners()
        setupCurrencyUi()
        recalc()
    }

    private fun bindViews() {
        priceInput = findViewById(R.id.priceInput)
        quantityInput = findViewById(R.id.quantityInput)
        quantityTypeSpinner = findViewById(R.id.quantityTypeSpinner)

        baseCurrencySpinner = findViewById(R.id.baseCurrencySpinner)
        selectCurrenciesButton = findViewById(R.id.selectCurrenciesButton)

        priceUnitRow = findViewById(R.id.priceUnitRow)
        priceUnitSpinner = findViewById(R.id.priceUnitSpinner)

        modePiece = findViewById(R.id.modePiece)
        piecePriceLabel = findViewById(R.id.piecePriceLabel)
        pieceCountLabel = findViewById(R.id.pieceCountLabel)
        pieceConvertedPerItem = findViewById(R.id.pieceConvertedPerItem)
        pieceConvertedTotal = findViewById(R.id.pieceConvertedTotal)

        modeWeight = findViewById(R.id.modeWeight)
        pricePerUnitLabel = findViewById(R.id.pricePerUnitLabel)
        takenAmountLabel = findViewById(R.id.takenAmountLabel)
        costPerBaseUnitLabel = findViewById(R.id.costPerBaseUnitLabel)
        convertedPerUnitLabel = findViewById(R.id.convertedPerUnitLabel)
        convertedPerBaseUnitLabel = findViewById(R.id.convertedPerBaseUnitLabel)
        convertedTotalLabel = findViewById(R.id.convertedTotalLabel)
    }

    // ========= Валюты: загрузка + привязка =========
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun setupCurrencyUi() {
        lifecycleScope.launch {
            // 1) мгновенно — из кэша
            val cached = CurrencyCache.loadSymbols(this@MainActivity)
            if (cached.isNotEmpty()) {
                symbolNames = cached
                allSymbols = cached.keys.sorted()
                inflateBaseSpinner(allSymbols)
            }

            // 2) онлайн — обновим кэш и UI
            val fresh = withContext(Dispatchers.IO) { CurrencyApi.fetchSymbols() } // Map<code,name>
            if (fresh.isNotEmpty()) {
                CurrencyCache.saveSymbols(this@MainActivity, fresh)
                symbolNames = fresh
                allSymbols = fresh.keys.sorted()
                inflateBaseSpinner(allSymbols)
            } else if (cached.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "Не удалось загрузить список валют",
                    Toast.LENGTH_LONG
                ).show()
            }

            selectCurrenciesButton.setOnClickListener { openSecondaryCurrenciesDialog() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun inflateBaseSpinner(list: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, list)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        baseCurrencySpinner.adapter = adapter
        baseCurrencySpinner.isEnabled = true
        selectCurrenciesButton.isEnabled = true

        // восстановление: сначала сохранённая, потом по гео, потом дефолты
        val persisted = getLastBasePersisted()
        val detected = detectDefaultCurrency(this)
        val defaultBase =
            when {
                persisted != null && list.contains(persisted) -> persisted
                detected != null && list.contains(detected) -> detected
                else -> listOf("USD", "EUR", "UAH", "PLN").firstOrNull { list.contains(it) } ?: list.first()
            }

        baseCurrencySpinner.setSelection(list.indexOf(defaultBase))

        // восстановить второстепенные
        val restoredSecondary = getSecondarySelectedPersisted()
            .filter { it != defaultBase && list.contains(it) }
            .take(5)
        if (restoredSecondary.isNotEmpty()) {
            selectedSymbols = restoredSecondary.toMutableList()
        }

        baseCurrencySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>, p1: View?, p2: Int, p3: Long) {
                val baseNow = base()
                setLastBasePersisted(baseNow)
                // вычеркнем базовую из secondary и сохраним
                if (selectedSymbols.remove(baseNow)) {
                    setSecondarySelectedPersisted(selectedSymbols.toSet())
                }
                reloadRates()
            }
            override fun onNothingSelected(p0: AdapterView<*>) {}
        }

        // перехватываем касание, чтобы открыть «красивый» диалог
        baseCurrencySpinner.setOnTouchListener { _, _ ->
            openBaseCurrencyDialogPretty()
            true
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun reloadRates() {
        val base = (baseCurrencySpinner.selectedItem ?: return).toString()
        val targets = selectedSymbols.filter { it != base }

        // мгновенно — из кэша
        val cached = CurrencyCache.loadRates(this, base)
        if (cached.isNotEmpty()) {
            rates = cached.filterKeys { targets.contains(it) || it == base }
            recalc()
        }

        // онлайн
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { CurrencyApi.fetchRates(base, targets) }
            if (fresh.isNotEmpty()) {
                CurrencyCache.saveRates(this@MainActivity, base, fresh)
                rates = fresh
                recalc()
            } else if (cached.isEmpty() && targets.isNotEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "Не удалось получить курсы для $base",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ========= Единицы товара =========
    private fun setupUnitSpinner() {
        val units = listOf("шт.", "кг", "г", "л")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, units)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        quantityTypeSpinner.adapter = adapter

        quantityTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                setupPriceUnitOptions()
                recalc()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupPriceUnitOptions() {
        val sellUnit = (quantityTypeSpinner.selectedItem ?: "шт.").toString()
        priceUnitRow.visibility = if (sellUnit == "шт.") View.GONE else View.VISIBLE
        val options = if (sellUnit == "л") listOf("л") else listOf("кг", "г", "100 г")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        priceUnitSpinner.adapter = adapter
    }

    private fun attachRecalcListeners() {
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = recalc()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        priceInput.addTextChangedListener(watcher)
        quantityInput.addTextChangedListener(watcher)

        priceUnitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) = recalc()

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    // ========= Расчёты =========
    private fun recalc() {
        val sellUnit = (quantityTypeSpinner.selectedItem ?: "шт.").toString()
        val price = priceInput.text.toString().toDoubleOrNull() ?: 0.0
        val qty = quantityInput.text.toString().toDoubleOrNull() ?: 0.0

        if (sellUnit == "шт.") {
            showPieceMode()
            val total = price * qty

            piecePriceLabel.text = "Цена за штуку: %.2f %s".format(price, base())
            pieceCountLabel.text = "Количество штук: ${qty.toInt()}"
            pieceConvertedPerItem.setText(
                SpannableStringBuilder("Конвертированная стоимость за штуку:\n").append(formatConverted(price)),
                TextView.BufferType.SPANNABLE
            )
            pieceConvertedTotal.setText(
                SpannableStringBuilder("Конвертированная итоговая стоимость:\n").append(formatConverted(total)),
                TextView.BufferType.SPANNABLE
            )
            return
        }

        showWeightMode()

        if (sellUnit == "л") {
            val perLiter = price
            val total = perLiter * qty
            pricePerUnitLabel.text = "Цена за л: %.2f %s".format(price, base())
            takenAmountLabel.text = "Взято (л): %.3f".format(qty)
            costPerBaseUnitLabel.text = "Стоимость за 1 л: %.2f %s".format(perLiter, base())
            convertedPerUnitLabel.setText(
                SpannableStringBuilder("Конвертированная стоимость за л:\n").append(formatConverted(perLiter)),
                TextView.BufferType.SPANNABLE
            )
            convertedPerBaseUnitLabel.setText(
                SpannableStringBuilder("Конвертированная стоимость за 1 л:\n").append(formatConverted(perLiter)),
                TextView.BufferType.SPANNABLE
            )
            convertedTotalLabel.setText(
                SpannableStringBuilder("Конвертированная итоговая стоимость:\n").append(formatConverted(total)),
                TextView.BufferType.SPANNABLE
            )
            return
        }

        // Масса
        val priceUnit = (priceUnitSpinner.selectedItem ?: "кг").toString()
        val perGram = when (priceUnit) {
            "кг" -> price / 1000.0
            "г" -> price
            "100 г" -> price / 100.0
            else -> price
        }
        val qtyGram = if (sellUnit == "кг") qty * 1000.0 else qty
        val total = perGram * qtyGram

        val perKg = perGram * 1000.0
        val per100g = perGram * 100.0

        pricePerUnitLabel.text = "Цена за $priceUnit: %.2f %s".format(price, base())
        takenAmountLabel.text =
            "Взято (${sellUnit}): ${if (sellUnit == "кг") "%.3f".format(qty) else "%.0f".format(qty)}"
        costPerBaseUnitLabel.text =
            "Стоимость за 1 кг / 1 г: %.2f / %.4f %s".format(perKg, perGram, base())
        convertedPerUnitLabel.setText(
            SpannableStringBuilder("Конвертированная стоимость за ${if (sellUnit == "кг") "кг" else "г"}:\n")
                .append(formatConverted(if (sellUnit == "кг") perKg else perGram)),
            TextView.BufferType.SPANNABLE
        )
        convertedPerBaseUnitLabel.setText(
            SpannableStringBuilder("Конвертированная стоимость за 1 кг / 100 г:\n")
                .append(formatConverted(perKg)).append("\n").append(formatConverted(per100g)),
            TextView.BufferType.SPANNABLE
        )
        convertedTotalLabel.setText(
            SpannableStringBuilder("Конвертированная итоговая стоимость:\n").append(formatConverted(total)),
            TextView.BufferType.SPANNABLE
        )
    }

    private fun base(): String = (baseCurrencySpinner.selectedItem ?: "USD").toString()

    private fun showPieceMode() {
        priceUnitRow.visibility = View.GONE
        modePiece.visibility = View.VISIBLE
        modeWeight.visibility = View.GONE
        quantityInput.hint = "Количество штук"
    }

    private fun showWeightMode() {
        priceUnitRow.visibility = View.VISIBLE
        modePiece.visibility = View.GONE
        modeWeight.visibility = View.VISIBLE
        val sellUnit = (quantityTypeSpinner.selectedItem ?: "кг").toString()
        quantityInput.hint =
            if (sellUnit == "л") "Объём, л" else if (sellUnit == "кг") "Вес, кг" else "Вес, г"
    }

    private fun formatConverted(amountInBase: Double): CharSequence {
        if (amountInBase == 0.0 || selectedSymbols.isEmpty()) return "—"
        val base = base()
        val sb = SpannableStringBuilder()
        var first = true
        for (code in selectedSymbols) {
            if (code == base) continue
            val rate = rates[code] ?: continue
            val v = amountInBase * rate
            val line = "$code ${formatMoney(v)}"
            if (!first) sb.append("\n")
            val start = sb.length
            sb.append(line)
            // делаем код жирным (только первые 3/4 символа кода, потом пробел)
            val boldEnd = start + code.length
            sb.setSpan(StyleSpan(Typeface.BOLD), start, boldEnd, 0)
            first = false
        }
        return sb.ifEmpty { "—" }
    }

    private fun formatMoney(v: Double): String =
        if (v < 1.0) String.format("%.4f", v) else String.format("%.2f", v)

    // ========= Диалоги выбора валют =========

    private fun codeToName(code: String) = symbolNames[code] ?: code

    // subsequence-поиск: "TY" найдём в "TRY"
    private fun isSubsequence(queryLower: String, codeUpper: String): Boolean {
        if (queryLower.isBlank()) return true
        val q = queryLower.uppercase()
        var i = 0
        for (ch in codeUpper) if (i < q.length && ch == q[i]) i++
        return i == q.length
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun openBaseCurrencyDialogPretty() {
        if (allSymbols.isEmpty()) return

        val view = layoutInflater.inflate(R.layout.dialog_currency_list, null)
        val search = view.findViewById<EditText>(R.id.searchInput)
        val rv = view.findViewById<RecyclerView>(R.id.currencyList)
        rv.layoutManager = LinearLayoutManager(this)

        val all = allSymbols.map { Row.Currency(it, codeToName(it)) }
        val currentCode = base()

        val recents = getRecentBase().map { Row.Currency(it, codeToName(it)) }
            .filter { r -> all.any { it.code == r.code } }

        val filter = CurrencyFilter(all) { cur, q ->
            val t = q.trim().lowercase()
            cur.name.lowercase().contains(t) ||
                    cur.code.lowercase().contains(t) ||
                    isSubsequence(t, cur.code.uppercase())
        }

        lateinit var dlg: AlertDialog

        val adapter = CurrencyAdapter(
            context = this,
            singleMode = true,
            isFavorite = { false },
            onToggleFavorite = {},
            isSelected = { it == currentCode },
            onToggleSelected = {},
            onSinglePick = { picked ->
                baseCurrencySpinner.setSelection(allSymbols.indexOf(picked).coerceAtLeast(0))
                setLastBasePersisted(picked)

                // убрать из secondary, если есть
                val sec = getSecondarySelectedPersisted().toMutableSet()
                if (sec.remove(picked)) setSecondarySelectedPersisted(sec)

                pushRecentBase(picked)
                reloadRates()
                dlg.dismiss()
            }
        )
        rv.adapter = adapter

        fun rebuild(q: String) {
            val filtered = filter.filter(q)
            val current = all.find { it.code == currentCode }
            adapter.submitList(CurrencySectionBuilder.forBase(current, recents, all, filtered))
        }

        dlg = AlertDialog.Builder(this)
            .setCustomTitle(buildCenteredTitle("Основная валюта"))
            .setView(view)
            .setNegativeButton("Закрыть", null)
            .create()

        dlg.show()
        rebuild("")
        search.addTextChangedListener { s -> rebuild(s?.toString() ?: "") }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openSecondaryCurrenciesDialog() {
        if (allSymbols.isEmpty()) return

        val view = layoutInflater.inflate(R.layout.dialog_currency_list, null)
        val search = view.findViewById<EditText>(R.id.searchInput)
        val rv = view.findViewById<RecyclerView>(R.id.currencyList)
        rv.layoutManager = LinearLayoutManager(this)

        val all = allSymbols.map { Row.Currency(it, codeToName(it)) }

        val filter = CurrencyFilter(all) { cur, q ->
            val t = q.trim().lowercase()
            cur.name.lowercase().contains(t) ||
                    cur.code.lowercase().contains(t) ||
                    isSubsequence(t, cur.code.uppercase())
        }

        val selected = selectedSymbols.toMutableSet()
        val baseNow = base()

        // снимаем базовую из выбранных, если она там была
        if (selected.remove(baseNow)) {
            setSecondarySelectedPersisted(selected)
        }
        lateinit var adapter: CurrencyAdapter
        var currentQuery = ""

        fun buildSelectedRows(): List<Row.Currency> =
            selected.map { Row.Currency(it, codeToName(it)) }.sortedBy { it.code }
        fun buildFavoriteRows(): List<Row.Currency> =
            getFavorites().map { Row.Currency(it, codeToName(it)) }.sortedBy { it.code }

        fun rebuild(q: String) {
            currentQuery = q
            val filtered = filter.filter(q)
            val data = CurrencySectionBuilder.forSecondary(
                selected = buildSelectedRows(),
                favorites = buildFavoriteRows(),
                all = all,
                filteredAll = filtered
            )
            adapter.submitList(data)
        }

        adapter = CurrencyAdapter(
            context = this,
            singleMode = false,
            isFavorite = { getFavorites().contains(it) },
            onToggleFavorite = { code ->
                val f = getFavorites()
                if (!f.add(code)) f.remove(code)
                setFavorites(f)
            },
            isSelected = { selected.contains(it) },
            onToggleSelected = { code ->
                if (!selected.add(code)) selected.remove(code)
                // сохраняем прямо на лету, чтобы переживало пересоздание
                setSecondarySelectedPersisted(selected)
            },
            onSinglePick = {}
        ).apply {
            disabledCodes = setOf(baseNow)
            onAfterToggle = { rebuild(currentQuery) }
        }

        rv.adapter = adapter

        val dlg = AlertDialog.Builder(this)
            .setCustomTitle(buildCenteredTitle("Валюты для конвертации (мин. 1, макс. 5)"))
            .setView(view)
            .setPositiveButton("OK") { d, _ ->
                val list = selected.toList().filter { it != baseNow }.take(5)
                if (list.isEmpty()) {
                    Toast.makeText(this, "Нужно выбрать хотя бы одну валюту", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    selectedSymbols = list.toMutableList()
                    setSecondarySelectedPersisted(selectedSymbols.toSet())
                    reloadRates()
                }
                d.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .create()

        dlg.show()
        rebuild("")
        search.addTextChangedListener { s -> rebuild(s?.toString() ?: "") }
    }

    // ========= Избранное / последние / хранение =========
    private fun getFavorites(): MutableSet<String> =
        prefs.getStringSet("favorites", emptySet())!!.toMutableSet()

    private fun setFavorites(s: Set<String>) {
        prefs.edit { putStringSet("favorites", s) }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun setLastBasePersisted(code: String) {
        prefs.edit { putString("last_base", code) }
        pushRecentBase(code)
    }

    private fun getLastBasePersisted(): String? =
        prefs.getString("last_base", null)

    private fun setSecondarySelectedPersisted(set: Set<String>) {
        prefs.edit { putStringSet("last_secondary", set) }
    }

    private fun getSecondarySelectedPersisted(): Set<String> =
        prefs.getStringSet("last_secondary", emptySet()) ?: emptySet()

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun pushRecentBase(code: String) {
        val list = prefs.getString("recent_base", "")!!
            .split(",").filter { it.isNotBlank() }.toMutableList()
        list.remove(code)
        list.add(0, code)
        while (list.size > 5) list.removeLast()
        prefs.edit { putString("recent_base", list.joinToString(",")) }
    }

    private fun getRecentBase(): List<String> =
        prefs.getString("recent_base", "")!!
            .split(",").filter { it.isNotBlank() }

    private fun buildCenteredTitle(title: String): View {
        val tv = TextView(this)
        tv.text = title
        tv.textSize = 18f
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.gravity = Gravity.CENTER_HORIZONTAL
        tv.setPadding(24, 24, 24, 16)
        return tv
    }

    // Гео-дефолт базовой валюты (без разрешений)
    private fun detectDefaultCurrency(ctx: Context): String? = try {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val iso = (tm.networkCountryIso?.ifBlank { null } ?: tm.simCountryIso?.ifBlank { null })
            ?.uppercase(Locale.US)
        val locale = if (iso != null) Locale("", iso) else Locale.getDefault()
        java.util.Currency.getInstance(locale)?.currencyCode
    } catch (_: Exception) { null }
}
