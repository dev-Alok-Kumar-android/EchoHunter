package com.appsbyalok.echohunter.systems

import android.media.ToneGenerator
import com.appsbyalok.echohunter.engine.DifficultyLevel
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.utils.EchoAudioManager
import com.appsbyalok.echohunter.utils.GameColors
import kotlin.math.sqrt

// =========================================================
// REGULAR ENEMY BEHAVIORS
// =========================================================

// Blueprint for Enemy Brains
interface IEnemyBehavior {
    fun updateBehavior(
        i: Int,
        dt: Float,
        gs: GameState,
        enemySys: EnemySystem,
        ai: EnemyAI,
        targetW: Float,
        targetH: Float,
        scale: Float,
    )
}

// 1. PATROL BEHAVIOR (Yellow - Investigates sounds)
object PatrolBehavior : IEnemyBehavior {
    override fun updateBehavior(
        i: Int,
        dt: Float,
        gs: GameState,
        enemySys: EnemySystem,
        ai: EnemyAI,
        targetW: Float,
        targetH: Float,
        scale: Float,
    ) {
        val targetX = if (gs.isDecoyActive) gs.decoyX else gs.px
        val targetY = if (gs.isDecoyActive) gs.decoyY else gs.py
        val tdx = targetX - enemySys.ex[i]
        val tdy = targetY - enemySys.ey[i]
        val td2 = tdx * tdx + tdy * tdy

        val hitDistSq = (scale * 0.045f) * (scale * 0.045f)

        // Intelligence Scaling: Reaction speed increases with level
        val config = com.appsbyalok.echohunter.data.LevelEngine.getLevelConfig(gs.currentLevel)
        val reactionThreshold = scale * (1.5f + (gs.currentLevel * 0.01f).coerceAtMost(1.0f))

        val speed = scale * (if (gs.difficulty == DifficultyLevel.NORMAL) 0.25f else 0.4f) * config.speedMultiplier

        val hitByPulse = (gs.pulse && td2 in gs.innerRSq..gs.outerRSq)

        // --- NEW: SONAR ALERT LOGIC ---
        // Base sonar alert range is high; reduced by upgrades.
        val sonarAlertRange =
            scale * 8f * com.appsbyalok.echohunter.data.UpgradeSystem.getSonarSilenceMultiplier()
        val heardPulse = hitByPulse && td2 < sonarAlertRange * sonarAlertRange

        val heardSound =
            heardPulse || (gs.localAttackAlert && td2 < reactionThreshold * reactionThreshold)

        if (heardSound && (enemySys.eState[i] != 2 || enemySys.investigateTimer[i] < 2.5f)) {
            enemySys.eState[i] = 2
            enemySys.invX[i] = gs.px
            enemySys.invY[i] = gs.py
            enemySys.investigateTimer[i] = 3.0f + (gs.currentLevel * 0.01f) // Stay alert longer
            if (gs.gridMap != null) ai.updateAlertHeatMap(gs, gs.px, gs.py)
        }

        if (enemySys.eState[i] == 2) {
            // High level enemies use predictive pathing (simulated by better LoS checks)
            val losRange = (scale * 7f) + (gs.currentLevel * 0.1f)
            val inLoS = td2 < losRange * losRange && ai.hasLineOfSight(
                enemySys.ex[i], enemySys.ey[i], gs.px, gs.py, gs
            )

            if (inLoS && td2 < hitDistSq * 50f) {
                // Transform to Hunter
                enemySys.type[i] = 1
                enemySys.enemyBrains[i] = HunterBehavior
                enemySys.eState[i] = 1
                return
            }

            val idx = enemySys.invX[i] - enemySys.ex[i]
            val idy = enemySys.invY[i] - enemySys.ey[i]
            val distToInvSq = idx * idx + idy * idy
            if (distToInvSq > (scale * 0.05f) * (scale * 0.05f)) {
                if (gs.gridMap != null) {
                    val (nvx, nvy) = ai.steerByAlertHeatMap(
                        enemySys.ex[i],
                        enemySys.ey[i],
                        enemySys.evx[i],
                        enemySys.evy[i],
                        speed * 0.7f,
                        gs,
                        dt
                    )
                    enemySys.evx[i] = nvx
                    enemySys.evy[i] = nvy
                } else {
                    val invDist = sqrt(distToInvSq)
                    val investigateSpeed = speed * 0.7f
                    val lerpFactor = (dt * 5f).coerceIn(0f, 1f)
                    enemySys.evx[i] =
                        (enemySys.evx[i] * (1f - lerpFactor)) + ((idx / invDist) * investigateSpeed * lerpFactor)
                    enemySys.evy[i] =
                        (enemySys.evy[i] * (1f - lerpFactor)) + ((idy / invDist) * investigateSpeed * lerpFactor)
                }
            } else {
                enemySys.investigateTimer[i] -= dt
                val damping = (dt * 10f).coerceIn(0f, 1f)
                enemySys.evx[i] *= (1f - damping); enemySys.evy[i] *= (1f - damping)
                if (enemySys.investigateTimer[i] <= 0f) enemySys.eState[i] = 0
            }
        }
    }
}

