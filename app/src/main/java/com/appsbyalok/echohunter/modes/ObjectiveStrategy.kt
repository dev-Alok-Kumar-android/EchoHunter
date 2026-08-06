package com.appsbyalok.echohunter.modes

import android.media.ToneGenerator
import com.appsbyalok.echohunter.data.LevelEngine
import com.appsbyalok.echohunter.data.LevelFeature
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.engine.AppStateId
import com.appsbyalok.echohunter.engine.DifficultyLevel
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.systems.EnemySystem
import com.appsbyalok.echohunter.systems.SpawnerSystem
import com.appsbyalok.echohunter.utils.EchoAudioManager
import kotlin.math.sqrt

// Blueprint for all level objectives
interface IGameObjective {
    fun setupObjective(gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float)
    fun updateObjective(dt: Float, gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float)
    fun checkWinCondition(gs: GameState): Boolean
    fun isBossTriggerReady(gs: GameState): Boolean

    fun getContextPrefix(gs: GameState): String = if (gs.modeStrategy.modeId == 1) "NODE ${gs.currentSector}" else "RING ${gs.currentLevel}"

    // Shared verification to ensure all level features are satisfied
    fun areSecondaryFeaturesMet(gs: GameState): Boolean {
        val config = LevelEngine.getLevelConfig(gs.currentLevel)
        // 1. Elimination Check
        if (config.features.contains(LevelFeature.ELIMINATION) && gs.elimTargetsKilled < gs.elimTargetsRequired) return false
        
        // 2. Boss Check (Boss must be dead if it exists)
        if (config.features.contains(LevelFeature.BOSS)) {
            val bossDead = !gs.bossActive && (gs.bossDeathTimer > 0.1f || gs.currentSector > 1)
            if (!bossDead) return false
        }
        
        // 3. Score Check (Classic)
        if (config.features.contains(LevelFeature.CLASSIC) && gs.score < config.targetScore) return false

        // 4. Defense Check
        if (config.features.contains(LevelFeature.DEFENSE)) {
            if (gs.defWaveCurrent <= gs.defWaveMax || gs.coreHp <= 0) return false
        }

        // 5. Clean Sweep Check
        if (config.features.contains(LevelFeature.CLEAN_SWEEP)) {
            val allDestroyed = gs.spawnerNodes.all { it.state == com.appsbyalok.echohunter.systems.SpawnState.DESTROYED }
            if (!allDestroyed) return false
        }
        
        return true
    }
}

// 1. STANDARD / ELIMINATION OBJECTIVE (Score based)
class StandardObjective : IGameObjective {
    private var trickleTimer = 2f

    override fun setupObjective(gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        gs.coreX = -9999f
        gs.coreY = -9999f
        gs.coreRadius = 0f
    }

    override fun updateObjective(dt: Float, gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        val config = LevelEngine.getLevelConfig(gs.currentLevel)
        
        // --- DYNAMIC OBJECTIVE LABEL ---
        val prefix = getContextPrefix(gs)
        gs.objectiveLabel = when {
            gs.bossActive -> "NEUTRALIZE ADMIN SECURITY"
            gs.escapeGateActive -> "REACH EXIT PORTAL"
            config.features.contains(LevelFeature.ELIMINATION) && gs.elimTargetsKilled < gs.elimTargetsRequired -> 
                "$prefix | ELIMINATION: ${gs.elimTargetsKilled} / ${gs.elimTargetsRequired}"
            config.features.contains(LevelFeature.BOMB) -> "$prefix | DEFUSE LOGIC BOMB"
            config.features.contains(LevelFeature.DEFENSE) -> "$prefix | DEFEND CORE UPLINK"
            else -> "$prefix | UPLINK: ${gs.score} / ${config.targetScore} KB"
        }
        
        gs.objectiveProgress = if (config.features.contains(LevelFeature.ELIMINATION) && gs.elimTargetsRequired > 0) {
            (gs.elimTargetsKilled.toFloat() / gs.elimTargetsRequired).coerceIn(0f, 1f)
        } else {
            (gs.score.toFloat() / config.targetScore).coerceIn(0f, 1f)
        }

        trickleTimer -= dt
        if (trickleTimer <= 0f) {
            var activeCount = 0
            for (i in 0 until enemySys.n) if (enemySys.ex[i] > -1000f) activeCount++
            val baseLimit = if (gs.difficulty == DifficultyLevel.HARD) 18f else 14f
            val limit = LevelEngine.getSaturatedValue(gs.currentLevel, baseLimit, 40f - baseLimit, 100f).toInt()
            val queued = spawnerSys.getTotalQueue(gs)
            if (activeCount + queued < limit) {
                spawnerSys.queueSpawns(limit - (activeCount + queued), gs)
            }
            trickleTimer = 1f
        }
    }

