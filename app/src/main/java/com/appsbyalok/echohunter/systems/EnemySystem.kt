package com.appsbyalok.echohunter.systems

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.appsbyalok.echohunter.data.UpgradeSystem
import com.appsbyalok.echohunter.data.UpgradeType
import com.appsbyalok.echohunter.engine.DifficultyLevel
import com.appsbyalok.echohunter.engine.GameModeId
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.utils.GameColors
import com.appsbyalok.echohunter.utils.SpawnValidator
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class EnemySystem {
    private val p = Paint().apply { isAntiAlias = true }
    private val pText = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val entityPath = Path()

    val ai = EnemyAI()
    private var heatTimer = 0f
    private var effectSys: EffectSystem? = null

    fun setEffectSystem(es: EffectSystem) { effectSys = es }
    fun getEffectSystem(): EffectSystem? = effectSys

    val n = 25
    val enemyBrains = Array<IEnemyBehavior>(n) { PatrolBehavior } // MODULAR BRAIN ARRAY

    val ex = FloatArray(n)
    val ey = FloatArray(n)
    val evx = FloatArray(n)
    val evy = FloatArray(n)
    val vis = FloatArray(n)
    val type = IntArray(n)
    val eState = IntArray(n)
    val investigateTimer = FloatArray(n)
    
    // --- NEW: ENEMY HEALTH SYSTEM ---
    val hp = IntArray(n)
    val maxHp = IntArray(n)
    val invX = FloatArray(n)
    val invY = FloatArray(n)

    val pwn = 4
    val pwX = FloatArray(pwn)
    val pwY = FloatArray(pwn)
    val pwType = IntArray(pwn)
    val pwVis = FloatArray(pwn)
    val pwActive = BooleanArray(pwn)
    private val puIcons = arrayOf("+", "V", "S")

    fun respawnAll(gs: GameState) {
        for (i in 0 until pwn) pwActive[i] = false
        // Clear map on level start. SpawnerSystem handles enemy spawns.
        for (i in 0 until n) {
            ex[i] = -9999f
            ey[i] = -9999f
            vis[i] = 0f
        }
        gs.bossActive = false
        gs.bossHp = 0
    }

    fun killEnemy(i: Int, gs: GameState) {
        if (ex[i] < -1000f) return
        ex[i] = -9999f
        ey[i] = -9999f

        val config = com.appsbyalok.echohunter.data.LevelEngine.getLevelConfig(gs.currentLevel)
        if (config.features.contains(com.appsbyalok.echohunter.data.LevelFeature.DEFENSE)) {
            gs.defEnemiesAlive--
            if (gs.defEnemiesAlive < 0) gs.defEnemiesAlive = 0
        }
    }

    // Helper for Boss mechanics (Glitch Clones, etc.)
    fun spawnBossClone(nx: Float, ny: Float, gs: GameState, scale: Float) {
        // Find an empty slot in the enemy arrays
        for (i in 0 until n) {
            if (vis[i] <= 0f) {
                ex[i] = nx
                ey[i] = ny
                evx[i] = (Random.nextFloat() - 0.5f) * scale * 0.5f
                evy[i] = (Random.nextFloat() - 0.5f) * scale * 0.5f
                type[i] = 1 // Use Hunter behavior for clones
                enemyBrains[i] = HunterBehavior
                hp[i] = 1 // 1-hit kill for decoys
                maxHp[i] = 1
                vis[i] = 0.8f // Start slightly faded
                eState[i] = 1
                break
            }
        }
    }

    // NEW: Enemy now spawns at a specified Node (nx, ny)
    fun spawnAt(i: Int, nx: Float, ny: Float, gs: GameState, scale: Float, nodeType: Int = -1) {
        val radius = scale * 0.03f
        val validPos = SpawnValidator.findValidNear(nx, ny, radius, gs, maxAttempts = 30, searchRadius = radius * 6f)

        if (validPos != null) {
            ex[i] = validPos.first
            ey[i] = validPos.second
        } else {
            // IMPROVED FALLBACK: Search for the nearest valid empty tile instead of just snapping.
            val ts = gs.tileSize
            val grid = gs.gridMap
            if (grid != null) {
                val centerCol = (nx / ts).toInt()
                val centerRow = (ny / ts).toInt()
                
                var found = false
                // Spiral search for nearest empty tile
                for (searchRadius in 0..5) {
                    for (dx in -searchRadius..searchRadius) {
                        for (dy in -searchRadius..searchRadius) {
                            if (kotlin.math.abs(dx) != searchRadius && kotlin.math.abs(dy) != searchRadius) continue

                            val c = centerCol + dx
                            val r = centerRow + dy
                            if (c in grid.indices && r in grid[0].indices && grid[c][r] == 0) {
                                ex[i] = c * ts + ts / 2f
                                ey[i] = r * ts + ts / 2f
                                found = true
                                break
                            }
                        }
                        if (found) break
                    }
                    if (found) break
                }
                if (!found) {
                    // Total failure: cancel spawn to avoid wall glitch
                    ex[i] = -9999f
                    ey[i] = -9999f
                    return
                }
            } else {
                ex[i] = nx
                ey[i] = ny
            }
        }

        val config = com.appsbyalok.echohunter.data.LevelEngine.getLevelConfig(gs.currentLevel)
        val isElimination = config.features.contains(com.appsbyalok.echohunter.data.LevelFeature.ELIMINATION)
        val hasDefense = config.features.contains(com.appsbyalok.echohunter.data.LevelFeature.DEFENSE)

        val hunterProb = 0.2f + (gs.difficulty.id * 0.15f)

        // NEW: If it's a Defense level, force BLUE (Kamikaze) enemies for the entire level
        // This ensures red/yellow enemies don't leak in during prep phases or transitions.
        if (hasDefense) {
            type[i] = 2; enemyBrains[i] = KamikazeBehavior
        } else {
            when (nodeType) {
                1 -> { // Glitch Tear (Swarmers)
                    type[i] = 1; enemyBrains[i] = HunterBehavior
                }
                2 -> { // Admin Gateway (HVTs or Elites)
                    if (isElimination) {
                        type[i] = 3; enemyBrains[i] = TargetBehavior
                    } else {
                        type[i] = 1; enemyBrains[i] = HunterBehavior
                    }
                }
                4 -> { // REPAIR DRONE
                    type[i] = 4; enemyBrains[i] = RepairDroneBehavior
                }
                5 -> { // GUARD AI (For HVTs)
                    type[i] = 5; enemyBrains[i] = GuardBehavior
                }
                else -> { // Compiler (Normal)
                    val repairProb = 0.15f // 15% chance to spawn repair drone instead of patrol
                    if (gs.spawnerNodes.any { it.state == SpawnState.DESTROYED } && Random.nextFloat() < repairProb) {
                        type[i] = 4; enemyBrains[i] = RepairDroneBehavior
                    } else {
                        type[i] = if (Random.nextFloat() < hunterProb) 1 else 0
                        enemyBrains[i] = if (type[i] == 1) HunterBehavior else PatrolBehavior
                    }
                }
            }
        }

        eState[i] = 0
        evx[i] = 0f
        evy[i] = 0f
        vis[i] = 0f
        invX[i] = -9999f
        invY[i] = -9999f
        investigateTimer[i] = 0f

        // --- NEW: DYNAMIC HP SCALING (Saturated Growth Curve via LevelEngine) ---
        // Level 1-10: 1 HP | Level 50: ~4 HP | Level 100: ~7 HP | Level 500: ~13 HP | Max Cap: 19 HP
        val baseHp = com.appsbyalok.echohunter.data.LevelEngine.getSaturatedValue(gs.currentLevel, 1f, 18f, 200f).toInt()

        maxHp[i] = when (nodeType) {
            1 -> 1 // Swarmers are very weak, 1 hit kill
            2 -> (baseHp * 2.5f * (1.0f + UpgradeSystem.getLevel(UpgradeType.DATA_SYNDICATE) * 0.2f)).toInt() // HVTs
            5 -> (baseHp * 1.5f).toInt() // Guards
            else -> baseHp
        }
        hp[i] = maxHp[i]
    }

    fun updateEnemies(dt: Float, gs: GameState, effectSys: EffectSystem, width: Float, height: Float, scale: Float) {
        gs.isEnemyNear = false
        gs.isEnemyVeryNear = false

        if (gs.gridMap != null) {
            heatTimer -= dt
            if (heatTimer <= 0f) {
                ai.updatePlayerHeatMap(gs)
                if (gs.coreRadius > 0f) ai.updateCoreHeatMap(gs)
                heatTimer = 0.5f
            }
        }

        val enemyRadius = scale * 0.02f
        val stealthCamoMult = UpgradeSystem.getStealthDetectionMultiplier()

        for (i in 0 until n) {
            if (ex[i] < -1000f) continue

            // Training targets stay at their briefing position and never attack the player.
            if (gs.gameMode == GameModeId.TRAINING && gs.tutorialHighlightedEnemyIndex == i) {
                evx[i] = 0f
                evy[i] = 0f
                vis[i] = 1f
                continue
            }
            
            // --- ANTI-STUCK LOGIC ---
            // If already in a wall, try to push out to the center of the current tile or a neighbor
            if (isCollidingWithWall(ex[i], ey[i], enemyRadius, gs)) {
                val ts = gs.tileSize
                val centerCol = (ex[i] / ts).toInt()
                val centerRow = (ey[i] / ts).toInt()
                
                // Check current tile center first
                val cx = centerCol * ts + ts / 2f
                val cy = centerRow * ts + ts / 2f
                if (!isCollidingWithWall(cx, cy, enemyRadius, gs)) {
                    ex[i] = cx; ey[i] = cy
                } else {
                    // Search neighboring tiles (N, S, E, W)
                    val dxs = intArrayOf(0, 0, -1, 1)
                    val dys = intArrayOf(-1, 1, 0, 0)
                    for (dir in dxs.indices) {
                        val ncx = (centerCol + dxs[dir]) * ts + ts / 2f
                        val ncy = (centerRow + dys[dir]) * ts + ts / 2f
                        if (!isCollidingWithWall(ncx, ncy, enemyRadius, gs)) {
                            ex[i] = ncx; ey[i] = ncy
                            break
                        }
                    }
                }
            }

            // Apply a small damping to velocity
            evx[i] *= (1.0f - dt * 2.0f).coerceAtLeast(0f)
            evy[i] *= (1.0f - dt * 2.0f).coerceAtLeast(0f)

            val nextEx = ex[i] + evx[i] * dt
            if (!isCollidingWithWall(nextEx, ey[i], enemyRadius, gs)) ex[i] = nextEx
            else evx[i] = -evx[i] * 0.5f

            val nextEy = ey[i] + evy[i] * dt
            if (!isCollidingWithWall(ex[i], nextEy, enemyRadius, gs)) ey[i] = nextEy
            else evy[i] = -evy[i] * 0.5f

            // DELEGATE TO MODULAR AI BRAIN
            enemyBrains[i].updateBehavior(i, dt, gs, this, ai, width, height, scale)

            // --- NEW: TRAP INFLUENCE (STASIS / DECOY) ---
            for (trap in gs.activeTraps) {
                if (trap.type == 3) { // Stasis
                    val r = scale * 0.25f * trap.rangeMultiplier
                    val dx = trap.x - ex[i]
                    val dy = trap.y - ey[i]
                    if (dx * dx + dy * dy < r * r) {
                        evx[i] *= 0.3f
                        evy[i] *= 0.3f
                    }
                }
            }

            val d2 = (gs.px - ex[i]) * (gs.px - ex[i]) + (gs.py - ey[i]) * (gs.py - ey[i])
            val hitByPulse = (gs.pulse && d2 in gs.innerRSq..gs.outerRSq)

            if (type[i] == 1) {
                val hitDistSq = (scale * 0.045f) * (scale * 0.045f)
                val detectionDistSq = hitDistSq * 15f * stealthCamoMult * stealthCamoMult
                val immediateDistSq = hitDistSq * 4f * stealthCamoMult * stealthCamoMult

                if (d2 < detectionDistSq) gs.isEnemyNear = true
                if (d2 < immediateDistSq) gs.isEnemyVeryNear = true
            }

            // --- NEW: PERSISTENT VISIBILITY LOGIC ---
            if (d2 < gs.passiveAuraRadiusSq) {
                vis[i] = 1.0f
            } else {
                if (hitByPulse) {
                    if (vis[i] <= 0.05f) { // Trigger ping if nearly invisible
                        effectSys.spawnSonarPing(ex[i], ey[i], if (type[i] == 1) GameColors.RED else GameColors.YELLOW)
                        
                        // Sonar Noise: Attract enemy to pulse location if they were patrolling
                        if (eState[i] == 0 && type[i] != 5) {
                            eState[i] = 2 // INVESTIGATE
                            investigateTimer[i] = 3f
                            invX[i] = gs.px
                            invY[i] = gs.py
                        } else if (type[i] == 5) {
                            // Guards don't wander, they just get alerted in place
                            investigateTimer[i] = 2f
                        }
                    }
                    vis[i] = 1.0f
                }
                
                // Linear decay instead of exponential for predictable duration
                val duration = UpgradeSystem.getSonarDurationBonus()
                vis[i] = max(0f, vis[i] - dt / duration)
            }

            val maxAllowedDistSq = if (gs.bossActive || gs.coreRadius > 0f || type[i] == 3 || type[i] == 5) (width * 1.5f) * (width * 1.5f) else (width * 4.0f) * (width * 4.0f)
            
            // SPECIAL HANDLING: Elimination Targets and Guards do NOT despawn based on distance
            // unless they are extremely far away (e.g., procedural glitching out).
            if (type[i] == 3 || type[i] == 5) {
                // Do nothing, keep them alive
            } else if (d2 > maxAllowedDistSq) {
                // Using killEnemy ensures defEnemiesAlive is decremented if in Defense mode.
                killEnemy(i, gs)
                vis[i] = 0f
            }
        }
        gs.globalSonarAlert = false
        gs.localAttackAlert = false
    }

    private fun isCollidingWithWall(cx: Float, cy: Float, radius: Float, gs: GameState): Boolean {
        val grid = gs.gridMap ?: return false
        val ts = gs.tileSize
        val hitbox = radius * 0.8f

        val left = ((cx - hitbox) / ts).toInt(); val right = ((cx + hitbox) / ts).toInt()
        val top = ((cy - hitbox) / ts).toInt(); val bottom = ((cy + hitbox) / ts).toInt()

        for (x in left..right) {
            for (y in top..bottom) {
                if (x in grid.indices && y in grid[0].indices) {
                    if (grid[x][y] == 1) return true
                } else return true
            }
        }
        return false
    }

    fun spawnSwarmIfNeeded(gs: GameState, scale: Float) {
        // Find an empty slot and spawn a Hunter near the boss
        for (i in 0 until n) {
            if (ex[i] < -1000f) {
                val angle = Random.nextFloat() * 6.28f
                val dist = scale * 0.5f // Spawn close to boss
                spawnAt(i, gs.bossX + kotlin.math.cos(angle) * dist, gs.bossY + sin(angle) * dist, gs, scale, 1)
                break
            }
        }
    }

    fun updateBoss(dt: Float, gs: GameState, effectSys: EffectSystem, scale: Float) {
        if (!gs.bossActive) return

        val behavior = when (gs.bossType) {
            1 -> GuardianBossBehavior
            2 -> StalkerBossBehavior
            3 -> GlitchBossBehavior
            4 -> OmegaBossBehavior
            5 -> UltimaBossBehavior
            else -> GuardianBossBehavior
        }

        val bdx = gs.px - gs.bossX; val bdy = gs.py - gs.bossY
        val bDistSq = bdx * bdx + bdy * bdy
        val bDist = sqrt(bDistSq)

        // Speed calculation using behavior multiplier
        val baseSpeed = scale * 0.45f * behavior.baseSpeedMult
        val difficultyMult = if (gs.difficulty == DifficultyLevel.NORMAL) 0.7f else 1.0f
        val rageMult = if (gs.isBossRage) 2.0f else 1.0f
        val bSpeed = baseSpeed * difficultyMult * rageMult

        if (bDist > 0f) {
            val hasDefense = com.appsbyalok.echohunter.data.LevelEngine.getLevelConfig(gs.currentLevel).features.contains(com.appsbyalok.echohunter.data.LevelFeature.DEFENSE)
            
            // Determine primary target distance for "Closing In" logic
            val targetDistSq = if (hasDefense && gs.coreRadius > 0f) {
                val cdx = gs.coreX - gs.bossX; val cdy = gs.coreY - gs.bossY
                cdx * cdx + cdy * cdy
            } else bDistSq

            val engagementRange = scale * 1.5f
            val isClosingIn = targetDistSq > engagementRange * engagementRange

            val (vx, vy) = if (gs.gridMap != null) {
                if (hasDefense && gs.coreRadius > 0f) {
                    // Hybrid Targeting: Switch to Player only if they are aggressively close
                    if (bDistSq < (scale * 1.0f) * (scale * 1.0f)) {
                        ai.steerByPlayerHeatMap(gs.bossX, gs.bossY, gs.bossVx, gs.bossVy, bSpeed * 5f, gs, dt)
                    } else {
                        ai.steerByCoreHeatMap(gs.bossX, gs.bossY, gs.bossVx, gs.bossVy, bSpeed * 5f, gs, dt)
                    }
                } else {
                    ai.steerByPlayerHeatMap(gs.bossX, gs.bossY, gs.bossVx, gs.bossVy, bSpeed * 5f, gs, dt)
                }
            } else {
                val tx = if (gs.isDecoyActive) gs.decoyX else if (hasDefense && gs.coreRadius > 0f && bDistSq > (scale * 1.5f) * (scale * 1.5f)) gs.coreX else gs.px
                val ty = if (gs.isDecoyActive) gs.decoyY else if (hasDefense && gs.coreRadius > 0f && bDistSq > (scale * 1.5f) * (scale * 1.5f)) gs.coreY else gs.py
                val tdx = tx - gs.bossX; val tdy = ty - gs.bossY
                val tDist = sqrt(tdx * tdx + tdy * tdy)
                
                if (tDist > 0f) {
                    val lerpFactor = (dt * 5f).coerceIn(0f, 1f)
                    val targetVx = (tdx / tDist) * bSpeed
                    val targetVy = (tdy / tDist) * bSpeed
                    Pair(gs.bossVx + (targetVx - gs.bossVx) * lerpFactor, gs.bossVy + (targetVy - gs.bossVy) * lerpFactor)
                } else Pair(0f, 0f)
            }

            // Update Boss Velocity State
            gs.bossVx = vx
            gs.bossVy = vy

            // --- TRAP INFLUENCE (STASIS / DECOY) ---
            for (trap in gs.activeTraps) {
                if (trap.type == 3) { // Stasis
                    val r = scale * 0.35f * trap.rangeMultiplier
                    val dx = trap.x - gs.bossX
                    val dy = trap.y - gs.bossY
                    if (dx * dx + dy * dy < r * r) {
                        gs.bossVx *= 0.6f
                        gs.bossVy *= 0.6f
                    }
                }
            }

            // --- NEW: FAST CLOSING LOGIC ---
            val (finalVx, finalVy) = if (isClosingIn) {
                Pair(vx * 1.5f, vy * 1.5f)
            } else {
                behavior.applyMovementPattern(vx, vy, dt, gs, scale)
            }

            val bossRadius = scale * 0.08f
            // --- ANTI-STUCK LOGIC ---
            if (isCollidingWithWall(gs.bossX, gs.bossY, bossRadius * 0.8f, gs)) {
                val ts = gs.tileSize
                val bCol = (gs.bossX / ts).toInt()
                val bRow = (gs.bossY / ts).toInt()
                
                val bcx = bCol * ts + ts / 2f
                val bcy = bRow * ts + ts / 2f
                
                if (!isCollidingWithWall(bcx, bcy, bossRadius * 0.8f, gs)) {
                    gs.bossX = bcx; gs.bossY = bcy
                } else {
                    val dxs = intArrayOf(0, 0, -1, 1)
                    val dys = intArrayOf(-1, 1, 0, 0)
                    for (dir in dxs.indices) {
                        val ncx = (bCol + dxs[dir]) * ts + ts / 2f
                        val ncy = (bRow + dys[dir]) * ts + ts / 2f
                        if (!isCollidingWithWall(ncx, ncy, bossRadius * 0.8f, gs)) {
                            gs.bossX = ncx; gs.bossY = ncy
                            break
                        }
                    }
                }
            }

            val nextBx = gs.bossX + finalVx * dt
            if (!isCollidingWithWall(nextBx, gs.bossY, bossRadius * 0.8f, gs)) gs.bossX = nextBx
            val nextBy = gs.bossY + finalVy * dt
            if (!isCollidingWithWall(gs.bossX, nextBy, bossRadius * 0.8f, gs)) gs.bossY = nextBy
        }

        behavior.updateSpecial(dt, gs, this, scale)

        // --- NEW: BOSS PERSISTENT VISIBILITY ---
        if (gs.isDarknessLevel) {
            val hitByPulse = (gs.pulse && bDistSq in gs.innerRSq..gs.outerRSq)
            if (hitByPulse || bDistSq < gs.passiveAuraRadiusSq) {
                if (hitByPulse && gs.bossVis <= 0f) {
                    effectSys.spawnSonarPing(gs.bossX, gs.bossY, behavior.color)
                }
                gs.bossVis = 1.0f
            } else {
                val duration = UpgradeSystem.getSonarDurationBonus()
                gs.bossVis = max(0f, gs.bossVis - dt / duration)
            }
        } else {
            gs.bossVis = 1.0f
        }
    }

    fun updatePowerups(dt: Float, gs: GameState, effectSys: EffectSystem, width: Float, height: Float) {
        val puDropRate = if (gs.difficulty == DifficultyLevel.NORMAL) 0.004f else 0.002f
        if (Random.nextFloat() < puDropRate * dt * 60f && gs.score > 15 && !gs.bossActive) {
            for (i in 0 until pwn) {
                if (!pwActive[i]) {
                    val nx = gs.cameraX + width * 0.2f + Random.nextFloat() * width * 0.8f
                    val ny = gs.cameraY + Random.nextFloat() * height
                    
                    if (SpawnValidator.isValid(nx, ny, 30f, gs)) {
                        pwX[i] = nx
                        pwY[i] = ny
                        pwType[i] = Random.nextInt(3)
                        pwActive[i] = true
                        pwVis[i] = 0f
                        break
                    }
                }
            }
        }

        // NEW: Powerup visibility update (Decay & Scan detection)
        for (i in 0 until pwn) {
            if (pwActive[i]) {
                val dx = pwX[i] - gs.px
                val dy = pwY[i] - gs.py
                val d2 = dx * dx + dy * dy
                val hitByPulse = (gs.pulse && d2 in gs.innerRSq..gs.outerRSq)

                if (d2 < gs.passiveAuraRadiusSq || hitByPulse) {
                    if (hitByPulse && pwVis[i] <= 0f) {
                        effectSys.spawnSonarPing(pwX[i], pwY[i], GameColors.PULSE)
                    }
                    pwVis[i] = 1f
                } else {
                    val duration = UpgradeSystem.getSonarDurationBonus()
                    pwVis[i] = max(0f, pwVis[i] - dt / duration)
                }
            }
        }
    }

    fun drawEntities(c: Canvas, gs: GameState, width: Float, scale: Float) {
        val entityRadius = scale * 0.03f

        for (i in 0 until pwn) {
            if (pwActive[i]) {
                val screenPwX = pwX[i] - gs.cameraX
                val screenPwY = pwY[i] - gs.cameraY

                // NEW: Deactivate only if far off-screen to allow backtracking
                if (screenPwX < -width || screenPwX > width * 2f) pwActive[i] = false

                // Darkness: Must be scanned or in aura (vis > 0)
                // Normal: 100% visibility
                val effectivePwVis = if (gs.isDarknessLevel && !gs.modFullVisibility) pwVis[i] else 1.0f

                if (effectivePwVis > 0.02f) {
                    p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.005f
                    p.color = ((effectivePwVis * 255).toInt() shl 24) or (when (pwType[i]) {
                        0 -> GameColors.HP; 1 -> GameColors.CLARITY; 4 -> GameColors.RED; else -> GameColors.SHIELD
                    } and 0xFFFFFF)

                    if (pwType[i] == 4) {
                        // DRAW EMP TRAP (Boss)
                        p.style = Paint.Style.FILL
                        c.drawCircle(screenPwX, screenPwY, entityRadius * 0.5f, p)
                        p.style = Paint.Style.STROKE
                        c.drawCircle(screenPwX, screenPwY, entityRadius * (0.8f + 0.2f * sin(gs.timeSinceStart * 10f)), p)
                    } else {
                        c.drawCircle(screenPwX, screenPwY, entityRadius * 0.8f, p)

                        pText.color = GameColors.BG
                        pText.textSize = scale * 0.04f
                        c.drawText(puIcons.getOrElse(pwType[i]) { "?" }, screenPwX, screenPwY + scale * 0.012f, pText)
                    }
                }
            }
        }

        for (i in 0 until n) {
            val screenEx = ex[i] - gs.cameraX
            val screenEy = ey[i] - gs.cameraY
            
            // NEW: Strict Visibility - Darkness: 0% without sonar/aura | Normal: 100% visible 
            val effectiveVis = if (gs.isDarknessLevel && !gs.modFullVisibility) vis[i] else 1.0f

            if (effectiveVis > 0.02f) {
                val a = (effectiveVis * 255).toInt()
                val entitySize = entityRadius * (if (type[i] == 2) 1.2f else 0.8f)

                p.style = Paint.Style.FILL

                when (type[i]) {
                    1 -> {
                        p.color = (a shl 24) or (GameColors.RED and 0xFFFFFF)
                        c.drawRect(screenEx - entitySize, screenEy - entitySize, screenEx + entitySize, screenEy + entitySize, p)
                        p.color = (a shl 24) or (GameColors.BG and 0xFFFFFF)
                        c.drawRect(screenEx - entitySize / 2f, screenEy - entitySize / 2f, screenEx + entitySize / 2f, screenEy + entitySize / 2f, p)

                        if (eState[i] == 2 || eState[i] == 1) {
                            pText.color = (a shl 24) or 0xFFFFFF
                            pText.textSize = scale * 0.03f
                            c.drawText("!", screenEx, screenEy - entitySize - scale * 0.01f, pText)
                        }
                    }
                    2 -> {
                        p.color = (a shl 24) or (GameColors.COOLANT and 0xFFFFFF)
                        entityPath.reset()
                        entityPath.moveTo(screenEx, screenEy - entitySize)
                        entityPath.lineTo(screenEx + entitySize, screenEy)
                        entityPath.lineTo(screenEx, screenEy + entitySize)
                        entityPath.lineTo(screenEx - entitySize, screenEy)
                        entityPath.close()
                        c.drawPath(entityPath, p)
                        p.color = (a shl 24) or (GameColors.CLARITY and 0xFFFFFF)
                        c.drawCircle(screenEx, screenEy, entityRadius * 0.3f, p)
                    }
                    3 -> {
                        // --- NEW: ELIMINATION TARGET (HVT) ---
                        // Large Bright Red Circle
                        p.color = (a shl 24) or (0xFFFF2A4D.toInt() and 0xFFFFFF)
                        c.drawCircle(screenEx, screenEy, entitySize, p)

                        // Inner black circle (Holo-look)
                        p.color = (a shl 24) or (GameColors.BG and 0xFFFFFF)
                        c.drawCircle(screenEx, screenEy, entitySize * 0.5f, p)

                        // "TARGET" label above
                        pText.color = (a shl 24) or (0xFFFF2A4D.toInt() and 0xFFFFFF)
                        pText.textSize = scale * 0.025f
                        c.drawText("TARGET", screenEx, screenEy - entitySize * 1.5f, pText)
                    }
                    4 -> {
                        // --- NEW: REPAIR DRONE (Support) ---
                        p.color = (a shl 24) or (0xFF00FF88.toInt() and 0xFFFFFF) // Spring Green
                        entityPath.reset()
                        entityPath.moveTo(screenEx, screenEy - entitySize)
                        entityPath.lineTo(screenEx + entitySize, screenEy)
                        entityPath.lineTo(screenEx, screenEy + entitySize)
                        entityPath.lineTo(screenEx - entitySize, screenEy)
                        entityPath.close()
                        c.drawPath(entityPath, p)
                        
                        // Inner "wrench" or tool symbol
                        p.color = (a shl 24) or (GameColors.BG and 0xFFFFFF)
                        c.drawRect(screenEx - entitySize * 0.4f, screenEy - entitySize * 0.1f, screenEx + entitySize * 0.4f, screenEy + entitySize * 0.1f, p)
                        c.drawRect(screenEx - entitySize * 0.1f, screenEy - entitySize * 0.4f, screenEx + entitySize * 0.1f, screenEy + entitySize * 0.4f, p)
                    }
                    5 -> {
                        // --- NEW: GUARD (Defense Drone) ---
                        p.color = (a shl 24) or (0xFF00AAFF.toInt() and 0xFFFFFF) // Cyan/Blue
                        c.drawCircle(screenEx, screenEy, entitySize * 0.8f, p)
                        p.color = (a shl 24) or (0xFFFFFFFF.toInt() and 0xFFFFFF)
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = scale * 0.005f
                        c.drawCircle(screenEx, screenEy, entitySize, p)
                        p.style = Paint.Style.FILL
                    }
                    else -> {
                        p.color = (a shl 24) or (GameColors.YELLOW and 0xFFFFFF)
                        entityPath.reset()
                        entityPath.moveTo(screenEx, screenEy - entitySize)
                        entityPath.lineTo(screenEx + entitySize, screenEy)
                        entityPath.lineTo(screenEx, screenEy + entitySize)
                        entityPath.lineTo(screenEx - entitySize, screenEy)
                        entityPath.close()
                        c.drawPath(entityPath, p)

                        // --- SUSPICIOUS QUESTION MARK ---
                        if (eState[i] == 2) {
                            pText.color = (a shl 24) or 0xFFFFFF
                            pText.textSize = scale * 0.035f
                            c.drawText("?", screenEx, screenEy - entitySize - scale * 0.01f, pText)
                        }
                    }
                }

                if (gs.gameMode == GameModeId.TRAINING && gs.tutorialHighlightedEnemyIndex == i) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = scale * 0.006f
                    p.color = GameColors.YELLOW
                    c.drawCircle(screenEx, screenEy, entitySize * 1.8f, p)
                    pText.color = GameColors.YELLOW
                    pText.textSize = scale * 0.022f
                    c.drawText("TRAINING TARGET", screenEx, screenEy - entitySize * 2.2f, pText)
                }

                // --- NEW: DRAW HEALTH BAR (When HP > 1 and visible) ---
                if (maxHp[i] > 1 && effectiveVis > 0.5f) {
                    val hpRatio = max(0f, hp[i].toFloat() / maxHp[i].toFloat())
                    val barW = scale * 0.06f
                    val barH = scale * 0.008f
                    val bx = screenEx - barW / 2f
                    val by = screenEy - entitySize - scale * 0.02f
                    
                    // Background
                    p.color = 0xAA000000.toInt()
                    c.drawRect(bx, by, bx + barW, by + barH, p)
                    // Foreground HP
                    p.color = GameColors.HP
                    c.drawRect(bx, by, bx + barW * hpRatio, by + barH, p)
                }
            }
        }

        if (gs.bossActive) {
            val behavior = when (gs.bossType) {
                1 -> GuardianBossBehavior
                2 -> StalkerBossBehavior
                3 -> GlitchBossBehavior
                4 -> OmegaBossBehavior
                5 -> UltimaBossBehavior
                else -> GuardianBossBehavior
            }

            val bossRadius = scale * 0.08f * behavior.sizeMult
            val screenBx = gs.bossX - gs.cameraX
            val screenBy = gs.bossY - gs.cameraY - gs.bossZ // Apply Jump Offset
            
            // NEW: Boss is always slightly visible (15%) in darkness as a shadow/ghost
            val effectiveBossVis = if (gs.isDarknessLevel && !gs.modFullVisibility) max(0.15f, gs.bossVis) else 1.0f
            val bossAlpha = (effectiveBossVis * 255).toInt()

            p.style = Paint.Style.FILL
            val pulseOffset = sin(gs.timeSinceStart * 15f) * (scale * 0.015f)

            // DRAW SHADOW WHEN JUMPING
            if (gs.bossZ > 0) {
                p.color = (bossAlpha / 2 shl 24) or 0x000000
                c.drawOval(
                    gs.bossX - gs.cameraX - bossRadius * 0.6f,
                    gs.bossY - gs.cameraY - bossRadius * 0.2f,
                    gs.bossX - gs.cameraX + bossRadius * 0.6f,
                    gs.bossY - gs.cameraY + bossRadius * 0.2f,
                    p
                )
            }

            p.color = (bossAlpha shl 24) or (behavior.color and 0xFFFFFF)
            c.drawCircle(screenBx, screenBy, bossRadius + pulseOffset, p)

            p.color = if (gs.bossIframe > 0f) (bossAlpha shl 24) or 0xFFFFFF else (bossAlpha shl 24) or (GameColors.RED and 0xFFFFFF)
            
            // Draw core shape based on attack type
            when (behavior.attackType) {
                "MELEE_SEISMIC" -> c.drawRect(screenBx - bossRadius*0.3f, screenBy - bossRadius*0.3f, screenBx + bossRadius*0.3f, screenBy + bossRadius*0.3f, p)
                "RANGED_KINETIC" -> {
                    entityPath.reset()
                    entityPath.moveTo(screenBx, screenBy - bossRadius*0.4f)
                    entityPath.lineTo(screenBx + bossRadius*0.4f, screenBy + bossRadius*0.4f)
                    entityPath.lineTo(screenBx - bossRadius*0.4f, screenBy + bossRadius*0.4f)
                    entityPath.close()
                    c.drawPath(entityPath, p)
                }
                "HACKER_PHASE" -> {
                    p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.01f
                    c.drawCircle(screenBx, screenBy, bossRadius * 0.4f, p)
                    p.style = Paint.Style.FILL
                }
                else -> c.drawCircle(screenBx, screenBy, bossRadius * 0.4f, p)
            }

            // BOSS NAME & ATTACK TYPE
            if (gs.bossVis > 0.8f) {
                pText.color = (bossAlpha shl 24) or (behavior.color and 0xFFFFFF)
                pText.textSize = scale * 0.03f
                c.drawText(behavior.name, screenBx, screenBy - bossRadius - scale * 0.06f, pText)
                pText.textSize = scale * 0.02f
                pText.color = (bossAlpha shl 24) or 0xBBBBBB
                c.drawText(behavior.attackType, screenBx, screenBy - bossRadius - scale * 0.035f, pText)
            }

            // HP BAR follows boss (Now includes bossZ via screenBy)
            val hpY = screenBy - bossRadius - scale * 0.02f
            p.color = (bossAlpha shl 24) or (GameColors.RED and 0xFFFFFF)
            c.drawRect(screenBx - bossRadius, hpY, screenBx + bossRadius, hpY + scale * 0.01f, p)

            p.color = (bossAlpha shl 24) or (GameColors.HP and 0xFFFFFF)
            val hpWidth = (bossRadius * 2f) * (gs.bossHp.toFloat() / gs.bossMaxHp)
            c.drawRect(screenBx - bossRadius, hpY, screenBx - bossRadius + hpWidth, hpY + scale * 0.01f, p)
        }
    }
}
