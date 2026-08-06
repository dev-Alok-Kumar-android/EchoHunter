package com.appsbyalok.echohunter.modes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.engine.AppStateId
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.systems.updateCameraLogic
import com.appsbyalok.echohunter.utils.GameColors
import kotlin.random.Random

// Game Mode 1: Story / Mainframe Salvation (The Infiltration)
class StoryMode : GameModeStrategy {
    override val modeId = 1
    private var lastSector = -1
    private var sectorStr = ""
    private var targetStr = ""

    override fun getIntroLines() = StoryProtocol.storyIntroLines

    override fun updateCameraAndMovement(dt: Float, gs: GameState, width: Float, height: Float, scale: Float) {
        // Use the centralized camera engine with mode-specific settings
        gs.updateCameraLogic(dt, width, height, leadMult = 0.1f)

        // Clamp the player within the visible screen (Story Mode restriction)
        // Only clamp if not in cinematic focus to prevent teleporting
        if (gs.cameraFocusWeight <= 0f) {
            val visibleW = gs.getViewportW(width, height)
            val visibleH = gs.getViewportH(width, height)
            val playerRadius = scale * 0.015f

            // Handle X clamping independently
            val targetPx = gs.px.coerceIn(gs.cameraX + playerRadius, gs.cameraX + visibleW - playerRadius)
            if (targetPx != gs.px && !gs.isCollidingWithWall(targetPx, gs.py, playerRadius)) {
                gs.px = targetPx
            }

            // Handle Y clamping independently
            val targetPy = gs.py.coerceIn(gs.cameraY + playerRadius, gs.cameraY + visibleH - playerRadius)
            if (targetPy != gs.py && !gs.isCollidingWithWall(gs.px, targetPy, playerRadius)) {
                gs.py = targetPy
            }
        }
    }

    override fun checkProgression(
        context: Context, gs: GameState, scale: Float,
        onTriggerBoss: (Int, Float) -> Unit, onSetStory: (IntArray, AppStateId) -> Unit
    ) {
        // Trigger a boss encounter when the target data requirement is met for the current node
        if (gs.score >= gs.sectorTarget && !gs.bossActive) {
            when(gs.currentSector) {
                1 -> {
                    StoryProtocol.showIngameMessage("ADMIN: \"UNAUTHORIZED UPLINK. SEVERING CONNECTION.\"", 3f)
                    onTriggerBoss(0, scale)
                }
                2 -> {
                    onSetStory(StoryProtocol.storyMidLines, AppStateId.PLAYING)
                    StoryProtocol.showIngameMessage("ADMIN: \"YOU ARE OUT OF YOUR DEPTH. LOCKING NODE 2.\"", 3f)
                    onTriggerBoss(1, scale)
                }
                3 -> {
                    StoryProtocol.showIngameMessage("ADMIN: \"INITIATING REVERSE TRACE... IP PARTIALLY ACQUIRED.\"", 3f)
                    onTriggerBoss(2, scale)
                }
                4 -> {
                    StoryProtocol.showIngameMessage("ADMIN: \"TRACE COMPLETE. WE KNOW WHERE YOU ARE.\"", 3f)
                    onTriggerBoss(3, scale)
                }
                5 -> {
                    StoryProtocol.showIngameMessage("ADMIN: \"ENACTING PURGE. YOUR HARDWARE WILL BURN.\"", 4f)
                    onTriggerBoss(4, scale)
                }
            }
        }

        // APT (Advanced Persistent Threat) LORE: Admin panics if player is on a 3-win streak
        if (SaveManager.unlockedStoryStreak >= 3 || SaveManager.unlockedHardStreak > 1) {
            // High chance of glitches because the system recognizes the Hacker as an imminent APT threat
            if (Random.nextDouble() < 0.015) StoryProtocol.triggerRandomGlitch(gs.score, modeId, gs.difficulty.id)

            // Just before the Omega Guardian on the 4th run, hint at the Blackout Protocol
            if (gs.currentSector == 5 && !gs.bossActive && gs.score >= gs.sectorTarget - 5) {
                if (!StoryProtocol.isBlackoutActive) {
                    StoryProtocol.showIngameMessage("ADMIN: \"APT CLASSIFICATION CONFIRMED. BLACKOUT PROTOCOL ENGAGED.\"", 4f)
                    StoryProtocol.isBlackoutActive = true
                }
            }
        } else {
            // Standard glitch rate
            if (Random.nextDouble() < 0.005) StoryProtocol.triggerRandomGlitch(gs.score, modeId, gs.difficulty.id)
        }
    }

    override fun getEnemySpawnPosition(gs: GameState, width: Float, height: Float, scale: Float): Pair<Float, Float> {
        // Enemies spawn randomly from slightly off-screen limits
        val x = gs.cameraX + if (Random.nextBoolean()) -scale * 0.1f else width + scale * 0.1f
        val y = Random.nextFloat() * height
        return Pair(x, y)
    }

    override fun drawModeSpecificWorld(c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, p: Paint) {
        // Space reserved for future Mainframe visual effects
    }

    override fun drawModeSpecificHUD(context: Context, c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, pText: Paint) {
        // Only format strings when the current sector changes to save CPU cycles
        if (gs.currentSector != lastSector) {
            sectorStr = "MAINFRAME NODE ${gs.currentSector}"
            lastSector = gs.currentSector
        }

        val topMargin = scale * 0.045f
        val centerY = topMargin

        // Draw Mainframe Node information
        pText.color = GameColors.CLARITY
        pText.textSize = scale * 0.032f
        c.drawText(sectorStr, width / 2f, centerY, pText)
    }
}
