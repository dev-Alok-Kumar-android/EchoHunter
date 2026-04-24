package com.appsbyalok.echohunter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

// Handles Logic & Rendering for AI (Enemies, Bosses, Powerups)
class EnemySystem {
    private val p = Paint().apply { isAntiAlias = true }
    private val pText = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // Normal Enemies Array
    val n = 25
    val ex = FloatArray(n)
    val ey = FloatArray(n)
    val evx = FloatArray(n)
    val evy = FloatArray(n)
    val vis = FloatArray(n)
    val type = IntArray(n)

    // Powerups Array
    val pwn = 4
    val pwX = FloatArray(pwn)
    val pwY = FloatArray(pwn)
    val pwType = IntArray(pwn)
    val pwVis = FloatArray(pwn)
    val pwActive = BooleanArray(pwn)
    private val puIcons = arrayOf("+", "V", "S")

    fun respawnAll(gs: GameState, width: Float, height: Float) {
        for (i in 0 until pwn) pwActive[i] = false
        for (i in 0 until n) spawn(i, gs, width, height)
    }

    fun spawn(i: Int, gs: GameState, width: Float, height: Float) {
        val scale = min(width, height)
        val isSwarm = gs.bossActive && (gs.bossType == 1 || gs.bossType == 4)
        var safeSpawn = false
        var attempts = 0

        while (!safeSpawn && attempts < 10) {
            attempts++
            if (gs.gameMode == 2) {
                ex[i] = gs.cameraX + width + Random.nextFloat() * (width * 0.5f)
                ey[i] = Random.nextFloat() * height
            } else {
                if (Random.nextBoolean()) {
                    ex[i] = gs.cameraX + Random.nextFloat() * width
                    ey[i] = if (Random.nextBoolean()) -scale * 0.1f else height + scale * 0.1f
                } else {
                    ex[i] =
                        gs.cameraX + if (Random.nextBoolean()) -scale * 0.1f else width + scale * 0.1f
                    ey[i] = Random.nextFloat() * height
                }
            }
            val dx = ex[i] - gs.px
            val dy = ey[i] - gs.py
            val dToPlayerSq = dx * dx + dy * dy
            val safeDistanceSq = (scale * 0.35f) * (scale * 0.35f)

            // Allow override if timeSinceStart > 0 to not block spawning mid-game
            if (dToPlayerSq > safeDistanceSq || gs.timeSinceStart == 0f) safeSpawn = true
        }

        if (!safeSpawn) {
            ex[i] = gs.cameraX + width + scale * 0.2f
            ey[i] = -scale * 0.2f
        }

        val diffSpeedMult = if (gs.difficulty == 0) 0.65f else 1.0f
        val speedMult = when (gs.gameMode) {
            0 -> 0.4f + (gs.wave * 0.1f)
            1 -> 0.5f + (gs.currentSector * 0.15f)
            else -> 1f + (gs.score * 0.005f)
        } * diffSpeedMult

        val baseSp = scale * 0.3f * speedMult
        val sp = baseSp + Random.nextFloat() * (scale * 0.2f)

        evx[i] = (Random.nextFloat() - 0.5f) * sp * 2f
        evy[i] = (Random.nextFloat() - 0.5f) * sp * 2f

        val diffHunterMult = if (gs.difficulty == 0) 0.005 else 0.015
        val hunterProbability =
            if (gs.score < 10) 0.0 else min(0.65, (gs.score - 10) * diffHunterMult)

        if (isSwarm) {
            type[i] = 1
        } else if (gs.gameMode == 2) {
            if (Random.nextDouble() < hunterProbability) type[i] = 1
            else type[i] = if (Random.nextDouble() > 0.7) 2 else 0
        } else {
            type[i] = if (Random.nextDouble() < hunterProbability) 1 else 0
        }
        vis[i] = 0f
    }

    fun spawnSwarmIfNeeded(gs: GameState, width: Float, height: Float) {
        if (gs.bossType == 1 || gs.bossType == 4) {
            for (i in 0 until n) spawn(i, gs, width, height)
        }
    }

