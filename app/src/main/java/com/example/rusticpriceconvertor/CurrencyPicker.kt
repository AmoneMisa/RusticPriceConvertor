package com.example.rusticpriceconvertor

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView

// ---------- Модель строк ----------
sealed class Row {
    data class Section(val title: String, val icon: String? = null) : Row()
    data class Currency(val code: String, val name: String) : Row()
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
        current: Row.Currency?,                   // выбранная базовая
        recents: List<Row.Currency>,              // последние
        all: List<Row.Currency>,                  // всё
        filteredAll: List<Row.Currency>           // результат поиска
    ): List<Row> {
        val result = mutableListOf<Row>()

        // Текущая
        current?.let {
            result += Row.Section("Текущая")
            result += it
        }

        // Последние
        if (recents.isNotEmpty()) {
            result += Row.Section("Последние", "clock")
            result += recents.distinctBy { it.code }
        }

        // Остальные (фильтрованные)
        result += Row.Section("Остальные")
        val exclude = (listOfNotNull(current) + recents).map { it.code }.toSet()
        result += filteredAll.filter { it.code !in exclude }

        return result
    }

    fun forSecondary(
        selected: List<Row.Currency>,             // уже выбранные
        favorites: List<Row.Currency>,            // избранные
        all: List<Row.Currency>,                  // всё
        filteredAll: List<Row.Currency>           // результат поиска
    ): List<Row> {
        val result = mutableListOf<Row>()

        // Выбранные
        if (selected.isNotEmpty()) {
            result += Row.Section("Выбранные")
            result += selected.distinctBy { it.code }
        }

        // Избранные
        if (favorites.isNotEmpty()) {
            result += Row.Section("Избранные", "heart")
            // не дублируем то, что уже в "Выбранных"
            val selSet = selected.map { it.code }.toSet()
            result += favorites.filter { it.code !in selSet }.distinctBy { it.code }
        }

        // Остальные (фильтрованные)
        result += Row.Section("Остальные")
        val exclude = (selected + favorites).map { it.code }.toSet()
        result += filteredAll.filter { it.code !in exclude }

        return result
    }
}

// ---------- ViewHolders ----------
private class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.sectionTitle)
    private val icon: ImageView = view.findViewById(R.id.sectionIcon)
    fun bind(s: Row.Section) {
        title.text = s.title
        when (s.icon) {
            "clock" -> {
                icon.setImageResource(R.drawable.ic_access_time_24); icon.visibility = View.VISIBLE
            }

            "heart" -> {
                icon.setImageResource(R.drawable.ic_favorite_24); icon.visibility = View.VISIBLE
            }

            else -> {
                icon.visibility = View.GONE
            }
        }
    }
}

private class CurrencyVH(view: View) : RecyclerView.ViewHolder(view) {
    val code: TextView = view.findViewById(R.id.code)
    val name: TextView = view.findViewById(R.id.name)
    val heart: ImageView = view.findViewById(R.id.favoriteIcon)
}

// ---------- Адаптер ----------
class CurrencyAdapter(
    private val context: android.content.Context,
    private val singleMode: Boolean,                        // true — выбор одной (база), false — мультивыбор
    private val isFavorite: (String) -> Boolean,
    private val onToggleFavorite: (String) -> Unit,
    private val isSelected: (String) -> Boolean,            // только для secondary
    private val onToggleSelected: (String) -> Unit,         // только для secondary
    private val onSinglePick: (String) -> Unit              // только для base
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<Row> = emptyList()
    var disabledCodes: Set<String> = emptySet()
    companion object {
        private const val TYPE_SECTION = 1
        private const val TYPE_CURRENCY = 2

        @ColorInt
        private fun pink(): Int = "#D81B60".toColorInt() // розово-фиолетовый
        @ColorInt
        private fun black(): Int = "#000000".toColorInt()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newList: List<Row>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Row.Section -> TYPE_SECTION
        is Row.Currency -> TYPE_CURRENCY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_SECTION -> {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.row_section, parent, false)
                SectionVH(v)
            }

            else -> {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.row_currency, parent, false)
                CurrencyVH(v)
            }
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is Row.Section -> (holder as SectionVH).bind(row)
            is Row.Currency -> bindCurrency(holder as CurrencyVH, row)
        }
    }

    private fun bindCurrency(vh: CurrencyVH, row: Row.Currency) {
        val code = row.code
        vh.code.text = code
        vh.name.text = row.name

        // disable, если это текущая базовая
        val disabled = disabledCodes.contains(code)
        vh.itemView.isEnabled = !disabled
        vh.itemView.alpha = if (disabled) 0.4f else 1f

        // сердечко видно только в списке второстепенных
        if (singleMode) {
            vh.heart.visibility = View.GONE
        } else {
            vh.heart.visibility = View.VISIBLE

            fun tintHeart(fav: Boolean) {
                vh.heart.setImageResource(
                    if (fav) R.drawable.ic_favorite_24 else R.drawable.ic_favorite_border_24
                )
                val color = ContextCompat.getColor(
                    context,
                    if (fav) R.color.fav_pink else R.color.light_black
                )
                ImageViewCompat.setImageTintList(vh.heart, ColorStateList.valueOf(color))
            }

            tintHeart(isFavorite(code))

            vh.heart.setOnClickListener {
                if (disabled) return@setOnClickListener
                onToggleFavorite(code)
                tintHeart(isFavorite(code)) // мгновенно обновляем вид
            }
        }

        vh.itemView.setOnClickListener {
            if (disabled) return@setOnClickListener
            if (singleMode) onSinglePick(code) else {
                onToggleSelected(code)
                notifyItemChanged(vh.bindingAdapterPosition)
            }
        }
    }
}