    override fun checkWinCondition(gs: GameState): Boolean = areSecondaryFeaturesMet(gs)
    override fun isBossTriggerReady(gs: GameState): Boolean = gs.score >= LevelEngine.getLevelConfig(gs.currentLevel).targetScore
}

// 2. DEFENSE OBJECTIVE (Wave based, Protect Core)
class DefenseObjective : IGameObjective {
    private var trickleTimer = 2f

    override fun setupObjective(gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        gs.defWaveMax = when {
            gs.currentLevel <= 30 -> 1
            gs.currentLevel <= 70 -> 2
            gs.currentLevel <= 150 -> 3
            gs.currentLevel <= 300 -> 4
            else -> 5
        }
        gs.defWaveCurrent = 1
        gs.defWaveState = 0 // Buffer phase
        gs.defWaveTimer = 5f // 5 seconds initial pause

        gs.coreMaxHp = LevelEngine.getSaturatedValue(gs.currentLevel, 10f, 15f, 150f).toInt()
        gs.coreHp = gs.coreMaxHp

        // Locate DEST_NODE for Core position
        val grid = gs.gridMap
        if (grid != null) {
            val ts = gs.tileSize
            for (x in 0 until grid.size) {
                for (y in 0 until grid[0].size) {
                    if (grid[x][y] == 2) { // 2 is DEST_NODE
                        val tx = x * ts + (ts / 2f)
                        val ty = y * ts + (ts / 2f)

                        // Verify core isn't inside a wall (DEST_NODE shouldn't be, but just in case)
                        if (!com.appsbyalok.echohunter.utils.SpawnValidator.isValid(tx, ty, ts * 0.4f, gs)) {
                            continue
                        }

                        gs.coreX = tx
                        gs.coreY = ty
                        gs.coreRadius = scale * 0.15f
                        return
                    }
                }
            }
        }

        // Fallback: Find any safe path tile near center
        if (grid != null) {
            val cx = grid.size / 2
            val cy = grid[0].size / 2
            val ts = gs.tileSize
            val found = com.appsbyalok.echohunter.utils.SpawnValidator.findValidNear(cx * ts, cy * ts, ts * 0.5f, gs, 50, ts * 5f)
            if (found != null) {
                gs.coreX = found.first
                gs.coreY = found.second
                gs.coreRadius = scale * 0.15f
                return
            }
        }

        gs.coreX = gs.px
        gs.coreY = gs.py
        gs.coreRadius = scale * 0.15f
    }

    override fun updateObjective(dt: Float, gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        val prefix = getContextPrefix(gs)
        if (gs.bossActive) {
            gs.objectiveLabel = "NEUTRALIZE ADMIN SECURITY"
        } else if (gs.defWaveCurrent <= gs.defWaveMax) {
            gs.objectiveLabel = "$prefix | WAVE ${gs.defWaveCurrent} / ${gs.defWaveMax}"
        } else {
            gs.objectiveLabel = "UPLINK SECURED - PURGING..."
        }

        val waveTotal = 2f * (if (gs.difficulty == DifficultyLevel.HARD) 1.5f else 1.0f)
        val expectedTotal = LevelEngine.getSaturatedValue(gs.currentLevel, waveTotal, 40f - waveTotal, 80f).toInt()
        val currentRemaining = gs.defEnemiesToSpawn + gs.defEnemiesAlive
        gs.objectiveProgress = if (expectedTotal > 0 && gs.defWaveState == 1) {
            (1f - (currentRemaining.toFloat() / expectedTotal)).coerceIn(0f, 1f)
        } else if (gs.defWaveState == 0 || gs.defWaveState == 2) {
            1f - (gs.defWaveTimer / 7f).coerceIn(0f, 1f)
        } else 1f

        // --- ESCAPE HYBRID TRICKLE ---
        val config = LevelEngine.getLevelConfig(gs.currentLevel)
        if (config.features.contains(LevelFeature.ESCAPE)) {
            trickleTimer -= dt
            if (trickleTimer <= 0f) {
                var activeCount = 0
                for (i in 0 until enemySys.n) if (enemySys.ex[i] > -1000f) activeCount++
                val baseLimit = if (gs.difficulty == DifficultyLevel.HARD) 16f else 12f
                val limit = LevelEngine.getSaturatedValue(gs.currentLevel, baseLimit, 35f - baseLimit, 120f).toInt()
                val queued = spawnerSys.getTotalQueue(gs)
                if (activeCount + queued < limit) {
                    spawnerSys.queueSpawns(limit - (activeCount + queued), gs)
                }
                trickleTimer = 1.5f
            }
        }

        when (gs.defWaveState) {
            0, 2 -> { // Buffer or Cooldown Phase
                gs.defWaveTimer -= dt
                if (gs.defWaveTimer <= 0f) {
                    gs.defWaveState = 1
                    val diffMult = if (gs.difficulty == DifficultyLevel.HARD) 1.5f else 1.0f
                    val hardCap = if (gs.difficulty == DifficultyLevel.HARD) 40f else 25f
                    val baseCount = 2f * diffMult

                    gs.defEnemiesToSpawn = LevelEngine.getSaturatedValue(gs.currentLevel, baseCount, hardCap - baseCount, 80f).toInt()
                    gs.defEnemiesAlive = 0

                    spawnerSys.queueSpawns(gs.defEnemiesToSpawn, gs)

                    StoryProtocol.showIngameMessage("WAVE ${gs.defWaveCurrent} / ${gs.defWaveMax} INCOMING!", 2f)
                    EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                }
            }
            1 -> { // Fighting Phase
                if (gs.defEnemiesAlive <= 0 && gs.defEnemiesToSpawn <= 0) {
                    if (gs.defWaveCurrent >= gs.defWaveMax) {
                        gs.defWaveCurrent++ // Win condition check increments this to > Max
                        gs.defWaveState = 3 // Finished
                    } else {
                        gs.defWaveCurrent++
                        gs.defWaveState = 2
                        gs.defWaveTimer = 7f 
                        StoryProtocol.showIngameMessage("WAVE CLEAR! REGROUPING...", 2f)
                    }
                }
            }
        }
    }

