package com.daydream.standby.ui.settings

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daydream.standby.appContainer
import com.daydream.standby.ui.theme.DaydreamTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            DaydreamTheme {
                SettingsScreen(viewModel(factory = SettingsViewModel.factory(appContainer)), onBack = ::finish)
            }
        }
    }
}