// 2. HUNTER BEHAVIOR (Red - Chases Player Aggressively)
object HunterBehavior : IEnemyBehavior {
    override fun updateBehavior(
        i: Int,
        dt: Float,
        gs: GameState,
        enemySys: EnemySystem,
        ai: EnemyAI,
        targetW: Float,
        targetH: Float,
        scale: Float,
    ) {
        val targetX = if (gs.isDecoyActive) gs.decoyX else gs.px
        val targetY = if (gs.isDecoyActive) gs.decoyY else gs.py
        val tdx = targetX - enemySys.ex[i]
        val tdy = targetY - enemySys.ey[i]
        val td2 = tdx * tdx + tdy * tdy

        val dist = sqrt(td2)
        val config = com.appsbyalok.echohunter.data.LevelEngine.getLevelConfig(gs.currentLevel)
        val speed = scale * (if (gs.difficulty == DifficultyLevel.NORMAL) 0.25f else 0.4f) * config.speedMultiplier

        // Predictive Movement Logic: Anticipate player movement but cap it by distance to prevent overshooting
        val baseLead = (gs.currentLevel * 0.004f).coerceAtMost(0.4f)
        val leadAmount =
            if (dist > 0.001f) (baseLead * (dist / scale)).coerceAtMost(baseLead) else 0f

        val pvx = gs.pvx
        val pvy = gs.pvy

        val predX = targetX + pvx * leadAmount
        val predY = targetY + pvy * leadAmount

        val ptdx = predX - enemySys.ex[i]
        val ptdy = predY - enemySys.ey[i]
        val ptd2 = ptdx * ptdx + ptdy * ptdy

        val inLoS = ai.hasLineOfSight(enemySys.ex[i], enemySys.ey[i], targetX, targetY, gs)

        if (inLoS) {
            enemySys.eState[i] = 1
            val eDist = sqrt(ptd2)

            // Speed Multiplier: Normal is easier, Hard is faster
            val chaseMult = if (gs.difficulty == DifficultyLevel.HARD) 1.2f else 1.05f

            // Effect of Decoy or Camouflage
            val isPlayerTarget = !gs.isDecoyActive && !gs.isCamouflaged
            val chaseSpeed = speed * (if (gs.isOverclocked && isPlayerTarget) -0.5f else chaseMult)

            if (eDist > 0f) {
                val lerpFactor = (dt * 6f).coerceIn(0f, 1f)
                enemySys.evx[i] =
                    (enemySys.evx[i] * (1f - lerpFactor)) + ((ptdx / eDist) * chaseSpeed * lerpFactor)
                enemySys.evy[i] =
                    (enemySys.evy[i] * (1f - lerpFactor)) + ((ptdy / eDist) * chaseSpeed * lerpFactor)
            }
        } else {
            enemySys.eState[i] = 2
        }

        if (enemySys.eState[i] == 2 && gs.gridMap != null) {
            val (nvx, nvy) = ai.steerByPlayerHeatMap(
                enemySys.ex[i], enemySys.ey[i], enemySys.evx[i], enemySys.evy[i], speed, gs, dt
            )
            enemySys.evx[i] = nvx
            enemySys.evy[i] = nvy
        }
    }
}

// 3. KAMIKAZE BEHAVIOR (Bombers for Core Defense)
object KamikazeBehavior : IEnemyBehavior {
    override fun updateBehavior(
        i: Int,
        dt: Float,
        gs: GameState,
        enemySys: EnemySystem,
        ai: EnemyAI,
        targetW: Float,
        targetH: Float,
        scale: Float,
    ) {
        val speed = scale * (if (gs.difficulty == DifficultyLevel.NORMAL) 0.25f else 0.35f)
        // Relentlessly pathfind to the Core Map
        if (gs.gridMap != null) {
            val (nvx, nvy) = ai.steerByCoreHeatMap(
                enemySys.ex[i], enemySys.ey[i], enemySys.evx[i], enemySys.evy[i], speed, gs, dt
            )
            enemySys.evx[i] = nvx
            enemySys.evy[i] = nvy
        }
    }
}


// 4. TARGET BEHAVIOR (High-Value Target - Runs away when spotted)
object TargetBehavior : IEnemyBehavior {
    override fun updateBehavior(
        i: Int,
        dt: Float,
        gs: GameState,
        enemySys: EnemySystem,
        ai: EnemyAI,
        targetW: Float,
        targetH: Float,
        scale: Float,
    ) {
        val targetX = if (gs.isDecoyActive) gs.decoyX else gs.px
        val targetY = if (gs.isDecoyActive) gs.decoyY else gs.py
        val tdx = targetX - enemySys.ex[i]
        val tdy = targetY - enemySys.ey[i]
        val td2 = tdx * tdx + tdy * tdy

        val dist = sqrt(td2)
        val config = com.appsbyalok.echohunter.data.LevelEngine.getLevelConfig(gs.currentLevel)
        val speedMult = config.speedMultiplier
        val speed = scale * (if (gs.difficulty == DifficultyLevel.NORMAL) 0.25f else 0.35f) * speedMult

        val panicDist = scale * (0.8f + (gs.currentLevel * 0.005f).coerceAtMost(0.5f))
        val inLoS = ai.hasLineOfSight(enemySys.ex[i], enemySys.ey[i], targetX, targetY, gs)

        // If player is close and in LoS, trigger panic mode (Run!)
        if (inLoS && td2 < panicDist * panicDist) {
            enemySys.eState[i] = 2 // Alert state
            if (dist > 0f) {
                val steerSharpness = if (gs.difficulty == DifficultyLevel.HARD) 8f else 4f
                val lerpFactor = (dt * steerSharpness).coerceIn(0f, 1f)

                val fleeMult = if (gs.difficulty == DifficultyLevel.HARD) 1.5f else 1.2f
                val targetVx = -(tdx / dist) * speed * fleeMult
                val targetVy = -(tdy / dist) * speed * fleeMult
                enemySys.evx[i] = (enemySys.evx[i] * (1f - lerpFactor)) + (targetVx * lerpFactor)
                enemySys.evy[i] = (enemySys.evy[i] * (1f - lerpFactor)) + (targetVy * lerpFactor)
            }
        } else {
            if (enemySys.eState[i] != 2) {
                enemySys.eState[i] = 0
                if (kotlin.random.Random.nextFloat() < 1.2f * dt) {
                    val angle = kotlin.random.Random.nextFloat() * 6.28f
                    enemySys.evx[i] = kotlin.math.cos(angle) * speed * 0.7f
                    enemySys.evy[i] = kotlin.math.sin(angle) * speed * 0.7f
                }
            }
        }
    }
}

