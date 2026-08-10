package com.appsbyalok.echohunter.systems

import com.appsbyalok.echohunter.data.LevelEngine
import com.appsbyalok.echohunter.data.LevelFeature
import com.appsbyalok.echohunter.engine.DifficultyLevel
import com.appsbyalok.echohunter.engine.GameModeId
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.utils.SpawnValidator
import kotlin.random.Random

enum class SpawnState {
    INACTIVE,       // Sleeping, player hasn't reached near
    READY,          // Charged and waiting to spawn
    ACTIVE,         // Currently producing enemies
    COOLDOWN,       // Recharging after a spawn
    DISABLED,       // Hacked or temporarily shut down
    DESTROYED,      // Permanently gone
    REPAIRING,      // Glitch is trying to bring it back
    SELF_DESTROYING // Triggered for cinematic or gameplay reasons
}

// Data Class for Spawn Nodes (Where enemies originate from)
class SpawnNode(
    var x: Float,
    var y: Float,
    var type: Int, // 0 = Compiler (Normal), 1 = Glitch Tear (Swarm), 2 = Admin Gateway (HVT/Boss)
    var cooldownTimer: Float = 0f,
    var maxCooldown: Float = 5f,
    var queue: Int = 0, // Number of enemies queued to be spawned
    var state: SpawnState = SpawnState.READY,
    var hp: Float = 100f,
    var maxHp: Float = 100f,
    var visibility: Float = 0f, // For sonar detection
    var overloadTimer: Float = 0f, // For Clean Sweep resonance puzzle
    var parentNodeIdx: Int = -1 // For Network Link puzzle (Clean Sweep)
)

class SpawnerSystem(private val enemySys: EnemySystem, private val effectSys: EffectSystem) {
    
