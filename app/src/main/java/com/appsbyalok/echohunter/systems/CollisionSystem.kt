package com.appsbyalok.echohunter.systems

import android.media.ToneGenerator
import com.appsbyalok.echohunter.data.LevelEngine
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.data.UpgradeSystem
import com.appsbyalok.echohunter.data.UpgradeType
import com.appsbyalok.echohunter.engine.AppStateId
import com.appsbyalok.echohunter.engine.GameModeId
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.utils.EchoAudioManager
import com.appsbyalok.echohunter.utils.GameColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class CollisionSystem(
    val gs: GameState,
    val effectSystem: EffectSystem,
    val enemySystem: EnemySystem,
    private val spawnerSys: SpawnerSystem
) {

    fun checkCollisions(
        scale: Float,
        onDamage: (Float) -> Unit,
        onScoreAdd: (Long) -> Unit,
        onCoreUnlock: (Boolean) -> Unit,
    ) {
        val hitRadius = scale * 0.045f
        val hitDistSq = hitRadius * hitRadius

        val isElimination = gs.activeObjective is com.appsbyalok.echohunter.modes.EliminationObjective

        var playedEnemyKillSound = false
        var playedEnemyHackSound = false

        // Powerups & Data Drops Collision
        for (i in 0 until enemySystem.pwn) {
            if (enemySystem.pwActive[i]) {
                val dx = enemySystem.pwX[i] - gs.px
                val dy = enemySystem.pwY[i] - gs.py
                val d2 = dx * dx + dy * dy

                val isTrap = enemySystem.pwType[i] == 4
                val magnetMult = if (isTrap) 1f else UpgradeSystem.getDataMagnetRadiusMultiplier()
                val pickupDistSq = hitDistSq * 1.5f * magnetMult * magnetMult

                if (d2 < pickupDistSq) {
                    enemySystem.pwActive[i] = false
                    
                    if (!isTrap) {
                        EchoAudioManager.playSound(ToneGenerator.TONE_SUP_CONFIRM, 100)
                        if (!gs.isOverclocked) {
                            gs.overclockMeter = min(100f, gs.overclockMeter + 25f)
                            if (gs.overclockMeter >= 100f && gs.showOverclockTextTimer <= 0f) {
                                gs.showOverclockTextTimer = 2.0f
                                EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 100)
                            }
                        }
                    }

                    when (enemySystem.pwType[i]) {
                        0 -> gs.hp = min(gs.maxHp, gs.hp + 1)
                        1 -> gs.visionClarity = 1.5f
                        2 -> gs.shieldTimer = UpgradeSystem.getShieldMaxDuration()
                        4 -> {
                            // BOSS EMP TRAP: Damaging/Slow
                            if (gs.playerIframe <= 0f) {
                                if (gs.shieldTimer > 0f) {
                                    gs.shieldTimer = 0f
                                } else {
                                    onDamage(scale)
                                }
                                gs.playerIframe = 1.0f
                                gs.empFlashTimer = 0.5f
                                gs.shakeAmount = max(gs.shakeAmount, scale * 0.1f)
                                EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_ALERT, 150)
                            }
                        }
                    }
                }
            }
        }

        // --- 1. EMP MINE TRAP COLLISION ---
        val trapIter = gs.activeTraps.iterator()
        while (trapIter.hasNext()) {
            val trap = trapIter.next()
            if (trap.type != 2) continue

            var mineTriggered = false
            for (i in 0 until enemySystem.n) {
                val dx = trap.x - enemySystem.ex[i]
                val dy = trap.y - enemySystem.ey[i]
                if (dx * dx + dy * dy < (scale * 0.15f) * (scale * 0.15f)) {
                    mineTriggered = true; break
                }
            }
            if (!mineTriggered && gs.bossActive) {
                val bdx = trap.x - gs.bossX
                val bdy = trap.y - gs.bossY
                if (bdx * bdx + bdy * bdy < (scale * 0.2f) * (scale * 0.2f)) {
                    mineTriggered = true
                }
            }

            if (!mineTriggered) {
                for (node in gs.spawnerNodes) {
                    if (node.state == SpawnState.DESTROYED || node.state == SpawnState.INACTIVE) continue
                    val dx = trap.x - node.x
                    val dy = trap.y - node.y
                    if (dx * dx + dy * dy < (scale * 0.15f) * (scale * 0.15f)) {
                        mineTriggered = true; break
                    }
                }
            }

            if (mineTriggered) {
                trapIter.remove()
                val trapPower = UpgradeSystem.getTrapPower(2)
                triggerExplosion(
                    trap.x, trap.y, scale * 0.45f, trapPower, scale, true, onScoreAdd, onCoreUnlock
                )
            }
        }

        // --- 4. SPIKES VS ENEMIES & BOSS COLLISION ---
        for (s in 0 until gs.maxSpikes) {
            if (gs.spikeActive[s]) {
                var spikeHit = false

                // A. Boss Hit Detection
                if (gs.bossActive && gs.bossIframe <= 0f) {
                    val bdx = gs.spikeX[s] - gs.bossX
                    val bdy = gs.spikeY[s] - gs.bossY
                    val bossHitRadiusSq = (scale * 0.1f) * (scale * 0.1f)

                    // NEW: Boss is invulnerable to ground spikes if jumping high (Z-axis dodge)
                    val isOutOfReach = gs.bossZ > scale * 0.15f

                    if (bdx * bdx + bdy * bdy < bossHitRadiusSq && !isOutOfReach) {
                        spikeHit = true
                        
                        // CRITICAL EXPLOIT vs BOSS
                        val isCrit = kotlin.random.Random.nextFloat() < UpgradeSystem.getCritChance()
                        val critMult = if (isCrit) UpgradeSystem.getCritDamageMultiplier() else 1
                        val damage = (gs.spikeDamage[s] * critMult).toInt().coerceAtLeast(1)
                        gs.bossHp -= damage

                        if (isCrit) {
                            effectSystem.spawnFloatingText(gs.bossX, gs.bossY, damage.toLong(), GameColors.RED)
                            gs.shakeAmount = max(gs.shakeAmount, scale * 0.15f)
                            effectSystem.spawnParticles(gs.bossX, gs.bossY, 4, scale * 2f) // Crit yellow particles

                            // PATCH: CRIT VAMP (Lifesteal)
                            if (UpgradeSystem.hasCritVampPatch() && gs.hp < gs.maxHp) {
                                if (kotlin.random.Random.nextFloat() < 0.2f) { // 20% chance as per spec
                                    gs.hp = min(gs.maxHp, gs.hp + 2)
                                    effectSystem.spawnFloatingText(gs.px, gs.py, 2L, GameColors.HP)
                                }
                            }

                            // NEW: KINETIC OVERLOAD (AoE Damage on Crit)
                            val overloadLvl = UpgradeSystem.getLevel(UpgradeType.KINETIC_OVERLOAD)
                            if (overloadLvl > 0) {
                                val explosionRadius = scale * (0.2f + overloadLvl * 0.15f)
                                val splashDamage = 1f + (overloadLvl / 2)
                                triggerExplosion(
                                    gs.bossX, gs.bossY, explosionRadius, splashDamage, scale, false, onScoreAdd, onCoreUnlock
                                )
                            }
                        }

                        if (gs.bossHp <= gs.bossMaxHp / 2 && !gs.isBossRage) {
                            gs.isBossRage = true
                            gs.chromaticIntensity = 1.0f
                            StoryProtocol.showIngameMessage(
                                "WARNING: BOSS OVERRIDE DETECTED - RAGE MODE ACTIVE!", 3f
                            )
                        }

                        gs.bossIframe = 0.15f // Quick iframe against multi-hit spikes

                        effectSystem.spawnParticles(gs.bossX, gs.bossY, 3, scale)
                        EchoAudioManager.playSound(ToneGenerator.TONE_SUP_INTERCEPT, 100)

                        gs.combo++
                        onScoreAdd((10 * UpgradeSystem.getRewardMultiplier()).toLong())

                        if (!gs.isOverclocked) {
                            gs.overclockMeter = min(100f, gs.overclockMeter + 5f)
                            if (gs.overclockMeter >= 100f && gs.showOverclockTextTimer <= 0f) {
                                gs.showOverclockTextTimer = 2.0f
                                EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 100)
                            }
                        }

                        // Check Boss Death from Spike
                        if (gs.bossHp <= 0) {
                            triggerBossDeath(scale, onScoreAdd, onCoreUnlock)
                        }
                    }
                }

                // B. Normal Enemy Hit Detection
                if (!spikeHit) {
                    for (i in 0 until enemySystem.n) {
                        if (enemySystem.ex[i] < -1000f) continue // Skip dead/inactive enemies

                        val dx = gs.spikeX[s] - enemySystem.ex[i]
                        val dy = gs.spikeY[s] - enemySystem.ey[i]

                        if (dx * dx + dy * dy < hitDistSq * 1.5f) {
                            spikeHit = true
                            
                            // CRITICAL EXPLOIT LOGIC
                            val isCrit = kotlin.random.Random.nextFloat() < UpgradeSystem.getCritChance()
                            val critMult = if (isCrit) UpgradeSystem.getCritDamageMultiplier() else 1
                            val damage = (gs.spikeDamage[s] * critMult).toInt().coerceAtLeast(1)
                            
                            enemySystem.hp[i] -= damage
                            effectSystem.spawnParticles(enemySystem.ex[i], enemySystem.ey[i], if (isCrit) 3 else 1, scale)

                            if (gs.spikeType[s] == 2 && UpgradeSystem.getSniperBeamWidthMultiplier() > 1f && !gs.spikeArcTriggered[s]) {
                                gs.spikeArcTriggered[s] = true
                                chainArcToNearestEnemy(i, damage, scale, onScoreAdd)
                            }
                            
                            if (isCrit) {
                                effectSystem.spawnFloatingText(enemySystem.ex[i], enemySystem.ey[i], damage.toLong(), GameColors.RED)
                                EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 50)
                                effectSystem.spawnParticles(enemySystem.ex[i], enemySystem.ey[i], 4, scale * 1.5f)
                                
                                // PATCH: CRIT VAMP (Lifesteal)
                                if (UpgradeSystem.hasCritVampPatch() && gs.hp < gs.maxHp) {
                                    if (kotlin.random.Random.nextFloat() < 0.2f) {
                                        gs.hp = min(gs.maxHp, gs.hp + 2)
                                        effectSystem.spawnFloatingText(gs.px, gs.py, 2L, GameColors.HP)
                                    }
                                }

                                // NEW: KINETIC OVERLOAD (Explosion on Crit)
                                val overloadLvl = UpgradeSystem.getLevel(UpgradeType.KINETIC_OVERLOAD)
                                if (overloadLvl > 0) {
                                    val explosionRadius = scale * (0.15f + overloadLvl * 0.15f)
                                    val splashDamage = 1f + (overloadLvl / 3)
                                    triggerExplosion(
                                        enemySystem.ex[i], enemySystem.ey[i], explosionRadius, splashDamage, scale, false, onScoreAdd, onCoreUnlock
                                    )
                                }
                            } else {
                                EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 50)
                            }

                            if (enemySystem.hp[i] <= 0) {
                                gs.combo++
                                // --- ELIMINATION MODE SCORE LOGIC ---
                                val baseReward = LevelEngine.getKillRewardKB(gs.currentLevel, false)
                                val bountyCap = LevelEngine.getKillRewardKB(1, false) * 15

                                if (isElimination) {
                                    if (enemySystem.type[i] == 3) {
                                        onScoreAdd(((2 + gs.combo) * UpgradeSystem.getRewardMultiplier()).toLong())
                                        val reward = min(baseReward * 2, bountyCap)
                                        gs.collectedDataKB += reward
                                        StoryProtocol.showIngameMessage("TARGET ELIMINATED!", 1.5f)
                                        gs.elimTargetsKilled++
                                    } else {
                                        val reward = min(baseReward / 2, bountyCap)
                                        gs.collectedDataKB += reward
                                    }
                                } else {
                                    onScoreAdd(((2 + gs.combo) * UpgradeSystem.getRewardMultiplier()).toLong())
                                    val reward = min(baseReward, bountyCap)
                                    gs.collectedDataKB += reward
                                }
                                
                                // Combo Window Upgrade
                                gs.comboBreakTimer = 3.0f + UpgradeSystem.getComboBonusTime()

                                // PATCH: COMBO SHIELD (Shield recharge on Combo x10)
                                if (UpgradeSystem.hasComboShieldPatch() && gs.combo % 10 == 0) {
                                    // Recharge existing timer by 2 seconds, max capacity
                                    gs.shieldTimer = min(UpgradeSystem.getShieldMaxDuration(), gs.shieldTimer + 2f)
                                    effectSystem.spawnFloatingText(gs.px, gs.py, 100L, GameColors.SHIELD) // 100 for "100%" boost feel
                                }

                                if (!gs.isOverclocked) {
                                    gs.overclockMeter = min(100f, gs.overclockMeter + 15f)
                                    if (gs.overclockMeter >= 100f && gs.showOverclockTextTimer <= 0f) {
                                        gs.showOverclockTextTimer = 2.0f
                                        EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 100)
                                    }
                                }

                                // FIX: Enemies should always die if HP <= 0, regardless of boss state.
                                // The old check prevented clearing the arena but caused invincibility bugs.
                                enemySystem.killEnemy(i, gs)
                            }
                            break // Spike breaks after impact
                        }
                    }
                }

                // C. Spawner Node Hit Detection
                if (!spikeHit) {
                    val spawnerHitRadiusSq = (scale * 0.05f) * (scale * 0.05f)
                    for (node in gs.spawnerNodes) {
                        if (node.state == SpawnState.DESTROYED || node.state == SpawnState.INACTIVE) continue
                        val dx = gs.spikeX[s] - node.x
                        val dy = gs.spikeY[s] - node.y
                        if (dx * dx + dy * dy < spawnerHitRadiusSq) {
                            spikeHit = true
                            
                            val isCrit = kotlin.random.Random.nextFloat() < UpgradeSystem.getCritChance()
                            val critMult = if (isCrit) UpgradeSystem.getCritDamageMultiplier() else 1
                            val damage = gs.spikeDamage[s] * critMult * 10f // Spawners take more damage from spikes
                            
                            val prevHp = node.hp
                            spawnerSys.damageNode(node, damage, gs, scale)
                            
                            if (node.hp < prevHp) {
                                effectSystem.spawnParticles(node.x, node.y, if (isCrit) 3 else 0, scale)
                                if (node.state == SpawnState.DESTROYED) {
                                    onScoreAdd((20 * UpgradeSystem.getRewardMultiplier()).toLong())
                                    var rewardKB = LevelEngine.getKillRewardKB(gs.currentLevel, false) * 3
                                    val cap = LevelEngine.getKillRewardKB(1, false) * 15 * 3
                                    rewardKB = min(rewardKB, cap)
                                    gs.collectedDataKB += rewardKB
                                    StoryProtocol.showIngameMessage("COMPILER BREACHED", 1.5f)

                                    // OVERCLOCK FRENZY: Clean Sweep specific reward
                                    if (gs.activeObjective is com.appsbyalok.echohunter.modes.CleanSweepObjective) {
                                        gs.overclockTimer = max(gs.overclockTimer, 4.0f) // 4 sec of pure chaos
                                        gs.overclockMeter = 100f
                                        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 100)
                                    }
                                }
                            }
                            break
                        }
                    }
                }

                // Sniper Spike (Type 2) pierces through enemies!
                if (spikeHit && gs.spikeType[s] != 2) {
                    gs.spikeActive[s] = false
                }

                // C. Enemy Projectile (Type 3) vs Player
                if (gs.spikeType[s] == 3 && gs.playerIframe <= 0f) {
                    val pdx = gs.spikeX[s] - gs.px
                    val pdy = gs.spikeY[s] - gs.py
                    if (pdx * pdx + pdy * pdy < hitDistSq) {
                        gs.spikeActive[s] = false
                        onDamage(scale)
                        gs.playerIframe = 1.0f
                    }
                }
            }
        }


        // 5. Enemies Collision
        for (i in 0 until enemySystem.n) {
            if (enemySystem.ex[i] < -1000f) continue // Skip dead enemies

            // --- CORE DEFENSE COLLISION LOGIC ---
            // NEW: Damage logic now depends on defWaveState instead of just Objective class
            val isCoreDamageable = gs.defWaveState == 1 && gs.coreRadius > 0f
            if (isCoreDamageable) {
                val cdx = gs.coreX - enemySystem.ex[i]
                val cdy = gs.coreY - enemySystem.ey[i]
                val coreHitDistSq =
                    (gs.coreRadius + (scale * 0.03f)) * (gs.coreRadius + (scale * 0.03f))

                if (cdx * cdx + cdy * cdy < coreHitDistSq) {
                    gs.coreHp--
                    effectSystem.spawnParticles(gs.coreX, gs.coreY, 1, scale * 1.5f)
                    EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 200)
                    gs.shakeAmount = max(gs.shakeAmount, scale * 0.1f)
                    gs.chromaticIntensity = max(gs.chromaticIntensity, 0.6f)

                    enemySystem.killEnemy(i, gs)

                    if (gs.coreHp <= 0) {
                        gs.hp = 0
                        StoryProtocol.showIngameMessage(
                            "CRITICAL: SYSTEM CORE COMPROMISED!", 4f
                        )
                        onDamage(scale)
                    }
                    continue
                }
            }





            val dx = gs.px - enemySystem.ex[i]
            val dy = gs.py - enemySystem.ey[i]
            val d2 = dx * dx + dy * dy

            if (d2 < hitDistSq) {
                if (gs.isOverclocked) {
                    // OVERCLOCK LOGIC: 
                    // Type 0 (Yellow) and Type 2 (Blue) are hacked instantly (1-hit).
                    // Type 1 (Red) and Type 3 (Purple) take 2 massive damage.
                    val isFragile = enemySystem.type[i] == 0 || enemySystem.type[i] == 2
                    
                    if (isFragile) {
                        enemySystem.hp[i] = 0 // Instant hack
                    } else {
                        enemySystem.hp[i] -= 2 // Heavy damage to tough enemies
                    }
                    
                    effectSystem.spawnParticles(enemySystem.ex[i], enemySystem.ey[i], 1, scale)
                    gs.hitStopTimer = max(gs.hitStopTimer, 0.12f)
                    gs.shakeAmount = max(gs.shakeAmount, scale * 0.05f)
                    
                    if (!playedEnemyKillSound) {
                        EchoAudioManager.playSound(ToneGenerator.TONE_SUP_INTERCEPT, 50)
                        playedEnemyKillSound = true
                    }

                    if (enemySystem.hp[i] <= 0) {
                        var rewardKB = LevelEngine.getKillRewardKB(gs.currentLevel, isBoss = false)
                        val cap = LevelEngine.getKillRewardKB(1, false) * 15
                        rewardKB = min(rewardKB, cap)

                        if (enemySystem.type[i] == 2 || enemySystem.type[i] == 0) {
                            onScoreAdd(( (if(enemySystem.type[i] == 2) 5L else 2L) * UpgradeSystem.getRewardMultiplier() ).toLong())
                        } else {
                            onScoreAdd((5 * UpgradeSystem.getRewardMultiplier()).toLong())
                        }
                        gs.collectedDataKB += rewardKB

                        if (isElimination && enemySystem.type[i] == 3) {
                            gs.elimTargetsKilled++
                            StoryProtocol.showIngameMessage("TARGET ELIMINATED!", 1.5f)
                        }
                        
                        // PATCH: HEALTH SIPHON
                        if (UpgradeSystem.hasHealthSiphonPatch() && kotlin.random.Random.nextFloat() < 0.05f) {
                            if (gs.hp < gs.maxHp) {
                                gs.hp++
                                effectSystem.spawnFloatingText(gs.px, gs.py, 1L, GameColors.HP)
                                EchoAudioManager.playSound(ToneGenerator.TONE_SUP_CONFIRM, 50)
                            }
                        }

                        enemySystem.killEnemy(i, gs)
                    }
                } else if (enemySystem.type[i] == 1 || enemySystem.type[i] == 5) {
                    // --- HUNTER & GUARD COLLISION ---
                    if (gs.playerIframe <= 0f) {
                        if (gs.shieldTimer > 0f) {
                            gs.shieldTimer = 0f
                            gs.playerIframe = 1.0f + UpgradeSystem.getIframeDurationBonus()
                            
                            // PATCH: SHIELD BURST (EMP Wave on break)
                            if (UpgradeSystem.hasShieldBurstPatch()) {
                                triggerExplosion(gs.px, gs.py, scale * 0.4f, 5f, scale, true, onScoreAdd, onCoreUnlock)
                            }
                            
                            effectSystem.spawnParticles(enemySystem.ex[i], enemySystem.ey[i], 1, scale)
                            
                            // Hunters die on shield hit (except in Training), but Guards are tanky—just bounce back
                            if (enemySystem.type[i] == 1 && gs.gameMode != GameModeId.TRAINING) {
                                enemySystem.killEnemy(i, gs)
                            } else {
                                // Guard Bounce
                                val bdx = enemySystem.ex[i] - gs.px
                                val bdy = enemySystem.ey[i] - gs.py
                                val dist = sqrt((bdx * bdx + bdy * bdy).toDouble()).toFloat().coerceAtLeast(0.01f)
                                enemySystem.evx[i] += (bdx / dist) * scale * 2f
                                enemySystem.evy[i] += (bdy / dist) * scale * 2f
                            }
                        } else {
                            effectSystem.spawnParticles(gs.px, gs.py, 1, scale)
                            onDamage(scale)
                            gs.playerIframe = 1.0f + UpgradeSystem.getIframeDurationBonus()
                            gs.chromaticIntensity = max(gs.chromaticIntensity, 0.8f)
                        }
                    }
                } else if (enemySystem.type[i] == 2) {
                    // --- KAMIKAZE/HACKER ---
                    if (gs.gameMode != GameModeId.TRAINING) {
                        onScoreAdd((5 * UpgradeSystem.getRewardMultiplier()).toLong())
                        gs.collectedDataKB += LevelEngine.getKillRewardKB(gs.currentLevel, isBoss = false)
                        effectSystem.spawnParticles(enemySystem.ex[i], enemySystem.ey[i], 1, scale)
                        if (!playedEnemyHackSound) {
                            EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 80)
                            playedEnemyHackSound = true
                        }
                        enemySystem.killEnemy(i, gs)
                    }
                } else if (enemySystem.type[i] == 0) {
                    // --- NORMAL YELLOW (PATROL) ---
                    if (gs.gameMode != GameModeId.TRAINING) {
                        onScoreAdd((2 * UpgradeSystem.getRewardMultiplier()).toLong())
                        gs.collectedDataKB += LevelEngine.getKillRewardKB(gs.currentLevel, isBoss = false)
                        effectSystem.spawnParticles(enemySystem.ex[i], enemySystem.ey[i], 0, scale * 0.5f) // Small pulse effect
                        if (!playedEnemyHackSound) {
                            EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 60)
                            playedEnemyHackSound = true
                        }
                        enemySystem.killEnemy(i, gs)
                    }
                } else if (enemySystem.type[i] == 6) {
                    // --- BOMBER COLLISION ---
                    // Bomber detonates on contact, creating a damaging explosion.
                    val explosionRadius = scale * 0.35f
                    val explosionDamage = 10f // High, fixed damage
                    triggerExplosion(
                        enemySystem.ex[i], enemySystem.ey[i], explosionRadius, explosionDamage, scale, true, onScoreAdd, onCoreUnlock
                    )
                    // The bomber is consumed in the explosion.
                    enemySystem.killEnemy(i, gs)
                    // Also damage the player if they are not invincible.
                    if (gs.playerIframe <= 0f) {
                        onDamage(scale)
                    }
                }
            }
        }

        // 6. Boss Collision (Player touching boss)
        if (gs.bossActive) {
            val bdx = gs.px - gs.bossX
            val bdy = gs.py - gs.bossY
            val bossRadius = scale * (if (gs.bossType == 5) 0.12f else 0.08f)
            val entityRadius = scale * 0.015f
            val combinedRadiusSq = (bossRadius + entityRadius) * (bossRadius + entityRadius)

            if (bdx * bdx + bdy * bdy < combinedRadiusSq) {
                // HEIGHT CHECK: Player only interacts with boss if it's on/near ground
                val isInteractable = gs.bossZ < scale * 0.15f
                
                if (isInteractable && gs.isOverclocked && gs.bossIframe <= 0f) {
                    gs.bossHp--
                    gs.bossIframe = 1.0f
                    effectSystem.spawnParticles(gs.bossX, gs.bossY, 3, scale)
                    EchoAudioManager.playSound(ToneGenerator.TONE_SUP_INTERCEPT, 150)
                    gs.shakeAmount = max(gs.shakeAmount, scale * 0.08f)
                    gs.chromaticIntensity = max(gs.chromaticIntensity, 0.6f)
                    gs.hitStopTimer = max(gs.hitStopTimer, 0.12f)

                    if (gs.bossHp <= 0) triggerBossDeath(scale, onScoreAdd, onCoreUnlock)

                } else if (isInteractable && gs.bossIframe <= 0f && gs.playerIframe <= 0f) {
                    gs.bossIframe = 1.0f

                    if (gs.shieldTimer > 0f) {
                        gs.shieldTimer = 0f
                        
                        // PATCH: SHIELD BURST
                        if (UpgradeSystem.hasShieldBurstPatch()) {
                            triggerExplosion(gs.px, gs.py, scale * 0.45f, 5f, scale, true, onScoreAdd, onCoreUnlock)
                        }

                        gs.playerIframe = 1.0f
                    } else {
                        onDamage(scale)
                        gs.chromaticIntensity = max(gs.chromaticIntensity, 1.0f)
                    }
                }
            }
        }

        // --- NEW: PASSIVE REGEN SYSTEM ---
        val regenInterval = UpgradeSystem.getRegenInterval()
        if (regenInterval > 0f && gs.hp < gs.maxHp && gs.state == AppStateId.PLAYING) {
            gs.regenTimer += 0.016f // Fixed update step assumed
            if (gs.regenTimer >= regenInterval) {
                gs.hp++
                gs.regenTimer = 0f
                effectSystem.spawnFloatingText(gs.px, gs.py, 1L, GameColors.HP)
                EchoAudioManager.playSound(ToneGenerator.TONE_SUP_CONFIRM, 50)
            }
        }
    }

    private fun chainArcToNearestEnemy(sourceIndex: Int, sourceDamage: Int, scale: Float, onScoreAdd: (Long) -> Unit) {
        var targetIndex = -1
        var bestDistSq = (scale * 0.55f) * (scale * 0.55f)
        val sourceX = enemySystem.ex[sourceIndex]
        val sourceY = enemySystem.ey[sourceIndex]
        for (i in 0 until enemySystem.n) {
            if (i == sourceIndex || enemySystem.ex[i] < -1000f) continue
            val dx = enemySystem.ex[i] - sourceX
            val dy = enemySystem.ey[i] - sourceY
            val distSq = dx * dx + dy * dy
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                targetIndex = i
            }
        }
        if (targetIndex == -1) return

        val chainDamage = (sourceDamage * 0.5f).toInt().coerceAtLeast(1)
        enemySystem.hp[targetIndex] -= chainDamage
        effectSystem.spawnElectricArc(sourceX, sourceY, enemySystem.ex[targetIndex], enemySystem.ey[targetIndex])
        effectSystem.spawnParticles(enemySystem.ex[targetIndex], enemySystem.ey[targetIndex], 0, scale * 1.5f)
        effectSystem.spawnFloatingText(enemySystem.ex[targetIndex], enemySystem.ey[targetIndex], chainDamage.toLong(), GameColors.CLARITY, "ARC $chainDamage")
        if (enemySystem.hp[targetIndex] <= 0) {
            gs.combo++
            onScoreAdd((2 * UpgradeSystem.getRewardMultiplier()).toLong())
            enemySystem.killEnemy(targetIndex, gs)
        }
    }

    /**
     * UNIFIED EXPLOSION LOGIC: Handles damage to enemies, spawners, and bosses.
     * Supports both standard Kinetic Overloads and EMP Blasts.
     */
    private fun triggerExplosion(
        x: Float,
        y: Float,
        radius: Float,
        damage: Float,
        scale: Float,
        isEmp: Boolean,
        onScoreAdd: (Long) -> Unit,
        onCoreUnlock: (Boolean) -> Unit
    ) {
        val radiusSq = radius * radius
        val isElimination = gs.activeObjective is com.appsbyalok.echohunter.modes.EliminationObjective

        // 1. Visual & Audio Feedback
        gs.shockwaveActive = true
        gs.shockwaveX = x
        gs.shockwaveY = y
        gs.shockwaveR = 0f
        gs.shakeAmount = max(gs.shakeAmount, scale * (if (isEmp) 0.18f else 0.1f))
        
        if (isEmp) {
            gs.empFlashTimer = 0.4f
            EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_ALERT, 300)
            effectSystem.spawnParticles(x, y, 6, scale * 2f)
        } else {
            EchoAudioManager.playSound(ToneGenerator.TONE_SUP_INTERCEPT, 200)
            effectSystem.spawnParticles(x, y, 4, scale * 1.5f)
        }

        // 2. Enemy Interaction
        for (i in 0 until enemySystem.n) {
            if (enemySystem.ex[i] < -1000f) continue
            val dx = x - enemySystem.ex[i]
            val dy = y - enemySystem.ey[i]
            if (dx * dx + dy * dy < radiusSq) {
                enemySystem.hp[i] -= damage.toInt()
                
                if (enemySystem.hp[i] <= 0) {
                    var rewardKB = LevelEngine.getKillRewardKB(gs.currentLevel, isBoss = false)
                    val cap = LevelEngine.getKillRewardKB(1, false) * 15
                    rewardKB = min(rewardKB, cap)
                    
                    onScoreAdd((5 * UpgradeSystem.getRewardMultiplier()).toLong())
                    gs.collectedDataKB += rewardKB
                    gs.combo++ // Explosion kills now contribute to combo
                    gs.comboBreakTimer = 3.0f + UpgradeSystem.getComboBonusTime()
                    
                    if (isElimination && enemySystem.type[i] == 3) {
                        gs.elimTargetsKilled++
                        StoryProtocol.showIngameMessage("TARGET ELIMINATED!", 1.5f)
                    }
                    enemySystem.killEnemy(i, gs)
                } else if (isEmp) {
                    // EMP Stun/Glitch
                    enemySystem.eState[i] = 2 
                    effectSystem.spawnFloatingText(enemySystem.ex[i], enemySystem.ey[i], 0, 0xFF00CCFF.toInt(), "GLITCH")
                }
            }
        }

        // 3. Spawner Interaction
        for (node in gs.spawnerNodes) {
            if (node.state == SpawnState.DESTROYED || node.state == SpawnState.INACTIVE) continue
            val dx = x - node.x
            val dy = y - node.y
            if (dx * dx + dy * dy < radiusSq * 1.44f) { // Spawners have larger hitbox for explosions
                val prevHp = node.hp
                val finalDamage = if (isEmp) 50f else damage * 5f // EMP is specialized for spawners
                
                spawnerSys.damageNode(node, finalDamage, gs, scale)

                if (node.hp < prevHp) {
                    if (node.state == SpawnState.DESTROYED) {
                        var rewardKB = LevelEngine.getKillRewardKB(gs.currentLevel, false) * 3
                        val cap = LevelEngine.getKillRewardKB(1, false) * 15 * 3
                        rewardKB = min(rewardKB, cap)
                        onScoreAdd((15 * UpgradeSystem.getRewardMultiplier()).toLong())
                        gs.collectedDataKB += rewardKB
                        StoryProtocol.showIngameMessage(if (isEmp) "COMPILER FRIED" else "COMPILER BREACHED", 1.5f)
                    } else if (isEmp) {
                        // EMP SPECIAL: Disable + Data Leak + Glitch Text
                        node.state = SpawnState.DISABLED
                        node.cooldownTimer = 6f 
                        
                        // Data Leak: 20% of destruction reward for EMP impact
                        val leakReward = (LevelEngine.getKillRewardKB(gs.currentLevel, false) * 0.2f).toLong()
                        gs.collectedDataKB += leakReward
                        onScoreAdd((leakReward / 10).coerceAtLeast(1L)) // Sync UI score with leak
                        
                        effectSystem.spawnFloatingText(node.x, node.y, leakReward, 0xFF00FFCC.toInt(), "DATA LEAK")
                        effectSystem.spawnFloatingText(node.x, node.y - scale * 0.05f, 0, 0xFFFFFF00.toInt(), "SHUTDOWN")
                        
                        // Extra sparking particles
                        effectSystem.spawnParticles(node.x, node.y, 1, scale * 1.5f)
                    }
                }
            }
        }

        // 4. Boss Interaction
        if (gs.bossActive && gs.bossIframe <= 0f) {
            val dx = x - gs.bossX
            val dy = y - gs.bossY
            if (dx * dx + dy * dy < radiusSq * 1.5f) {
                gs.bossHp -= damage.toInt()
                if (gs.bossHp <= 0) triggerBossDeath(scale, onScoreAdd, onCoreUnlock)
            }
        }
    }

    private fun triggerBossDeath(
        scale: Float,
        onScoreAdd: (Long) -> Unit,
        onCoreUnlock: (Boolean) -> Unit,
    ) {
        gs.bossActive = false
        gs.bossDeathX = gs.bossX
        gs.bossDeathY = gs.bossY
        gs.bossDeathTimer = 2.5f
        gs.shockwaveActive = true
        gs.shockwaveX = gs.bossX
        gs.shockwaveY = gs.bossY
        gs.shockwaveR = 0f
        gs.chromaticIntensity = 1.0f

        // Push away surviving enemies
        for (j in 0 until enemySystem.n) {
            val edx = enemySystem.ex[j] - gs.bossX
            val edy = enemySystem.ey[j] - gs.bossY
            val distSq = edx * edx + edy * edy
            if (distSq > 0.001f) {
                val dist = sqrt(distSq)
                enemySystem.evx[j] = (edx / dist) * scale * 2f
                enemySystem.evy[j] = (edy / dist) * scale * 2f
            }
        }

        gs.currentSector++
        gs.sectorTarget += (gs.currentSector * 40 * (1.0f + UpgradeSystem.getRewardBonusPercent())).toInt()
        gs.hp = min(gs.maxHp, gs.hp + 1)
        gs.sectorFlash = 1f; gs.shakeAmount = scale * 0.15f

        // FIX: Reset score for next sector in Story Mode to maintain challenge
        if (gs.gameMode == GameModeId.STORY) {
            gs.score = 0
        }

        onScoreAdd((50 * UpgradeSystem.getRewardMultiplier()).toLong())
        
        // PATCH: DATA OVERFLOW (2x Boss Data Drops)
        var bossReward = LevelEngine.getKillRewardKB(gs.currentLevel, isBoss = true)
        val cap = LevelEngine.getKillRewardKB(1, false) * 15 * 5 // Bosses get 5x the normal cap
        bossReward = min(bossReward, cap)

        val finalReward = if (UpgradeSystem.hasDataOverflowPatch()) bossReward * 2 else bossReward
        gs.collectedDataKB += finalReward

        effectSystem.spawnFloatingText(gs.bossX, gs.bossY, (50 * UpgradeSystem.getRewardMultiplier()).toLong(), GameColors.YELLOW)
        EchoAudioManager.playSound(ToneGenerator.TONE_SUP_CONFIRM, 500)

        // Reset inverted controls after boss death
        if (StoryProtocol.areControlsInverted) {
            StoryProtocol.areControlsInverted = false
            StoryProtocol.showIngameMessage("SYSTEM: UPLINK STABILIZED. CONTROLS RESTORED.", 2f)
        }

        if (gs.activeObjective.checkWinCondition(gs)) {
            gs.isLevelCleared = true
        }

        if (gs.gameMode == GameModeId.STORY && gs.currentSector > 5) {
            onCoreUnlock(gs.hp == gs.maxHp)
        }
    }
}