// 5. REPAIR DRONE BEHAVIOR (Support Unit - Repairs Destroyed Spawners)
object RepairDroneBehavior : IEnemyBehavior {

    override fun updateBehavior(
        i: Int,
        dt: Float,
        gs: GameState,
        enemySys: EnemySystem,
        ai: EnemyAI,
        targetW: Float,
        targetH: Float,
        scale: Float,
    ) {
        val speed = scale * 0.4f

        // 1. Find the nearest destroyed spawner using system helper
        val spawnerSys = gs.spawnerNodes.let { nodes ->
            // We can't easily get SpawnerSystem instance here without interface change,
            // but we can at least centralize the search logic if we had access.
            // For now, let's keep the logic but prepare it for future SpawnerSystem integration
            nodes.filter { it.state == SpawnState.DESTROYED }
                .minByOrNull { (it.x - enemySys.ex[i]) * (it.x - enemySys.ex[i]) + (it.y - enemySys.ey[i]) * (it.y - enemySys.ey[i]) }
        }

        if (spawnerSys != null) {
            val dx = spawnerSys.x - enemySys.ex[i]
            val dy = spawnerSys.y - enemySys.ey[i]
            val d2 = dx * dx + dy * dy

            if (d2 < (scale * 0.1f) * (scale * 0.1f)) {
                // At destination: Trigger Repair
                enemySys.investigateTimer[i] -= dt
                val damping = (dt * 10f).coerceIn(0f, 1f)
                enemySys.evx[i] *= (1f - damping)
                enemySys.evy[i] *= (1f - damping)

                if (enemySys.investigateTimer[i] <= 0f) {
                    // Logic centralized in SpawnNode state management later, but for now:
                    spawnerSys.state = SpawnState.REPAIRING
                    spawnerSys.hp = 1f
                    enemySys.investigateTimer[i] = 5f
                    // Notify system
                    com.appsbyalok.echohunter.data.StoryProtocol.showIngameMessage(
                        "SYSTEM: REPAIR SEQUENCE INITIATED", 1f
                    )

                    // Distinct visual alert
                    enemySys.getEffectSystem()?.let { effects ->
                        effects.spawnAlertPulse(spawnerSys.x, spawnerSys.y, GameColors.RED)
                        effects.spawnFloatingText(
                            spawnerSys.x, spawnerSys.y, 0, GameColors.RED, "REPAIRING"
                        )
                    }

                    // Audio alert
                    EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_ALERT, 200)
                }
            } else {
                // Move towards destroyed node
                val dist = sqrt(d2)
                val lerpFactor = (dt * 5f).coerceIn(0f, 1f)
                enemySys.evx[i] =
                    (enemySys.evx[i] * (1f - lerpFactor)) + ((dx / dist) * speed * lerpFactor)
                enemySys.evy[i] =
                    (enemySys.evy[i] * (1f - lerpFactor)) + ((dy / dist) * speed * lerpFactor)
            }
        } else {
            // No destroyed nodes: Wander or flee player
            val tdx = gs.px - enemySys.ex[i]
            val tdy = gs.py - enemySys.ey[i]
            if (tdx * tdx + tdy * tdy < (scale * 1.5f) * (scale * 1.5f)) {
                // Flee
                val dist = sqrt(tdx * tdx + tdy * tdy)
                val lerpFactor = (dt * 3f).coerceIn(0f, 1f)
                enemySys.evx[i] =
                    (enemySys.evx[i] * (1f - lerpFactor)) - ((tdx / dist) * speed * lerpFactor)
                enemySys.evy[i] =
                    (enemySys.evy[i] * (1f - lerpFactor)) - ((tdy / dist) * speed * lerpFactor)
            } else {
                // Wander
                if (kotlin.random.Random.nextFloat() < 0.6f * dt) {
                    val angle = kotlin.random.Random.nextFloat() * 6.28f
                    enemySys.evx[i] = kotlin.math.cos(angle) * speed * 0.5f
                    enemySys.evy[i] = kotlin.math.sin(angle) * speed * 0.5f
                }
            }
        }
    }
}
// Not need now. because it doesn't seem good currently. I keep it maybe later it can be use anywhere
//// 7. BOMBER BEHAVIOR (Suicide Unit)
//object BomberBehavior : IEnemyBehavior {
//    override fun updateBehavior(
//        i: Int,
//        dt: Float,
//        gs: GameState,
//        enemySys: EnemySystem,
//        ai: EnemyAI,
//        targetW: Float,
//        targetH: Float,
//        scale: Float,
//    ) {
//        val targetX = if (gs.isDecoyActive) gs.decoyX else gs.px
//        val targetY = if (gs.isDecoyActive) gs.decoyY else gs.py
//        val tdx = targetX - enemySys.ex[i]
//        val tdy = targetY - enemySys.ey[i]
//        val td2 = tdx * tdx + tdy * tdy
//
//        val dist = sqrt(td2)
//        val config = com.appsbyalok.echohunter.data.LevelEngine.getLevelConfig(gs.currentLevel)
//        val speed = scale * 0.3f * config.speedMultiplier // Slightly faster than normal
//
//        if (dist > 0f) {
//            val lerpFactor = (dt * 8f).coerceIn(0f, 1f) // More responsive steering
//            enemySys.evx[i] =
//                (enemySys.evx[i] * (1f - lerpFactor)) + ((tdx / dist) * speed * lerpFactor)
//            enemySys.evy[i] =
//                (enemySys.evy[i] * (1f - lerpFactor)) + ((tdy / dist) * speed * lerpFactor)
//        }
//    }
//}

