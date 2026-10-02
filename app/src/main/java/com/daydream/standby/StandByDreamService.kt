package com.daydream.standby

import android.app.KeyguardManager
import android.content.Intent
import android.service.dreams.DreamService
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.daydream.standby.ui.common.applyNightBrightness
import com.daydream.standby.ui.settings.SettingsActivity
import com.daydream.standby.ui.standby.StandByScreen
import com.daydream.standby.ui.standby.StandByViewModel
import com.daydream.standby.ui.theme.DaydreamTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The same StandBy UI exposed as an Android screensaver (Settings › Display › Screen saver),
 * so it can also start automatically while charging or docked.
 */
class StandByDreamService : DreamService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /** Polled while dreaming: the keyguard can engage or clear (Smart Lock, face unlock) mid-dream. */
    private val deviceLocked = mutableStateOf(true)

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val keyguard = getSystemService(KeyguardManager::class.java)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    deviceLocked.value = keyguard?.isKeyguardLocked ?: true
                    delay(LOCK_POLL_MILLIS)
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                DaydreamTheme {
                    StandByScreen(
                        viewModel = viewModel(factory = StandByViewModel.factory(appContainer)),
                        onOpenSettings = {
                            startActivity(Intent(this@StandByDreamService, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        },
                        onNightModeChange = { night -> window?.applyNightBrightness(night) },
                        onExit = ::finish,
                        isDeviceLocked = deviceLocked.value,
                    )
                }
            }
        }
        window.decorView.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStopped() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    private companion object {
        const val LOCK_POLL_MILLIS = 1_000L
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }
}
