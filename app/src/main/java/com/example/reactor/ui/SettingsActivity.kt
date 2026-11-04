package com.example.reactor.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.example.reactor.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings, rootKey)

            // 范围在代码里设置：不同 androidx.preference 版本对 app:min / app:max 的支持不一致，
            // 直接写 XML 会在 aapt 链接阶段报 "attribute max not found"。
            findPreference<SeekBarPreference>("sensitivity")?.apply {
                min = 2
                max = 30
                setSummaryProvider { "${value / 10f}x" }
            }
        }
    }
}