// 6. GUARD BEHAVIOR (Orbits HVT, switches to Aggro when player enters range)
object GuardBehavior : IEnemyBehavior {
    override fun updateBehavior(
        i: Int,
        dt: Float,
        gs: GameState,
        enemySys: EnemySystem,
        ai: EnemyAI,
        targetW: Float,
        targetH: Float,
        scale: Float,
    ) {
        // HVT Index is stored in invX
        val hvtIdx = enemySys.invX[i].toInt()
        val hasHvt = hvtIdx >= 0 && hvtIdx < enemySys.n && enemySys.ex[hvtIdx] > -1000f

        // CRITICAL FIX: If HVT is lost/destroyed, the Guard must be terminated to prevent crash.
        if (!hasHvt) {
            enemySys.hp[i] = 0
            enemySys.killEnemy(i, gs)
            return
        }

        val targetX = if (gs.isDecoyActive) gs.decoyX else gs.px
        val targetY = if (gs.isDecoyActive) gs.decoyY else gs.py
        val tdx = targetX - enemySys.ex[i]
        val tdy = targetY - enemySys.ey[i]
        val distToPlayerSq = tdx * tdx + tdy * tdy
        
        val hx = enemySys.ex[hvtIdx]
        val hy = enemySys.ey[hvtIdx]
        
        // 1. SPLIT ROLE LOGIC: First guard is always a Defender, next 2 can be Interceptors.
        // Any guards beyond the 3rd also stay to defend (HVT is heavily fortified).
        var guardRank = 0
        for (j in 0 until i) {
            if (enemySys.type[j] == 5 && enemySys.invX[j].toInt() == hvtIdx && enemySys.ex[j] > -1000f) {
                guardRank++
            }
        }
        
        val isInterceptor = guardRank in 1..2
        val aggroRange = scale * 1.5f
        val tetherRange = gs.tileSize * 4.0f
        
        // Alerted if player is near OR if pulse was recently heard (investigateTimer > 0)
        val isAlerted = distToPlayerSq < aggroRange * aggroRange || enemySys.investigateTimer[i] > 0f
        
        // Check if we are within tether range of our HVT
        val hvtToPlayerDx = targetX - hx
        val hvtToPlayerDy = targetY - hy
        val hvtToPlayerDistSq = hvtToPlayerDx * hvtToPlayerDx + hvtToPlayerDy * hvtToPlayerDy
        val playerWithinTether = hvtToPlayerDistSq < tetherRange * tetherRange

        if (isInterceptor && isAlerted && playerWithinTether) {
            // INTERCEPT MODE: Move towards player
            enemySys.eState[i] = 1
            val pdist = sqrt(distToPlayerSq.toDouble()).toFloat().coerceAtLeast(0.01f)
            val speed = scale * 0.5f
            enemySys.evx[i] = (tdx / pdist) * speed
            enemySys.evy[i] = (tdy / pdist) * speed
            
            // If very close to player, slightly push away from other guards to avoid stacking
            for (j in 0 until enemySys.n) {
                if (i == j || enemySys.type[j] != 5 || enemySys.ex[j] < -1000f) continue
                val gdx = enemySys.ex[i] - enemySys.ex[j]
                val gdy = enemySys.ey[i] - enemySys.ey[j]
                val gdistSq = gdx * gdx + gdy * gdy
                if (gdistSq < (scale * 0.1f) * (scale * 0.1f)) {
                    enemySys.evx[i] += (gdx / 0.01f.coerceAtLeast(sqrt(gdistSq.toDouble()).toFloat())) * scale * 0.1f
                    enemySys.evy[i] += (gdy / 0.01f.coerceAtLeast(sqrt(gdistSq.toDouble()).toFloat())) * scale * 0.1f
                }
            }
        } else {
            // ORBIT/DEFEND MODE
            enemySys.eState[i] = 0
            
            // Rotation: Slightly faster if alerted to look more active
            val rotationSpeed = if (isAlerted) 2.2f else 1.2f
            enemySys.invY[i] += dt * rotationSpeed 
            
            val angle = enemySys.invY[i]
            val orbitRadius = scale * 0.35f
            
            val orbitX = hx + kotlin.math.cos(angle.toDouble()).toFloat() * orbitRadius
            val orbitY = hy + kotlin.math.sin(angle.toDouble()).toFloat() * orbitRadius
            
            val odx = orbitX - enemySys.ex[i]
            val ody = orbitY - enemySys.ey[i]
            val odist = sqrt((odx * odx + ody * ody).toDouble()).toFloat().coerceAtLeast(0.01f)
            
            // High tracking speed to stay glued to the HVT
            val speed = scale * 0.8f 
            enemySys.evx[i] = (odx / odist) * speed
            enemySys.evy[i] = (ody / odist) * speed
        }
    }
}