    fun generateNodes(gs: GameState, mapW: Float, mapH: Float, scale: Float) {
        gs.spawnerNodes.clear()

        val ts = gs.tileSize
        val grid = gs.gridMap
        
        // 1. First priority: Use GUARD_SPAWN markers from the map if they exist
        if (grid != null) {
            val gridW = grid.size
            val gridH = grid[0].size
            for (x in 0 until gridW) {
                for (y in 0 until gridH) {
                    if (grid[x][y] == 3) { // 3 = GUARD_SPAWN
                        val tx = x * ts + (ts / 2f)
                        val ty = y * ts + (ts / 2f)
                        
                        // Still validate just in case, but usually markers are safe
                        if (SpawnValidator.isFarFromNodes(tx, ty, gs, ts * 2f)) {
                            val type = Random.nextInt(0, 3)
                            val stats = getNodeStats(type)
                            gs.spawnerNodes.add(SpawnNode(
                                tx, ty, type, 
                                maxCooldown = stats.first,
                                hp = stats.second,
                                maxHp = stats.second
                            ))
                        }
                    }
                }
            }
        }

        // 2. Second priority: If we don't have enough nodes, procedurally generate the rest
        val config = LevelEngine.getLevelConfig(gs.currentLevel)
        val isHard = gs.difficulty == DifficultyLevel.HARD
        
        // Use original base nodes but allow feature-based expansion
        val baseNodes = if (isHard) 5f else 4f
        val bonus = if (gs.gameMode == GameModeId.STORY) 4f else 0f
        
        // Scale max addition based on level features for more density at higher levels
        var maxAdd = if (isHard) 12f else 8f
        if (config.features.contains(LevelFeature.CLEAN_SWEEP)) maxAdd += 25f
        if (config.features.contains(LevelFeature.ELIMINATION)) maxAdd += 10f

        val targetCount = if (config.features.contains(LevelFeature.CLEAN_SWEEP)) {
            // Density-based scaling for Clean Sweep (Total Destruction)
            // Area-based target: ~1 spawner per 20x20 tile area + base
            val area = (gs.gridMap?.size ?: 100) * (gs.gridMap?.get(0)?.size ?: 100)
            val areaDensityBonus = (area / 400f) // 1 per 20x20
            (baseNodes + bonus + areaDensityBonus).coerceAtMost(60f).toInt()
        } else {
            LevelEngine.getSaturatedValue(gs.currentLevel, baseNodes + bonus, maxAdd, 20f).toInt()
        }
        
        // --- NEW: DENSITY MINIMUM ---
        // Ensure spawners aren't too sparse in large maps (especially at high levels)
        val currentArea = (gs.gridMap?.size ?: 1) * (gs.gridMap?.get(0)?.size ?: 1)
        val minDensityCount = (currentArea / 625f).toInt() // At least 1 spawner per 25x25 area
        val finalTarget = kotlin.math.max(targetCount, minDensityCount)

        if (gs.spawnerNodes.size < finalTarget) {
            val remaining = finalTarget - gs.spawnerNodes.size
            for (i in 0 until remaining) {
                var nx = Random.nextFloat() * mapW
                var ny = Random.nextFloat() * mapH
                
                if (grid != null) {
                    val gridW = grid.size
                    val gridH = grid[0].size
                    var attempts = 0
                    var found = false

                    val maxAttempts = 300
                    while (attempts < maxAttempts) {
                        val col = Random.nextInt(1, gridW - 1)
                        val row = Random.nextInt(1, gridH - 1)
                        val tx = col * ts + (ts / 2f)
                        val ty = row * ts + (ts / 2f)

                        // --- PROGRESSIVE RELAXATION ---
                        // 0-100: Original Strict Constraints
                        // 101-200: Moderate Relaxation
                        // 201-300: Emergency Relaxation
                        val distRelaxation = when {
                            attempts > 200 -> 0.3f
                            attempts > 100 -> 0.6f
                            else -> 1.0f
                        }
                        
                        // VALIDATION: Far from player, AND far from other nodes
                        val pDist = (400f * scale) * distRelaxation
                        val nDist = (ts * 4f) * distRelaxation
                        
                        val isValidSpot = SpawnValidator.isValid(tx, ty, ts / 2f, gs, minPlayerDist = pDist)
                        val isFarEnough = SpawnValidator.isFarFromNodes(tx, ty, gs, nDist)
                        
                        // RESTORED: Core safety distance (ts * 6f)
                        val isDefense = config.features.contains(LevelFeature.DEFENSE)
                        val distToCoreSq = if (isDefense) {
                            val dx = tx - gs.coreX
                            val dy = ty - gs.coreY
                            dx * dx + dy * dy
                        } else Float.MAX_VALUE
                        val isSafeFromCore = distToCoreSq > (ts * 6f * distRelaxation) * (ts * 6f * distRelaxation)

                        if (isValidSpot && isFarEnough && isSafeFromCore) {
                            nx = tx
                            ny = ty
                            found = true
                            break
                        }
                        attempts++
                    }
                    if (!found) continue 
                } else {
                    if (!SpawnValidator.isFarFromNodes(nx, ny, gs, scale * 0.5f)) continue
                }

                val type = when {
                    config.features.contains(LevelFeature.ELIMINATION) -> {
                        if (i < 3) 2 else Random.nextInt(0, 2)
                    }
                    config.features.contains(LevelFeature.CLEAN_SWEEP) -> {
                        if (i % 4 == 0) 4 else Random.nextInt(0, 2)
                    }
                    Random.nextFloat() < 0.1f -> 6 // 10% chance for a Bomber spawner
                    i == 0 -> 0 
                    i == 1 -> 1 
                    Random.nextFloat() < 0.2f -> 2 
                    else -> Random.nextInt(0, 2)
                }

                val stats = getNodeStats(type)
                gs.spawnerNodes.add(SpawnNode(
                    nx, ny, type, 
                    maxCooldown = stats.first,
                    hp = stats.second,
                    maxHp = stats.second
                ))
            }
        }
        
        // 3. EMERGENCY SAFETY NET: Guaranteed minimums
        // If we still have very few spawners, force some in safe spots (walkable floor)
        val floorCount = if (gs.difficulty == DifficultyLevel.HARD) 10 else 7
        val minTarget = kotlin.math.max(floorCount, targetCount / 2)
        
        if (gs.spawnerNodes.size < minTarget && grid != null) {
            val gridW = grid.size
            val gridH = grid[0].size
            val needed = minTarget - gs.spawnerNodes.size
            
            var foundCount = 0
            outer@for (c in 1 until gridW - 1) {
                for (r in 1 until gridH - 1) {
                    if (grid[c][r] == 0) {
                        val tx = c * ts + (ts / 2f)
                        val ty = r * ts + (ts / 2f)
                        
                        // Even in emergency, stay away from player and core if possible
                        val distToPlayerSq = (tx - gs.px) * (tx - gs.px) + (ty - gs.py) * (ty - gs.py)
                        if (distToPlayerSq < (250f * scale) * (250f * scale)) continue
                        
                        if (config.features.contains(LevelFeature.DEFENSE)) {
                            val distToCoreSq = (tx - gs.coreX) * (tx - gs.coreX) + (ty - gs.coreY) * (ty - gs.coreY)
                            if (distToCoreSq < (ts * 4f) * (ts * 4f)) continue
                        }

                        if (SpawnValidator.isFarFromNodes(tx, ty, gs, ts * 2f)) {
                            val type = when {
                                config.features.contains(LevelFeature.ELIMINATION) -> 2
                                config.features.contains(LevelFeature.CLEAN_SWEEP) && foundCount % 4 == 0 -> 4
                                Random.nextFloat() < 0.1f -> 6 // Bomber
                                else -> Random.nextInt(0, 3)
                            }
                            val stats = getNodeStats(type)
                            gs.spawnerNodes.add(SpawnNode(
                                tx, ty, type,
                                maxCooldown = stats.first,
                                hp = stats.second,
                                maxHp = stats.second
                            ))
                            foundCount++
                            if (foundCount >= needed) break@outer
                        }
                    }
                }
            }
        }

        // 4. CLEAN SWEEP: Generate Network Links (The Puzzle)
        if (gs.activeObjective is com.appsbyalok.echohunter.modes.CleanSweepObjective) {
            val nodes = gs.spawnerNodes
            if (nodes.size > 1) {
                // Shuffle to make the "Root" nodes random every time
                val indices = nodes.indices.shuffled()
                
                // Root nodes (about 20-30%) have no parent
                val rootCount = kotlin.math.max(1, nodes.size / 4)
                
                for (i in rootCount until indices.size) {
                    val childIdx = indices[i]
                    // Pick a parent from the nodes processed BEFORE this one to prevent cycles
                    val parentIdx = indices[Random.nextInt(0, i)]
                    nodes[childIdx].parentNodeIdx = parentIdx
                }
            }
        }
    }

