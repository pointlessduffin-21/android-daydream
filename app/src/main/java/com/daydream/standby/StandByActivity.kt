package com.daydream.standby

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daydream.standby.ui.common.applyNightBrightness
import com.daydream.standby.ui.common.enterStandByMode
import com.daydream.standby.ui.settings.SettingsActivity
import com.daydream.standby.ui.standby.StandByScreen
import com.daydream.standby.ui.standby.StandByViewModel
import com.daydream.standby.ui.theme.DaydreamTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Launcher entry point: opens straight into StandBy, any time, charging or not. */
class StandByActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.enterStandByMode()
        observeShowWhenLocked()
        setContent {
            DaydreamTheme {
                StandByScreen(
                    viewModel = viewModel(factory = StandByViewModel.factory(appContainer)),
                    onOpenSettings = ::openSettings,
                    onNightModeChange = window::applyNightBrightness,
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.enterStandByMode()
    }

    private fun observeShowWhenLocked() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                appContainer.settings.settings.map { it.showWhenLocked }.distinctUntilChanged().collect(::applyShowWhenLocked)
            }
        }
    }

    private fun applyShowWhenLocked(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(show)
        } else {
            @Suppress("DEPRECATION")
            if (show) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    /** Settings may expose the API key, so require unlock before showing it over the keyguard. */
    private fun openSettings() {
        val keyguard = ContextCompat.getSystemService(this, KeyguardManager::class.java)
        val launch = { startActivity(Intent(this, SettingsActivity::class.java)) }
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = launch()
            })
        } else {
            launch()
        }
    }
}
