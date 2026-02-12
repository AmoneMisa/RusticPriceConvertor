package com.example.rusticpriceconvertor

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class CurrencyPickerDialog : DialogFragment() {

    companion object {
        // ===== Data providers =====
        var provideFavoritesCodes: (() -> Set<String>)? = null
        var provideSelectedSecondaryCodes: (() -> Set<String>)? = null
        var provideCurrentBaseCode: (() -> String?)? = null

        // ===== Actions =====
        var onPickBase: ((String) -> Unit)? = null
        var onToggleSecondary: ((String) -> Unit)? = null
        var onApplySecondary: ((Set<String>) -> Unit)? = null

        private const val ARG_MODE = "mode"
        private const val ARG_DISABLED_CODES = "disabled_codes"

        private const val MODE_BASE_SINGLE = 1
        private const val MODE_SECONDARY_MULTI = 2

        const val MAX_SECONDARY = 20

        fun newBasePicker(
            disabledCodes: Set<String> = emptySet(),
            currentCode: String
        ): CurrencyPickerDialog =
            CurrencyPickerDialog().apply {
                arguments = Bundle().apply {
                    putInt(ARG_MODE, MODE_BASE_SINGLE)
                    putStringArrayList(ARG_DISABLED_CODES, ArrayList(disabledCodes))
                }
            }

        fun newSecondaryPicker(disabledCodes: Set<String> = emptySet()): CurrencyPickerDialog =
            CurrencyPickerDialog().apply {
                arguments = Bundle().apply {
                    putInt(ARG_MODE, MODE_SECONDARY_MULTI)
                    putStringArrayList(ARG_DISABLED_CODES, ArrayList(disabledCodes))
                }
            }
    }

    // ====== data providers (задаются снаружи) ======
    var provideAllCurrencies: (() -> List<Row.Currency>)? = null                 // полный список
    var provideFavoritesCodes: (() -> Set<String>)? = null                       // коды избранного
    var provideCurrentBaseCode: (() -> String?)? = null                          // текущая базовая
    var provideSelectedSecondaryCodes: (() -> Set<String>)? =
        null               // текущие выбранные (для multi)

    // ====== callbacks (задаются снаружи) ======
    var onToggleFavorite: ((String) -> Unit)? = null                             // toggle heart
    var onPickBase: ((String) -> Unit)? =
        null                                   // single: выбрать и закрыть
    var onApplySecondary: ((Set<String>) -> Unit)? = null                        // multi: OK
    var onToggleSecondaryExternal: ((String) -> Unit)? =
        null                    // если хочешь сразу писать в prefs на каждое нажатие (не обязательно)

    // ====== views (ids как в твоём layout) ======
    private lateinit var tvTitle: TextView
    private lateinit var btnClose: ImageButton
    private lateinit var etSearch: EditText
    private lateinit var rvList: RecyclerView
    private lateinit var btnOk: View

    // multi-only
    private lateinit var selectedContainer: View
    private lateinit var rvSelected: RecyclerView

    // adapters
    private lateinit var listAdapter: CurrencyListAdapter
    private lateinit var selectedAdapter: SelectedChipAdapter

    private val disabledCodes: MutableSet<String> = mutableSetOf()
    private val selectedSecondaryLocal: LinkedHashSet<String> =
        linkedSetOf() // local state for multi

    private fun isBaseSingle(): Boolean =
        requireArguments().getInt(ARG_MODE, MODE_BASE_SINGLE) == MODE_BASE_SINGLE

    private fun allCurrencies(): List<Row.Currency> =
        provideAllCurrencies?.invoke().orEmpty()

    private fun favoritesCodes(): Set<String> =
        provideFavoritesCodes?.invoke().orEmpty()

    private fun selectedSecondaryCodes(): Set<String> =
        provideSelectedSecondaryCodes?.invoke().orEmpty()

    private fun currentBaseCode(): String? =
        provideCurrentBaseCode?.invoke()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = Dialog(requireContext())
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_currency_list)
        d.setCanceledOnTouchOutside(true)
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)

        bindViews(d)
        readArgs()
        setupUi()
        rebuildAll()

        return d
    }

    override fun onStart() {
        super.onStart()
        // ширина: single — уже, multi — шире
        dialog?.window?.let { w ->
            val dm = resources.displayMetrics
            val width = (dm.widthPixels * if (isBaseSingle()) 0.86f else 0.92f).toInt()
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun bindViews(d: Dialog) {
        tvTitle = d.findViewById(R.id.title)
        btnClose = d.findViewById(R.id.btnClose)
        etSearch = d.findViewById(R.id.searchInput)
        rvList = d.findViewById(R.id.currencyList)
        btnOk = d.findViewById(R.id.btnOk)

        // multi-only views (мы добавим их в layout ниже)
        selectedContainer = d.findViewById(R.id.selectedContainer)
        rvSelected = d.findViewById(R.id.selectedGrid)
    }

    private fun readArgs() {
        disabledCodes.clear()
        disabledCodes.addAll(requireArguments().getStringArrayList(ARG_DISABLED_CODES).orEmpty())

        selectedSecondaryLocal.clear()
        if (!isBaseSingle()) {
            selectedSecondaryLocal.addAll(selectedSecondaryCodes())
        }
    }

    private val searchWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = rebuildListOnly()
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    private fun setupUi() {
        btnClose.setOnClickListener { dismissAllowingStateLoss() }

        etSearch.removeTextChangedListener(searchWatcher)
        etSearch.addTextChangedListener(searchWatcher)

        if (isBaseSingle()) {
            tvTitle.text = getString(R.string.title_base_currency)
            btnOk.visibility = View.GONE
            selectedContainer.visibility = View.GONE
        } else {
            // ВАЖНО: обнови строку в strings на "… (мин. 1, макс. 20)"
            tvTitle.text = getString(R.string.title_secondary_currencies)
            btnOk.visibility = View.VISIBLE
            selectedContainer.visibility = View.VISIBLE
        }

        btnOk.setOnClickListener {
            if (selectedSecondaryLocal.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.warn_need_at_least_one),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            onApplySecondary?.invoke(selectedSecondaryLocal.toSet())
            dismissAllowingStateLoss()
        }

        // selected chips (multi)
        rvSelected.layoutManager = GridLayoutManager(requireContext(), 3)
        rvSelected.itemAnimator = null
        selectedAdapter = SelectedChipAdapter(
            onRemove = { code ->
                if (selectedSecondaryLocal.remove(code)) {
                    onToggleSecondaryExternal?.invoke(code)
                    rebuildAll()
                }
            }
        )
        rvSelected.adapter = selectedAdapter

        // main list
        rvList.layoutManager = LinearLayoutManager(requireContext())
        rvList.itemAnimator = null
        listAdapter = CurrencyListAdapter(
            context = requireContext(),
            isBaseSingle = isBaseSingle(),
            disabledCodesProvider = { disabledCodes },
            isFavorite = { favoritesCodes().contains(it) },
            onToggleFavorite = { code ->
                onToggleFavorite?.invoke(code)
                // фавы влияют на секции
                rebuildAll()
            },
            isSelected = { code ->
                if (isBaseSingle()) {
                    currentBaseCode() == code
                } else {
                    selectedSecondaryLocal.contains(code)
                }
            },
            onRowClick = { code ->
                if (disabledCodes.contains(code)) return@CurrencyListAdapter
                if (isBaseSingle()) {
                    onPickBase?.invoke(code)
                    dismissAllowingStateLoss()
                } else {
                    toggleSecondary(code)
                }
            }
        )
        rvList.adapter = listAdapter
    }

    private fun toggleSecondary(code: String) {
        val had = selectedSecondaryLocal.contains(code)
        if (had) {
            selectedSecondaryLocal.remove(code)
            onToggleSecondaryExternal?.invoke(code)
            rebuildAll()
            return
        }

        if (selectedSecondaryLocal.size >= MAX_SECONDARY) {
            // чтобы не плодить новых строк — просто тостом на существующую, если хочешь.
            // но у тебя нет готовой строки "max 20" -> поэтому без новых ключей:
            Toast.makeText(requireContext(), "Максимум $MAX_SECONDARY", Toast.LENGTH_SHORT).show()
            return
        }

        selectedSecondaryLocal.add(code)
        onToggleSecondaryExternal?.invoke(code)
        rebuildAll()
    }

    private fun rebuildAll() {
        rebuildSelectedChips()
        rebuildListOnly()
    }

    private fun rebuildSelectedChips() {
        if (isBaseSingle()) return

        val all = allCurrencies()
        val selected = selectedSecondaryLocal
            .mapNotNull { code -> all.find { it.code == code } }
        selectedAdapter.submit(selected)
    }

    private fun rebuildListOnly() {
        val query = etSearch.text?.toString().orEmpty().trim().lowercase()
        val all = allCurrencies()
        val favCodes = favoritesCodes()

        fun matches(c: Row.Currency): Boolean {
            if (query.isEmpty()) return true
            return c.code.lowercase().contains(query) || c.name.lowercase().contains(query)
        }

        val filteredAll = all.filter(::matches)

        val rows: List<Row> = if (isBaseSingle()) {
            val current = currentBaseCode()?.let { code -> all.find { it.code == code } }
            val favorites = filteredAll.filter { it.code in favCodes && it.code != current?.code }
            val others = filteredAll.filter { it.code !in favCodes && it.code != current?.code }

            buildList {
                if (current != null) {
                    add(Row.SectionHeader(getString(R.string.section_selected_one)))
                    add(current)
                    add(Row.Divider)
                }
                if (favorites.isNotEmpty()) {
                    add(Row.SectionHeader(getString(R.string.section_favorites)))
                    addAll(favorites)
                    add(Row.Divider)
                }
                add(Row.SectionHeader(getString(R.string.section_all)))
                addAll(others)
            }
        } else {
            // multi: выбранные мы показываем ВВЕРХУ чипами, поэтому из списка их убираем
            val selectedCodes = selectedSecondaryLocal.toSet()

            val favorites = filteredAll.filter { it.code in favCodes && it.code !in selectedCodes }
            val others = filteredAll.filter { it.code !in favCodes && it.code !in selectedCodes }

            buildList {
                // если хочешь — можешь добавить заголовок "Избранное" только если есть
                if (favorites.isNotEmpty()) {
                    add(Row.SectionHeader(getString(R.string.section_favorites)))
                    addAll(favorites)
                    add(Row.Divider)
                }
                add(Row.SectionHeader(getString(R.string.section_others)))
                addAll(others)
            }
        }

        listAdapter.submit(rows)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::etSearch.isInitialized) etSearch.removeTextChangedListener(searchWatcher)
    }

    // ===== models =====
    sealed class Row {
        data class Currency(val code: String, val name: String, val iconRes: Int? = null) : Row()
        data class SectionHeader(val title: String) : Row()
        data object Divider : Row()
    }

    // ===== selected chips adapter (3 per row) =====
    private class SelectedChipVH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val code: TextView = v.findViewById(R.id.code)
    }

    private class SelectedChipAdapter(
        private val onRemove: (String) -> Unit
    ) : RecyclerView.Adapter<SelectedChipVH>() {

        private var items: List<Row.Currency> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Row.Currency>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectedChipVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.row_currency_chip, parent, false)
            return SelectedChipVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: SelectedChipVH, position: Int) {
            val item = items[position]

            holder.code.text = item.code
            holder.icon.setImageResource(item.iconRes ?: R.drawable.ic_currency_placeholder)

            holder.itemView.setOnClickListener {
                onRemove(item.code)
            }
        }
    }

    // ===== main list adapter (sections + currencies) =====
    private class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.sectionTitle)
        fun bind(text: String) {
            title.text = text
        }
    }

    private class DividerVH(v: View) : RecyclerView.ViewHolder(v)

    private class CurrencyVH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val code: TextView = v.findViewById(R.id.code)
        val name: TextView = v.findViewById(R.id.name)
        val heart: ImageView = v.findViewById(R.id.fav)
    }

    private class CurrencyListAdapter(
        private val context: Context,
        private val isBaseSingle: Boolean,
        private val disabledCodesProvider: () -> Set<String>,
        private val isFavorite: (String) -> Boolean,
        private val onToggleFavorite: (String) -> Unit,
        private val isSelected: (String) -> Boolean,
        private val onRowClick: (String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<Row> = emptyList()

        private companion object {
            private const val TYPE_SECTION = 1
            private const val TYPE_CURRENCY = 2
            private const val TYPE_DIVIDER = 3
        }

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Row>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is Row.SectionHeader -> TYPE_SECTION
            is Row.Currency -> TYPE_CURRENCY
            Row.Divider -> TYPE_DIVIDER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_SECTION -> SectionVH(inf.inflate(R.layout.row_section, parent, false))
                TYPE_DIVIDER -> DividerVH(inf.inflate(R.layout.row_divider, parent, false))
                else -> CurrencyVH(inf.inflate(R.layout.row_currency, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is Row.SectionHeader -> (holder as SectionVH).bind(row.title)
                Row.Divider -> Unit
                is Row.Currency -> bindCurrency(holder as CurrencyVH, row)
            }
        }

        private fun bindCurrency(vh: CurrencyVH, row: Row.Currency) {
            val code = row.code
            val disabled = disabledCodesProvider().contains(code)

            vh.icon.setImageResource(row.iconRes ?: R.drawable.ic_currency_placeholder)
            vh.code.text = code
            vh.name.text = row.name

            val selected = isSelected(code)
            vh.code.setTypeface(vh.code.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            vh.name.setTypeface(vh.name.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            vh.itemView.isEnabled = !disabled
            vh.itemView.alpha = if (disabled) 0.4f else 1f
            vh.heart.visibility = View.VISIBLE

            fun applyHeart(fav: Boolean) {
                vh.heart.setImageResource(if (fav) R.drawable.ic_favorite_24 else R.drawable.ic_favorite_border_24)
                val color = ContextCompat.getColor(
                    context,
                    if (fav) R.color.fav_pink else R.color.fav_orange
                )
                ImageViewCompat.setImageTintList(vh.heart, ColorStateList.valueOf(color))
                vh.heart.contentDescription = context.getString(
                    if (fav) R.string.cd_favorite_remove else R.string.cd_favorite
                )
            }

            applyHeart(isFavorite(code))

            vh.heart.setOnClickListener {
                if (disabled) return@setOnClickListener
                onToggleFavorite(code)
                applyHeart(isFavorite(code))
            }

            vh.itemView.setOnClickListener {
                if (disabled) return@setOnClickListener
                onRowClick(code)
            }
        }
    }
}