    override fun checkWinCondition(gs: GameState): Boolean {
        // Must complete all waves AND secondary features (Boss/Elim)
        return gs.defWaveCurrent > gs.defWaveMax && gs.coreHp > 0 && areSecondaryFeaturesMet(gs)
    }

    override fun isBossTriggerReady(gs: GameState): Boolean {
        // Hybrid: Boss spawns at the start of the final wave for maximum chaos
        return gs.defWaveCurrent == gs.defWaveMax && gs.defWaveState == 1
    }
}

// 3. ESCAPE OBJECTIVE (Reach the portal)
class EscapeObjective : IGameObjective {
    private var trickleTimer = 2f

    private var coreReached = false
    private var exitX = -9999f
    private var exitY = -9999f

    override fun setupObjective(gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        gs.escapeGateActive = false
        coreReached = false

        val grid = gs.gridMap
        if (grid != null) {
            // 1. Locate Exit Portal (2) and store it
            for (x in 0 until grid.size) {
                for (y in 0 until grid[0].size) {
                    if (grid[x][y] == 2) {
                        exitX = x * gs.tileSize + (gs.tileSize / 2f)
                        exitY = y * gs.tileSize + (gs.tileSize / 2f)
                    }
                }
            }

            // 2. Set initial Core to map center (Defense Hub)
            val centerX = grid.size / 2
            val centerY = grid[0].size / 2
            
            var foundPath = false
            for (d in 0..6) {
                for (dx in -d..d) {
                    for (dy in -d..d) {
                        val nx = centerX + dx
                        val ny = centerY + dy
                        if (nx in grid.indices && ny in grid[0].indices && grid[nx][ny] == 0) {
                            gs.coreX = nx * gs.tileSize + (gs.tileSize / 2f)
                            gs.coreY = ny * gs.tileSize + (gs.tileSize / 2f)
                            foundPath = true; break
                        }
                    }
                    if (foundPath) break
                }
                if (foundPath) break
            }
            
            if (!foundPath) {
                gs.coreX = centerX * gs.tileSize + (gs.tileSize / 2f)
                gs.coreY = centerY * gs.tileSize + (gs.tileSize / 2f)
            }
        } else {
            gs.coreX = gs.px + (if (Math.random() > 0.5) 1f else -1f) * targetW * 0.8f
            gs.coreY = gs.py + (if (Math.random() > 0.5) 1f else -1f) * targetH * 0.8f
        }

        gs.coreRadius = scale * 0.12f

        // --- DEFENSE HYBRID SETUP ---
        val config = LevelEngine.getLevelConfig(gs.currentLevel)
        if (config.features.contains(LevelFeature.DEFENSE)) {
            gs.defWaveMax = when {
                gs.currentLevel <= 70 -> 1
                gs.currentLevel <= 150 -> 2
                else -> 3
            }
            gs.defWaveCurrent = 1
            gs.defWaveState = -1 // Waiting for core reach
            gs.defWaveTimer = 0f
            gs.coreMaxHp = LevelEngine.getSaturatedValue(gs.currentLevel, 10f, 15f, 150f).toInt()
            gs.coreHp = gs.coreMaxHp
        }
    }