    fun updateEnemies(dt: Float, gs: GameState, width: Float, height: Float, scale: Float) {
        gs.isEnemyNear = false
        gs.isEnemyVeryNear = false

        val hitDistSq = (scale * 0.045f) * (scale * 0.045f)

        for (i in 0 until n) {

            // --- Distance BEFORE movement (for chasing) ---
            val edx = gs.px - ex[i]
            val edy = gs.py - ey[i]
            val d2 = edx * edx + edy * edy

            // --- Hunter AI ---
            if (type[i] == 1 && d2 > 0f) {
                val eDist = sqrt(d2)
                val chaseSpeed = scale * (
                        if (gs.isOverclocked) -0.5f
                        else if (gs.difficulty == 0) 0.3f
                        else 0.4f
                        )

                evx[i] = (evx[i] * 0.95f) + ((edx / eDist) * chaseSpeed * 0.05f)
                evy[i] = (evy[i] * 0.95f) + ((edy / eDist) * chaseSpeed * 0.05f)
            }

            // --- Apply movement ---
            ex[i] += evx[i] * dt
            ey[i] += evy[i] * dt

            // CAMERA-AWARE RESPAWN

            // 1. Firewall priority
            if (gs.gameMode == 2 && ex[i] < gs.firewallWorldX) {
                spawn(i, gs, width, height)
                vis[i] = 0f
                continue
            }

            // 2. Left side cleanup
            if (ex[i] < gs.cameraX - width) {
                spawn(i, gs, width, height)
                vis[i] = 0f
                continue
            }

            // 3. Right side cleanup
            if (ex[i] > gs.cameraX + width * 2f) {
                spawn(i, gs, width, height)
                vis[i] = 0f
                continue
            }

            // VERTICAL BOUNDS
            if (ey[i] < 0) evy[i] = kotlin.math.abs(evy[i])
            if (ey[i] > height) evy[i] = -kotlin.math.abs(evy[i])

            //  Distance AFTER movement
            val ndx = gs.px - ex[i]
            val ndy = gs.py - ey[i]
            val nd2 = ndx * ndx + ndy * ndy

            // --- Proximity flags ---
            if (type[i] == 1) {
                if (nd2 < hitDistSq * 15f) gs.isEnemyNear = true
                if (nd2 < hitDistSq * 4f) gs.isEnemyVeryNear = true
            }

            //  Visibility logic
            if ((gs.pulse && nd2 in gs.innerRSq..gs.outerRSq) ||
                nd2 < gs.passiveAuraRadiusSq
            ) {
                vis[i] = 1f
            }

            vis[i] *= gs.fadeMultiplier
        }
    }

    fun updateBoss(dt: Float, gs: GameState, width: Float, scale: Float) {
        if (!gs.bossActive) return

        val bdx = gs.px - gs.bossX
        val bdy = gs.py - gs.bossY
        val bDistSq = bdx * bdx + bdy * bdy
        val bDist = sqrt(bDistSq)

        val bSpeed =
            scale * (if (gs.bossType == 3 || gs.bossType == 4) 0.6f else 0.3f) * (if (gs.difficulty == 0) 0.7f else 1.0f)

        if (bDist > 0f) {
            gs.bossX += (bdx / bDist) * bSpeed * dt
            gs.bossY += (bdy / bDist) * bSpeed * dt
        }

        if ((gs.bossType == 2 || gs.bossType == 4) && Random.nextDouble() < 0.01) {
            gs.empFlashTimer = 1.0f
            EchoAudioManager.playSound(android.media.ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 100)
            StoryProtocol.showIngameMessage("SYSTEM EMP DETECTED", 1f)
        }
        val screenBx = gs.bossX - gs.cameraX
        if (screenBx < -scale * 0.4f) gs.bossX = gs.cameraX - scale * 0.4f
        if (screenBx > width + scale * 0.4f) gs.bossX = gs.cameraX + width + scale * 0.4f
    }

    fun updatePowerups(dt: Float, gs: GameState, width: Float, height: Float) {
        val puDropRate = if (gs.difficulty == 0) 0.004 else 0.002
        if (Random.nextDouble() < puDropRate * dt * 60 && gs.score > 15 && !gs.bossActive) {
            for (i in 0 until pwn) {
                if (!pwActive[i]) {
                    pwX[i] = gs.cameraX + width * 0.2f + Random.nextFloat() * width * 0.8f
                    pwY[i] = Random.nextFloat() * height
                    pwType[i] = Random.nextInt(3)
                    pwActive[i] = true
                    pwVis[i] = 0f
                    break
                }
            }
        }
    }