    private fun getNodeStats(type: Int): Pair<Float, Float> {
        return when(type) {
            0 -> 1.5f to 100f   // Normal: Fast, Standard HP
            1 -> 4.0f to 200f   // Swarm: Medium, High HP
            4 -> 3.0f to 300f   // Repair Hub: Medium, Beefy
            6 -> 2.5f to 50f    // Bomber: Fast, Low HP (glass cannon)
            else -> 6.0f to 500f // Admin: Slow, Tanky
        }
    }

    fun queueSpawns(count: Int, gs: GameState) {
        val validNodes = gs.spawnerNodes.filter { it.state != SpawnState.DESTROYED && it.state != SpawnState.DISABLED }
        if (validNodes.isEmpty()) return
        
        repeat(count) {
            val node = validNodes.random()
            node.queue++
            // If it was READY, move it to ACTIVE to indicate it has work to do
            if (node.state == SpawnState.READY) {
                node.state = SpawnState.ACTIVE
            }
        }
    }

    fun update(dt: Float, gs: GameState, targetW: Float, scale: Float) {
        val maxAllowedDistSq = (targetW * 2.5f) * (targetW * 2.5f)
        val activationDistSq = (targetW * 1.5f) * (targetW * 1.5f)

        for (node in gs.spawnerNodes) {
            if (node.state == SpawnState.DESTROYED) continue

            val dx = node.x - gs.px
            val dy = node.y - gs.py
            val distSq = dx * dx + dy * dy

            // 1. VISIBILITY & SONAR DETECTION
            val hitByPulse = (gs.pulse && distSq in gs.innerRSq..gs.outerRSq)
            if (hitByPulse || distSq < gs.passiveAuraRadiusSq) {
                if (hitByPulse && node.visibility <= 0f) {
                    effectSys.spawnSonarPing(node.x, node.y, com.appsbyalok.echohunter.utils.GameColors.PULSE)
                }
                node.visibility = 1.0f
                
                // PUZZLE MECHANIC: Pulse "primes" the node for a chain reaction in Clean Sweep
                if (hitByPulse && gs.activeObjective is com.appsbyalok.echohunter.modes.CleanSweepObjective) {
                    node.overloadTimer = 8f 
                }
            } else {
                val duration = com.appsbyalok.echohunter.data.UpgradeSystem.getSonarDurationBonus()
                node.visibility = kotlin.math.max(0f, node.visibility - dt / duration)
            }

            if (node.overloadTimer > 0f) {
                node.overloadTimer -= dt
                if (Random.nextFloat() < 2f * dt) {
                    effectSys.spawnParticles(node.x, node.y, 1, scale * 0.35f, count = 1)
                }
            }

            // 2. PROXIMITY STATE MANAGEMENT
            if (distSq > maxAllowedDistSq && node.type != 2) {
                // If extremely far (procedural drift), relocate safely
                relocateNode(node, gs, targetW)
            }

            if (distSq > activationDistSq) {
                node.state = SpawnState.INACTIVE
            } else if (node.state == SpawnState.INACTIVE) {
                node.state = SpawnState.READY
            }

            // --- NEW: STASIS PULSE EFFECTS ---
            var isStasised = false
            for (trap in gs.activeTraps) {
                if (trap.type == 3) { // Stasis Pulse
                    val tr = scale * 0.25f
                    val tdx = node.x - trap.x
                    val tdy = node.y - trap.y
                    if (tdx * tdx + tdy * tdy < tr * tr) {
                        isStasised = true
                        break
                    }
                }
            }

            // 3. STATE MACHINE LOGIC
            if (!isStasised) {
                when (node.state) {
                    SpawnState.COOLDOWN -> {
                        node.cooldownTimer -= dt
                        if (node.cooldownTimer <= 0f) {
                            node.state = if (node.queue > 0) SpawnState.ACTIVE else SpawnState.READY
                        }
                    }
                    SpawnState.REPAIRING -> {
                        node.hp += dt * 5f // Gradually regain HP
                        if (node.hp >= node.maxHp) {
                            node.hp = node.maxHp
                            node.state = SpawnState.READY
                            node.queue = 0
                            node.cooldownTimer = 0f
                            node.visibility = 0f
                        }
                    }
                    SpawnState.DISABLED -> {
                        node.cooldownTimer -= dt
                        if (node.cooldownTimer <= 0f) node.state = SpawnState.READY
                    }
                    SpawnState.SELF_DESTROYING -> {
                        if (node.cooldownTimer > 0f) {
                            node.cooldownTimer -= dt
                            if (Random.nextFloat() < 3f * dt) effectSys.spawnParticles(node.x, node.y, 0, scale * 0.35f, count = 1)
                        } else {
                            node.hp -= dt * 100f
                            if (node.hp <= 0f) onNodeDestroyed(node, gs, scale)
                        }
                    }
                    SpawnState.READY, SpawnState.ACTIVE -> {
                        if (node.queue > 0 && node.cooldownTimer <= 0f) {
                            node.state = SpawnState.ACTIVE
                            trySpawnEnemy(node, gs, scale)
                        } else if (node.queue <= 0) {
                            node.state = SpawnState.READY
                        }
                    }
                    else -> {}
                }
            } else if (Random.nextFloat() < 1.5f * dt) {
                // Visual feedback for stasis
                effectSys.spawnParticles(node.x, node.y, 2, scale * 0.25f, count = 1)
            }

            // An active node gets a subtle cue; idle/ready nodes stay visually quiet.
            if (node.state == SpawnState.ACTIVE && Random.nextFloat() < 0.75f * dt) {
                effectSys.spawnParticles(node.x, node.y, 0, scale * 0.3f, count = 1)
            }
        }

        // --- SOFT-LOCK PROTECTION ---
        // If all nodes are gone, we must ensure objectives keep progressing
        val allActuallyDestroyed = gs.spawnerNodes.isNotEmpty() && gs.spawnerNodes.all { it.state == SpawnState.DESTROYED }
        if (allActuallyDestroyed) {
            triggerPurgeVictory(gs)
        }
    }

