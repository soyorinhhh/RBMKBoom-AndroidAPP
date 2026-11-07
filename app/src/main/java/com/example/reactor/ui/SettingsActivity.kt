package com.example.reactor.ui

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.example.reactor.R
import com.example.reactor.config.ReactorConfig

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // 必须先把 preferenceManager 的存储文件对齐到 ReactorConfig.PREF。
            // setPreferencesFromResource 默认写的是「包名_preferences」，
            // 而 ReactorConfig.load() 读的是 "reactor_config" —— 两个文件不是同一个，
            // 结果就是设置页能点、能选、能看着变，但应用侧永远读到默认值。
            preferenceManager.sharedPreferencesName = ReactorConfig.PREF
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE

            setPreferencesFromResource(R.xml.settings, rootKey)

            // 范围在代码里设置：不同 androidx.preference 版本对 app:min / app:max 的支持不一致，
            // 直接写 XML 会在 aapt 链接阶段报 "attribute max not found"。
            findPreference<SeekBarPreference>("sensitivity")?.apply {
                min = 2
                max = 30
                setSummaryProvider { "${value / 10f}x" }
            }

            findPreference<SeekBarPreference>(ReactorConfig.KEY_SMALL_SIZE)?.apply {
                // 范围已经在 settings.xml 的 android:min/max 里声明了（必须在 XML 阶段生效，
                // 否则 defaultValue=150 会先被 SeekBarPreference 内置的 max=100 夹掉）。
                //
                // 这里不设 summary provider：实测拖动后列表项不会重新绑定，
                // 底下的 summary 会停在旧值（显示 150 而实际已 225），比不显示更容易误导。
                // 当前值由 SeekBar 右侧的数字（showSeekBarValue）实时显示。
            }
        }
    }
}
