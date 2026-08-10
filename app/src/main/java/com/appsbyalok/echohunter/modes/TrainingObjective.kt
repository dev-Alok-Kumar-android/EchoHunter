package com.appsbyalok.echohunter.modes

import android.media.ToneGenerator
import com.appsbyalok.echohunter.data.MazeGenerator
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.input.AttackMode
import com.appsbyalok.echohunter.input.HudAction
import com.appsbyalok.echohunter.systems.EnemySystem
import com.appsbyalok.echohunter.systems.SpawnerSystem
import com.appsbyalok.echohunter.systems.triggerCinematicFocus
import com.appsbyalok.echohunter.utils.EchoAudioManager
import com.appsbyalok.echohunter.utils.SpawnValidator
import kotlin.math.sqrt

/**
 * A safe, gated onboarding run. Each phase only exposes the control that is
 * being taught, while the player remains invulnerable for the whole training.
 */
class TrainingObjective : IGameObjective {
    private var phase = 0
    private var phaseTimer = 0f
    private var messageStep = 0
    private var combatTrial = 0
    private var trapTrial = 0
    private var targetIndex = -1
    private var sonarPings = 0
    private var pulseCounted = false
    private var trapCounter = 0f
    private var trainingCompleteTriggered = false
    private var empNeutralizedCount = 0
    private var combatKills = 0
    private val requiredKills = 3


    private val combatModes = arrayOf(
        AttackMode.MANUAL_AIM,
        AttackMode.DIRECTIONAL,
        AttackMode.AUTO_AIM
    )
    private val weaponNames = arrayOf("SPIKE", "BLAST", "SLUG")
    private val aimNames = arrayOf("MANUAL AIM", "DIRECTIONAL AIM", "AUTO AIM")
    private val trapNames = arrayOf("CAMO", "DECOY", "EMP")

    override fun setupObjective(
        gs: GameState,
        enemySys: EnemySystem,
        spawnerSys: SpawnerSystem,
        targetW: Float,
        targetH: Float,
        scale: Float
    ) {
        phase = 0
        phaseTimer = 0f
        messageStep = 0
        combatTrial = 0
        trapTrial = 0
        targetIndex = -1
        sonarPings = 0
        pulseCounted = false
        trapCounter = 0f
        trainingCompleteTriggered = false
        empNeutralizedCount = 0
        combatKills = 0

        gs.coreX = -9999f
        gs.coreY = -9999f
        gs.coreRadius = 0f
        gs.tutorialGateOpen = false
        gs.escapeGateActive = false
        gs.tutorialHighlightedEnemyIndex = -1
        gs.tutorialEnabledActions = emptySet()
        gs.controls.activeAttackMode = AttackMode.MANUAL_AIM
        gs.controls.isManualAimUnlocked = true
        gs.hp = gs.maxHp
        StoryProtocol.isBlackoutActive = false
        StoryProtocol.blackoutAlpha = 0.8f
        gs.isBlackoutActive = false

        StoryProtocol.showTypewriterMessage("TRAINING LINK ESTABLISHED. YOU ARE PROTECTED FOR THIS RUN.", 4f)
    }

    override fun updateObjective(
        dt: Float,
        gs: GameState,
        enemySys: EnemySystem,
        spawnerSys: SpawnerSystem,
        targetW: Float,
        targetH: Float,
        scale: Float
    ) {
        phaseTimer += dt
        gs.hp = gs.maxHp // Training is always god mode, including environmental damage.

        when (phase) {
            0 -> updateHudIntroduction(gs)
            1 -> updateCombatTraining(gs, enemySys, scale)
            2 -> updateTrapTraining(gs, enemySys, scale)
            3 -> updateSonarEscape(gs)
            4 -> finishTraining(gs)
        }
    }

