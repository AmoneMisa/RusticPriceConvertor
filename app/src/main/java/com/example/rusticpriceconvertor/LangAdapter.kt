package com.example.rusticpriceconvertor

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

data class LangItem(val persistCode: String, val displayCode: String, val labelRes: Int)

class LangAdapter(
    ctx: Context,
    private val items: List<LangItem>
) : ArrayAdapter<LangItem>(ctx, android.R.layout.simple_spinner_item, items) {

    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = super.getView(position, convertView, parent) as TextView
        v.text = items[position].displayCode
        return v
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = super.getDropDownView(position, convertView, parent) as TextView
        v.text = context.getString(items[position].labelRes)
        return v
    }
}