// =========================================================
// BOSS BEHAVIORS (Modular Patterns for Boss Entities)
// =========================================================

interface IBossBehavior {
    val name: String
    val color: Int
    val sizeMult: Float
    val baseHpMult: Float
    val baseSpeedMult: Float
    val attackType: String
    val spawnMessage: String

    fun applyMovementPattern(
        vx: Float,
        vy: Float,
        dt: Float,
        gs: GameState,
        scale: Float,
    ): Pair<Float, Float>

    fun updateSpecial(dt: Float, gs: GameState, enemySys: EnemySystem, scale: Float)
}

// 0. ULTIMA (Type 5 - Level 100 Ultra Boss)
object UltimaBossBehavior : IBossBehavior {
    override val name = "ULTIMA_OVERLORD"
    override val color = 0xFFFF00FF.toInt() // Neon Magenta
    override val sizeMult = 1.8f
    override val baseHpMult = 5.0f
    override val baseSpeedMult = 1.2f
    override val attackType = "OMNI-HYBRID"
    override val spawnMessage = "CRITICAL: ADMINISTRATIVE ENTITY 'ULTIMA' DETECTED."

    override fun applyMovementPattern(
        vx: Float,
        vy: Float,
        dt: Float,
        gs: GameState,
        scale: Float,
    ): Pair<Float, Float> {
        val rageMult = if (gs.isBossRage) 1.5f else 1.0f
        val damping = (dt * 5f).coerceIn(0f, 1f)

        // Jittery movement
        val jx = (kotlin.random.Random.nextFloat() - 0.5f) * scale * 0.2f
        val jy = (kotlin.random.Random.nextFloat() - 0.5f) * scale * 0.2f

        return Pair(vx * (1f - damping) * 0.4f * rageMult + jx, vy * (1f - damping) * 0.4f * rageMult + jy)
    }
    // ... existing updateSpecial ...

    override fun updateSpecial(dt: Float, gs: GameState, enemySys: EnemySystem, scale: Float) {
        gs.bossAttackTimer += dt

        // Phase-based multi-attack logic
        val cycle = (gs.bossAttackTimer % 15f)

        when {
            cycle < 4f -> { // Phase 1: Drone Rain
                if (cycle < dt) gs.bossAttackState = 0 // Reset state for the new cycle
                if (gs.bossAttackTimer % 1.5f < dt) {
                    enemySys.spawnSwarmIfNeeded(gs, scale)
                }
            }

            cycle < 8f -> { // Phase 2: Glitch Storm
                gs.chromaticIntensity = 0.5f
                if (gs.bossAttackTimer % 0.5f < dt) {
                    gs.empFlashTimer = 0.2f
                    // Random projectile bursts
                    spawnProjectile(gs, scale, (kotlin.random.Random.nextFloat() * 6.28f))
                }
            }

            cycle < 12f -> { // Phase 3: Seismic Pulsar
                if (gs.bossAttackTimer % 3f < dt) {
                    gs.shakeAmount = scale * 0.3f
                    gs.shockwaveActive = true
                    gs.shockwaveX = gs.bossX
                    gs.shockwaveY = gs.bossY
                    gs.shockwaveR = 0f
                    // Global damage if not moving (hypothetical mechanic: stay in motion)
                }
            }

            else -> { // Phase 4: Recovery/Teleport
                // FIX: Use bossAttackState to ensure teleport only triggers ONCE per cycle
                if (cycle > 14.5f && gs.bossAttackState == 0) {
                    gs.bossAttackState = 1
                    val angle = (kotlin.random.Random.nextFloat() * 6.28f)
                    val targetX = gs.px + kotlin.math.cos(angle) * scale * 1.5f
                    val targetY = gs.py + kotlin.math.sin(angle) * scale * 1.5f

                    val bossRadius = scale * 0.08f * sizeMult
                    val validPos = com.appsbyalok.echohunter.utils.SpawnValidator.findValidNear(
                        targetX,
                        targetY,
                        bossRadius,
                        gs,
                        maxAttempts = 30,
                        searchRadius = scale * 0.8f,
                        hitboxScale = 1.0f
                    )
                    if (validPos != null) {
                        gs.bossX = validPos.first
                        gs.bossY = validPos.second
                        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 50)
                    }
                }
            }
        }
    }

    private fun spawnProjectile(gs: GameState, scale: Float, angle: Float) {
        for (i in 0 until gs.maxSpikes) {
            if (!gs.spikeActive[i]) {
                gs.spikeActive[i] = true
                gs.spikeX[i] = gs.bossX
                gs.spikeY[i] = gs.bossY
                gs.spikeLife[i] = 3.0f
                gs.spikeType[i] = 3
                val speed = scale * 1.2f
                gs.spikeVx[i] = kotlin.math.cos(angle) * speed
                gs.spikeVy[i] = kotlin.math.sin(angle) * speed
                break
            }
        }
    }
}

