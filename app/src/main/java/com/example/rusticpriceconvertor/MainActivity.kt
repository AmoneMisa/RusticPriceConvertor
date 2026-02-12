package com.example.rusticpriceconvertor

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Spinner
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.blongho.country_data.World
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var languageControl: LanguageControl
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleUtil.updateBaseContextLocale(newBase))
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        World.init(this)
        Log.d("FiatFlags", "World.init() called")
        thread {
            val symbols = CurrencyApi.fetchSymbols()
            Log.d("FiatFlags", "symbols.size=${symbols.size}")

            CurrencyIconProvider.updateFiatCodes(symbols)
            CurrencyIconProvider.updateCryptoIconUrls(symbols)
            FiatFlagProvider.buildIndex()
        }
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = statusBarHeight)
            insets
        }

        val languagePrefs = getSharedPreferences(LanguageControl.PREFS_NAME, MODE_PRIVATE)
        languageControl = LanguageControl(this, languagePrefs)
        val langSpinnerMain = findViewById<Spinner>(R.id.langSpinner)
        languageControl.setupLanguageSpinner(langSpinnerMain)

        val vp = findViewById<ViewPager2>(R.id.viewPager)
        val tabs = findViewById<TabLayout>(R.id.tabLayout)

        vp.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment =
                if (position == 0) ConverterFragment() else CompareFragment()
        }
        TabLayoutMediator(tabs, vp) { tab, pos ->
            tab.text =
                if (pos == 0) getString(R.string.tab_converter) else getString(R.string.tab_compare)
        }.attach()
    }
}