    override fun updateObjective(dt: Float, gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        val prefix = getContextPrefix(gs)
        val config = LevelEngine.getLevelConfig(gs.currentLevel)
        val hasDefense = config.features.contains(LevelFeature.DEFENSE)

        // UI Label Logic
        if (!gs.escapeGateActive) {
            if (hasDefense && !coreReached) {
                gs.objectiveLabel = "$prefix | LOCATE UPLINK"
                val dx = gs.px - gs.coreX
                val dy = gs.py - gs.coreY
                val dist = sqrt(dx * dx + dy * dy)
                gs.objectiveProgress = (1f - (dist / (gs.mapWidth * 0.5f))).coerceIn(0f, 0.95f)
            } else if (hasDefense && gs.defWaveCurrent <= gs.defWaveMax) {
                gs.objectiveLabel = "$prefix | WAVE ${gs.defWaveCurrent}/${gs.defWaveMax}"
                // PROGRESS: Ratio of enemies killed in current wave
                val waveTotal = 6f * (if (gs.difficulty == DifficultyLevel.HARD) 1.5f else 1.0f)
                val expectedTotal = LevelEngine.getSaturatedValue(gs.currentLevel, waveTotal, 22f, 100f).toInt()

                val currentRemaining = gs.defEnemiesToSpawn + gs.defEnemiesAlive
                gs.objectiveProgress = if (expectedTotal > 0) {
                   (1f - (currentRemaining.toFloat() / expectedTotal)).coerceIn(0f, 1f)
                } else 1f
            } else {
                gs.objectiveLabel = "$prefix | BYPASSING SECURITY..."
                // Progress based on secondary features
                var progress = 0f
                if (config.features.contains(LevelFeature.ELIMINATION)) progress = (gs.elimTargetsKilled.toFloat() / gs.elimTargetsRequired)
                gs.objectiveProgress = progress.coerceIn(0f, 1f)
            }
        } else {
            gs.objectiveLabel = "EXIT PORTAL ACTIVE"
            gs.objectiveProgress = 1f
        }

        trickleTimer -= dt
        if (trickleTimer <= 0f) {
            // SUPPRESS TRICKLE DURING ACTIVE WAVES to keep slots for wave enemies
            val waveActive = gs.defWaveState == 1

            var activeCount = 0
            for (i in 0 until enemySys.n) if (enemySys.ex[i] > -1000f) activeCount++
            val baseLimit = if (gs.difficulty == DifficultyLevel.HARD) 16f else 12f
            val limit = LevelEngine.getSaturatedValue(gs.currentLevel, baseLimit, 35f - baseLimit, 120f).toInt()
            val queued = spawnerSys.getTotalQueue(gs)

            if (!waveActive && activeCount + queued < limit) {
                spawnerSys.queueSpawns(limit - (activeCount + queued), gs)
            }
            trickleTimer = 1f
        }

        // Hybrid check: Gate activates ONLY when all other features are met
        if (areSecondaryFeaturesMet(gs) && !gs.escapeGateActive) {
            gs.escapeGateActive = true

            // NEW: Relocate core marker to the actual Exit Portal
            if (exitX > 0) {
                gs.coreX = exitX
                gs.coreY = exitY
            }

            StoryProtocol.showIngameMessage("SYSTEM BREACHED! EXIT PORTAL OPEN!", 4f)
            EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_ALERT, 300)
        }

