package com.example.rusticpriceconvertor

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

class CompareFragment : Fragment(R.layout.fragment_compare) {

    companion object {
        private const val MAX_ITEMS = 4
    }

    private lateinit var switchByWeight: SwitchMaterial
    private lateinit var unitSpinner: Spinner
    private lateinit var btnAdd: Button

    private lateinit var resultCard: View
    private lateinit var tvWinnerTitle: TextView
    private lateinit var rvCompareItems: RecyclerView
    private lateinit var tvCompareSummary: TextView
    private lateinit var winnerBlock: View
    private lateinit var tvWinnerProduct: TextView
    private lateinit var tvWinnerWord: TextView

    private lateinit var adapter: CompareItemAdapter

    enum class QtyUnit { G, KG, ML, L }

    data class CompareItem(
        val id: Long,
        var index: Int,
        var price: Double? = null,
        var amount: Double? = null,
        var removable: Boolean = true
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        switchByWeight = view.findViewById(R.id.switchByWeight)
        unitSpinner = view.findViewById(R.id.unitSpinner)
        btnAdd = view.findViewById(R.id.btnAdd)

        // inside include
        resultCard = view.findViewById(R.id.resultCard)
        tvWinnerTitle = view.findViewById(R.id.tvWinnerTitle)
        winnerBlock = view.findViewById(R.id.winnerBlock)
        tvWinnerProduct = view.findViewById(R.id.tvWinnerProduct)
        tvWinnerWord = view.findViewById(R.id.tvWinnerWord)

        rvCompareItems = view.findViewById(R.id.rvCompareItems)

        rvCompareItems.layoutManager = GridLayoutManager(requireContext(), 2)
        rvCompareItems.itemAnimator = null

        adapter = CompareItemAdapter(
            onChanged = { recalc() },
            onRemoved = { updateAddButtonState(); recalc() }
        )
        rvCompareItems.adapter = adapter

        adapter.addItem(removable = false)
        adapter.addItem(removable = false)
        updateAddButtonState()

        val unitsTitles = resources.getStringArray(R.array.units_compare).toList()
        unitSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            unitsTitles
        )
        unitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, i: Int, id: Long) {
                adapter.currentUnit = when (i) {
                    0 -> QtyUnit.G
                    1 -> QtyUnit.KG
                    2 -> QtyUnit.ML
                    else -> QtyUnit.L
                }
                recalc()
            }

            override fun onNothingSelected(p0: AdapterView<*>) {}
        }

        // mode toggle
        switchByWeight.setOnCheckedChangeListener { _, checked ->
            unitSpinner.visibility = if (checked) View.VISIBLE else View.GONE
            adapter.weighted = checked
            recalc()
        }

        // add
        btnAdd.setOnClickListener {
            if (adapter.itemCount >= MAX_ITEMS) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.compare_max_items, MAX_ITEMS),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            adapter.addItem(removable = true)
            updateAddButtonState()
            recalc()
        }

        winnerBlock.visibility = View.GONE
        tvWinnerTitle.visibility = View.GONE
        unitSpinner.visibility = View.GONE
        recalc()
    }

    private fun updateAddButtonState() {
        btnAdd.isEnabled = adapter.itemCount < MAX_ITEMS
    }

    private fun recalc() {
        val weighted = adapter.weighted
        val unit = adapter.currentUnit

        fun metric(ci: CompareItem): Double {
            val price = ci.price ?: return Double.POSITIVE_INFINITY
            if (!weighted) return price

            val amount = ci.amount ?: return Double.POSITIVE_INFINITY
            if (amount <= 0.0) return Double.POSITIVE_INFINITY

            val baseGOrMl = when (unit) {
                QtyUnit.G  -> amount
                QtyUnit.KG -> amount * 1000.0
                QtyUnit.ML -> amount
                QtyUnit.L  -> amount * 1000.0
            }

            return price * 1000.0 / baseGOrMl // цена за 1кг/1л
        }

        fun notifyById(id: Long?) {
            if (id == null) return
            val idx = adapter.items.indexOfFirst { it.id == id }
            if (idx != -1) adapter.notifyItemChanged(idx)
        }

        val prevBestId = adapter.bestId

        val filled = adapter.items.filter {
            val pOk = (it.price ?: 0.0) > 0.0
            val aOk = if (!weighted) true else (it.amount ?: 0.0) > 0.0
            pOk && aOk
        }

        // --- нет расчёта ---
        if (filled.size < 2) {
            adapter.bestId = null
            notifyById(prevBestId)

            tvWinnerTitle.visibility = View.GONE
            winnerBlock.visibility = View.GONE
            return
        }

        // --- ранжирование ---
        val ranked = filled.map { it to metric(it) }.sortedBy { it.second }
        val best = ranked[0].first
        val bestM = ranked[0].second
        val next = ranked[1].first
        val nextM = ranked[1].second

        // --- подсветка только нужных карточек (без notifyDataSetChanged!) ---
        adapter.bestId = best.id
        if (prevBestId != adapter.bestId) {
            notifyById(prevBestId)
            notifyById(adapter.bestId)
        }

        // --- верхний "Выгоднее" только после расчёта ---
        tvWinnerTitle.visibility = View.VISIBLE
        tvWinnerTitle.text = getString(R.string.compare_better)

        // --- низ: две строки "Товар N" (белым) и "Выгоднее" (зелёным) ---
        winnerBlock.visibility = View.VISIBLE

        val diffPct = if (nextM > 0.0) ((nextM - bestM) / nextM) * 100.0 else 0.0
        val diffAbs = kotlin.math.abs(nextM - bestM)

        val unitText = if (!weighted) {
            getString(R.string.compare_per_piece)
        } else {
            when (unit) {
                QtyUnit.G, QtyUnit.KG -> getString(R.string.compare_per_kg)
                QtyUnit.ML, QtyUnit.L -> getString(R.string.compare_per_l)
            }
        }

        // строка 1: "Товар N — выгоднее на X CUR (Y%)"
        tvWinnerProduct.text = getString(
            R.string.compare_best_line,
            getString(R.string.item_n, best.index),
            format2(diffAbs),
            baseCurrency(),
            format2(diffPct),
            unitText
        )

        // строка 2: "Выгоднее"
        tvWinnerWord.text = getString(R.string.compare_better)
    }

    private fun format2(v: Double): String =
        String.format(Locale.US, "%.2f", v)

    private val prefs by lazy {
        requireContext().getSharedPreferences("currency_prefs", Context.MODE_PRIVATE)
    }

    private fun baseCurrency(): String = prefs.getString("last_base", "USD") ?: "USD"

    // ---------------- Adapter (ввод прямо в карточках) ----------------

    override fun onResume() {
        super.onResume()
        recalc()
    }

    private inner class CompareItemAdapter(
        private val onChanged: () -> Unit,
        private val onRemoved: () -> Unit
    ) : RecyclerView.Adapter<CompareItemVH>() {

        val items = mutableListOf<CompareItem>()
        var bestId: Long? = null

        var weighted: Boolean = false
            set(value) {
                field = value; notifyDataSetChanged()
            }

        var currentUnit: QtyUnit = QtyUnit.G
            set(value) {
                field = value; notifyDataSetChanged()
            }

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = items[position].id

        fun addItem(removable: Boolean) {
            val idx = items.size + 1
            items += CompareItem(
                id = System.nanoTime(),
                index = idx,
                removable = removable
            )
            notifyItemInserted(items.lastIndex)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CompareItemVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_compare_input_card, parent, false)
            return CompareItemVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(vh: CompareItemVH, position: Int) {
            val item = items[position]

            vh.tvLabel.text = getString(R.string.item_n, item.index)
            val cur = baseCurrency()
            vh.tvCurrency.text = if (!weighted) {
                cur
            } else {
                val u = when (currentUnit) {
                    QtyUnit.G, QtyUnit.KG -> getString(R.string.compare_per_kg)
                    QtyUnit.ML, QtyUnit.L -> getString(R.string.compare_per_l)
                }
                "$cur • $u"
            }

            // remove
            vh.btnRemove.visibility = if (item.removable) View.VISIBLE else View.GONE
            vh.btnRemove.setOnClickListener {
                val p = vh.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION && items.getOrNull(p)?.removable == true) {
                    items.removeAt(p)
                    notifyItemRemoved(p)
                    // переиндексация
                    for (i in p until items.size) items[i].index = i + 1
                    notifyItemRangeChanged(p, items.size - p)
                    onRemoved()
                }
            }

            // amount visibility + hint
            vh.etAmount.visibility = if (weighted) View.VISIBLE else View.GONE
            vh.etAmount.hint = when (currentUnit) {
                QtyUnit.G -> getString(R.string.compare_amount_hint_g)
                QtyUnit.KG -> getString(R.string.compare_amount_hint_kg)
                QtyUnit.ML -> getString(R.string.compare_amount_hint_ml)
                QtyUnit.L -> getString(R.string.compare_amount_hint_l)
            }

            // highlight best
            val isBest = (bestId != null && item.id == bestId)
            vh.root.setBackgroundResource(if (isBest) R.drawable.bg_best_card else R.drawable.bg_card_glow)

            // watchers без потери фокуса
            vh.bindInputs(item, onChanged)
        }
    }

    private class CompareItemVH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.root)
        val tvLabel: TextView = v.findViewById(R.id.tvLabel)
        val etPrice: EditText = v.findViewById(R.id.etPrice)
        val etAmount: EditText = v.findViewById(R.id.etAmount)
        val btnRemove: ImageButton = v.findViewById(R.id.btnRemove)
        val tvCurrency: TextView = v.findViewById(R.id.tvCurrency)

        fun bindInputs(item: CompareItem, onChanged: () -> Unit) {
            // снять старые watcher'ы
            (etPrice.tag as? TextWatcher)?.let { etPrice.removeTextChangedListener(it) }
            (etAmount.tag as? TextWatcher)?.let { etAmount.removeTextChangedListener(it) }

            val priceText = item.price?.toString().orEmpty()
            if (!etPrice.hasFocus() && etPrice.text.toString() != priceText) etPrice.setText(
                priceText
            )

            val amountText = item.amount?.toString().orEmpty()
            if (!etAmount.hasFocus() && etAmount.text.toString() != amountText) etAmount.setText(
                amountText
            )

            val wPrice = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    item.price = s?.toString()?.replace(',', '.')?.toDoubleOrNull()
                    onChanged()
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }
            val wAmount = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    item.amount = s?.toString()?.replace(',', '.')?.toDoubleOrNull()
                    onChanged()
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }

            etPrice.addTextChangedListener(wPrice)
            etAmount.addTextChangedListener(wAmount)
            etPrice.tag = wPrice
            etAmount.tag = wAmount
        }
    }
}
