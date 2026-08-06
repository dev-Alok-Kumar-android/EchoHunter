package com.appsbyalok.echohunter

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.window.OnBackInvokedDispatcher
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.data.UpgradeSystem
import com.appsbyalok.echohunter.utils.EchoAudioManager
import com.appsbyalok.echohunter.view.GameView

/**
 * Main activity for the EchoHunter game application.
 *
 * Serves as the primary entry point, hosting the custom [GameView] and managing lifecycle events,
 * window insets (notches/safe areas), system bar visibility (fullscreen/immersive mode),
 * display orientation preferences, and back navigation across different Android API levels.
 */
class MainActivity : Activity() {
    private lateinit var gameView: GameView

    /**
     * Initializes singletons, views, cutout/system bar insets listeners, and back handlers.
     * Sets immersive display mode and restores state if available.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SaveManager.init(this)
        UpgradeSystem.init(this)

        gameView = GameView(this)
        setContentView(gameView)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Notch / Safe Area Insets Handling
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            gameView.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val displayCutout = insets.getInsets(WindowInsets.Type.displayCutout())
                
                SaveManager.lastInsetTop = maxOf(bars.top, displayCutout.top).toFloat()
                SaveManager.lastInsetBottom = maxOf(bars.bottom, displayCutout.bottom).toFloat()
                SaveManager.lastInsetLeft = maxOf(bars.left, displayCutout.left).toFloat()
                SaveManager.lastInsetRight = maxOf(bars.right, displayCutout.right).toFloat()
                gameView.resolveHudLayout()
                
                insets
            }
        }

        savedInstanceState?.let { gameView.restoreState(it) }
        applyOrientation()

        // Android 13+ Back Navigation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (!gameView.handleBackPressed()) finish()
            }
        }

        // Immersive Mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    /**
     * Pauses the game loop and sound playback when the activity goes into the background.
     */
    override fun onPause() {
        super.onPause()
        gameView.onPause()
    }

    /**
     * Resumes the game loop and audio playback when the activity comes to the foreground.
     */
    override fun onResume() {
        super.onResume()
        gameView.onResume()
    }

    /**
     * Saves game state to [outState] bundle during activity destruction or configuration changes.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        gameView.saveState(outState)
    }

    /**
     * Legacy back button handler for Android 12 and below.
     * Delegates back event handling to [GameView]. If unhandled by the game UI, triggers default back behavior.
     */
    @Deprecated("Deprecated in Java")
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!gameView.handleBackPressed()) {
            super.onBackPressed()
        }
    }

    /**
     * Releases audio and other hardware resources upon activity destruction.
     */
    override fun onDestroy() {
        super.onDestroy()
        EchoAudioManager.release() // Releasing audio resources
    }

    /**
     * Updates requested screen orientation based on user configuration stored in [SaveManager].
     */
    fun applyOrientation() {
        requestedOrientation = when (SaveManager.screenOrientation) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            0 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
