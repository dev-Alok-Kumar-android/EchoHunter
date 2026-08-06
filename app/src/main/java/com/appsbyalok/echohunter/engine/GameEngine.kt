package com.appsbyalok.echohunter.engine

import android.content.Context
import android.media.ToneGenerator
import com.appsbyalok.echohunter.data.LevelEngine
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.data.UpgradeSystem
import com.appsbyalok.echohunter.systems.ArsenalSystem
import com.appsbyalok.echohunter.systems.CollisionSystem
import com.appsbyalok.echohunter.systems.EffectSystem
import com.appsbyalok.echohunter.systems.EnemySystem
import com.appsbyalok.echohunter.systems.PlayerAI
import com.appsbyalok.echohunter.systems.SpawnerSystem
import com.appsbyalok.echohunter.utils.EchoAudioManager

/**
 * Central game engine orchestrating state updates, system ticks, combat events, and audio feedback.
 *
 * Coordinates physics, enemy AI, spawner routines, arsenal actions, and collision checks per frame tick.
 * Communicates game state changes back to UI components via event callbacks.
 */
class GameEngine(
    private val gs: GameState,
    private val effectSys: EffectSystem,
    private val enemySys: EnemySystem,
    private val spawnerSys: SpawnerSystem,
    private val collisionSys: CollisionSystem,
    private val context: Context
) {

    var onChangeState: ((AppStateId) -> Unit)? = null
    var onDamage: ((Float) -> Unit)? = null
    var onScore: ((Long) -> Unit)? = null
    var onCoreUnlock: ((Boolean) -> Unit)? = null
    var onBossTrigger: ((Int, Float) -> Unit)? = null
    var onStoryState: ((IntArray, AppStateId) -> Unit)? = null

    private val arsenalSys = ArsenalSystem(gs, effectSys, spawnerSys, enemySys)
    private val playerAI = PlayerAI(gs, enemySys)
    private val inputSys = com.appsbyalok.echohunter.systems.InputSystem(gs)

    init {
        enemySys.setEffectSystem(effectSys)
    }

    fun update(dt: Float, targetW: Float, targetH: Float, scale: Float) {
        gs.lastDt = dt
        gs.timeSinceStart += dt
        gs.stateTimer += dt
        StoryProtocol.update(dt)
        gs.isBlackoutActive = StoryProtocol.isBlackoutActive

        if (gs.state == AppStateId.MENU) {
            effectSys.update(dt, targetH)
            return
        }

        if (gs.state == AppStateId.HELP) return
        if (!gs.state.isGameplay) return

        if (gs.hitStopTimer > 0f) {
            gs.hitStopTimer -= dt
            return
        }

        gs.updateTimers(dt, scale)
        val simDt = if (gs.slowMoTimer > 0f) dt * 0.15f else dt

        if (gs.modInfiniteOvr) {
            gs.overclockMeter = 100f
            if (gs.isOverclocked) gs.overclockTimer = 5f
        }

        if (gs.state == AppStateId.PLAYING || gs.state == AppStateId.CORE_MERGE) {
            inputSys.update(simDt, scale, enemySys)
            if (gs.isAutoPilotActive) playerAI.update(simDt, scale)
            arsenalSys.update(simDt, scale)

            if (gs.controls.isOverclockPressed && gs.overclockMeter >= 100f && !gs.isOverclocked) activateOverclock(scale)

            for (i in 0 until gs.maxSpikes) {
                if (gs.spikeActive[i]) {
                    gs.spikeX[i] += gs.spikeVx[i] * simDt
                    gs.spikeY[i] += gs.spikeVy[i] * simDt
                    gs.spikeLife[i] -= simDt
                    if (gs.spikeLife[i] <= 0f) gs.spikeActive[i] = false
                }
            }

            gs.updatePlayerMovement(simDt, targetW, targetH, scale)

            // --- FIX: Camera update should always follow ModeStrategy ---
            gs.updateCameraAndMovement(simDt, targetW, targetH ,scale)

            if (gs.state == AppStateId.CORE_MERGE) {
                val cdx = gs.px - gs.coreX
                val cdy = gs.py - gs.coreY
                if (cdx * cdx + cdy * cdy < gs.coreRadius * gs.coreRadius) {
                    gs.state = AppStateId.PERFECT_END_ZOOM
                    gs.mergeTimer = 0f
                    gs.whiteFlash = 0f
                    gs.isTouching = false
                    EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_ALERT, 800)
                }
            }
        }

        if (gs.state == AppStateId.PERFECT_END_ZOOM) {
            gs.cameraX += (gs.coreX - targetW / 2f - gs.cameraX) * 2f * dt
            gs.cameraY += (gs.coreY - targetH / 2f - gs.cameraY) * 2f * dt
            gs.px += (gs.coreX - gs.px) * 2.5f * dt
            gs.py += (gs.coreY - gs.py) * 2.5f * dt
            gs.mergeTimer += dt
            gs.shakeAmount = scale * 0.04f

            if (gs.mergeTimer > 2.5f) gs.whiteFlash += dt * 0.5f
            if (gs.whiteFlash >= 1f) {
                onStoryState?.invoke(if (gs.isPerfectEnd) StoryProtocol.storyPerfectEnding else StoryProtocol.storyNeutralEnding, AppStateId.STORY_ENDING)
                gs.whiteFlash = 0f
            }
        }

        val viewportW = gs.getViewportW(targetW, targetH)
        val viewportH = gs.getViewportH(targetW, targetH)
        val maxSonarRad = viewportW * 0.75f * UpgradeSystem.getSonarRangeMultiplier()
        gs.updateVisibilityMath(scale, maxSonarRad, simDt)
        gs.updatePulseRadius(simDt, maxSonarRad)


        if (gs.isLevelCleared && gs.state == AppStateId.PLAYING) {
            // Wait for winDelayTimer before showing victory UI
            if (gs.winDelayTimer > 0f) {
                // Keep updating visuals but skip objective reward logic
            } else {
                if (gs.gameMode == GameModeId.TRAINING) {
                    // Training Mode Completion: Redirect to Mainframe
                    gs.isLevelCleared = false
                    onChangeState?.invoke(AppStateId.MAINFRAME)
                    return
                }

                if (gs.gameMode == GameModeId.CAMPAIGN) {
                    gs.isLevelCleared = false // Reset only AFTER rewards are processed
                    val config = LevelEngine.getLevelConfig(gs.currentLevel)
                    var finalReward = config.clearRewardKB
                    if (gs.currentLevel < SaveManager.maxCampaignLevel) finalReward /= 2
                    finalReward += (finalReward * UpgradeSystem.getRewardBonusPercent()).toLong()

                    gs.collectedDataKB += finalReward
                    SaveManager.addData(finalReward)

                    if (gs.currentLevel == Int.MAX_VALUE) {
                        gs.showGlobalMessage(
                            "LIMIT REACHED: \"YOU HAVE EXHAUSTED THE MULTIVERSE.\"\nADMIN: \"REST IN BITS, LEGEND.\"",
                            10f
                        )
                    } else {
                        SaveManager.updateCampaignProgress(gs.currentLevel)
                    }

                    // Calculate Stars based on specific achievements
                    val duration = gs.timeSinceStart - gs.levelStartTime
                    val isHard = gs.difficulty == DifficultyLevel.HARD
                    val noDamage = !gs.tookDamageInLevel
                    val underPar = duration <= config.parTime

                    // Record achievements (masks)
                    var recordsMask = 0
                    if (noDamage) recordsMask = recordsMask or 1 // NO DAMAGE
                    if (noDamage && underPar) recordsMask = recordsMask or 2 // PERFECT CLEAR
                    if (gs.sonarTimer == 0f && !gs.pulse) recordsMask = recordsMask or 8 // NO SONAR

                    // MANUAL AIM logic: Award if NOT using AUTO_AIM
                    if (gs.controls.activeAttackMode != com.appsbyalok.echohunter.input.AttackMode.AUTO_AIM) {
                        recordsMask = recordsMask or 16
                    }

                    if (!gs.isAutoPilotActive) recordsMask = recordsMask or 64 // NO AUTOPILOT

                    val stars = when {
                        isHard && noDamage && underPar -> 5
                        isHard -> 4
                        underPar -> 3
                        noDamage -> 2
                        else -> 1
                    }

                    SaveManager.saveLevelStats(gs.currentLevel, duration, stars, recordsMask, isHard)
                    EchoAudioManager.playSound(ToneGenerator.TONE_SUP_CONFIRM, 500)

                    onChangeState?.invoke(AppStateId.VICTORY)
                    return
                }
            }
        }

        effectSys.recordTrail(gs.px, gs.py)
        effectSys.update(simDt, scale)

        if (gs.state.isGameplay) {
            // --- MODULAR OBJECTIVE CALL ---
            // Updates timers and dynamic logic (like Core activation in Story Mode)
            gs.activeObjective.updateObjective(simDt, gs, enemySys, spawnerSys, viewportW, viewportH, scale)

            // --- CRITICAL: WIN CONDITION CHECK ---
            // Modular objectives determine if the level is cleared (Campaign Mode)
            if (gs.gameMode == GameModeId.CAMPAIGN && !gs.isLevelCleared && gs.activeObjective.checkWinCondition(gs)) {
                gs.isLevelCleared = true
            }

            if (gs.gameMode != GameModeId.TRAINING) spawnerSys.update(simDt, gs, viewportW, scale)
            enemySys.updateEnemies(simDt, gs, effectSys, viewportW, viewportH, scale)
            enemySys.updateBoss(simDt, gs, effectSys, scale)
            enemySys.updatePowerups(simDt, gs, effectSys, viewportW, viewportH)

            collisionSys.checkCollisions(scale, onDamage!!, onScore!!, onCoreUnlock!!)

            // Boss Spawns & Sector Story triggers only in main gameplay
            if (gs.state == AppStateId.PLAYING) gs.modeStrategy.checkProgression(context, gs, scale, onBossTrigger!!, onStoryState!!)

            handleAudioBeats(simDt)
        }
    }

    private fun handleAudioBeats(dt: Float) {
        if (gs.isEnemyVeryNear) {
            gs.heartbeatTimer -= dt
            if (gs.heartbeatTimer <= 0f) {
                EchoAudioManager.playSound(ToneGenerator.TONE_SUP_RADIO_NOTAVAIL, 50)
                gs.heartbeatTimer = 0.5f
            }
        } else if (gs.isEnemyNear) {
            gs.radarPingTimer -= dt
            if (gs.radarPingTimer <= 0f) {
                EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 40)
                gs.radarPingTimer = 0.8f
            }
        } else {
            gs.radarPingTimer = 0f; gs.heartbeatTimer = 0f
        }
    }

    fun generateLevelMaze(targetW: Float, targetH: Float, scale: Float) {
        val seed = 1000 + gs.currentLevel
        gs.gridMap = com.appsbyalok.echohunter.data.MazeGenerator.generateLevelMap(gs.currentLevel, gs.gameMode.id, gs.difficulty.id, seed, gs.selectedStoryAct)

        val columns = gs.gridMap!!.size
        val rows = gs.gridMap!![0].size

        // Initialize wall visibility map
        gs.wallVisMap = Array(columns) { FloatArray(rows) }

        gs.tileSize = scale * 0.15f
        gs.mapWidth = columns * gs.tileSize
        gs.mapHeight = rows * gs.tileSize

        var pCol = 2
        var pRow = 2

        for (x in 0 until columns) {
            for (y in 0 until rows) {
                if (gs.gridMap!![x][y] == com.appsbyalok.echohunter.data.MazeGenerator.PLAYER_SPAWN) {
                    pCol = x; pRow = y
                    gs.gridMap!![x][y] = com.appsbyalok.echohunter.data.MazeGenerator.PATH
                }
            }
        }

        gs.px = pCol * gs.tileSize + (gs.tileSize / 2f)
        gs.py = pRow * gs.tileSize + (gs.tileSize / 2f)

        // Generate Spawner Nodes for the level
        spawnerSys.generateNodes(gs, gs.mapWidth, gs.mapHeight, scale)

        // --- OBJECTIVE SETUP ---
        // Note: activeObjective is assigned in GameState.resetGame() based on LevelFeature.
        // We just need to call its setup here after map generation.
        gs.activeObjective.setupObjective(gs, enemySys, spawnerSys, targetW, targetH, scale)

        // Physically spawn enemies immediately so the map isn't empty
        if (gs.gameMode != GameModeId.TRAINING && gs.spawnerNodes.isNotEmpty()) {
            val preSpawnCount = if (gs.difficulty == DifficultyLevel.HARD) 10 else 7
            repeat(preSpawnCount) {
                val node = gs.spawnerNodes.random()
                for (j in 0 until enemySys.n) {
                    if (enemySys.ex[j] < -1000f) {
                        enemySys.spawnAt(j, node.x, node.y, gs, scale, node.type)
                        break
                    }
                }
            }
        }

        // Initialize progression flags
        gs.controls.isManualAimUnlocked = SaveManager.isManualAimUnlocked

        // Start level with a few more enemies queued to emerge shortly
        val initialPop = if (gs.difficulty == DifficultyLevel.HARD) 8 else 5
        spawnerSys.queueSpawns(initialPop, gs)

        gs.cameraX = gs.px - targetW / 2f
        gs.cameraY = gs.py - targetH / 2f
    }

    private fun activateOverclock(scale: Float) {
        gs.overclockTimer = 5f + UpgradeSystem.getBonusOverclockTime()
        gs.overclockMeter = 0f
        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_ALERT, 200)
        gs.shakeAmount = scale * 0.08f
        gs.sectorFlash = 0.5f
        gs.showOverclockTextTimer = 2.0f
        gs.controls.isOverclockPressed = false
    }
}
