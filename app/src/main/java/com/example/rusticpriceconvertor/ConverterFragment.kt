package com.example.rusticpriceconvertor

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
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.rusticpriceconvertor.CurrencyPickerDialog.Companion.onToggleSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale

class ConverterFragment : Fragment(R.layout.fragment_converter) {

    // --------- UI ----------
    private lateinit var scanPriceButton: ImageButton
    private lateinit var priceInput: EditText
    private lateinit var quantityInput: EditText
    private lateinit var quantityTypeSpinner: Spinner

    private lateinit var baseCurrencySpinner: Spinner
    private lateinit var selectCurrenciesButton: Button

    private lateinit var priceUnitRow: LinearLayout
    private lateinit var priceUnitSpinner: Spinner
    private lateinit var priceAmountInput: EditText
    private lateinit var labelPriceUnit: TextView

    // Итог
    private lateinit var resultList: LinearLayout
    private lateinit var resultHint: TextView

    private var baseDialog: AlertDialog? = null

    // Штучно
    private lateinit var modePiece: LinearLayout
    private lateinit var piecePriceLabel: TextView
    private lateinit var pieceCountLabel: TextView
    private lateinit var pieceConvertedPerItem: TextView
    private lateinit var pieceConvertedTotal: TextView
    private lateinit var modeWeight: LinearLayout
    private lateinit var pricePerUnitLabel: TextView
    private lateinit var takenAmountLabel: TextView
    private lateinit var costPerBaseUnitLabel: TextView
    private lateinit var convertedPerUnitLabel: TextView
    private lateinit var convertedPerBaseUnitLabel: TextView
    private lateinit var convertedTotalLabel: TextView

    private var symbolNames: Map<String, String> = emptyMap()  // code -> name
    private var allSymbols: List<String> = emptyList()
    private var selectedSymbols: MutableList<String> = mutableListOf("USD", "EUR")
    private var rates: Map<String, Double> = emptyMap()

    private val prefs by lazy {
        requireContext().getSharedPreferences("currency_prefs", Context.MODE_PRIVATE)
    }

    private fun parseDoubleFrom(et: EditText): Double {
        val s = et.text?.toString().orEmpty()
            .trim()
            .replace('\u00A0', ' ')
            .replace(" ", "")
            .replace(',', '.')
        return s.toDoubleOrNull() ?: 0.0
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        val hasCamera = requireContext().packageManager
            .hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)

        scanPriceButton.visibility = if (hasCamera) View.VISIBLE else View.GONE
        scanPriceButton.isEnabled = hasCamera

        scanPriceButton.setOnClickListener {
            ScanPriceDialogFragment().show(parentFragmentManager, "scan_price")
        }

        parentFragmentManager.setFragmentResultListener(
            ScanPriceDialogFragment.REQ_KEY,
            viewLifecycleOwner
        ) { _, b ->
            val valueRaw = b.getString(ScanPriceDialogFragment.RESULT_VALUE)
                ?: return@setFragmentResultListener
            val cur = b.getString(ScanPriceDialogFragment.RESULT_CURRENCY)

            val value = valueRaw
                .trim()
                .replace('\u00A0', ' ')
                .replace(" ", "")
                .replace(',', '.')

            priceInput.setText(value)
            priceInput.setSelection(value.length)

            if (!cur.isNullOrBlank() && allSymbols.contains(cur)) {
                baseCurrencySpinner.setSelection(allSymbols.indexOf(cur))
            } else {
                recalc()
            }
        }

        baseCurrencySpinner.isEnabled = false
        selectCurrenciesButton.isEnabled = false

