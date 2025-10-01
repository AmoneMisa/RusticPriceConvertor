package com.example.rusticpriceconvertor

import android.content.Context
import android.os.Bundle
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {
    private lateinit var languageControl: LanguageControl
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleUtil.updateBaseContextLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
