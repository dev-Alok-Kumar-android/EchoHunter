package com.appsbyalok.echohunter

import android.media.AudioManager
import android.media.ToneGenerator

// Singleton object for handling audio globally
object EchoAudioManager {
    private var tone: ToneGenerator? = null
    private var lastToneTime = 0L

    // Initialize once in your app/activity lifecycle
    fun init() {
        if (tone == null) {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        }
    }

    // Play sound with a small cooldown to prevent overlapping audio glitches
    fun playSound(toneType: Int, duration: Int) {
        val now = System.currentTimeMillis()
        if (now - lastToneTime > 40) {
            try {
                tone?.startTone(toneType, duration)
                lastToneTime = now
            } catch (_: Exception) {
                // Ignore audio errors to prevent crashes
            }
        }
    }

    // Release resources when game is closed
    fun release() {
        tone?.release()
        tone = null
    }
}