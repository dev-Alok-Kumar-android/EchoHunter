package com.appsbyalok.echohunter

import android.os.Bundle
import kotlin.math.*

// Shared constants for colors to avoid duplicate declarations across files
object GameColors {
    const val BG = 0xFF08080C.toInt()
    const val GRID = 0xFF141420.toInt()
    const val PULSE = 0xFF00FFFF.toInt()
    const val RED = 0xFFFF2A4D.toInt()
    const val YELLOW = 0xFFFFD700.toInt()
    const val TEXT = 0xFFEEEEEE.toInt()
    const val HP = 0xFF00FF7F.toInt()
    const val CLARITY = 0xFFFFFFFF.toInt()
    const val SHIELD = 0xFFAA00FF.toInt()
    const val OVERCLOCK = 0xFFFF5500.toInt()
    const val BOSS = 0xFFFF00FF.toInt()
    const val COOLANT = 0xFF00AAFF.toInt()
}

// Single Source of Truth for game data
class GameState {
    // UI & App State
    var state = 5 // 0: Menu, 1: Game, 2: Pause, 5/7: Story, 8: Cinematic
    var gameMode = 0
    var difficulty = 0
    var stateTimer = 0f
    var nextStateAfterStory = 0
    var timeSinceStart = 0f

    // Player State
    var px = 0f; var py = 0f
    var targetPx = 0f; var targetPy = 0f
    var hp = 3; val maxHp = 3
    var isTouching = false

    // Abilities & Timers
    var pulse = false; var pulseR = 0f
    var cooldownTimer = 0f
    var visionClarity = 1.0f
    var shieldTimer = 0f
    var playerIframe = 0f

    var overclockMeter = 0f
    var overclockTimer = 0f
    var showOverclockTextTimer = 0f
    val isOverclocked: Boolean get() = overclockTimer > 0f

    // Stats & Progression
    var score = 0; var combo = 0
    var maxCombo = 0
    var wave = 1
    var comboBreakTimer = 0f
    var currentSector = 1
    var sectorTarget = 30

    // Camera & Environment
    var cameraX = 0f
    var baseWorldSpeed = 0f

    // Effects & Feedback Variables
    var damageFlash = 0f
    var sectorFlash = 0f
    var shakeAmount = 0f
    var empFlashTimer = 0f

    // Radar / Heartbeat Ping System
    var isEnemyNear = false
    var isEnemyVeryNear = false
    var radarPingTimer = 0f
    var heartbeatTimer = 0f

    // Firewall (Game Mode 2)
    var firewallWorldX = 0f
    var firewallOffset = -100f
    val obsCount = 4
    val obsX = FloatArray(obsCount)
    val obsGapY = FloatArray(obsCount)
    val obsGapSize = FloatArray(obsCount)
    val obsType = IntArray(obsCount)

    // Boss State
    var bossActive = false
    var bossHp = 0; var bossMaxHp = 0
    var bossX = -1000f; var bossY = -1000f
    var bossIframe = 0f
    var bossType = 0

    // Optimization: Pre-calculated math variables shared across rendering
    var innerRSq = 0f
    var outerRSq = 0f
    var passiveAuraRadiusSq = 0f
    var fadeMultiplier = 1f

    // Time & Storage
    var timeScale = 1.0f
    var slowMoTimer = 0f

    fun saveState(b: Bundle) {
        b.putInt("state", state)
        b.putInt("difficulty", difficulty)
        b.putInt("score", score)
        b.putInt("gameMode", gameMode)
        b.putInt("hp", hp)
    }

    fun restoreState(b: Bundle) {
        state = b.getInt("state", 5)
        difficulty = b.getInt("difficulty", 0)
        score = b.getInt("score", 0)
        gameMode = b.getInt("gameMode", 0)
        hp = b.getInt("hp", 3)
    }

    fun resetGame() {
        score = 0; combo = 0; hp = maxHp; wave = 1
        visionClarity = 1.0f; shieldTimer = 0f; playerIframe = 0f
        overclockMeter = 0f; overclockTimer = 0f
        cameraX = 0f; firewallWorldX = cameraX - 1000f // Gets overridden
        currentSector = 1; sectorTarget = 30; bossActive = false
        empFlashTimer = 0f; comboBreakTimer = 0f

        StoryProtocol.popupTimer = 0f
        StoryProtocol.isGlitchActive = false
        StoryProtocol.areControlsInverted = false

        timeScale = 1.0f
        slowMoTimer = 0f
    }