    private fun relocateNode(node: SpawnNode, gs: GameState, targetW: Float) {
        run search@{
            repeat(15) {
                val angle = Random.nextFloat() * 6.2831855f
                val dist = (targetW * 1.2f) + Random.nextFloat() * (targetW * 0.5f)
                val tx = gs.px + kotlin.math.cos(angle) * dist
                val ty = gs.py + kotlin.math.sin(angle) * dist

                if (SpawnValidator.isValid(tx, ty, gs.tileSize / 2f, gs) && 
                    SpawnValidator.isFarFromNodes(tx, ty, gs, gs.tileSize * 2f)) {
                    node.x = tx
                    node.y = ty
                    return@search
                }
            }
        }
    }

    private fun trySpawnEnemy(node: SpawnNode, gs: GameState, scale: Float) {
        val config = LevelEngine.getLevelConfig(gs.currentLevel)
        val isDefenseWave = config.features.contains(LevelFeature.DEFENSE) && gs.defWaveState == 1

        for (i in 0 until enemySys.n) {
            if (enemySys.ex[i] < -1000f) {
                enemySys.spawnAt(i, node.x, node.y, gs, scale, node.type)
                node.queue--

                if (isDefenseWave) {
                    gs.defEnemiesAlive++
                    gs.defEnemiesToSpawn--
                    if (gs.defEnemiesToSpawn < 0) gs.defEnemiesToSpawn = 0
                }

                // Set cooldown and move to COOLDOWN state
                val rushFactor = if (node.queue > 2) 0.5f else 1.0f
                node.cooldownTimer = node.maxCooldown * rushFactor * (0.8f + Random.nextFloat() * 0.4f)
                node.state = SpawnState.COOLDOWN
                
                val particleType = if (node.type == 1) 1 else 0
                effectSys.spawnParticles(node.x, node.y, particleType, scale * 2f)
                break
            }
        }
    }

