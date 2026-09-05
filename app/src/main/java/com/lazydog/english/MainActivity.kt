package com.lazydog.english

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.lazydog.english.core.designsystem.CopyTone
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.core.designsystem.ProvideAppCopy

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = (application as LazyDogApplication).userPreferences
            val themeMode by prefs.themeMode.collectAsState(initial = "system")
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            // 语气和主题一样从偏好读一次、整棵树共用，切换后所有页面一起变。
            val toneWire by prefs.copyTone.collectAsState(initial = CopyTone.DEFAULT.wire)
            LazyDogTheme(darkTheme = darkTheme) {
                ProvideAppCopy(CopyTone.fromWire(toneWire)) {
                    LazyDogApp()
                }
            }
        }
    }
}