    // Handles decrementing logic for all active status effects/timers
    fun updateTimers(dt: Float) {
        if (playerIframe > 0f) playerIframe -= dt
        if (showOverclockTextTimer > 0f) showOverclockTextTimer -= dt
        if (comboBreakTimer > 0f) comboBreakTimer -= dt
        if (shieldTimer > 0f) shieldTimer -= dt
        if (bossIframe > 0f) bossIframe -= dt
        if (cooldownTimer > 0f) cooldownTimer -= dt
        if (empFlashTimer > 0f) empFlashTimer -= dt

        if (visionClarity < 1.0f) visionClarity = min(1.0f, visionClarity + 0.1f * dt)
        else if (visionClarity > 1.0f) visionClarity = max(1.0f, visionClarity - 0.1f * dt)

        if (overclockTimer > 0f) {
            overclockTimer -= dt
            overclockMeter = (overclockTimer / 5f) * 100f
            if (overclockTimer <= 0f) EchoAudioManager.playSound(android.media.ToneGenerator.TONE_CDMA_PIP, 100)
        } else if (overclockMeter > 0f) {
            val drainSpeed = if(difficulty == 0) 5f else 10f
            overclockMeter = max(0f, overclockMeter - drainSpeed * dt)
        }
    }

    fun updatePulseRadius(dt: Float, maxRad: Float) {
        if (pulse) {
            pulseR += maxRad * 2.5f * dt
            if (pulseR > maxRad) { pulse = false; pulseR = 0f }
        }
    }

    fun updatePlayerMovement(dt: Float, height: Float, scale: Float) {
        val targetWorldX = cameraX + targetPx
        val pdx = targetWorldX - px
        val pdy = targetPy - py
        val pDistSq = pdx * pdx + pdy * pdy
        val pSpeed = scale * (if (isOverclocked) 1.5f else 1.0f)
        val pSpeedDt = pSpeed * dt

        if (pDistSq > pSpeedDt * pSpeedDt) {
            val pDist = sqrt(pDistSq)
            px += (pdx / pDist) * pSpeedDt
            py += (pdy / pDist) * pSpeedDt
        } else {
            px = targetWorldX
            py = targetPy
        }

        val playerRadius = scale * 0.015f
        if (py < playerRadius) py = playerRadius
        if (py > height - playerRadius) py = height - playerRadius
    }

    // Handles the infinite background scrolling and Firewall logic
    fun updateCameraAndFirewall(dt: Float, width: Float, scale: Float) {
        if (gameMode == 2) {
            val fwScrollMult = if (difficulty == 0) 0.8f else 1.0f
            val currentScrollSpeed = baseWorldSpeed + (score * scale * 0.005f * fwScrollMult)
            cameraX += currentScrollSpeed * dt

            val screenPx = px - cameraX
            if (screenPx < width * 0.1f) px = cameraX + width * 0.1f
            if (screenPx > width * 0.8f) px = cameraX + width * 0.8f

            if (!isTouching) targetPx = px - cameraX

            // Firewall logic
            val baseCreep = if (difficulty == 0) 0.015f else 0.035f
            var fwWorldSpeed = currentScrollSpeed + (scale * baseCreep)
            if (difficulty == 1 && timeSinceStart % 10f > 8f) fwWorldSpeed += scale * 0.2f

            firewallWorldX += fwWorldSpeed * dt
            if (firewallWorldX < cameraX - width * 0.8f) firewallWorldX = cameraX - width * 0.8f
            firewallOffset = firewallWorldX - cameraX
        } else {
            val screenPx = px - cameraX
            if (screenPx > width * 0.6f) cameraX += (screenPx - width * 0.6f) * 5f * dt
            else if (screenPx < width * 0.2f && cameraX > 0) cameraX += (screenPx - width * 0.2f) * 5f * dt
            if (cameraX < 0) cameraX = 0f

            if (px < cameraX) px = cameraX
            if (px > cameraX + width) px = cameraX + width
        }
    }

    // Pre-calculates distance bounds to reduce Math.sqrt usage in draw loops
    fun updateVisibilityMath(scale: Float, maxRad: Float) {
        val passiveAuraRadius = scale * 0.12f
        passiveAuraRadiusSq = passiveAuraRadius * passiveAuraRadius
        fadeMultiplier = min(0.99f, 0.80f + 0.16f * visionClarity)

        val echoThickness = maxRad * 0.05f
        if (pulse) {
            val innerR = max(0f, pulseR - echoThickness)
            val outerR = pulseR + echoThickness
            innerRSq = innerR * innerR
            outerRSq = outerR * outerR
        } else {
            innerRSq = 0f
            outerRSq = 0f
        }
    }

    fun getNextObstacleX(width: Float): Float {
        var maxX = cameraX + width
        for (j in 0 until obsCount) {
            if (obsX[j] > maxX) maxX = obsX[j]
        }
        return maxX + (width * 0.6f)
    }

    fun randomizeObstacle(i: Int, height: Float) {
        val diffMult = if (difficulty == 0) 0.8f else 1.0f
        val gapBase = height * 0.4f
        obsGapSize[i] = max(height * (if(difficulty==0) 0.35f else 0.25f), gapBase - (score * 0.002f * height * diffMult))
        obsGapY[i] = (height * 0.1f) + kotlin.random.Random.nextFloat() * (height * 0.8f - obsGapSize[i])

        val baseRedChance = if (score < 15) 0.0 else (score - 15) * 0.02
        val maxRedChance = if (difficulty == 0) 0.3 else 0.8
        val redChance = min(maxRedChance, baseRedChance)
        obsType[i] = if (kotlin.random.Random.nextDouble() < redChance) 1 else 0
    }
}