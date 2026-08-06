package com.appsbyalok.echohunter.modes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.appsbyalok.echohunter.R
import com.appsbyalok.echohunter.data.LevelEngine
import com.appsbyalok.echohunter.data.LevelFeature
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.engine.AppStateId
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.systems.updateCameraLogic
import com.appsbyalok.echohunter.utils.GameColors
import kotlin.random.Random

// Game Mode 0: The Sandbox Simulation (Act 1: First Contact)
class CampaignMode : GameModeStrategy {
    override val modeId = 0
    private var lastLevel = -1
    private var levelStr = ""

    private var bossSpawnedForLevel = -1

    // Meta-Narrative: Player intercepts the drone signal
    private val sandboxIntroLines = intArrayOf(
        R.string.lore_sandbox_1, // "> UNKNOWN SIGNAL INTERCEPTED..."
        R.string.lore_sandbox_2, // "> CONNECTING TO MAINTENANCE DRONE: PROBE-7..."
        R.string.lore_sandbox_3  // "> UPLINK ESTABLISHED. MANUAL CONTROL ACTIVE."
    )

    override fun getIntroLines() = sandboxIntroLines

    override fun updateCameraAndMovement(dt: Float, gs: GameState, width: Float, height: Float, scale: Float) {
        // Use the centralized camera engine with mode-specific settings
        gs.updateCameraLogic(dt, width, height, leadMult = 0.15f)
    }

    override fun checkProgression(
        context: Context,
        gs: GameState, scale: Float,
        onTriggerBoss: (Int, Float) -> Unit,
        onSetStory: (IntArray, AppStateId) -> Unit
    ) {
        if (gs.currentLevel == Int.MAX_VALUE) {
            StoryProtocol.showIngameMessage(R.string.lore_system_overflow, 10f)
            gs.score = 0
            return
        }

        val config = LevelEngine.getLevelConfig(gs.currentLevel)

        // Stop spawning if level is already cleared
        if (gs.isLevelCleared) return

        // --- MODULAR PROGRESSION ---
        // Use the objective's trigger logic to spawn the boss
        if (gs.activeObjective.isBossTriggerReady(gs)) {
            if (config.features.contains(LevelFeature.BOSS) && !gs.bossActive && bossSpawnedForLevel != gs.currentLevel) {
                bossSpawnedForLevel = gs.currentLevel
                
                // BOSS SELECTION LOGIC
                // Lvl 5: Guardian (Type 0)
                // Lvl 10: Swarm Mother (Type 1)
                // Lvl 15: Glitcher (Type 2)
                // Lvl 20: Admin (Type 3)
                // Lvl 25: Singularity (Type 4)
                // Lvl 30+: Random but weighted
                val bossType = when {
                    gs.currentLevel == 5 -> 0
                    gs.currentLevel == 10 -> 1
                    gs.currentLevel == 15 -> 2
                    gs.currentLevel == 20 -> 3
                    gs.currentLevel == 25 -> 4
                    gs.currentLevel < 5 -> 0 // Early bosses always Guardian
                    else -> Random.nextInt(0, 5)
                }
                onTriggerBoss(bossType, scale)
            }
        }
    }

    override fun getEnemySpawnPosition(gs: GameState, width: Float, height: Float, scale: Float): Pair<Float, Float> {
        val x = if (Random.nextBoolean()) gs.cameraX + Random.nextFloat() * width else gs.cameraX + if (Random.nextBoolean()) -scale * 0.1f else width + scale * 0.1f
        val y = if (Random.nextBoolean()) -scale * 0.1f else height + scale * 0.1f
        return Pair(x, y)
    }

    override fun drawModeSpecificWorld(c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, p: Paint) {
        // Can be expanded to include specific lore-based background graphics
    }

    override fun drawModeSpecificHUD(context: Context, c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, pText: Paint) {
        // Level text logic
        if (gs.currentLevel != lastLevel) {
            levelStr = "SECURITY RING ${gs.currentLevel}"
            lastLevel = gs.currentLevel
        }

        val topMargin = scale * 0.05f
        val centerY = topMargin + scale * 0.02f

        // Top Header (Static Ring Level)
        pText.color = GameColors.CLARITY
        pText.textSize = scale * 0.032f
        c.drawText(levelStr,scale * 0.25f, centerY, pText)
    }
}