    fun damageNode(node: SpawnNode, amount: Float, gs: GameState, scale: Float) {
        if (node.state == SpawnState.DESTROYED || node.state == SpawnState.SELF_DESTROYING) return

        node.hp -= amount
        node.visibility = 1.0f // Reveal on damage
        
        if (node.hp <= 0f) {
            onNodeDestroyed(node, gs, scale)
        } else {
            effectSys.spawnParticles(node.x, node.y, 0, scale * 1f)
        }
    }

    private fun onNodeDestroyed(node: SpawnNode, gs: GameState, scale: Float) {
        if (node.state == SpawnState.DESTROYED) return
        
        node.state = SpawnState.DESTROYED
        node.hp = 0f
        effectSys.spawnParticles(node.x, node.y, 1, scale * 3f)

        // Reward for EACH node destroyed
        gs.collectedDataKB += (50 * (1.0f + com.appsbyalok.echohunter.data.UpgradeSystem.getRewardBonusPercent())).toLong()
        gs.score += (100 * com.appsbyalok.echohunter.data.UpgradeSystem.getRewardMultiplier()).toLong()
        gs.overclockMeter = kotlin.math.min(100f, gs.overclockMeter + 15f)
        effectSys.spawnFloatingText(node.x, node.y, 0, com.appsbyalok.echohunter.utils.GameColors.CLARITY, "+${(50 * (1.0f + com.appsbyalok.echohunter.data.UpgradeSystem.getRewardBonusPercent())).toLong()} KB")

        // CHAIN REACTION: If Clean Sweep mode, trigger cascading destruction
        // Instead of immediate recursion, we use SpawnState.SELF_DESTROYING with a delay
        // so the player can see the "Network" collapsing in real-time.
        if (gs.activeObjective is com.appsbyalok.echohunter.modes.CleanSweepObjective) {
            val isOverloaded = node.overloadTimer > 0f
            val baseRadius = if (isOverloaded) 6f else 2.5f
            val resonanceBonus = com.appsbyalok.echohunter.data.UpgradeSystem.getLevel(com.appsbyalok.echohunter.data.UpgradeType.RESONANCE_CHAMBER) * 0.5f
            val chainRadiusSq = (scale * (baseRadius + resonanceBonus)) * (scale * (baseRadius + resonanceBonus))

            // 1. Proximity Surge
            for (other in gs.spawnerNodes) {
                if (other.state == SpawnState.DESTROYED || other.state == SpawnState.SELF_DESTROYING) continue
                val dx = node.x - other.x
                val dy = node.y - other.y
                val distSq = dx * dx + dy * dy
                
                if (distSq < chainRadiusSq || (isOverloaded && other.overloadTimer > 0f)) {
                    other.state = SpawnState.SELF_DESTROYING
                    other.cooldownTimer = 0.1f + Random.nextFloat() * 0.2f // Tiny delay for visuals
                    other.hp = 1f 
                }
            }

            // 2. NETWORK LINK PURGE (The Puzzle)
            val nodeIdx = gs.spawnerNodes.indexOf(node)
            if (nodeIdx != -1) {
                for (child in gs.spawnerNodes) {
                    if (child.parentNodeIdx == nodeIdx && child.state != SpawnState.DESTROYED && child.state != SpawnState.SELF_DESTROYING) {
                        child.state = SpawnState.SELF_DESTROYING
                        child.cooldownTimer = 0.35f // Fixed delay to show hierarchy flow
                        child.hp = 1f
                        effectSys.spawnSonarPing(child.x, child.y, com.appsbyalok.echohunter.utils.GameColors.PULSE)
                        effectSys.spawnFloatingText(child.x, child.y, 0, com.appsbyalok.echohunter.utils.GameColors.PULSE, "LINK BREACH")
                    }
                }
            }
            
            if (isOverloaded) {
                effectSys.spawnSonarPing(node.x, node.y, com.appsbyalok.echohunter.utils.GameColors.PULSE)
                effectSys.spawnFloatingText(node.x, node.y, 0, com.appsbyalok.echohunter.utils.GameColors.PULSE, "RESONANCE")
            } else {
                effectSys.spawnSonarPing(node.x, node.y, com.appsbyalok.echohunter.utils.GameColors.RED)
            }
        }

        // Final Purge Check
        val allActuallyDestroyed = gs.spawnerNodes.isNotEmpty() && gs.spawnerNodes.all { it.state == SpawnState.DESTROYED }
        if (allActuallyDestroyed) {
            triggerPurgeVictory(gs)
        }
    }