// 1. GUARDIAN (Type 0/1 - Seismic Jump & Tanky Movement)
object GuardianBossBehavior : IBossBehavior {
    override val name = "GUARDIAN_UNIT"
    override val color = 0xFF555555.toInt() // Iron Grey
    override val sizeMult = 1.4f
    override val baseHpMult = 2.5f
    override val baseSpeedMult = 0.7f
    override val attackType = "MELEE_SEISMIC"
    override val spawnMessage = "GUARDIAN: SECTOR LOCKDOWN INITIATED."

    override fun applyMovementPattern(
        vx: Float,
        vy: Float,
        dt: Float,
        gs: GameState,
        scale: Float,
    ): Pair<Float, Float> {
        // Guardian is slow but steady. Stops when jumping or recovering.
        if (gs.bossAttackState != 0) return Pair(0f, 0f)

        val rageMult = if (gs.isBossRage) 1.5f else 1.0f
        val damping = (dt * 5f).coerceIn(0f, 1f)
        return Pair(
            vx * (1f - damping) * 0.8f,
            vy + kotlin.math.sin(gs.timeSinceStart * (3f * rageMult)) * scale * 0.3f
        )
    }

    override fun updateSpecial(dt: Float, gs: GameState, enemySys: EnemySystem, scale: Float) {
        // --- DYNAMIC SCALING (Targeting Extreme Depths) ---
        val depthFactor =
            (gs.currentLevel / 1200f).coerceAtMost(0.7f) // Attack frequency increases with level
        val hardMult = if (gs.difficulty == DifficultyLevel.HARD) 0.8f else 1.0f // 20% faster in Hard Mode

        val baseCooldown = if (gs.isBossRage) 2.2f else 3.8f
        val attackCooldown = baseCooldown * (1f - depthFactor) * hardMult
        gs.bossAttackTimer += dt

        if (gs.bossAttackState == 0 && gs.bossAttackTimer > attackCooldown) {
            gs.bossAttackState = 1 // Charging Jump
            gs.bossAttackTimer = 0f
        }

        if (gs.bossAttackState == 1) {
            val chargeDuration = 0.8f * hardMult
            if (gs.bossAttackTimer > chargeDuration) {
                gs.bossAttackState = 2 // In Air
                gs.bossAttackTimer = 0f
                EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 100)
            }
        } else if (gs.bossAttackState == 2) {
            // Parabolic Jump
            val jumpDuration = 0.8f * hardMult
            val t = gs.bossAttackTimer / jumpDuration
            gs.bossZ = kotlin.math.sin(t * kotlin.math.PI.toFloat()) * scale * 0.6f

            if (t >= 1.0f) {
                gs.bossZ = 0f
                // SEISMIC SLAM EFFECT
                gs.shakeAmount = scale * 0.25f
                gs.shockwaveActive = true
                gs.shockwaveX = gs.bossX
                gs.shockwaveY = gs.bossY
                gs.shockwaveR = 0f
                EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)

                // Damage player if close
                val dx = gs.px - gs.bossX
                val dy = gs.py - gs.bossY
                if (dx * dx + dy * dy < (scale * 0.7f) * (scale * 0.7f) && gs.playerIframe <= 0f) {
                    gs.playerIframe = 1.0f
                    gs.hp--
                    gs.damageFlash = 0.5f
                }

                // --- CONTINUOUS PROBABILISTIC DOUBLE JUMP ---
                val baseChance = if (gs.currentLevel >= 15) 0.10f else 0f
                val levelBonus = (gs.currentLevel / 1500f).coerceAtMost(0.35f)
                val rageBonus =
                    if (gs.isBossRage) (if (gs.difficulty == DifficultyLevel.HARD) 0.50f else 0.30f) else 0.0f

                val totalChance = baseChance + levelBonus + rageBonus

                if (kotlin.random.Random.nextFloat() < totalChance && gs.bossAttackCounter < 1) {
                    gs.bossAttackCounter++
                    gs.bossAttackState = 1
                    gs.bossAttackTimer = 0.2f // Faster second charge
                } else {
                    gs.bossAttackCounter = 0
                    gs.bossAttackState = 3 // Recovery State
                    gs.bossAttackTimer = 0f
                }
            }
        } else if (gs.bossAttackState == 3) {
            // Recovery: Scales down with level and difficulty
            val baseRecovery = if (gs.isBossRage) 0.4f else 1.0f
            val recoveryTime = baseRecovery * (1f - depthFactor * 0.5f) * hardMult
            if (gs.bossAttackTimer > recoveryTime) {
                gs.bossAttackState = 0
                gs.bossAttackTimer = 0f
            }
        }
    }
}

// 2. STALKER (Type 2 - EMP Traps & Quick Dashes)
object StalkerBossBehavior : IBossBehavior {
    override val name = "STALKER_PROTOTYPE"
    override val color = 0xFF44FF44.toInt() // Toxic Green
    override val sizeMult = 1.0f
    override val baseHpMult = 1.8f
    override val baseSpeedMult = 1.1f // Nerfed from 1.4f for better early-game pacing
    override val attackType = "ASSASSIN_STRIKE"
    override val spawnMessage = "STALKER: TARGET ACQUIRED. NO ESCAPE."