    private fun updateHudIntroduction(gs: GameState) {
        gs.objectiveLabel = "PHASE 0: HUD & BASIC CONTROLS"
        gs.objectiveProgress = (phaseTimer / 9f).coerceIn(0f, 1f)
        gs.tutorialEnabledActions = emptySet()

        when {
            messageStep == 0 && phaseTimer >= 2f -> {
                StoryProtocol.showTypewriterMessage("MOVE: DRAG ANYWHERE ON THE LEFT SIDE TO STEER THE HUNTER.", 4f)
                messageStep++
            }
            messageStep == 1 && phaseTimer >= 5f -> {
                StoryProtocol.showTypewriterMessage("ATTACK AND TRAP CONTROLS WILL UNLOCK ONLY WHEN REQUIRED.", 4f)
                messageStep++
            }
            phaseTimer >= 9f -> advancePhase()
        }
    }

    private fun updateCombatTraining(gs: GameState, enemySys: EnemySystem, scale: Float) {
        if (combatTrial >= combatModes.size) {
            clearTarget(gs)
            advancePhase()
            return
        }

        gs.tutorialEnabledActions = setOf(HudAction.ATTACK)
        gs.objectiveLabel = "PHASE 1: COMBAT - ${aimNames[combatTrial]} ($combatKills/$requiredKills)"
        gs.objectiveProgress = (combatTrial.toFloat() + (combatKills.toFloat() / requiredKills)) / combatModes.size

        // Detect if the previous target was just eliminated
        if (targetIndex != -1 && enemySys.ex[targetIndex] < -1000f) {
            combatKills++
            targetIndex = -1
            gs.tutorialHighlightedEnemyIndex = -1
            EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 100)
            
            if (combatKills >= requiredKills) {
                combatTrial++
                combatKills = 0
                return // Let next frame handle the next weapon/mode spawning
            }
        }

