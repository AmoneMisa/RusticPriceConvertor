package com.example.rusticpriceconvertor

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView

// ---------- Модель строк ----------
sealed class Row {
    data class Currency(
        val code: String,
        val name: String,
        val iconRes: Int? = null
    ) : Row()

    data class SectionHeader(
        val title: String,
        val iconRes: Int? = null
    ) : Row()

    data object Divider : Row()
}

// ---------- Фильтр ----------
class CurrencyFilter(
    private val all: List<Row.Currency>,
    private val matcher: (Row.Currency, String) -> Boolean
) {
    fun filter(query: String): List<Row.Currency> {
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.filter { matcher(it, q) }
    }
}

// ---------- Сборка секций ----------
object CurrencySectionBuilder {

    fun forBase(
        ctx: Context,
        current: Row.Currency?,
        favorites: List<Row.Currency>,
        filteredAll: List<Row.Currency>
    ): List<Row> {
        val out = mutableListOf<Row>()

        val currentCode = current?.code
        val favCodes = favorites.map { it.code }.toSet()

        // 1) Выбранная
        if (current != null) {
            out += Row.SectionHeader(ctx.getString(R.string.section_selected_one))
            out += current
            out += Row.Divider
        }

        // 2) Избранное (без текущей)
        val favFiltered = favorites
            .filter { it.code != currentCode }
            .filter { f -> filteredAll.any { it.code == f.code } } // поиск тоже влияет
        if (favFiltered.isNotEmpty()) {
            out += Row.SectionHeader(ctx.getString(R.string.section_favorites))
            out += favFiltered
            out += Row.Divider
        }

        // 3) Общий список (без текущей и без фавов)
        val others = filteredAll.filter { it.code != currentCode && it.code !in favCodes }
        out += Row.SectionHeader(ctx.getString(R.string.section_all))
        out += others

        return out
    }

    fun forSecondary(
        ctx: Context,
        selected: List<Row.Currency>,             // уже выбранные
        favorites: List<Row.Currency>,            // избранные
        all: List<Row.Currency>,                  // всё
        filteredAll: List<Row.Currency>           // результат поиска
    ): List<Row> {
        val selectedCodes = selected.map { it.code }.toSet()
        val favCodes = favorites.map { it.code }.toSet()

        // Избранные показываем из фильтра, но без уже выбранных
        val filteredFavs = filteredAll.filter { it.code in favCodes && it.code !in selectedCodes }

        // Остальные — из фильтра, которых нет ни в selected, ни в favorites
        val others = filteredAll.filter { it.code !in selectedCodes && it.code !in favCodes }

        val out = mutableListOf<Row>()
        if (selected.isNotEmpty()) {
            out += Row.SectionHeader(ctx.getString(R.string.section_selected))
            out += selected.filter { s -> filteredAll.any { it.code == s.code } }
            out += Row.Divider
        }
        if (filteredFavs.isNotEmpty()) {
            out += Row.SectionHeader(ctx.getString(R.string.section_favorites))
            out += filteredFavs
            out += Row.Divider
        }
        out += Row.SectionHeader(ctx.getString(R.string.section_others))
        out += others
        return out
    }
}

// ---------- ViewHolders ----------
private class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.sectionTitle)
    private val icon: ImageView = view.findViewById(R.id.sectionIcon)

    fun bind(s: Row.SectionHeader) {
        title.text = s.title
        if (s.iconRes != null) {
            icon.visibility = View.VISIBLE
            icon.setImageResource(s.iconRes)
        } else {
            icon.visibility = View.GONE
        }
        // небольшой горизонтальный «воздух»
        val pad = (itemView.resources.displayMetrics.density * 10).toInt()
        itemView.setPadding(pad, itemView.paddingTop, pad, itemView.paddingBottom)
    }
}

private class DividerVH(view: View) : RecyclerView.ViewHolder(view)

private class CurrencyVH(view: View) : RecyclerView.ViewHolder(view) {
    val icon: ImageView = view.findViewById(R.id.icon)
    val code: TextView = view.findViewById(R.id.code)
    val name: TextView = view.findViewById(R.id.name)
    val heart: ImageView = view.findViewById(R.id.fav)
}

// ---------- Адаптер ----------
class CurrencyAdapter(
    private val context: Context,
    private val singleMode: Boolean,                        // true — выбор одной (база), false — мультивыбор
    private val isFavorite: (String) -> Boolean,
    private val onToggleFavorite: (String) -> Unit,
    private val isSelected: (String) -> Boolean,            // только для secondary (в singleMode можно передать {false})
    private val onToggleSelected: (String) -> Unit,         // только для secondary
    private val onSinglePick: (String) -> Unit,
    var onAfterToggle: (() -> Unit)? = null,                // для пересборки секций после кликов
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<Row> = emptyList()
    var disabledCodes: Set<String> = emptySet()

    private companion object {
        private const val TYPE_SECTION = 1
        private const val TYPE_CURRENCY = 2
        private const val TYPE_DIVIDER = 3
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newList: List<Row>) {
        items = newList
        notifyDataSetChanged()
    }

    private fun getItem(position: Int): Row = items[position]

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Row.SectionHeader -> TYPE_SECTION
        is Row.Currency -> TYPE_CURRENCY
        Row.Divider -> TYPE_DIVIDER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_SECTION -> {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.row_section, parent, false)
                SectionVH(v)
            }

            TYPE_DIVIDER -> {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.row_divider, parent, false)
                DividerVH(v)
            }

            else -> {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.row_currency, parent, false)
                CurrencyVH(v)
            }
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.SectionHeader -> (holder as SectionVH).bind(row)
            Row.Divider -> { /* no-op */
            }

            is Row.Currency -> bindCurrency(holder as CurrencyVH, row)
        }
    }

    private fun bindCurrency(vh: CurrencyVH, row: Row.Currency) {
        val code = row.code
        vh.code.text = code
        vh.name.text = row.name
        vh.icon.setImageResource(row.iconRes ?: R.drawable.ic_currency_placeholder)

        // жирным — если валюта выбрана (для secondary)
        val selected = isSelected(code)
        vh.code.setTypeface(vh.code.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        vh.name.setTypeface(vh.name.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)

        // disable, если это текущая базовая
        val disabled = disabledCodes.contains(code)
        vh.itemView.isEnabled = !disabled
        vh.itemView.alpha = if (disabled) 0.4f else 1f

        vh.heart.visibility = View.VISIBLE

        fun tintHeart(fav: Boolean) {
            // используем разные иконки: filled/border
            vh.heart.setImageResource(
                if (fav) R.drawable.ic_favorite_24 else R.drawable.ic_favorite_border_24
            )
            val color = ContextCompat.getColor(
                context,
                if (fav) R.color.fav_pink else R.color.fav_orange
            )
            ImageViewCompat.setImageTintList(vh.heart, ColorStateList.valueOf(color))
        }

        tintHeart(isFavorite(code))

        vh.heart.setOnClickListener {
            if (disabled) return@setOnClickListener
            onToggleFavorite(code)
            tintHeart(isFavorite(code))      // мгновенно обновляем вид
            onAfterToggle?.invoke()          // пересобрать секции (перетасовать блоки)
        }

        vh.itemView.setOnClickListener {
            if (disabled) return@setOnClickListener
            if (singleMode) {
                onSinglePick(code)               // базовая — выбираем и закрываем диалог снаружи
            } else {
                onToggleSelected(code)           // мультивыбор
                onAfterToggle?.invoke()          // пересобрать секции (переместить «Выбранные»)
            }
        }
    }
}