    override fun applyMovementPattern(
        vx: Float,
        vy: Float,
        dt: Float,
        gs: GameState,
        scale: Float,
    ): Pair<Float, Float> {
        // Stalker stops completely when charging or recovering
        if (gs.bossAttackState == 1 || gs.bossAttackState == 3) return Pair(0f, 0f)

        val damping = (dt * 5f).coerceIn(0f, 1f)
        return Pair(vx * (1f - damping), vy * (1f - damping))
    }

    override fun updateSpecial(dt: Float, gs: GameState, enemySys: EnemySystem, scale: Float) {
        // --- DYNAMIC SCALING ---
        val depthFactor = (gs.currentLevel / 1500f).coerceAtMost(0.6f)
        val hardMult = if (gs.difficulty == DifficultyLevel.HARD) 0.85f else 1.0f

        gs.bossAttackTimer += dt
        val baseCooldown = if (gs.isBossRage) 1.5f else 3.2f
        val dashCooldown = baseCooldown * (1f - depthFactor) * hardMult

        // State 0: Idle/Chasing
        if (gs.bossAttackState == 0 && gs.bossAttackTimer > dashCooldown) {
            gs.bossAttackState = 1 // Charging Dash
            gs.bossAttackTimer = 0f
            deployTrap(gs, enemySys)
        }

        // State 1: Charge Dash (Telegraphing)
        if (gs.bossAttackState == 1) {
            val chargeDuration = 0.5f * hardMult
            if (gs.bossAttackTimer > chargeDuration) {
                val dx = gs.px - gs.bossX
                val dy = gs.py - gs.bossY
                val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)

                // Dash Speed scales with level
                val dashSpeed = scale * (4.0f + (gs.currentLevel / 200f).coerceAtMost(2.0f))
                gs.bossVx = (dx / dist) * dashSpeed
                gs.bossVy = (dy / dist) * dashSpeed

                gs.bossAttackState = 2 // Executing Dash
                gs.bossAttackTimer = 0f
                EchoAudioManager.playSound(
                    ToneGenerator.TONE_CDMA_PIP, 60
                )
            }
        }

        // State 2: Dashing
        if (gs.bossAttackState == 2) {
            if (gs.bossAttackTimer > 0.3f) {
                // CONTINUOUS CHAIN DASH LOGIC
                val chainChance =
                    (gs.currentLevel / 2000f).coerceAtMost(0.4f) + (if (gs.isBossRage) 0.3f else 0f)
                val maxDashes =
                    if (gs.currentLevel >= 150) 3 else (if (gs.currentLevel >= 50) 2 else 1)

                if (kotlin.random.Random.nextFloat() < chainChance && gs.bossAttackCounter < maxDashes - 1) {
                    gs.bossAttackCounter++
                    gs.bossAttackState = 1
                    gs.bossAttackTimer = 0.15f // Faster follow-up
                } else {
                    gs.bossAttackCounter = 0
                    gs.bossAttackState = 3 // Recovery
                    gs.bossAttackTimer = 0f
                }
            }
        }

        // State 3: Recovery
        if (gs.bossAttackState == 3) {
            val baseRecovery = if (gs.isBossRage) 0.4f else 1.0f
            val recoveryTime = baseRecovery * (1f - depthFactor) * hardMult
            if (gs.bossAttackTimer > recoveryTime) {
                gs.bossAttackState = 0
                gs.bossAttackTimer = 0f
            }
            // Decelerate quickly
            val damping = (dt * 15f).coerceIn(0f, 1f)
            gs.bossVx *= (1f - damping)
            gs.bossVy *= (1f - damping)
        }
    }

    private fun deployTrap(gs: GameState, enemySys: EnemySystem) {
        for (i in 0 until enemySys.pwn) {
            if (!enemySys.pwActive[i]) {
                enemySys.pwX[i] = gs.bossX
                enemySys.pwY[i] = gs.bossY
                enemySys.pwType[i] = 4 // EMP Slow Trap
                enemySys.pwActive[i] = true
                enemySys.pwVis[i] = 1.0f
                break
            }
        }
    }
}

// 3. GLITCH (Type 3 - Teleporting & Glitch Pulse)
object GlitchBossBehavior : IBossBehavior {
    override val name = "GLITCH_ENTITY"
    override val color = 0xFF00FFFF.toInt() // Neon Cyan
    override val sizeMult = 1.2f
    override val baseHpMult = 1.8f
    override val baseSpeedMult = 1.1f
    override val attackType = "HACKER_PHASE"
    override val spawnMessage = "GLITCH: SYST3M... ERR0R... DELETE(YOU)."

    override fun applyMovementPattern(
        vx: Float,
        vy: Float,
        dt: Float,
        gs: GameState,
        scale: Float,
    ): Pair<Float, Float> {
        val rageMult = if (gs.isBossRage) 1.5f else 1.0f
        
        // State 1: Invisible/Stealth Phase -> Extremely fast and jittery
        val moveMult = if (gs.bossAttackState == 1) 2.2f * rageMult else 1.0f
        
        val damping = (dt * 10f).coerceIn(0f, 1f)
        val jitter = if (gs.bossAttackState == 1) 2.0f else 1.2f
        val jx = (kotlin.random.Random.nextFloat() - 0.5f) * scale * jitter * moveMult
        val jy = (kotlin.random.Random.nextFloat() - 0.5f) * scale * jitter * moveMult
        
        return Pair(vx * (1f - damping) + jx, vy * (1f - damping) + jy)
    }