        // --- DEFENSE HYBRID UPDATE ---
        if (hasDefense) {
            if (!coreReached) {
                val dx = gs.px - gs.coreX
                val dy = gs.py - gs.coreY
                if (dx * dx + dy * dy < (gs.coreRadius * 2.8f) * (gs.coreRadius * 2.8f)) {
                    coreReached = true
                    gs.defWaveState = 0
                    gs.defWaveTimer = 3f
                    StoryProtocol.showIngameMessage("CORE LOCATED! DEFENSE SYSTEMS ONLINE!", 3f)
                    EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 200)

                    // Force all spawners to relocate near the core area (7x7 room)
                    val grid = gs.gridMap
                    if (grid != null) {
                        val ts = gs.tileSize

                        for (i in gs.spawnerNodes.indices) {
                            val node = gs.spawnerNodes[i]
                            node.queue = 0
                            node.cooldownTimer = 0.5f + (i * 0.2f)

                            // Core ke charo taraf circle mein spawners set karo (5.5 tiles door)
                            val angle = (i.toFloat() / gs.spawnerNodes.size) * 6.283f
                            val dist = 5.5f * ts
                            val targetX = gs.coreX + kotlin.math.cos(angle) * dist
                            val targetY = gs.coreY + kotlin.math.sin(angle) * dist

                            // Use SpawnValidator to find a safe spot near the ideal circular placement
                            val safeSpot = com.appsbyalok.echohunter.utils.SpawnValidator.findValidNear(
                                targetX, targetY, ts * 0.4f, gs, 20, ts * 2f
                            )

                            if (safeSpot != null) {
                                node.x = safeSpot.first
                                node.y = safeSpot.second
                            } else {
                                // IMPROVED: Search for a safe spot near the player instead of snapping exactly to gs.px
                                val safeNearPlayer = com.appsbyalok.echohunter.utils.SpawnValidator.findValidNear(
                                    gs.px, gs.py, ts * 0.4f, gs, 20, ts * 3f
                                )
                                if (safeNearPlayer != null) {
                                    node.x = safeNearPlayer.first
                                    node.y = safeNearPlayer.second
                                } else {
                                    node.x = gs.px
                                    node.y = gs.py
                                }
                            }
                        }
                    }
                }
            } else {
                updateDefenseSubLogic(dt, gs, spawnerSys)
            }
        }
    }

    private fun updateDefenseSubLogic(dt: Float, gs: GameState, spawnerSys: SpawnerSystem) {
        when (gs.defWaveState) {
            -1 -> { // Exploration Phase
                gs.objectiveLabel = "SEARCH FOR CORE UPLINK"
                // No timer logic here, waiting for coreReached = true
            }
            0, 2 -> {
                gs.defWaveTimer -= dt
                if (gs.defWaveTimer <= 0f) {
                    gs.defWaveState = 1
                    val diffMult = if (gs.difficulty == DifficultyLevel.HARD) 1.5f else 1.0f
                    val baseCount = 6f * diffMult
                    val count = LevelEngine.getSaturatedValue(gs.currentLevel, baseCount, 22f, 100f).toInt()

                    gs.defEnemiesToSpawn = count
                    gs.defEnemiesAlive = 0
                    spawnerSys.queueSpawns(count, gs)
                    StoryProtocol.showIngameMessage("WAVE ${gs.defWaveCurrent} INCOMING!", 2f)
                }
            }
            1 -> {
                if (gs.defEnemiesAlive <= 0 && gs.defEnemiesToSpawn <= 0) {
                    if (gs.defWaveCurrent < gs.defWaveMax) {
                        gs.defWaveCurrent++
                        gs.defWaveState = 2
                        gs.defWaveTimer = 7f
                        StoryProtocol.showIngameMessage("WAVE CLEAR! REGROUPING...", 2f)
                    } else {
                        gs.defWaveCurrent++ // Win condition
                        gs.defWaveState = 3 // Finished - enables normal spawns for escape
                    }
                }
            }
        }
    }
    override fun checkWinCondition(gs: GameState): Boolean {
        if (!gs.escapeGateActive) return false
        val cdx = gs.px - gs.coreX
        val cdy = gs.py - gs.coreY
        return (cdx * cdx + cdy * cdy < gs.coreRadius * gs.coreRadius)
    }

    override fun isBossTriggerReady(gs: GameState): Boolean {
        val config = LevelEngine.getLevelConfig(gs.currentLevel)
        return gs.score >= config.targetScore || gs.elimTargetsKilled >= gs.elimTargetsRequired
    }
}

// 4. STORY OBJECTIVE (Progresses only via Boss Kills)
class StoryObjective : IGameObjective {
    private var trickleTimer = 2f
    override fun setupObjective(gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        gs.coreRadius = 0f 

        // STORY MODE CORE: Set central core position for the "Merge" sequence
        val grid = gs.gridMap
        if (grid != null) {
            val ts = gs.tileSize
            val cx = grid.size / 2
            val cy = grid[0].size / 2

            // Look for a safe spot near center
            val safeCenter = com.appsbyalok.echohunter.utils.SpawnValidator.findValidNear(
                cx * ts, cy * ts, ts * 0.5f, gs, 100, ts * 10f
            )

            if (safeCenter != null) {
                gs.coreX = safeCenter.first
                gs.coreY = safeCenter.second
            } else {
                gs.coreX = cx * ts + (ts / 2f)
                gs.coreY = cy * ts + (ts / 2f)
            }
        }
    }

    override fun updateObjective(dt: Float, gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        val prefix = getContextPrefix(gs)
        gs.objectiveLabel = when {
            gs.bossActive -> "NEUTRALIZE ADMIN SECURITY"
            else -> "$prefix | UPLINK: ${gs.score} / ${gs.sectorTarget} KB"
        }
        gs.objectiveProgress = if (gs.sectorTarget > 0) (gs.score.toFloat() / gs.sectorTarget).coerceIn(0f, 1f) else 1f

        trickleTimer -= dt
        if (trickleTimer <= 0f) {
            var activeCount = 0
            for (i in 0 until enemySys.n) if (enemySys.ex[i] > -1000f) activeCount++
            val baseLimit = if (gs.difficulty == DifficultyLevel.HARD) 20 else 15
            val limit = kotlin.math.min(45, baseLimit + (gs.currentSector * 5))
            val queued = spawnerSys.getTotalQueue(gs)
            if (activeCount + queued < limit) {
                spawnerSys.queueSpawns(limit - (activeCount + queued), gs)
            }
            trickleTimer = 1f
        }
        
        // Final Boss Kill logic in Story Mode: If boss was active and now dead
        // And we are past the final sector, activate the central core (State 8)
        if (gs.currentSector > 5 && gs.state == AppStateId.PLAYING) {
            gs.state = AppStateId.CORE_MERGE
            gs.coreRadius = 100f
            StoryProtocol.showIngameMessage("MAINFRAME COMPROMISED. ACCESSING CENTRAL CORE...", 5f)
            EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_HIGH_L, 1000)
        }

