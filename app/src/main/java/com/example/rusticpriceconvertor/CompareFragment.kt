package com.example.rusticpriceconvertor

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

class CompareFragment : Fragment(R.layout.fragment_compare) {

    companion object { private const val MAX_ITEMS = 4 }

    private lateinit var adapter: CompareAdapter
    private lateinit var btnAdd: Button
    private lateinit var switchByWeight: SwitchMaterial
    private lateinit var unitSpinner: Spinner
    private lateinit var tvResult: TextView
    private lateinit var rv: RecyclerView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        switchByWeight = view.findViewById(R.id.switchByWeight)
        unitSpinner    = view.findViewById(R.id.unitSpinner)
        tvResult       = view.findViewById(R.id.tvResult)
        rv             = view.findViewById(R.id.rv)
        btnAdd         = view.findViewById(R.id.btnAdd)

        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())

        adapter = CompareAdapter(requireContext(),
            onChanged = { recalc() },
            onRemoved = { updateAddButtonState(); recalc() }
        )
        rv.adapter = adapter

        // стартовые две позиции — без удаления
        adapter.addItem(removable = false)
        adapter.addItem(removable = false)
        updateAddButtonState()

        // единый селект единиц
        val unitsTitles = resources.getStringArray(R.array.units_compare).toList() // "г", "кг", "мл", "л"
        unitSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, unitsTitles
        )
        unitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, i: Int, id: Long) {
                adapter.currentUnit = when (i) { 0 -> QtyUnit.G; 1 -> QtyUnit.KG; 2 -> QtyUnit.ML; else -> QtyUnit.L }
            }
            override fun onNothingSelected(p0: AdapterView<*>) {}
        }

        // переключатель «по весу/объёму»
        switchByWeight.setOnCheckedChangeListener { _, checked ->
            unitSpinner.visibility = if (checked) View.VISIBLE else View.GONE
            adapter.weighted = checked
            recalc()
        }

        // добавление новых товаров (до 4)
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
            rv.post { rv.smoothScrollToPosition(adapter.itemCount - 1) }
            updateAddButtonState()
            recalc()
        }

        recalc()
    }

    private fun updateAddButtonState() {
        btnAdd.isEnabled = adapter.itemCount < MAX_ITEMS
    }

    private fun relabelItems() {
        adapter.items.forEachIndexed { idx, it -> it.label = getString(R.string.item_n, idx + 1) }
        adapter.notifyDataSetChanged()
    }

    private fun recalc() {
        val weighted = adapter.weighted
        val items = adapter.items // уже текущие значения из адаптера

        // оставляем только заполненные
        val filled = items.filter {
            val priceOk = it.price != null && it.price!! > 0.0
            val amountOk = if (!weighted) true else (it.amount != null && it.amount!! > 0.0)
            priceOk && amountOk
        }
        if (filled.size < 2) {
            tvResult.text = getString(R.string.compare_need_more)
            return
        }

        fun metric(ci: CompareItem): Double {
            if (!weighted) return ci.price!!
            val baseMlOrG = when (adapter.currentUnit) {
                QtyUnit.G  -> ci.amount!!
                QtyUnit.KG -> ci.amount!! * 1000.0
                QtyUnit.ML -> ci.amount!!
                QtyUnit.L  -> ci.amount!! * 1000.0
            }
            return ci.price!! / (baseMlOrG / 1000.0) // цена за 1 кг/1 л
        }

        val sb = StringBuilder()
        for (i in 0 until filled.size - 1) {
            for (j in i + 1 until filled.size) {
                val a = filled[i]; val b = filled[j]
                val ma = metric(a); val mb = metric(b)

                val (cheaper, costlier) = if (ma <= mb) a to b else b to a
                val diffAbs = kotlin.math.abs(ma - mb)
                val base    = if (ma <= mb) mb else ma
                val diffPct = if (base > 0) (diffAbs / base) * 100.0 else 0.0

                sb.append(
                    getString(
                        R.string.compare_pair_line,
                        cheaper.label,
                        costlier.label,
                        String.format(Locale.US, "%.2f", diffAbs),
                        String.format(Locale.US, "%.2f", diffPct)
                    )
                ).append('\n')
            }
        }
        tvResult.text = sb.trim().toString()
    }
}

/* ===== ВСПОМОГАТЕЛЬНЫЕ ТИПЫ ===== */
enum class QtyUnit { G, KG, ML, L }

data class CompareItem(
    var label: String,
    var price: Double? = null,
    var amount: Double? = null,
    var removable: Boolean = true
)

class CompareVH(v: View) : RecyclerView.ViewHolder(v) {
    val tvLabel: TextView     = v.findViewById(R.id.tvLabel)
    val etPrice: EditText     = v.findViewById(R.id.etPrice)
    val etAmount: EditText    = v.findViewById(R.id.etAmount)
    val btnRemove: ImageButton = v.findViewById(R.id.btnRemove)
}

class CompareAdapter(
    private val context: Context,
    private val onChanged: () -> Unit,
    private val onRemoved: () -> Unit
) : RecyclerView.Adapter<CompareVH>() {

    val items = mutableListOf<CompareItem>()

    var weighted: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }

    var currentUnit: QtyUnit = QtyUnit.G
        set(value) { field = value; notifyDataSetChanged() }

    fun addItem(removable: Boolean) {
        val idx = items.size + 1
        items += CompareItem(label = labelFor(idx), removable = removable)
        notifyItemInserted(items.lastIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CompareVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_compare_row, parent, false)
        return CompareVH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(vh: CompareVH, pos: Int) {
        val item = items[pos]

        vh.tvLabel.text = item.label

        // видимость и хинт для количества (вес/объём)
        vh.etAmount.visibility = if (weighted) View.VISIBLE else View.GONE
        vh.etAmount.hint = when (currentUnit) {
            QtyUnit.G  -> vh.itemView.context.getString(R.string.compare_amount_hint_g)
            QtyUnit.KG -> vh.itemView.context.getString(R.string.compare_amount_hint_kg)
            QtyUnit.ML -> vh.itemView.context.getString(R.string.compare_amount_hint_ml)
            QtyUnit.L  -> vh.itemView.context.getString(R.string.compare_amount_hint_l)
        }

        // заполнение текущими значениями (без зацикливания слушателей)
        if (vh.etPrice.text.toString() != (item.price?.toString() ?: "")) {
            vh.etPrice.setText(item.price?.toString().orEmpty())
        }
        if (vh.etAmount.text.toString() != (item.amount?.toString() ?: "")) {
            vh.etAmount.setText(item.amount?.toString().orEmpty())
        }

        // слушатели
        vh.etPrice.doAfterTextChanged {
            item.price = it?.toString()?.replace(',', '.')?.toDoubleOrNull()
            onChanged()
        }
        vh.etAmount.doAfterTextChanged {
            item.amount = it?.toString()?.replace(',', '.')?.toDoubleOrNull()
            onChanged()
        }

        // удалить — только если можно
        vh.btnRemove.visibility = if (item.removable) View.VISIBLE else View.GONE
        vh.btnRemove.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION && items.getOrNull(p)?.removable == true) {
                items.removeAt(p)
                notifyItemRemoved(p)
                // обновим подписи "Товар N"
                for (i in p until items.size) items[i].label = labelFor(i + 1)
                notifyItemRangeChanged(p, items.size - p)
                onRemoved()
            }
        }
    }

    private fun labelFor(idx: Int) = context.getString(R.string.item_n, idx)  // или context.getString(R.string.item_n, idx)
}