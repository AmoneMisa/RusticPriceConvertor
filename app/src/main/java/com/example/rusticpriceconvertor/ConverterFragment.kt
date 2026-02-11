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
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private var baseDialog: AlertDialog? = null

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

    private val prefs by lazy {
        requireContext().getSharedPreferences("currency_prefs", Context.MODE_PRIVATE)
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
            val value = b.getString(ScanPriceDialogFragment.RESULT_VALUE)
                ?: return@setFragmentResultListener
            val cur = b.getString(ScanPriceDialogFragment.RESULT_CURRENCY)

            priceInput.setText(value)

            if (!cur.isNullOrBlank() && allSymbols.contains(cur)) {
                baseCurrencySpinner.setSelection(allSymbols.indexOf(cur))
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

        modePiece = v.findViewById(R.id.modePiece)
        piecePriceLabel = v.findViewById(R.id.piecePriceLabel)
        pieceCountLabel = v.findViewById(R.id.pieceCountLabel)
        pieceConvertedPerItem = v.findViewById(R.id.pieceConvertedPerItem)
        pieceConvertedTotal = v.findViewById(R.id.pieceConvertedTotal)

        modeWeight = v.findViewById(R.id.modeWeight)
        pricePerUnitLabel = v.findViewById(R.id.pricePerUnitLabel)
        takenAmountLabel = v.findViewById(R.id.takenAmountLabel)
        costPerBaseUnitLabel = v.findViewById(R.id.costPerBaseUnitLabel)
        convertedPerUnitLabel = v.findViewById(R.id.convertedPerUnitLabel)
        convertedPerBaseUnitLabel = v.findViewById(R.id.convertedPerBaseUnitLabel)
        convertedTotalLabel = v.findViewById(R.id.convertedTotalLabel)
    }

    // ========= Валюты: загрузка + привязка =========
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
            .take(5)
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

    @RequiresApi(Build.VERSION_CODES.O)
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

    // ========= Расчёты =========
    private fun recalc() {
        val sellUnit =
            (quantityTypeSpinner.selectedItem ?: getString(R.string.unit_piece)).toString()
        val price = priceInput.text.toString().toDoubleOrNull() ?: 0.0
        val qty = quantityInput.text.toString().toDoubleOrNull() ?: 0.0

        if (sellUnit == getString(R.string.unit_piece)) {
            showPieceMode()
            val total = price * qty
            piecePriceLabel.text = getString(R.string.piece_price, price, base())
            pieceCountLabel.text = getString(R.string.piece_count, qty.toInt())
            pieceConvertedPerItem.text =
                getString(R.string.converted_per_piece_title) + "\n" + formatConverted(price)
            pieceConvertedTotal.text =
                getString(R.string.converted_total_title) + "\n" + formatConverted(total)
            return
        }

        showWeightMode()

        val unitOfPrice = (priceUnitSpinner.selectedItem ?: "").toString() // "мл"/"л" или "г"/"кг"
        val amountOfPrice = priceAmountInput.text.toString().toDoubleOrNull() ?: 0.0
        if (amountOfPrice <= 0.0) {
            // пустая/некорректная цена-за — просто показываем прочерки
            pricePerUnitLabel.text = getString(R.string.dash_price)
            takenAmountLabel.text = getString(R.string.dash)
            costPerBaseUnitLabel.text = getString(R.string.dash)
            convertedPerUnitLabel.text = getString(R.string.dash)
            convertedPerBaseUnitLabel.text = getString(R.string.dash)
            convertedTotalLabel.text = getString(R.string.dash)
            return
        }

        // ===== ОБЪЁМ =====
        if (sellUnit == getString(R.string.unit_l) || sellUnit == getString(R.string.unit_ml)) {
            val pkgMl = when (unitOfPrice) {
                getString(R.string.unit_l) -> amountOfPrice * 1000.0
                else -> amountOfPrice // "мл"
            }
            val perMl = price / pkgMl
            val perL = perMl * 1000.0
            val per100ml = perMl * 100.0

            val qtyMl = if (sellUnit == getString(R.string.unit_l)) qty * 1000.0 else qty
            val total = perMl * qtyMl

            pricePerUnitLabel.text =
                getString(R.string.price_for) + " ${trimZeros(amountOfPrice)} $unitOfPrice: " +
                        String.format("%.2f %s", price, base())
            takenAmountLabel.text =
                getString(R.string.taken_generic, getString(R.string.unit_ml), trimZeros(qtyMl))
            costPerBaseUnitLabel.text =
                getString(R.string.cost_per_l_and_100ml, perL, per100ml, base())

            convertedPerUnitLabel.text =
                getString(R.string.converted_per_l_title) + "\n" + formatConverted(perL)
            convertedPerBaseUnitLabel.text =
                getString(R.string.converted_per_100ml_title) + "\n" + formatConverted(per100ml)
            convertedTotalLabel.text =
                getString(R.string.converted_total_weight_title) + "\n" + formatConverted(total)
            return
        }

        // ===== ВЕС ===== (sellUnit == "кг" или "г")
        val pkgG = when (unitOfPrice) {
            getString(R.string.unit_kg) -> amountOfPrice * 1000.0
            else -> amountOfPrice // "г"
        }
        val perGram = price / pkgG
        val perKg = perGram * 1000.0
        val per100g = perGram * 100.0

        val qtyGram = if (sellUnit == getString(R.string.unit_kg)) qty * 1000.0 else qty
        val total = perGram * qtyGram

        pricePerUnitLabel.text =
            getString(R.string.price_for) + " ${trimZeros(amountOfPrice)} $unitOfPrice: " +
                    String.format("%.2f %s", price, base())
        takenAmountLabel.text =
            getString(R.string.taken_generic, getString(R.string.unit_g), trimZeros(qtyGram))
        costPerBaseUnitLabel.text =
            getString(R.string.cost_per_kg_and_100g, perKg, per100g, base())

        convertedPerUnitLabel.text =
            getString(R.string.converted_per_kg_title) + "\n" + formatConverted(perKg)
        convertedPerBaseUnitLabel.text =
            getString(R.string.converted_per_100g_title) + "\n" + formatConverted(per100g)
        convertedTotalLabel.text =
            getString(R.string.converted_total_weight_title) + "\n" + formatConverted(total)
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

        if (baseDialog?.isShowing == true) return

        val view = layoutInflater.inflate(R.layout.dialog_currency_list, null)
        val search = view.findViewById<EditText>(R.id.searchInput)
        val rv = view.findViewById<RecyclerView>(R.id.currencyList)
        rv.layoutManager = LinearLayoutManager(requireContext())

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

        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(buildCenteredTitle(getString(R.string.title_base_currency)))
            .setView(view)
            .setNegativeButton(R.string.btn_close, null)
            .create()
        baseDialog = dialog
        dialog.setOnDismissListener { baseDialog = null }
        dialog.show()

        val orange = ContextCompat.getColor(requireContext(), R.color.fav_orange)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(orange)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(orange)

        val adapter = CurrencyAdapter(
            context = requireContext(),
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
                baseDialog?.dismiss()
            }
        )
        rv.adapter = adapter

        fun rebuild(q: String) {
            val filtered = filter.filter(q)
            val current = all.find { it.code == currentCode }
            adapter.submitList(
                CurrencySectionBuilder.forBase(
                    ctx = requireContext(),
                    current,
                    recents,
                    all,
                    filtered
                )
            )
        }

        rebuild("")
        search.addTextChangedListener { s -> rebuild(s?.toString() ?: "") }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openSecondaryCurrenciesDialog() {
        if (allSymbols.isEmpty()) return

        val view = layoutInflater.inflate(R.layout.dialog_currency_list, null)
        val search = view.findViewById<EditText>(R.id.searchInput)
        val rv = view.findViewById<RecyclerView>(R.id.currencyList)
        rv.layoutManager = LinearLayoutManager(requireContext())

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
                requireContext(),
                selected = buildSelectedRows(),
                favorites = buildFavoriteRows(),
                all = all,
                filteredAll = filtered
            )
            adapter.submitList(data)
        }

        adapter = CurrencyAdapter(
            context = requireContext(),
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

        val dlg = AlertDialog.Builder(requireContext())
            .setCustomTitle(buildCenteredTitle(getString(R.string.title_secondary_currencies)))
            .setView(view)
            .setPositiveButton(getString(R.string.btn_ok)) { d, _ ->
                val list = selected.toList().filter { it != baseNow }.take(5)
                if (list.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.warn_need_at_least_one),
                        Toast.LENGTH_SHORT
                    )
                        .show()
                } else {
                    selectedSymbols = list.toMutableList()
                    setSecondarySelectedPersisted(selectedSymbols.toSet())
                    reloadRates()
                }
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_close), null)
            .create()

        dlg.show()


        val orange = ContextCompat.getColor(requireContext(), R.color.fav_orange)
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(orange)
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(orange)
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
