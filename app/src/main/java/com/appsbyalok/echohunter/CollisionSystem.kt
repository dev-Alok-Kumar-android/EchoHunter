package com.appsbyalok.echohunter

import android.media.ToneGenerator
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

// Contains the math to evaluate distance checks and mutate GameState
class CollisionSystem(
    private val gs: GameState,
    private val effectSystem: EffectSystem,
    private val enemySystem: EnemySystem
) {

    fun checkCollisions(
        width: Float,
        height: Float,
        scale: Float,
        onDamage: (Float) -> Unit,
        onScoreAdd: (Int) -> Unit,
        onBossDefeated: (Boolean) -> Unit
    ) {
        val screenPlayerX = gs.px - gs.cameraX
        val hitDistSq = (scale * 0.045f).pow(2)

        // 1. Firewall Logic
        if (gs.gameMode == 2) {
            if (screenPlayerX < gs.firewallOffset) {
                gs.px += width * 0.2f
                onDamage(scale)
            }

            // Obstacles Collision
            for (i in 0 until gs.obsCount) {
                val screenObsX = gs.obsX[i] - gs.cameraX
                val obsW = scale * 0.05f
                val isDanger = gs.obsType[i] == 1

                if (screenPlayerX + scale * 0.02f > screenObsX && screenPlayerX - scale * 0.02f < screenObsX + obsW) {
                    if (gs.py < gs.obsGapY[i] || gs.py > gs.obsGapY[i] + gs.obsGapSize[i]) {
                        if (isDanger) {
                            if (gs.playerIframe <= 0f) {
                                if (gs.shieldTimer <= 0) {
                                    val dmgAmount = if(gs.difficulty == 0 && Random.nextBoolean()) 0 else 1
                                    if(dmgAmount > 0) {
                                        onDamage(scale)
                                        gs.px = gs.cameraX + screenObsX - scale * 0.1f
                                    }
                                } else {
                                    gs.shieldTimer = 0f
                                    gs.px = gs.cameraX + screenObsX + obsW + scale*0.05f
                                    gs.playerIframe = 1.0f
                                }
                            } else gs.px = gs.cameraX + screenObsX - scale * 0.05f
                        } else gs.px = gs.cameraX + screenObsX - scale * 0.02f
                    }
                }

                // Recycle Obstacle
                if (screenObsX + obsW < 0) {
                    gs.obsX[i] = gs.getNextObstacleX(width)
                    gs.randomizeObstacle(i, height)
                    onScoreAdd(5)
                }
            }
        }

        // 2. Powerups Collision
        for (i in 0 until enemySystem.pwn) {
            if (enemySystem.pwActive[i]) {
                val dx = enemySystem.pwX[i] - gs.px
                val dy = enemySystem.pwY[i] - gs.py
                val d2 = dx * dx + dy * dy

                if (d2 < hitDistSq * 1.5f) {
                    enemySystem.pwActive[i] = false
                    EchoAudioManager.playSound(ToneGenerator.TONE_SUP_CONFIRM, 100)
                    if (!gs.isOverclocked) {
                        gs.overclockMeter = min(100f, gs.overclockMeter + 25f)
                        if (gs.overclockMeter >= 100f) {
                            gs.overclockTimer = 5f
                            EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_ALERT, 200)
                            gs.shakeAmount = scale * 0.08f; gs.sectorFlash = 0.5f; gs.showOverclockTextTimer = 2.0f
                        }
                    }
                    when (enemySystem.pwType[i]) {
                        0 -> gs.hp = min(gs.maxHp, gs.hp + 1)
                        1 -> gs.visionClarity = 1.5f
                        2 -> gs.shieldTimer = 5f
                    }
                }
            }
        }

        // 3. Enemies Collision
        for (i in 0 until enemySystem.n) {
            val dx = gs.px - enemySystem.ex[i]
            val dy = gs.py - enemySystem.ey[i]
            val d2 = dx * dx + dy * dy
            val screenEx = enemySystem.ex[i] - gs.cameraX

            if (d2 < hitDistSq) {
                if (gs.isOverclocked && enemySystem.type[i] == 1) {
                    onScoreAdd(5)
                    effectSystem.spawnParticles(screenEx, enemySystem.ey[i], 1, scale)
                    EchoAudioManager.playSound(ToneGenerator.TONE_SUP_INTERCEPT, 50)
                } else if (enemySystem.type[i] == 1) {
                    if (gs.shieldTimer <= 0 && gs.playerIframe <= 0f) {
                        effectSystem.spawnParticles(screenPlayerX, gs.py, 0, scale)
                        onDamage(scale)
                    }
                } else if (enemySystem.type[i] == 2) {
                    onScoreAdd(5)
                    gs.firewallWorldX -= width * 0.25f
                    effectSystem.spawnParticles(screenEx, enemySystem.ey[i], 0, scale)
                    EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 80)
                } else {
                    onScoreAdd(2)
                }
                enemySystem.spawn(i, gs, width, height)
            }
        }

        // 4. Boss Collision
        if (gs.bossActive) {
            val bdx = gs.px - gs.bossX; val bdy = gs.py - gs.bossY
            val bDist = sqrt(bdx * bdx + bdy * bdy)
            val bossRadius = scale * 0.08f
            val entityRadius = scale * 0.03f
            val screenBx = gs.bossX - gs.cameraX

            if (bDist < bossRadius + entityRadius) {
                if (gs.isOverclocked && gs.bossIframe <= 0f) {
                    gs.bossHp--
                    gs.bossIframe = 1.0f
                    effectSystem.spawnParticles(screenBx, gs.bossY, 2, scale)
                    EchoAudioManager.playSound(ToneGenerator.TONE_SUP_INTERCEPT, 150)
                    gs.shakeAmount = scale * 0.05f

                    if (bDist > 0f) {
                        gs.px -= (bdx/bDist)*scale*0.2f
                        gs.py -= (bdy/bDist)*scale*0.2f
                    }
                    gs.targetPx = gs.px - gs.cameraX
                    gs.targetPy = gs.py

                    if (gs.bossHp <= 0) {
                        gs.bossActive = false
                        gs.currentSector++
                        gs.sectorTarget += gs.currentSector * 40
                        gs.hp = min(gs.maxHp, gs.hp + 1)
                        gs.sectorFlash = 1f; gs.shakeAmount = scale * 0.1f
                        onScoreAdd(50)

                        effectSystem.spawnFloatingText(screenBx, gs.bossY, 50, GameColors.YELLOW)
                        EchoAudioManager.playSound(ToneGenerator.TONE_SUP_CONFIRM, 500)

                        if (gs.currentSector > 5 && gs.gameMode == 1) {
                            onBossDefeated(gs.hp == gs.maxHp)
                        } else {
                            enemySystem.respawnAll(gs, width, height)
                        }
                    }
                } else if (gs.bossIframe <= 0f && gs.shieldTimer <= 0f && gs.playerIframe <= 0f) {
                    gs.bossIframe = 1.0f
                    if (bDist > 0f) {
                        gs.px -= (bdx/bDist)*scale*0.2f
                        gs.py -= (bdy/bDist)*scale*0.2f
                    }
                    gs.targetPx = gs.px - gs.cameraX
                    gs.targetPy = gs.py
                    onDamage(scale)
                }
            }
        }
    }
}