    override fun updateSpecial(dt: Float, gs: GameState, enemySys: EnemySystem, scale: Float) {
        val depthFactor = (gs.currentLevel / 1200f).coerceAtMost(0.7f)
        val hardMult = if (gs.difficulty == DifficultyLevel.HARD) 0.8f else 1.0f
        
        gs.bossAttackTimer += dt
        val baseInterval = if (gs.isBossRage) 3.5f else 6.0f
        val stateInterval = baseInterval * (1f - depthFactor) * hardMult

        when (gs.bossAttackState) {
            0 -> { // IDLE / CHASING
                if (gs.bossAttackTimer > stateInterval) {
                    val roll = kotlin.random.Random.nextFloat()
                    gs.bossAttackState = when {
                        roll < 0.35f -> 1 // Invisible Phase
                        roll < 0.75f && gs.currentLevel >= 40 -> 3 // Glitch Clones
                        else -> 2 // Hyper-Teleport
                    }
                    gs.bossAttackTimer = 0f
                }
            }
            1 -> { // PHASE: INVISIBLE (Scanline / Glitch Effect)
                // We simulate the "vertical scanline" glitch by pulsing chromatic aberration
                gs.chromaticIntensity = 0.4f + (kotlin.math.sin(gs.timeSinceStart * 25f) * 0.4f)
                
                val duration = (2.0f + depthFactor * 2.5f) * hardMult
                if (gs.bossAttackTimer > duration) {
                    gs.bossAttackState = 0
                    gs.bossAttackTimer = -0.5f // Delay
                    gs.chromaticIntensity = 0f
                }
            }
            2 -> { // PHASE: HYPER-TELEPORT
                val angle = kotlin.random.Random.nextFloat() * 6.28f
                // Teleport distance decreases as player gets better (more aggressive boss)
                val dist = scale * (1.1f - depthFactor * 0.4f)
                val targetX = gs.px + kotlin.math.cos(angle) * dist
                val targetY = gs.py + kotlin.math.sin(angle) * dist

                val validPos = com.appsbyalok.echohunter.utils.SpawnValidator.findValidNear(
                    targetX, targetY, scale * 0.1f, gs, 30
                )
                
                if (validPos != null) {
                    gs.bossX = validPos.first
                    gs.bossY = validPos.second
                    gs.shakeAmount = scale * 0.2f
                    EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 100)
                    gs.chromaticIntensity = 0.7f // Flash glitch on exit
                }
                
                gs.bossAttackState = 0
                gs.bossAttackTimer = -1.0f
            }
            3 -> { // PHASE: GLITCH CLONES (Decoys)
                val maxClones = when {
                    gs.currentLevel >= 500 -> 4
                    gs.currentLevel >= 150 -> 3
                    else -> 2
                }
                
                for (i in 0 until maxClones) {
                    val angle = (6.28f / maxClones) * i + (kotlin.random.Random.nextFloat() * 0.5f)
                    val cx = gs.bossX + kotlin.math.cos(angle) * scale * 0.7f
                    val cy = gs.bossY + kotlin.math.sin(angle) * scale * 0.7f
                    
                    enemySys.spawnBossClone(cx, cy, scale)
                }
                
                EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 120)
                gs.bossAttackState = 0
                gs.bossAttackTimer = -0.8f
            }
        }
    }
}

// 4. OMEGA (Type 4 - Multi-Threat & Drone Swarm)
object OmegaBossBehavior : IBossBehavior {
    override val name = "OMEGA_COMMANDER"
    override val color = 0xFFFF3333.toInt() // Crimson Red
    override val sizeMult = 1.5f
    override val baseHpMult = 3.0f
    override val baseSpeedMult = 1.0f
    override val attackType = "HYBRID_COMMAND"
    override val spawnMessage = "OMEGA: WE ARE THE SWARM. WE ARE MANY."

    override fun applyMovementPattern(
        vx: Float,
        vy: Float,
        dt: Float,
        gs: GameState,
        scale: Float,
    ): Pair<Float, Float> {
        val rageMult = if (gs.isBossRage) 2.0f else 1.0f
        val damping = (dt * 5f).coerceIn(0f, 1f)
        val svy =
            vy * (1f - damping) + kotlin.math.cos(gs.timeSinceStart * (5f * rageMult)) * scale * 0.6f
        val svx =
            vx * (1f - damping) + kotlin.math.sin(gs.timeSinceStart * (3f * rageMult)) * scale * 0.3f
        return Pair(svx, svy)
    }

    override fun updateSpecial(dt: Float, gs: GameState, enemySys: EnemySystem, scale: Float) {
        gs.bossAttackTimer += dt
        if (gs.bossAttackTimer > 8f) {
            gs.bossAttackTimer = 0f
            // Spawn Hunter Swarm
            val swarmSpawnCount = 3
            repeat(swarmSpawnCount) {
                enemySys.spawnSwarmIfNeeded(gs, scale)
            }
            com.appsbyalok.echohunter.data.StoryProtocol.showIngameMessage(
                "OMEGA: DEPLOYING SWARM UNITS", 2f
            )
        }
    }
}
