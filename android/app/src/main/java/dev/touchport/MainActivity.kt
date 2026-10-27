package dev.touchport

import android.app.Activity
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher

class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var link: Link
    private lateinit var gyro: Gyro
    private lateinit var view: ControllerView
    private var wifiLock: WifiManager.WifiLock? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) = updateRotation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        link = Link(settings)
        gyro = Gyro(this, link, settings)
        view = ControllerView(this, link, gyro, settings, ProfileStore(this)) {
            // Settings saved: apply connection and gyro changes.
            link.reconnect()
            gyro.stop()
            gyro.start()
        }
        link.onStatus = { view.postInvalidate() }
        setContentView(view)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            preferredDisplayModeId = fastestMode()
        }

        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = getSystemService(WifiManager::class.java)?.createWifiLock(mode, "TouchPort")?.apply {
            setReferenceCounted(false)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            // Back leaves edit mode. During play it does nothing, so a stray swipe cannot close the HUD.
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (view.editing) view.exitEdit()
            }
        }
    }

    @Deprecated("Used below Android 13 only")
    override fun onBackPressed() {
        if (view.editing) view.exitEdit()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        updateRotation()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        wifiLock?.acquire()
        link.start()
        gyro.start()
    }

    override fun onPause() {
        gyro.stop()
        view.releaseAll()
        link.stop()          // sends "bye", so the laptop releases every key at once
        wifiLock?.release()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        if (view.editing) view.exitEdit()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun currentDisplay(): Display =
        if (Build.VERSION.SDK_INT >= 30) display!! else @Suppress("DEPRECATION") windowManager.defaultDisplay

    private fun updateRotation() {
        gyro.rotation = currentDisplay().rotation
    }

    /** The highest refresh rate at the current resolution, so touch is read as often as possible. */
    private fun fastestMode(): Int {
        val d = currentDisplay()
        val cur = d.mode
        return d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }?.modeId ?: 0
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }
}