        if (gs.state == AppStateId.CORE_MERGE && gs.coreRadius == 0f) gs.coreRadius = 100f
    }
    
    override fun checkWinCondition(gs: GameState): Boolean {
        // In Story mode, "Winning" a level means clearing the final sector (Sector 5)
        // Intermediate sectors are handled by StoryMode logic. 
        // Level is cleared only when final boss is dead.
        return gs.currentSector >= 5 && !gs.bossActive && gs.bossHp <= 0 && areSecondaryFeaturesMet(gs)
    }
    
    override fun isBossTriggerReady(gs: GameState): Boolean {
        return gs.score >= gs.sectorTarget
    }
}

// 5. ELIMINATION OBJECTIVE (Kill High Value Targets)
class EliminationObjective : IGameObjective {
    private var trickleTimer = 1f

    override fun setupObjective(gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        gs.coreX = -9999f
        gs.coreY = -9999f
        gs.coreRadius = 0f
        gs.elimTargetsKilled = 0
        gs.elimTargetsRequired = LevelEngine.getSaturatedValue(gs.currentLevel, 5f, 10f, 100f).toInt()

        // --- NEW: MANUAL HVT & GUARD INJECTION ---
        // Elimination mode should have fixed HVTs and Guards spawned at start, not just trickle.
        if (gs.spawnerNodes.isNotEmpty()) {
            val hvtCount = gs.elimTargetsRequired
            var spawnedHVT = 0
            
            // 1. Spawn HVTs at Gateway nodes
            val gateways = gs.spawnerNodes.filter { it.type == 2 } // Type 2 = Admin Gateway
            for (node in gateways) {
                if (spawnedHVT >= hvtCount) break
                
                // Find empty slot for HVT
                var hvtIdx = -1
                for (i in 0 until enemySys.n) {
                    if (enemySys.ex[i] < -1000f) {
                        hvtIdx = i; break
                    }
                }
                
                if (hvtIdx != -1) {
                    enemySys.spawnAt(hvtIdx, node.x, node.y, gs, scale, 2) // nodeType 2 triggers TargetBehavior
                    spawnedHVT++
                    
                    // 2. Spawn 2 Guards for each HVT
                    var guardsSpawnedForThisHVT = 0
                    for (i in 0 until enemySys.n) {
                        if (guardsSpawnedForThisHVT >= 2) break
                        if (enemySys.ex[i] < -1000f) {
                            enemySys.spawnAt(i, node.x, node.y, gs, scale, 5) 
                            // Setup Guard AI parameters (spawnAt resets them)
                            enemySys.invX[i] = hvtIdx.toFloat()
                            enemySys.invY[i] = guardsSpawnedForThisHVT * 180f // Start on opposite sides
                            guardsSpawnedForThisHVT++
                        }
                    }
                }
            }
            
            // 3. Fallback: If not enough gateways, use normal nodes for remaining HVTs
            if (spawnedHVT < hvtCount) {
                val others = gs.spawnerNodes.filter { it.type != 2 }
                for (node in others) {
                    if (spawnedHVT >= hvtCount) break
                    var hvtIdx = -1
                    for (i in 0 until enemySys.n) {
                        if (enemySys.ex[i] < -1000f) {
                            hvtIdx = i; break
                        }
                    }
                    if (hvtIdx != -1) {
                        enemySys.spawnAt(hvtIdx, node.x, node.y, gs, scale, 2)
                        spawnedHVT++
                        
                        // Guard for fallback HVTs too
                        var guardsSpawnedForThisHVT = 0
                        for (i in 0 until enemySys.n) {
                            if (guardsSpawnedForThisHVT >= 1) break // Only 1 guard for "exposed" HVTs
                            if (enemySys.ex[i] < -1000f) {
                                enemySys.spawnAt(i, node.x, node.y, gs, scale, 5)
                                enemySys.invX[i] = hvtIdx.toFloat()
                                enemySys.invY[i] = 0f
                                guardsSpawnedForThisHVT++
                            }
                        }
                    }
                }
            }

            // FINAL SAFETY: If we couldn't spawn enough HVTs, adjust requirement to avoid soft-lock
            if (spawnedHVT < gs.elimTargetsRequired) {
                gs.elimTargetsRequired = spawnedHVT
            }
        }
    }