        setupUnitSpinner()
        attachRecalcListeners()
        setupCurrencyUi()
        recalc()
    }

    private fun bindViews(v: View) {
        priceInput = v.findViewById(R.id.priceInput)
        quantityInput = v.findViewById(R.id.quantityInput)
        quantityTypeSpinner = v.findViewById(R.id.quantityTypeSpinner)
        scanPriceButton = v.findViewById(R.id.scanPriceButton)

        baseCurrencySpinner = v.findViewById(R.id.baseCurrencySpinner)
        selectCurrenciesButton = v.findViewById(R.id.selectCurrenciesButton)

        labelPriceUnit = v.findViewById(R.id.labelPriceUnit)
        priceAmountInput = v.findViewById(R.id.priceAmountInput)

        priceUnitRow = v.findViewById(R.id.priceUnitRow)
        priceUnitSpinner = v.findViewById(R.id.priceUnitSpinner)

        resultList = v.findViewById(R.id.resultList)
        resultHint = v.findViewById(R.id.resultHint)
    }

    private fun renderCompareTable(amountAInBase: Double, amountBInBase: Double) {
        resultList.removeAllViews()

        val base = base()
        val rows = selectedSymbols
            .filter { it != base }
            .mapNotNull { code ->
                val rate = rates[code] ?: return@mapNotNull null

                val a = formatMoney(amountAInBase * rate)
                val b = formatMoney(amountBInBase * rate)

                Triple(code, a, b)
            }

        val layoutId = R.layout.item_result_row_2cols

        if (rows.isEmpty()) {
            val row = layoutInflater.inflate(layoutId, resultList, false)
            row.findViewById<TextView>(R.id.code).text = getString(R.string.dash)
            row.findViewById<TextView>(R.id.perUnit).text = getString(R.string.dash)
            row.findViewById<TextView>(R.id.total).text = getString(R.string.dash)
            resultList.addView(row)
            return
        }

        rows.forEachIndexed { i, (code, a, b) ->
            val row = layoutInflater.inflate(layoutId, resultList, false)
            row.findViewById<TextView>(R.id.code).text = code
            row.findViewById<TextView>(R.id.perUnit).text = a
            row.findViewById<TextView>(R.id.total).text = b
            resultList.addView(row)

            if (i != rows.lastIndex) {
                val div = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    )
                    background = ContextCompat.getDrawable(requireContext(), R.drawable.divider_result)
                }
                resultList.addView(div)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun setupCurrencyUi() {
        viewLifecycleOwner.lifecycleScope.launch {
            val cached = CurrencyCache.loadSymbols(requireContext())
            if (cached.isNotEmpty()) {
                symbolNames = cached
                allSymbols = cached.keys.sorted()
                inflateBaseSpinner(allSymbols)
            }
            val fresh = withContext(Dispatchers.IO) { CurrencyApi.fetchSymbols() }
            if (fresh.isNotEmpty()) {
                CurrencyCache.saveSymbols(requireContext(), fresh)
                symbolNames = fresh
                allSymbols = fresh.keys.sorted()
                inflateBaseSpinner(allSymbols)
            } else if (cached.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.err_load_currencies),
                    Toast.LENGTH_LONG
                ).show()
            }
            selectCurrenciesButton.setOnClickListener { openSecondaryCurrenciesDialog() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun inflateBaseSpinner(list: List<String>) {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, list)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        baseCurrencySpinner.adapter = adapter
        baseCurrencySpinner.isEnabled = true
        selectCurrenciesButton.isEnabled = true

        val persisted = getLastBasePersisted()
        val detected = detectDefaultCurrency(requireContext())
        val defaultBase =
            when {
                persisted != null && list.contains(persisted) -> persisted
                detected != null && list.contains(detected) -> detected
                else -> listOf("USD", "EUR", "UAH", "PLN").firstOrNull { list.contains(it) }
                    ?: list.first()
            }
        baseCurrencySpinner.setSelection(list.indexOf(defaultBase))

        val restoredSecondary = getSecondarySelectedPersisted()
            .filter { it != defaultBase && list.contains(it) }
            .take(20)
        if (restoredSecondary.isNotEmpty()) selectedSymbols = restoredSecondary.toMutableList()

        baseCurrencySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>, p1: View?, p2: Int, p3: Long) {
                val baseNow = base()
                setLastBasePersisted(baseNow)
                if (selectedSymbols.remove(baseNow)) setSecondarySelectedPersisted(selectedSymbols.toSet())
                reloadRates()
            }

            override fun onNothingSelected(p0: AdapterView<*>) {}
        }

        baseCurrencySpinner.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_UP) {
                if (baseDialog?.isShowing != true) openBaseCurrencyDialogPretty()
            }
            true
        }
    }

    private fun reloadRates() {
        val base = (baseCurrencySpinner.selectedItem ?: return).toString()
        val targets = selectedSymbols.filter { it != base }

        val cached = CurrencyCache.loadRates(requireContext(), base)
        if (cached.isNotEmpty()) {
            rates = cached.filterKeys { targets.contains(it) || it == base }
            recalc()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { CurrencyApi.fetchRates(base, targets) }
            if (fresh.isNotEmpty()) {
                CurrencyCache.saveRates(requireContext(), base, fresh)
                rates = fresh
                recalc()
            } else if (cached.isEmpty() && targets.isNotEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.err_fetch_rates, base),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ========= Единицы товара =========
    private fun setupUnitSpinner() {
        val units = resources.getStringArray(R.array.units_main).toList()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, units)
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
        val unitPiece = getString(R.string.unit_piece)
        val unitMl = getString(R.string.unit_ml)
        val unitL = getString(R.string.unit_l)
        val unitG = getString(R.string.unit_g)
        val unitKg = getString(R.string.unit_kg)

        val sellUnit = (quantityTypeSpinner.selectedItem ?: unitPiece).toString()

        if (sellUnit == unitPiece) {
            priceUnitRow.visibility = View.GONE
            return
        }
        priceUnitRow.visibility = View.VISIBLE

        val opts = when (sellUnit) {
            unitL, unitMl -> resources.getStringArray(R.array.units_volume).toList()
            else -> resources.getStringArray(R.array.units_mass).toList()
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, opts)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        priceUnitSpinner.adapter = adapter

        if (priceAmountInput.text.isNullOrBlank()) priceAmountInput.setText("1")
        priceAmountInput.hint = if (opts.first() == unitMl)
            getString(R.string.price_amount_hint_volume)
        else
            getString(R.string.price_amount_hint_mass)
    }

    private fun attachRecalcListeners() {
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = recalc()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        priceInput.addTextChangedListener(watcher)
        quantityInput.addTextChangedListener(watcher)
        priceAmountInput.addTextChangedListener(watcher)

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

    private fun recalc() {
        val qtyRaw = quantityInput.text?.toString().orEmpty().trim()

        val qty = when {
            qtyRaw.isBlank() -> 1.0
            else -> qtyRaw.replace(',', '.').toDoubleOrNull() ?: 0.0
        }

        val unitPiece = getString(R.string.unit_piece)
        val unitMl = getString(R.string.unit_ml)
        val unitL = getString(R.string.unit_l)
        val unitG = getString(R.string.unit_g)
        val unitKg = getString(R.string.unit_kg)
        val sellUnit = (quantityTypeSpinner.selectedItem ?: unitPiece).toString()
        val price = parseDoubleFrom(priceInput)

        if (sellUnit == unitPiece) {
            priceUnitRow.visibility = View.GONE
            quantityInput.hint = getString(R.string.hint_qty_pieces)

            val total = price * qty
            renderCompareTable(
                amountAInBase = price,
                amountBInBase = total
            )

            val onePiece = getString(R.string.unit_1, 1, unitPiece)
            resultHint.text = getString(R.string.result_hint_piece, onePiece)
            return
        }

        priceUnitRow.visibility = View.VISIBLE
        quantityInput.hint = when (sellUnit) {
            unitL -> getString(R.string.hint_qty_l)
            unitMl -> getString(R.string.hint_qty_ml)
            unitKg -> getString(R.string.hint_qty_kg)
            else -> getString(R.string.hint_qty_g)
        }

        val unitOfPrice = (priceUnitSpinner.selectedItem ?: "").toString()
        val amountOfPrice = parseDoubleFrom(priceAmountInput)
        if (amountOfPrice <= 0.0) {
            renderCompareTable(0.0, 0.0)
            resultHint.text = getString(R.string.dash)
            return
        }

        if (sellUnit == unitL || sellUnit == unitMl) {
            val pkgMl = when (unitOfPrice) {
                unitL -> amountOfPrice * 1000.0
                else -> amountOfPrice
            }
            val perMl = price / pkgMl
            val per100ml = perMl * 100.0

            val qtyMl = if (sellUnit == unitL) qty * 1000.0 else qty
            val total = perMl * qtyMl
            renderCompareTable(
                amountAInBase = per100ml,
                amountBInBase = total
            )

            val hundredMl = getString(R.string.unit_100, 100, unitMl)
            resultHint.text = getString(R.string.result_hint_volume, hundredMl)
            return
        }

        val pkgG = when (unitOfPrice) {
            unitKg -> amountOfPrice * 1000.0
            else -> amountOfPrice
        }
        val perGram = price / pkgG
        val per100g = perGram * 100.0
        val qtyGram = if (sellUnit == unitKg) qty * 1000.0 else qty
        val total = perGram * qtyGram
        renderCompareTable(
            amountAInBase = per100g,
            amountBInBase = total
        )

        val hundredG = getString(R.string.unit_100, 100, unitG)
        resultHint.text = getString(R.string.result_hint_weight, hundredG)
    }

    private fun showPieceMode() {
        priceUnitRow.visibility = View.GONE
        modePiece.visibility = View.VISIBLE
        modeWeight.visibility = View.GONE
        quantityInput.hint = getString(R.string.hint_qty_pieces)
    }

    private fun showWeightMode() {
        priceUnitRow.visibility = View.VISIBLE
        modePiece.visibility = View.GONE
        modeWeight.visibility = View.VISIBLE
        val sellUnit = (quantityTypeSpinner.selectedItem ?: getString(R.string.unit_kg)).toString()
        quantityInput.hint = when (sellUnit) {
            getString(R.string.unit_l) -> getString(R.string.hint_qty_l)
            getString(R.string.unit_ml) -> getString(R.string.hint_qty_ml)
            getString(R.string.unit_kg) -> getString(R.string.hint_qty_kg)
            else -> getString(R.string.hint_qty_g)
        }
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

        CurrencyPickerDialog.newBasePicker(
            currentCode = base(),
            disabledCodes = emptySet()
        ).apply {

            provideAllCurrencies = {
                allSymbols.map { code ->
                    CurrencyPickerDialog.Row.Currency(code = code, name = codeToName(code))
                }
            }

            provideFavoritesCodes = { getFavorites() }

            provideCurrentBaseCode = { base() }

            onToggleFavorite = { code ->
                val f = getFavorites()
                if (!f.add(code)) f.remove(code)
                setFavorites(f)
            }

            onPickBase = { picked ->
                baseCurrencySpinner.setSelection(allSymbols.indexOf(picked).coerceAtLeast(0))
                setLastBasePersisted(picked)

                val sec = getSecondarySelectedPersisted().toMutableSet()
                if (sec.remove(picked)) setSecondarySelectedPersisted(sec)

                reloadRates()
            }

        }.show(parentFragmentManager, "base_currency_picker")
    }


    private fun openSecondaryCurrenciesDialog() {
        if (allSymbols.isEmpty()) return

        val baseNow = base()
        val selected = getSecondarySelectedPersisted().toMutableSet().apply { remove(baseNow) }

        CurrencyPickerDialog.newSecondaryPicker(
            disabledCodes = setOf(baseNow)
        ).apply {

            provideAllCurrencies = {
                allSymbols.map { code ->
                    CurrencyPickerDialog.Row.Currency(code = code, name = codeToName(code))
                }
            }

            provideFavoritesCodes = { getFavorites() }

            provideSelectedSecondaryCodes = { selected }

            provideCurrentBaseCode = { baseNow }

            onToggleFavorite = { code ->
                val f = getFavorites()
                if (!f.add(code)) f.remove(code)
                setFavorites(f)
            }

            onToggleSecondary = { code ->
                if (!selected.add(code)) selected.remove(code)
                setSecondarySelectedPersisted(selected) // сохраняем на лету
            }

            onApplySecondary = onApplySecondary@{ finalSet ->
                val list = finalSet.filter { it != baseNow }.take(20)

                if (list.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.warn_need_at_least_one),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@onApplySecondary
                }

                selectedSymbols = list.toMutableList()
                setSecondarySelectedPersisted(selectedSymbols.toSet())
                reloadRates()
            }

        }.show(parentFragmentManager, "secondary_currency_picker")
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

    private fun base(): String = (baseCurrencySpinner.selectedItem ?: "USD").toString()

    private fun buildCenteredTitle(title: String): View = TextView(requireContext()).apply {
        text = title
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(24, 24, 24, 16)
    }

    // Гео-дефолт базовой валюты (без разрешений)
    private fun detectDefaultCurrency(ctx: Context): String? = try {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val iso = (tm.networkCountryIso?.ifBlank { null } ?: tm.simCountryIso?.ifBlank { null })
            ?.uppercase(Locale.US)
        val locale = if (iso != null) Locale("", iso) else Locale.getDefault()
        java.util.Currency.getInstance(locale)?.currencyCode
    } catch (_: Exception) {
        null
    }
}
