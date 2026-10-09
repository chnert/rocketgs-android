package com.rocketgs

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import com.rocketgs.ui.C
import com.rocketgs.ui.ConsoleScreen
import com.rocketgs.ui.SetupScreen

class MainActivity : ComponentActivity() {
    private val vm: GroundStationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleUsbIntent(intent)
        setContent {
            var setup by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = setup) { setup = false }

            // Keep the screen on while a source runs (optional, in SETUP).
            val view = LocalView.current
            val keepOn = vm.keepScreenOn && vm.source != null
            DisposableEffect(keepOn) {
                view.keepScreenOn = keepOn
                onDispose { view.keepScreenOn = false }
            }

            Box(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding()) {
                if (setup) SetupScreen(vm) { setup = false }
                else ConsoleScreen(vm) { setup = true }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    /** Opened because the receiver was plugged in: start reading it. */
    private fun handleUsbIntent(i: Intent?) {
        if (i?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && !vm.isUsbRunning()) vm.startUsb()
    }
}