    private fun triggerPurgeVictory(gs: GameState) {
        val config = LevelEngine.getLevelConfig(gs.currentLevel)
        var objectivesCompletedByPurge = false
        
        // 1. AUTO-COMPLETE OBJECTIVES that depend on compilers
        if (config.features.contains(LevelFeature.ELIMINATION) && gs.elimTargetsKilled < gs.elimTargetsRequired) {
            gs.elimTargetsKilled = gs.elimTargetsRequired
            objectivesCompletedByPurge = true
        }
        
        if (config.features.contains(LevelFeature.CLASSIC) && gs.score < config.targetScore) {
            gs.score = config.targetScore
            objectivesCompletedByPurge = true
        }

        if (config.features.contains(LevelFeature.DEFENSE) && gs.defWaveCurrent <= gs.defWaveMax) {
            gs.defWaveCurrent = gs.defWaveMax + 1
            gs.defWaveState = 3
            objectivesCompletedByPurge = true
        }

        // 1.5 FORCE BOSS TRIGGER: If spawners are gone, boss MUST arrive or player gets soft-locked
        // In Story Mode, we force the score to meet the current sector's target.
        if (config.features.contains(LevelFeature.BOSS) && !gs.bossActive) {
            if (gs.gameMode == GameModeId.STORY) {
                if (gs.score < gs.sectorTarget) {
                    gs.score = gs.sectorTarget.toLong()
                    objectivesCompletedByPurge = true
                }
            } else if (gs.score < config.targetScore) {
                gs.score = config.targetScore
                objectivesCompletedByPurge = true
            }
        }

        // 2. Wipe all current enemies since their source is gone
        var enemiesWipedAny = false
        for (i in 0 until enemySys.n) {
            // Check state first to avoid re-killing already dead/dying enemies
            if (enemySys.ex[i] > -1000f && enemySys.hp[i] > 0) {
                enemySys.hp[i] = 0
                enemySys.killEnemy(i, gs)
                enemiesWipedAny = true
            }
        }

        // 3. Victory or Progression Check
        if (gs.activeObjective.checkWinCondition(gs)) {
            if (!gs.isLevelCleared) {
                gs.isLevelCleared = true
                com.appsbyalok.echohunter.data.StoryProtocol.showTypewriterMessage(
                    "CRITICAL SYSTEM FAILURE DETECTED...\n" +
                    "ALL SECURITY COMPILERS TERMINATED.\n" +
                    "ADMIN UPLINK SEVERED. ACCESS GRANTED."
                )
            }
        } else if (objectivesCompletedByPurge || enemiesWipedAny) {
            // Level not cleared yet (maybe BOMB or ESCAPE still pending)
            // Show message only when we actually take an action (wipe or objective jump)
            val msg = if (objectivesCompletedByPurge) {
                "SECURITY LAYER PURGED: DEPENDENT PROTOCOLS BYPASSED.\n" +
                "COMPLETE REMAINING OBJECTIVES."
            } else {
                "ALL COMPILERS DESTROYED.\n" +
                "ADAPTIVE SECURITY OFFLINE."
            }
            com.appsbyalok.echohunter.data.StoryProtocol.showTypewriterMessage(msg, 4f)
            
            // Massive rewards for the purge (Only given once per 'action' event)
            gs.collectedDataKB += (250 * (1.0f + com.appsbyalok.echohunter.data.UpgradeSystem.getRewardBonusPercent())).toLong()
            gs.overclockTimer = 8f
        }
    }

    fun getTotalQueue(gs: GameState): Int {
        return gs.spawnerNodes.sumOf { it.queue }
    }

    fun getActiveNodesCount(gs: GameState, includeInactive: Boolean = false): Int {
        return gs.spawnerNodes.count { 
            it.state != SpawnState.DESTROYED && (includeInactive || it.state != SpawnState.INACTIVE) 
        }
    }
}