        // Spawn a target if none is active
        if (targetIndex == -1 || enemySys.ex[targetIndex] < -1000f) {
            gs.controls.activeAttackMode = combatModes[combatTrial]
            gs.controls.currentWeapon = combatTrial
            
            // Only show the briefing message for the first kill of a new weapon/mode
            if (combatKills == 0) {
                StoryProtocol.showTypewriterMessage(
                    "ENEMY DETECTED. ${aimNames[combatTrial]} + ${weaponNames[combatTrial]} ONLINE. PURGE THE TARGETS.",
                    5f
                )
            }
            
            targetIndex = spawnTrainingTarget(gs, enemySys, scale, triggerCinematic = true)
            gs.tutorialHighlightedEnemyIndex = targetIndex
        }
    }

    private fun updateTrapTraining(gs: GameState, enemySys: EnemySystem, scale: Float) {
        if (trapTrial >= trapNames.size) {
            // Already finished all traps, wait for transition gate logic below
        } else {
            gs.tutorialEnabledActions = setOf(HudAction.TRAP)
            gs.objectiveLabel = "PHASE 2: TRAPS - ${trapNames[trapTrial]}"
            gs.objectiveProgress = (trapTrial.toFloat() + (trapCounter / 1.5f).coerceAtMost(1f)) / (trapNames.size + 1)
        }

        if (trapTrial < trapNames.size) {
            gs.controls.currentTrap = trapTrial
            if (messageStep != trapTrial) {
                val instruction = when(trapTrial) {
                    0 -> "VOID SHADOW: CLOAK ACTIVE. BYPASS THE SENSOR BY STAYING NEAR IT WHILE INVISIBLE."
                    1 -> "DECOY: DEPLOY AN ECHO. WAIT FOR THE HUNTER TO REACH THE DECOY'S POSITION."
                    2 -> "EMP: SWARM DETECTED. RELEASE A PULSE TO NEUTRALIZE MULTIPLE TARGETS."
                    else -> ""
                }
                StoryProtocol.showTypewriterMessage(instruction, 5f)
                messageStep = trapTrial
                
                // Specialized Spawning for logical situations
                when(trapTrial) {
                    0 -> { // Camo: Stationary Target
                        targetIndex = spawnTrainingTarget(gs, enemySys, scale, type = 0, triggerCinematic = true) // Patrol
                        gs.tutorialHighlightedEnemyIndex = targetIndex
                        if (targetIndex != -1) {
                            enemySys.evx[targetIndex] = 0f
                            enemySys.evy[targetIndex] = 0f
                        }
                    }
                    1 -> { // Decoy: Hunter that needs to be lured
                        targetIndex = spawnTrainingTarget(gs, enemySys, scale, type = 1, triggerCinematic = true) // Force Hunter
                        gs.tutorialHighlightedEnemyIndex = targetIndex
                    }
                    2 -> { // EMP: Swarm of targets
                        clearTarget(gs)
                        empNeutralizedCount = 0
                        // Spawn them in a cluster
                        val offsets = arrayOf(
                            0.55f to -0.1f, 0.58f to 0.1f, 
                            0.68f to -0.05f, 0.72f to 0.15f
                        )
                        offsets.forEachIndexed { i, off ->
                            val idx = spawnTrainingTarget(gs, enemySys, scale, 
                                offsetX = off.first, offsetY = off.second, type = 1, triggerCinematic = (i == 0))
                            if (i == 0) targetIndex = idx
                        }
                    }
                }
            }

            // Success Condition for each trap
            val isTrapped = when(trapTrial) {
                0 -> { // Camo: Player invisible and close
                    val dx = if (targetIndex != -1 && enemySys.ex[targetIndex] > -1000f) enemySys.ex[targetIndex] - gs.px else 999f
                    val dy = if (targetIndex != -1 && enemySys.ey[targetIndex] > -1000f) enemySys.ey[targetIndex] - gs.py else 999f
                    gs.isCamouflaged && (dx*dx + dy*dy) < (scale * 0.25f) * (scale * 0.25f)
                }
                1 -> { // Decoy: Target reached decoy
                    if (targetIndex != -1 && enemySys.ex[targetIndex] > -1000f && gs.isDecoyActive) {
                        val ddx = enemySys.ex[targetIndex] - gs.decoyX
                        val ddy = enemySys.ey[targetIndex] - gs.decoyY
                        (ddx * ddx + ddy * ddy) < (scale * 0.15f) * (scale * 0.15f)
                    } else false
                }
                2 -> { // EMP: Check how many were cleared (monitored via enemySys counts)
                    var currentActiveCount = 0
                    for (i in 0 until enemySys.n) if (enemySys.ex[i] > -1000f) currentActiveCount++
                    
                    // If enemies died in this step, count them as neutralized by EMP
                    if (currentActiveCount < 4 - empNeutralizedCount) {
                        empNeutralizedCount = 4 - currentActiveCount
                    }
                    
                    // Also count spawners if they are fried
                    val friedSpawners = gs.spawnerNodes.count { it.state == com.appsbyalok.echohunter.systems.SpawnState.DISABLED || it.state == com.appsbyalok.echohunter.systems.SpawnState.DESTROYED }
                    
                    val currentCount = empNeutralizedCount + friedSpawners
                    currentCount >= 3
                }
                else -> false
            }

            // --- AUTO-RESPAWN IF TARGET LOST ---
            if (!isTrapped) {
                if (trapTrial < 2) { // Camo or Decoy
                    if (targetIndex == -1 || enemySys.ex[targetIndex] < -1000f) {
                        // Reset messageStep to force re-spawning/re-briefing logic
                        messageStep = -1 
                    }
                } else if (trapTrial == 2) { // EMP Swarm
                    val aliveCount = (0 until enemySys.n).count { enemySys.ex[it] > -1000f }
                    if (aliveCount == 0 && empNeutralizedCount < 3) {
                         messageStep = -1 // Respawn swarm
                    }
                }
            }

            if (isTrapped) {
                // For Camo/Decoy, we need to hold the condition for 1.5s
                // EMP is an instant clear once hitCount >= 3 is met
                val requiredTime = if (trapTrial == 2) 0.01f else 1.5f
                
                trapCounter += gs.lastDt
                if (trapCounter >= requiredTime) {
                    if (trapTrial == 2) {
                        // Wipe the swarm
                        for (i in 0 until enemySys.n) if (enemySys.ex[i] > -1000f) enemySys.killEnemy(i, gs)
                    } else if (targetIndex != -1) {
                        enemySys.killEnemy(targetIndex, gs)
                    }
                    
                    trapTrial++
                    trapCounter = 0f
                    clearTarget(gs)
                    gs.trapCooldownTimer = 0f
                    EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 150)
                }
            } else {
                trapCounter = (trapCounter - gs.lastDt * 0.5f).coerceAtLeast(0f)
            }

            // --- FORCE SITUATION LOGIC ---
            if (trapTrial == 0 && targetIndex != -1) {
                // Camo Target: Passive/Stationary
                enemySys.evx[targetIndex] = 0f
                enemySys.evy[targetIndex] = 0f
                enemySys.eState[targetIndex] = 0
            }
            
            return
        }

        gs.tutorialEnabledActions = emptySet()
        if (!gs.tutorialGateOpen) {
            openTransitionGate(gs, scale)
            StoryProtocol.showTypewriterMessage("GATE OPEN. CROSS THE UPLINK TO BEGIN THE DARKNESS ESCAPE.", 5f)
        }

        gs.objectiveLabel = "PHASE 2: CROSS THE TRAINING GATE"
        gs.objectiveProgress = 0.75f
        if (distanceToCore(gs) < gs.coreRadius * 1.4f) {
            beginDarkMaze(gs, scale)
        }
    }

    private fun updateSonarEscape(gs: GameState) {
        gs.tutorialEnabledActions = setOf(HudAction.SONAR)
        gs.objectiveLabel = if (sonarPings < 3) {
            "PHASE 3: SONAR CALIBRATION $sonarPings / 3"
        } else {
            "PHASE 3: FIND THE ESCAPE PORTAL"
        }
        gs.objectiveProgress = (0.75f + sonarPings.toFloat() / 12f).coerceAtMost(0.95f)

        if (gs.pulse && !pulseCounted) {
            pulseCounted = true
            sonarPings++
        } else if (!gs.pulse) {
            pulseCounted = false
        }

        if (sonarPings == 1 && messageStep != 1) {
            StoryProtocol.showTypewriterMessage("SONAR REVEALS WALLS, TARGETS, AND THE EXIT SIGNAL FOR A SHORT TIME.", 4f)
            messageStep = 1
        }

        if (sonarPings >= 3 && distanceToCore(gs) < gs.coreRadius * 1.4f) {
            advancePhase()
        }
    }

    private fun finishTraining(gs: GameState) {
        gs.tutorialEnabledActions = emptySet()
        gs.objectiveLabel = "TRAINING COMPLETE"
        gs.objectiveProgress = 1f
        if (!trainingCompleteTriggered) {
            trainingCompleteTriggered = true
            SaveManager.setGameTutorialCompleted(true)
            StoryProtocol.showTypewriterMessage("TRAINING COMPLETE. ALL HUNTER SYSTEMS ARE AVAILABLE.", 4f)
        }
        if (phaseTimer >= 4f) gs.isLevelCleared = true
    }

    private fun spawnTrainingTarget(
        gs: GameState, 
        enemySys: EnemySystem, 
        scale: Float, 
        offsetX: Float = 0.65f, 
        offsetY: Float = 0f,
        type: Int = 0,
        triggerCinematic: Boolean = false
    ): Int {
        val index = (0 until enemySys.n).firstOrNull { enemySys.ex[it] < -1000f } ?: return -1
        val tx = gs.px + scale * offsetX
        val ty = gs.py + scale * offsetY
        enemySys.spawnAt(index, tx, ty, gs, scale, type)
        if (enemySys.ex[index] > -1000f) {
            enemySys.hp[index] = 1
            enemySys.maxHp[index] = 1
            enemySys.vis[index] = 1f
            
            // Explicitly set behavior based on forced type
            enemySys.type[index] = type
            enemySys.enemyBrains[index] = if (type == 1) com.appsbyalok.echohunter.systems.HunterBehavior else com.appsbyalok.echohunter.systems.PatrolBehavior

            if (triggerCinematic) {
                // Use ACTUAL position after spawn validation
                gs.triggerCinematicFocus(enemySys.ex[index], enemySys.ey[index], zoom = 1.35f, duration = 1.2f)
            }
        }
        return index
    }

    private fun openTransitionGate(gs: GameState, scale: Float) {
        val position = SpawnValidator.findValidNear(
            gs.px + scale * 1.2f,
            gs.py,
            scale * 0.04f,
            gs,
            maxAttempts = 40,
            searchRadius = scale * 3f
        ) ?: Pair(gs.px + scale * 0.8f, gs.py)
        gs.coreX = position.first
        gs.coreY = position.second
        gs.coreRadius = scale * 0.13f
        gs.tutorialGateOpen = true
        gs.escapeGateActive = true
    }

    private fun beginDarkMaze(gs: GameState, scale: Float) {
        val exit = findMazeExit(gs) ?: SpawnValidator.findValidNear(
            gs.px + scale * 4f,
            gs.py + scale * 4f,
            scale * 0.04f,
            gs,
            maxAttempts = 80,
            searchRadius = scale * 12f
        ) ?: Pair(gs.px, gs.py)
        gs.coreX = exit.first
        gs.coreY = exit.second
        gs.coreRadius = scale * 0.14f
        gs.escapeGateActive = true
        StoryProtocol.isBlackoutActive = true
        StoryProtocol.blackoutAlpha = 0.93f
        gs.isBlackoutActive = true
        advancePhase()
        StoryProtocol.showTypewriterMessage("BLACKOUT ACTIVE. USE SONAR TO MAP THE MAZE AND LOCATE THE ESCAPE PORTAL.", 5f)
    }

    private fun findMazeExit(gs: GameState): Pair<Float, Float>? {
        val grid = gs.gridMap ?: return null
        for (x in grid.indices) {
            for (y in grid[x].indices) {
                if (grid[x][y] == MazeGenerator.DEST_NODE) {
                    return Pair(x * gs.tileSize + gs.tileSize / 2f, y * gs.tileSize + gs.tileSize / 2f)
                }
            }
        }
        return null
    }

    private fun distanceToCore(gs: GameState): Float {
        val dx = gs.px - gs.coreX
        val dy = gs.py - gs.coreY
        return sqrt(dx * dx + dy * dy)
    }

    private fun clearTarget(gs: GameState) {
        gs.tutorialHighlightedEnemyIndex = -1
        targetIndex = -1
    }

    private fun advancePhase() {
        phase++
        phaseTimer = 0f
        messageStep = -1
        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 150)
    }

    fun skipStep(gs: GameState) {
        if (phase < 4) {
            clearTarget(gs)
            if (phase == 2) {
                // Calculate scale from tileSize (since tileSize = scale * 0.15)
                val scale = gs.tileSize / 0.15f
                beginDarkMaze(gs, scale)
            } else {
                advancePhase()
            }
            StoryProtocol.showTypewriterMessage("TRAINING PHASE SKIPPED. PROCEEDING...", 2f)
        }
    }

    fun skipAll(gs: GameState) {
        if (phase >= 4) return
        clearTarget(gs)
        phase = 4
        phaseTimer = 0f
        messageStep = -1
        gs.tutorialEnabledActions = emptySet()
        gs.objectiveProgress = 1f
        StoryProtocol.showTypewriterMessage("TRAINING BYPASSED. SYSTEMS OPTIMAL.", 3f)
    }

    override fun checkWinCondition(gs: GameState): Boolean = phase >= 4 && gs.objectiveProgress >= 1f
    override fun isBossTriggerReady(gs: GameState): Boolean = false
}