    fun drawEntities(c: Canvas, gs: GameState, width: Float, scale: Float) {
        val entityRadius = scale * 0.03f

        // 1. Draw Powerups
        for (i in 0 until pwn) {
            if (pwActive[i]) {
                val dx = pwX[i] - gs.px
                val dy = pwY[i] - gs.py
                val d2 = dx * dx + dy * dy

                if ((gs.pulse && d2 in gs.innerRSq..gs.outerRSq) || d2 < gs.passiveAuraRadiusSq) pwVis[i] =
                    1f
                pwVis[i] *= gs.fadeMultiplier
                val screenPwX = pwX[i] - gs.cameraX

                if (screenPwX < -scale || screenPwX > width + scale) pwActive[i] = false

                if (pwVis[i] > 0.02f) {
                    p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.005f
                    p.color = ((pwVis[i] * 255).toInt() shl 24) or (when (pwType[i]) {
                        0 -> GameColors.HP; 1 -> GameColors.CLARITY; else -> GameColors.SHIELD
                    } and 0xFFFFFF)
                    c.drawCircle(screenPwX, pwY[i], entityRadius * 0.8f, p)

                    pText.color = GameColors.BG
                    pText.textSize = scale * 0.04f
                    c.drawText(puIcons[pwType[i]], screenPwX, pwY[i] + scale * 0.012f, pText)
                }
            }
        }

        // 2. Draw Normal Enemies
        for (i in 0 until n) {
            val screenEx = ex[i] - gs.cameraX
            if (vis[i] > 0.02f || gs.bossActive) {
                val a = (vis[i] * 255).toInt()
                val entitySize = entityRadius * (if (type[i] == 2) 1.2f else 0.8f)

                p.style = Paint.Style.FILL

                when (type[i]) {
                    1 -> {
                        // HUNTER: Red, Jagged/Glitchy Square
                        p.color = (a shl 24) or (GameColors.RED and 0xFFFFFF)
                        c.drawRect(
                            screenEx - entitySize,
                            ey[i] - entitySize,
                            screenEx + entitySize,
                            ey[i] + entitySize,
                            p
                        )
                        p.color = GameColors.BG
                        c.drawRect(
                            screenEx - entitySize / 2,
                            ey[i] - entitySize / 2,
                            screenEx + entitySize / 2,
                            ey[i] + entitySize / 2,
                            p
                        )
                    }

                    2 -> {
                        // MEMORY/COOLANT: Blue Glowing Diamond
                        p.color = (a shl 24) or (GameColors.COOLANT and 0xFFFFFF)
                        val path = android.graphics.Path()
                        path.moveTo(screenEx, ey[i] - entitySize)
                        path.lineTo(screenEx + entitySize, ey[i])
                        path.lineTo(screenEx, ey[i] + entitySize)
                        path.lineTo(screenEx - entitySize, ey[i])
                        path.close()
                        c.drawPath(path, p)
                        p.color = GameColors.CLARITY
                        c.drawCircle(screenEx, ey[i], entityRadius * 0.3f, p)
                    }

                    else -> {
                        // YELLOW DATA: Small Diamond
                        p.color = (a shl 24) or (GameColors.YELLOW and 0xFFFFFF)
                        val path = android.graphics.Path()
                        path.moveTo(screenEx, ey[i] - entitySize)
                        path.lineTo(screenEx + entitySize, ey[i])
                        path.lineTo(screenEx, ey[i] + entitySize)
                        path.lineTo(screenEx - entitySize, ey[i])
                        path.close()
                        c.drawPath(path, p)
                    }
                }
            }
        }

        // 3. Draw Boss
        if (gs.bossActive) {
            val bdx = gs.px - gs.bossX
            val bdy = gs.py - gs.bossY
            val bDistSq = bdx * bdx + bdy * bdy

            val bossRadius = scale * 0.08f
            val isBossRevealed =
                (gs.pulse && bDistSq in gs.innerRSq..gs.outerRSq) || gs.bossIframe > 0f || bDistSq < gs.passiveAuraRadiusSq
            val bAlpha = if (isBossRevealed) 255 else 100
            val screenBx = gs.bossX - gs.cameraX

            p.style = Paint.Style.FILL
            p.color = Color.argb(
                bAlpha,
                Color.red(GameColors.BOSS),
                Color.green(GameColors.BOSS),
                Color.blue(GameColors.BOSS)
            )
            c.drawCircle(
                screenBx,
                gs.bossY,
                bossRadius + kotlin.math.sin(gs.timeSinceStart * 15f) * 10f,
                p
            )

            p.color = if (gs.bossIframe > 0f) GameColors.CLARITY else GameColors.RED
            c.drawCircle(screenBx, gs.bossY, bossRadius * 0.5f, p)

            p.color = GameColors.RED
            c.drawRect(
                screenBx - bossRadius,
                gs.bossY - bossRadius - scale * 0.02f,
                screenBx + bossRadius,
                gs.bossY - bossRadius - scale * 0.01f,
                p
            )

            p.color = GameColors.HP
            val hpWidth = (bossRadius * 2f) * (gs.bossHp.toFloat() / gs.bossMaxHp)
            c.drawRect(
                screenBx - bossRadius,
                gs.bossY - bossRadius - scale * 0.02f,
                screenBx - bossRadius + hpWidth,
                gs.bossY - bossRadius - scale * 0.01f,
                p
            )
        }
    }
}