    override fun updateObjective(dt: Float, gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        val prefix = getContextPrefix(gs)
        gs.objectiveLabel = when {
            gs.bossActive -> "NEUTRALIZE ADMIN SECURITY"
            else -> "$prefix | ELIMINATION: ${gs.elimTargetsKilled} / ${gs.elimTargetsRequired}"
        }
        gs.objectiveProgress = if (gs.elimTargetsRequired > 0) (gs.elimTargetsKilled.toFloat() / gs.elimTargetsRequired).coerceIn(0f, 1f) else 1f

        trickleTimer -= dt
        if (trickleTimer <= 0f) {
            var activeCount = 0
            for (i in 0 until enemySys.n) if (enemySys.ex[i] > -1000f) activeCount++
            val baseLimit = if (gs.difficulty == DifficultyLevel.HARD) 18f else 14f
            val limit = LevelEngine.getSaturatedValue(gs.currentLevel, baseLimit, 40f - baseLimit, 100f).toInt()
            val queued = spawnerSys.getTotalQueue(gs)
            if (activeCount + queued < limit) {
                spawnerSys.queueSpawns(limit - (activeCount + queued), gs)
            }
            trickleTimer = 1f
        }
    }

    override fun checkWinCondition(gs: GameState): Boolean {
        return gs.elimTargetsKilled >= gs.elimTargetsRequired && areSecondaryFeaturesMet(gs)
    }

    override fun isBossTriggerReady(gs: GameState): Boolean {
        return gs.elimTargetsKilled >= gs.elimTargetsRequired
    }
}

// 6. BOMB OBJECTIVE (Plant and Defend)
class BombObjective : IGameObjective {
    private var trickleTimer = 1f
    private var bombPlanted = false
    private var plantTimer = 3f 
    private var defuseTimer = 45f 
    private var detonationTimer = 0f

    override fun setupObjective(gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        gs.coreX = -9999f
        gs.coreY = -9999f
        gs.coreRadius = 0f
        bombPlanted = false
        plantTimer = 3f
        detonationTimer = 0f
        defuseTimer = 30f + (gs.currentLevel / 5f).coerceAtMost(30f)

        val grid = gs.gridMap
        if (grid != null) {
            val paths = mutableListOf<Pair<Int, Int>>()
            for (x in 0 until grid.size) {
                for (y in 0 until grid[0].size) {
                    if (grid[x][y] == 0) paths.add(Pair(x, y))
                }
            }
            val targetNode = paths.filter {
                val dx = it.first * gs.tileSize - gs.px
                val dy = it.second * gs.tileSize - gs.py
                (dx*dx + dy*dy) > (targetW * targetW * 0.5f)
            }.randomOrNull() ?: paths.random()

            gs.bombTargetX = targetNode.first * gs.tileSize + gs.tileSize/2f
            gs.bombTargetY = targetNode.second * gs.tileSize + gs.tileSize/2f
        } else {
            gs.bombTargetX = gs.px + (if (Math.random() > 0.5) 1f else -1f) * targetW * 0.8f
            gs.bombTargetY = gs.py + (if (Math.random() > 0.5) 1f else -1f) * targetH * 0.8f
        }
    }

    override fun updateObjective(dt: Float, gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        val prefix = getContextPrefix(gs)

        if (!bombPlanted) {
            val dx = gs.px - gs.bombTargetX
            val dy = gs.py - gs.bombTargetY
            val dist = sqrt(dx * dx + dy * dy)
            gs.objectiveLabel = "$prefix | LOCATE PLANT SITE"
            gs.objectiveProgress = (1.0f - (dist / 1000f)).coerceIn(0f, 0.9f)
            if (dist < 150f) {
                gs.objectiveLabel = "PLANTING LOGIC BOMB..."
                gs.objectiveProgress = 1.0f - (plantTimer / 3f)
                plantTimer -= dt
                if (plantTimer <= 0f) {
                    bombPlanted = true
                    StoryProtocol.showIngameMessage("LOGIC BOMB PLANTED! DEFEND THE UPLINK!", 3f)
                    EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_ALERT, 500)
                    for(node in gs.spawnerNodes) node.queue += 5
                }
            } else plantTimer = 3f
        } else {
            if (detonationTimer > 0f) {
                detonationTimer -= dt
                gs.shakeAmount = scale * 0.2f
                gs.whiteFlash = (detonationTimer / 1.5f).coerceIn(0f, 1f)
                if (detonationTimer <= 0f) gs.isLevelCleared = true
                return
            }

            defuseTimer -= dt
            gs.objectiveLabel = "BOMB DETONATION IN: ${defuseTimer.toInt()}s"
            val totalTime = 45f + (gs.currentLevel / 10f)
            gs.objectiveProgress = 1.0f - (defuseTimer / totalTime)

            // Hybrid: Detonation only starts if boss/elim features are ALSO cleared
            if (defuseTimer <= 0f && areSecondaryFeaturesMet(gs)) {
                detonationTimer = 1.5f
                gs.whiteFlash = 1.0f
                gs.shakeAmount = scale * 0.25f
                StoryProtocol.isGlitchActive = true
                gs.objectiveLabel = "CRITICAL FAILURE IMMINENT"
                EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_HIGH_L, 1000)
            }
        }

        trickleTimer -= dt
        if (trickleTimer <= 0f) {
            var activeCount = 0
            for (i in 0 until enemySys.n) if (enemySys.ex[i] > -1000f) activeCount++
            val baseLimit = if (gs.difficulty == DifficultyLevel.HARD) 20f else 15f
            val limit = LevelEngine.getSaturatedValue(gs.currentLevel, baseLimit, 45f - baseLimit, 100f).toInt()
            val queued = spawnerSys.getTotalQueue(gs)
            if (activeCount + queued < limit) {
                spawnerSys.queueSpawns(limit - (activeCount + queued), gs)
            }
            trickleTimer = 1f
        }
    }

    override fun checkWinCondition(gs: GameState): Boolean {
        return bombPlanted && defuseTimer <= 0f && detonationTimer <= 0f && areSecondaryFeaturesMet(gs)
    }

    override fun isBossTriggerReady(gs: GameState): Boolean {
        return bombPlanted && defuseTimer <= 15f && defuseTimer > 0f
    }
}

