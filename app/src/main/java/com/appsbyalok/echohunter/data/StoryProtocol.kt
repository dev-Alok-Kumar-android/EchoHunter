package com.appsbyalok.echohunter.data

import com.appsbyalok.echohunter.R
import kotlin.random.Random

object StoryProtocol {

    var currentPopupRes: Int = 0
    var currentPopupText: String? = null
    var currentPopupArg: Int? = null
    var popupTimer: Float = 0f
    var isGlitchActive: Boolean = false
    var areControlsInverted: Boolean = false
    var isBlackoutActive: Boolean = false
    var blackoutAlpha: Float = 0.8f // Controls depth of tutorial darkness

    // --- Typewriter Effect ---
    var typewriterText: String? = null
    var typewriterVisibleChars: Int = 0
    private var typewriterCharTimer: Float = 0f
    private val scrambleSymbols = "01#@$%&<>[]{}?+*=".toCharArray()

    /**
     * Returns a version of the text where the characters currently being revealed
     * are scrambled with hacker symbols for a "decryption" effect.
     */
    fun getScrambledTypewriterText(): String {
        val text = typewriterText ?: return ""
        val visible = typewriterVisibleChars
        if (visible >= text.length) return text

        val sb = StringBuilder(text.substring(0, visible))
        
        // Add 1-2 scrambling characters at the reveal point
        val scrambleCount = minOf(2, text.length - visible)
        for (i in 0 until scrambleCount) {
            val charAtPos = text[visible + i]
            if (charAtPos == ' ' || charAtPos == '\n') {
                sb.append(charAtPos)
            } else {
                sb.append(scrambleSymbols[Random.nextInt(scrambleSymbols.size)])
            }
        }
        
        // Add blinking cursor
        if ((System.currentTimeMillis() / 250) % 2 == 0L) {
            sb.append("█")
        }

        return sb.toString()
    }

    fun showIngameMessage(resId: Int, duration: Float = 3f) {
        currentPopupRes = resId
        currentPopupText = null
        currentPopupArg = null
        typewriterText = null
        popupTimer = duration
    }

    fun showIngameMessage(text: String, duration: Float = 3f) {
        currentPopupRes = 0
        currentPopupText = text
        typewriterText = null
        popupTimer = duration
    }

    fun showTypewriterMessage(text: String, duration: Float = 5f) {
        currentPopupRes = 0
        currentPopupText = null
        typewriterText = text
        typewriterVisibleChars = 0
        typewriterCharTimer = 0f
        popupTimer = duration
    }

    fun update(dt: Float) {
        if (popupTimer > 0f) {
            popupTimer -= dt
            
            // Handle Typewriter Progression
            typewriterText?.let { text ->
                if (typewriterVisibleChars < text.length) {
                    typewriterCharTimer += dt
                    // Hacker speed: Reveal roughly 20-30 chars per second
                    val charDelay = 0.035f 
                    while (typewriterCharTimer >= charDelay && typewriterVisibleChars < text.length) {
                        typewriterVisibleChars++
                        typewriterCharTimer -= charDelay
                        
                        // Optional: Play a tiny click sound here if supported
                    }
                }
            }
        } else {
            typewriterText = null
        }

        if (bossIntroTimer > 0f) bossIntroTimer -= dt
    }

    var bossIntroTimer: Float = 0f
    var currentBossNameRes: Int = 0

    fun startBossIntro(bossType: Int) {
        bossIntroTimer = 4f
        currentBossNameRes = when (bossType) {
            0 -> R.string.boss_0
            1 -> R.string.boss_1
            2 -> R.string.boss_2
            3 -> R.string.boss_3
            else -> R.string.boss_omega
        }
    }

    // --- Story Line Arrays ---
    val trainingIntroLines = intArrayOf(
        R.string.training_intro_1, R.string.training_intro_2, R.string.training_intro_3
    )

    val storyIntroLines = intArrayOf(
        R.string.story_main_1, R.string.story_main_2,
        R.string.story_main_3, R.string.story_main_4
    )
    val storyMidLines = intArrayOf(
        R.string.story_mid_1, R.string.story_mid_2, R.string.story_mid_3
    )
    val storyPerfectEnding = intArrayOf(
        R.string.story_perfect_1, R.string.story_perfect_2,
        R.string.story_perfect_3, R.string.story_perfect_4, R.string.story_perfect_5
    )
    val storyNeutralEnding = intArrayOf(
        R.string.story_neutral_1, R.string.story_neutral_2,
        R.string.story_neutral_3, R.string.story_neutral_4
    )
    val badEndingLines = intArrayOf(
        R.string.story_bad_1, R.string.story_bad_2, R.string.story_bad_3
    )

    fun triggerRandomGlitch(score: Long, gameMode: Int, difficulty: Int) {
        if (score > 150L && Random.nextDouble() < 0.05) {
            isGlitchActive = true
            showIngameMessage("ADMIN: \"I FOUND YOUR IP.\"", 2f)
        }

        // Inverse Controls Glitch (Admin tries to mess with the Hacker's controller)
        val isAPT = SaveManager.unlockedStoryStreak >= 3 || gameMode == 1
        val invertedChance = if (difficulty == 1 || isAPT) 0.15 else 0.02

        if (isGlitchActive && Random.nextDouble() < invertedChance) {
            areControlsInverted = true
            showIngameMessage("UPLINK INTERFERENCE: CONTROLS REVERSED", 2f)
        }
    }
}