// 7. CLEAN SWEEP OBJECTIVE (Destroy all Spawners)
class CleanSweepObjective : IGameObjective {
    private var trickleTimer = 1f
    private var initialNodes = 0

    override fun setupObjective(gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        gs.coreX = -9999f
        gs.coreY = -9999f
        gs.coreRadius = 0f
        initialNodes = gs.spawnerNodes.size
    }

    override fun updateObjective(dt: Float, gs: GameState, enemySys: EnemySystem, spawnerSys: SpawnerSystem, targetW: Float, targetH: Float, scale: Float) {
        val prefix = getContextPrefix(gs)
        val total = gs.spawnerNodes.size
        val activeNodes = spawnerSys.getActiveNodesCount(gs, includeInactive = true)
        val destroyed = total - activeNodes
        val remaining = activeNodes

        gs.objectiveProgress = if (total > 0) destroyed.toFloat() / total else 1f

        trickleTimer -= dt
        if (trickleTimer <= 0f) {
            var activeCount = 0
            for (i in 0 until enemySys.n) if (enemySys.ex[i] > -1000f) activeCount++
            val baseLimit = if (gs.difficulty == DifficultyLevel.HARD) 22f else 18f
            val limit = LevelEngine.getSaturatedValue(gs.currentLevel, baseLimit, 45f - baseLimit, 100f).toInt()
            val queued = spawnerSys.getTotalQueue(gs)
            if (activeCount + queued < limit) {
                spawnerSys.queueSpawns(limit - (activeCount + queued), gs)
            }

            // CLEAN SWEEP: If a lot of compilers are destroyed, speed up spawns for remaining ones
            val destroyRatio = if (total > 0) destroyed.toFloat() / total else 0f
            trickleTimer = if (destroyRatio > 0.5f) 0.5f else 1f
        }

        // NETWORK REVEAL: If only 2 compilers are left, reveal them permanently
        if (remaining in 1..2) {
            for (node in gs.spawnerNodes) {
                if (node.state != com.appsbyalok.echohunter.systems.SpawnState.DESTROYED) {
                    node.visibility = 1.0f
                }
            }
            if (gs.timeSinceStart.toInt() % 2 == 0) {
                gs.objectiveLabel = "CRITICAL: FINAL COMPILERS LOCATED"
            } else {
                gs.objectiveLabel = "PURGE REMAINING SECURITY"
            }
        } else {
            gs.objectiveLabel = "$prefix | PURGE COMPILERS"
        }

        // Hybrid Feature Support: Handle Darkness ambient reveal in Clean Sweep
        if (gs.isDarknessLevel) {
            for (node in gs.spawnerNodes) {
                if (node.state == com.appsbyalok.echohunter.systems.SpawnState.READY && node.visibility < 0.2f) {
                   // Clean sweep nodes in dark levels should ping occasionally to avoid frustration
                   if (kotlin.random.Random.nextFloat() < 0.005f) {
                       node.visibility = 0.5f
                   }
                }
            }
        }
    }

    override fun checkWinCondition(gs: GameState): Boolean {
        val allDestroyed = gs.spawnerNodes.all { it.state == com.appsbyalok.echohunter.systems.SpawnState.DESTROYED }
        return allDestroyed && areSecondaryFeaturesMet(gs)
    }

    override fun isBossTriggerReady(gs: GameState): Boolean {
        // Boss triggers when 70% of nodes are destroyed
        val total = gs.spawnerNodes.size
        val activeNodes = (total - gs.spawnerNodes.count { it.state == com.appsbyalok.echohunter.systems.SpawnState.DESTROYED })
        val destroyed = total - activeNodes
        return if (total > 0) (destroyed.toFloat() / total) >= 0.7f else false
    }
}
