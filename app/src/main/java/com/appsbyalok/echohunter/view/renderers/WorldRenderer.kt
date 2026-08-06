package com.appsbyalok.echohunter.view.renderers

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.appsbyalok.echohunter.R
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.engine.AppStateId
import com.appsbyalok.echohunter.engine.DifficultyLevel
import com.appsbyalok.echohunter.engine.GameModeId
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.systems.EffectSystem
import com.appsbyalok.echohunter.systems.EnemySystem
import com.appsbyalok.echohunter.utils.GameColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class WorldRenderer(
    private val context: Context,
    private val effectSys: EffectSystem,
    private val enemySys: EnemySystem,
) {
    private val p = Paint().apply { isAntiAlias = true }
    private val pGlow = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    private val pDash =
        Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; color = 0x55FFFF00 }
    private val pText = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val arrowPath = Path()
    private var lastCoreDist = -1
    private var coreDistStr = ""

    fun updateDashEffect(scale: Float) {
        pDash.pathEffect = DashPathEffect(floatArrayOf(scale * 0.05f, scale * 0.05f), 0f)
    }

    fun drawGrid(c: Canvas, scale: Float, gs: GameState, targetW: Float, targetH: Float, showSpawners: Boolean = true) {
        val viewportW = gs.getViewportW(targetW, targetH)
        val viewportH = gs.getViewportH(targetW, targetH)

        p.style = Paint.Style.STROKE
        p.color = if (gs.difficulty == DifficultyLevel.HARD) 0xFF441010.toInt() else 0xFF1A1C2E.toInt()
        p.strokeWidth = max(1f, scale * 0.002f)
        val gap = scale / 8.5f
        val parallaxX = gs.cameraX * 0.5f
        val parallaxY = gs.cameraY * 0.5f

        val gridOffsetX = -(parallaxX % gap)
        val gridOffsetY = -(parallaxY % gap)

        var i = -gap + gridOffsetX
        while (i < viewportW + gap) {
            c.drawLine(i, 0f, i, viewportH, p); i += gap
        }
        var j = -gap + gridOffsetY
        while (j < viewportH + gap) {
            c.drawLine(0f, j, viewportW, j, p); j += gap
        }

        if (!showSpawners) return

        // --- NEW: NETWORK DATA STREAMS ---
        if (gs.activeObjective is com.appsbyalok.echohunter.modes.CleanSweepObjective) {
            pGlow.style = Paint.Style.STROKE
            pGlow.strokeWidth = scale * 0.003f
            for (node in gs.spawnerNodes) {
                if (node.parentNodeIdx >= 0 && node.parentNodeIdx < gs.spawnerNodes.size && node.state != com.appsbyalok.echohunter.systems.SpawnState.DESTROYED) {
                    val parent = gs.spawnerNodes[node.parentNodeIdx]
                    if (parent.state != com.appsbyalok.echohunter.systems.SpawnState.DESTROYED) {
                        val x1 = node.x - gs.cameraX
                        val y1 = node.y - gs.cameraY
                        val x2 = parent.x - gs.cameraX
                        val y2 = parent.y - gs.cameraY
                        
                        // Pulse effect along the stream
                        val pulse = (sin(gs.timeSinceStart * 4f + node.x * 0.01f) + 1f) / 2f
                        pGlow.color = (GameColors.PULSE and 0x00FFFFFF) or ((0.15f + pulse * 0.25f) * 255).toInt().shl(24)
                        c.drawLine(x1, y1, x2, y2, pGlow)
                    }
                }
            }
        }

        // --- NEW: SPAWNER NODES (CIRCUIT CHIP LOOK) ---
        for (node in gs.spawnerNodes) {
            val nx = node.x - gs.cameraX
            val ny = node.y - gs.cameraY
            val r = scale * 0.035f // Smaller size to reduce clutter

            // Calculate node visibility for darkness levels
            var nodeAlpha = 1.0f
            val applyDarkness = gs.isDarknessLevel || StoryProtocol.isBlackoutActive
            if (applyDarkness && !gs.modFullVisibility) {
                val dx = node.x - gs.px
                val dy = node.y - gs.py
                val d2 = dx * dx + dy * dy
                
                nodeAlpha = 0f
                // 1. Passive Aura
                if (d2 < gs.passiveAuraRadiusSq) {
                    val dist = sqrt(d2)
                    val auraRad = sqrt(gs.passiveAuraRadiusSq)
                    nodeAlpha = max(0f, 1f - dist / auraRad)
                }
                
                // 2. Persistent Scan (Grid based)
                val gx = (node.x / gs.tileSize).toInt()
                val gy = (node.y / gs.tileSize).toInt()
                val persistentVis = gs.wallVisMap?.getOrNull(gx)?.getOrNull(gy) ?: 0f
                nodeAlpha = max(nodeAlpha, persistentVis)

                // 3. Sonar Pulse
                if (gs.pulse && d2 >= gs.innerRSq && d2 <= gs.outerRSq) {
                    nodeAlpha = max(nodeAlpha, 0.8f)
                }
            }

            if (nodeAlpha < 0.05f) continue
            val alphaInt = (nodeAlpha * 255).toInt()

            val nodeColor = when (node.state) {
                com.appsbyalok.echohunter.systems.SpawnState.DESTROYED -> 0xFF333333.toInt()
                com.appsbyalok.echohunter.systems.SpawnState.DISABLED -> GameColors.YELLOW
                com.appsbyalok.echohunter.systems.SpawnState.REPAIRING -> 0xFF00FF88.toInt()
                com.appsbyalok.echohunter.systems.SpawnState.SELF_DESTROYING -> GameColors.OVERCLOCK
                else -> when (node.type) {
                    1 -> GameColors.RED
                    2 -> GameColors.YELLOW
                    else -> GameColors.PULSE
                }
            }

            // 1. Chip Base (Semi-transparent)
            p.style = Paint.Style.FILL
            
            // Highlight ROOT nodes in Clean Sweep
            val isRoot = gs.activeObjective is com.appsbyalok.echohunter.modes.CleanSweepObjective && node.parentNodeIdx == -1 && node.state != com.appsbyalok.echohunter.systems.SpawnState.DESTROYED
            if (isRoot) {
                p.color = (alphaInt shl 24) or (GameColors.PULSE and 0xFFFFFF)
                c.drawCircle(nx, ny, r * 1.4f, p)
            }

            val baseAlpha = if (node.state == com.appsbyalok.echohunter.systems.SpawnState.DISABLED) 
                (alphaInt * 0.2f * (0.5f + 0.5f * sin(gs.timeSinceStart * 15f))).toInt()
            else (alphaInt * 0.4f).toInt()
            
            p.color = (baseAlpha shl 24) or (nodeColor and 0xFFFFFF)
            c.drawRect(nx - r, ny - r, nx + r, ny + r, p)

            // 4. SELF-DESTRUCT LIGHTNING EFFECT
            if (node.state == com.appsbyalok.echohunter.systems.SpawnState.SELF_DESTROYING) {
                p.style = Paint.Style.STROKE
                p.color = GameColors.OVERCLOCK
                p.alpha = (alphaInt * (0.7f + 0.3f * sin(gs.timeSinceStart * 25f))).toInt()
                p.strokeWidth = scale * 0.008f
                val jitter = scale * 0.005f * sin(gs.timeSinceStart * 50f)
                c.drawRect(nx - r - jitter, ny - r - jitter, nx + r + jitter, ny + r + jitter, p)
            }

            // 2. Progress Fill or Status Indicator
            when (node.state) {
                com.appsbyalok.echohunter.systems.SpawnState.DESTROYED -> {
                    p.color = (alphaInt shl 24) or 0xFF440000.toInt()
                    p.strokeWidth = scale * 0.005f
                    c.drawLine(nx - r, ny - r, nx + r, ny + r, p)
                    c.drawLine(nx + r, ny - r, nx - r, ny + r, p)
                }
                com.appsbyalok.echohunter.systems.SpawnState.REPAIRING -> {
                    val repairProgress = node.hp / node.maxHp
                    p.color = (alphaInt shl 24) or 0xFF00FF88.toInt()
                    c.drawRect(nx - r, ny + r - (2 * r * repairProgress), nx + r, ny + r, p)
                }
                else -> {
                    val progress = 1f - (node.cooldownTimer / node.maxCooldown)
                    p.color = ((alphaInt * 0.7f).toInt() shl 24) or (nodeColor and 0xFFFFFF)
                    c.drawRect(nx - r, ny + r - (2 * r * progress), nx + r, ny + r, p)
                }
            }

            // 3. Border & Pins
            p.style = Paint.Style.STROKE
            p.strokeWidth = scale * 0.005f
            p.color = (alphaInt shl 24) or (nodeColor and 0xFFFFFF)
            if (node.state == com.appsbyalok.echohunter.systems.SpawnState.DISABLED && (gs.timeSinceStart * 10).toInt() % 2 == 0) {
                p.color = (alphaInt shl 24) or 0xFFFFFFFF.toInt()
            }
            c.drawRect(nx - r, ny - r, nx + r, ny + r, p)

            // 4. SELF-DESTRUCT LIGHTNING EFFECT
            if (node.state == com.appsbyalok.echohunter.systems.SpawnState.SELF_DESTROYING) {
                p.style = Paint.Style.STROKE
                p.color = GameColors.OVERCLOCK
                p.alpha = (alphaInt * (0.7f + 0.3f * sin(gs.timeSinceStart * 25f))).toInt()
                p.strokeWidth = scale * 0.008f
                val jitter = scale * 0.005f * sin(gs.timeSinceStart * 50f)
                c.drawRect(nx - r - jitter, ny - r - jitter, nx + r + jitter, ny + r + jitter, p)
            }

            // HP BAR for Spawners (Only if damaged or high tier)
            if (node.hp < node.maxHp && node.state != com.appsbyalok.echohunter.systems.SpawnState.DESTROYED) {
                val hpW = r * 1.5f
                val hpH = scale * 0.005f
                val hpX = nx - hpW / 2f
                val hpY = ny - r - scale * 0.015f
                
                p.style = Paint.Style.FILL
                p.color = 0x66000000
                c.drawRect(hpX, hpY, hpX + hpW, hpY + hpH, p)
                
                p.color = (alphaInt shl 24) or GameColors.HP
                c.drawRect(hpX, hpY, hpX + hpW * (node.hp / node.maxHp), hpY + hpH, p)
            }

            // Inner technical detailing
            p.strokeWidth = scale * 0.001f
            c.drawCircle(nx, ny, r * 0.4f, p)
            c.drawLine(nx - r, ny, nx + r, ny, p)
            c.drawLine(nx, ny - r, nx, ny + r, p)

            p.strokeWidth = scale * 0.002f
            val pin = r * 0.4f
            for (idx in -1..1) {
                val off = idx * r * 0.6f
                c.drawLine(nx - r - pin, ny + off, nx - r, ny + off, p) // Left pins
                c.drawLine(nx + r, ny + off, nx + r + pin, ny + off, p) // Right pins
            }

            // 4. Queue Count
            if (node.queue > 0) {
                pText.textSize = scale * 0.022f
                pText.color = (alphaInt shl 24) or 0xFFFFFFFF.toInt()
                pText.textAlign = Paint.Align.CENTER
                c.drawText("${node.queue}", nx, ny + scale * 0.008f, pText)
            }

            // --- NEW: RESONANCE OVERLOAD TIMER ---
            if (node.overloadTimer > 0f) {
                val barW = r * 1.6f
                val barH = scale * 0.008f
                val barX = nx - barW / 2f
                val barY = ny + r + scale * 0.01f
                
                p.style = Paint.Style.FILL
                p.color = 0x66000000
                c.drawRect(barX, barY, barX + barW, barY + barH, p)
                
                // Pulsing yellow-white for resonance
                val pulseAlpha = (180 + 75 * sin(gs.timeSinceStart * 15f)).toInt()
                p.color = (pulseAlpha shl 24) or (GameColors.YELLOW and 0xFFFFFF)
                c.drawRect(barX, barY, barX + barW * (node.overloadTimer / 8f), barY + barH, p)
            }
        }
    }

    fun drawCRTOverlay(c: Canvas, gs: GameState, targetW: Float, targetH: Float) {
        p.style = Paint.Style.STROKE
        p.color = 0x22000000
        p.strokeWidth = 2f
        var yLine = (gs.timeSinceStart * 20f) % 8f
        while (yLine < targetH) {
            c.drawLine(0f, yLine, targetW, yLine, p)
            yLine += 8f
        }

        // --- NEW: BLACKOUT & STORY GLITCH EFFECTS ---
        if (StoryProtocol.isBlackoutActive || StoryProtocol.isGlitchActive) {
            val intensity = if (StoryProtocol.isGlitchActive) 0.3f else 0.15f
            
            // 1. Heavy flickering overlay
            if (Random.nextFloat() < intensity) {
                c.drawColor(0x44000000)
            }

            // 2. RGB Shift Glitch Strips
            if (Random.nextFloat() < (intensity * 1.2f)) {
                p.style = Paint.Style.FILL
                val stripY = Random.nextFloat() * targetH
                val stripH = Random.nextFloat() * (targetH * 0.02f)
                
                // Cyan strip
                p.color = 0x3300FFFF
                c.drawRect(0f, stripY, targetW, stripY + stripH, p)
                
                // Red strip (slightly offset)
                p.color = 0x33FF0000
                c.drawRect(0f, (stripY + 5f) % targetH, targetW, (stripY + 5f + stripH) % targetH, p)
            }

            // 3. Static/Noise pixels
            if (Random.nextFloat() < (intensity + 0.1f)) {
                p.strokeWidth = 3f
                p.color = 0xCCFFFFFF.toInt()
                repeat(25) {
                    c.drawPoint(Random.nextFloat() * targetW, Random.nextFloat() * targetH, p)
                }
            }
        }
    }

    fun drawMaze(c: Canvas, gs: GameState, scale: Float, targetW: Float, targetH: Float) {
        val grid = gs.gridMap ?: return
        val ts = gs.tileSize

        p.style = Paint.Style.FILL

        // --- Calculate actual visible viewport accounting for Zoom ---
        val viewportW = gs.getViewportW(targetW, targetH)
        val viewportH = gs.getViewportH(targetW, targetH)
        val buffer = ts * 2f // 2-tile extra padding to prevent edge popping

        for (x in grid.indices) {
            for (y in grid[x].indices) {
                val drawX = x * ts - gs.cameraX
                val drawY = y * ts - gs.cameraY


                // Culling (Don't draw if outside screen)
                if (drawX < -buffer || drawX > viewportW + buffer || drawY < -buffer || drawY > viewportH + buffer) continue

                // Calculate wall visibility based on Pulse and Passive Aura
                val worldCenterX = x * ts + ts / 2f
                val worldCenterY = y * ts + ts / 2f
                val dx = worldCenterX - gs.px
                val dy = worldCenterY - gs.py
                val d2 = dx * dx + dy * dy

                var wallAlpha = 0f
                val applyDarkness = gs.isDarknessLevel || StoryProtocol.isBlackoutActive
                if (!applyDarkness || gs.modFullVisibility) {
                    wallAlpha = 1.0f // Normal levels have full scan visibility
                } else {
                    // 1. Reveal by Passive Aura (Player's close range light)
                    if (d2 < gs.passiveAuraRadiusSq) {
                        val dist = sqrt(d2)
                        val auraRad = sqrt(gs.passiveAuraRadiusSq)
                        // Smooth fade at the edge of aura
                        wallAlpha = max(0f, 0.2f * (1f - dist / auraRad))
                    } 
                    
                    // 2. Reveal by Persistent Visibility (Uses getSonarDurationBonus decay from GameState)
                    val persistentVis = gs.wallVisMap?.getOrNull(x)?.getOrNull(y) ?: 0f
                    if (persistentVis * 0.4f > wallAlpha) {
                        wallAlpha = persistentVis * 0.4f
                    }

                    // 3. Reveal by active Sonar Pulse (Brightest hit)
                    if (gs.pulse && d2 >= gs.innerRSq && d2 <= gs.outerRSq) {
                        wallAlpha = max(wallAlpha, 0.6f)
                    }
                }

                if (wallAlpha > 0.01f) {
                    when (grid[x][y]) {
                        1 -> { // 2D NEON WALL RENDERING
                            val strokeAlpha = (wallAlpha * 255).toInt()
                            val fillAlpha = (wallAlpha * 0.15f * 255).toInt() // Faint interior look
                            
                            p.color = (fillAlpha shl 24) or (GameColors.PULSE and 0xFFFFFF)
                            c.drawRect(drawX, drawY, drawX + ts, drawY + ts, p)

                            p.style = Paint.Style.STROKE
                            p.strokeWidth = scale * 0.005f
                            p.color = (strokeAlpha shl 24) or (GameColors.PULSE and 0xFFFFFF)
                            val m = ts * 0.1f
                            c.drawRect(drawX + m, drawY + m, drawX + ts - m, drawY + ts - m, p)
                            p.style = Paint.Style.FILL
                        }
                    }
                }
            }
        }
    }

    fun drawGamePlay(c: Canvas, scale: Float, gs: GameState, targetW: Float, targetH: Float) {
        val viewportW = gs.getViewportW(targetW, targetH)
        val viewportH = gs.getViewportH(targetW, targetH)
        val currentPlayerColor = if (gs.isOverclocked) GameColors.OVERCLOCK else GameColors.PULSE
        val screenPlayerX = gs.px - gs.cameraX
        val screenPlayerY = gs.py - gs.cameraY

        p.style = Paint.Style.FILL; p.color = 0x1A00FFFF
        c.drawCircle(
            screenPlayerX,
            screenPlayerY,
            scale * 0.12f + sin(gs.timeSinceStart * 3f) * scale * 0.01f,
            p
        )

        gs.modeStrategy.drawModeSpecificWorld(c, gs, viewportW, viewportH, scale, p)

        if (gs.pulse) {
            val alpha = (255 * (1f - (gs.pulseR / (viewportW * 0.75f)))).toInt()
            val colorGlow =
                if (StoryProtocol.isGlitchActive) GameColors.RED else if (gs.isOverclocked) GameColors.OVERCLOCK else if (gs.visionClarity > 0.3f) GameColors.PULSE else 0xFF006666.toInt()
            pGlow.color = (max(0, alpha) shl 24) or (colorGlow and 0xFFFFFF)
            pGlow.strokeWidth = scale * 0.008f
            c.drawCircle(screenPlayerX, screenPlayerY, gs.pulseR, pGlow)

            // CLEAN SWEEP COMPASS: Guide player to the nearest compiler during pulse
            if (gs.activeObjective is com.appsbyalok.echohunter.modes.CleanSweepObjective) {
                val nearest = gs.spawnerNodes
                    .filter { it.state != com.appsbyalok.echohunter.systems.SpawnState.DESTROYED }
                    .minByOrNull { (it.x - gs.px) * (it.x - gs.px) + (it.y - gs.py) * (it.y - gs.py) }

                if (nearest != null) {
                    val dx = nearest.x - gs.px
                    val dy = nearest.y - gs.py
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist > scale * 0.2f) {
                        val lineLen = scale * 0.2f
                        val dirX = dx / dist
                        val dirY = dy / dist
                        val startX = screenPlayerX + dirX * scale * 0.1f
                        val startY = screenPlayerY + dirY * scale * 0.1f

                        pGlow.strokeWidth = scale * 0.01f
                        // Drawing a pointer line
                        c.drawLine(startX, startY, startX + dirX * lineLen, startY + dirY * lineLen, pGlow)
                        // Arrow tip
                        c.drawCircle(startX + dirX * lineLen, startY + dirY * lineLen, scale * 0.015f, pGlow)
                    }
                }
            }
        }

        if (gs.shockwaveActive) {
            val screenShockX = gs.shockwaveX - gs.cameraX
            val screenShockY = gs.shockwaveY - gs.cameraY
            p.style = Paint.Style.STROKE
            p.strokeWidth = max(2f, scale * 0.05f * (1f - (gs.shockwaveR / (scale * 1.5f))))
            p.color = GameColors.CLARITY
            c.drawCircle(screenShockX, screenShockY, gs.shockwaveR, p)
            p.style = Paint.Style.FILL
            p.color = 0x22FFFFFF
            c.drawCircle(screenShockX, screenShockY, gs.shockwaveR, p)
        }

        // --- FIX: DEFENSE mode, ESCAPE mode or Story Core for all ---
        val config = com.appsbyalok.echohunter.data.LevelEngine.getLevelConfig(gs.currentLevel)
        val isDefense =
            config.features.contains(com.appsbyalok.echohunter.data.LevelFeature.DEFENSE) && gs.gameMode == GameModeId.CAMPAIGN
        val isEscape =
            config.features.contains(com.appsbyalok.echohunter.data.LevelFeature.ESCAPE) && gs.gameMode == GameModeId.CAMPAIGN

        // Now Escape level also triggers drawCore!
        if (isDefense || isEscape || gs.state == AppStateId.CORE_MERGE || gs.state == AppStateId.PERFECT_END_ZOOM || (gs.gameMode == GameModeId.TRAINING && gs.tutorialGateOpen)) {
            drawCore(c, scale, gs, viewportW, screenPlayerX, screenPlayerY)
        }

        // 6. RENDER DECOYS / MINES / TRAPS
        for (trap in gs.activeTraps) {
            val tx = trap.x - gs.cameraX
            val ty = trap.y - gs.cameraY
            val alpha = (min(1f, trap.timer / 0.5f) * 255).toInt()
            
            p.style = Paint.Style.STROKE
            p.strokeWidth = scale * 0.005f
            
            when (trap.type) {
                1 -> { // DECOY
                    p.color = (alpha shl 24) or (GameColors.PULSE and 0xFFFFFF)
                    val holoPulse = sin(gs.timeSinceStart * 20f) * scale * 0.005f
                    c.drawCircle(tx, ty, scale * 0.02f + holoPulse, p)
                    c.drawCircle(tx, ty, scale * 0.035f - holoPulse, p)
                }
                2 -> { // EMP MINE
                    p.style = Paint.Style.FILL
                    p.color = (alpha shl 24) or (GameColors.RED and 0xFFFFFF)
                    c.drawCircle(tx, ty, scale * 0.015f, p)

                    p.style = Paint.Style.STROKE
                    p.strokeWidth = scale * 0.003f
                    p.color = (alpha shl 24) or (GameColors.YELLOW and 0xFFFFFF)
                    val pulse = sin(gs.timeSinceStart * 10f) * scale * 0.015f
                    c.drawCircle(tx, ty, scale * 0.03f + max(0f, pulse), p)
                }
                3 -> { // STASIS PULSE
                    p.color = (alpha shl 24) or (0xFF00FFFF.toInt() and 0xFFFFFF)
                    c.drawCircle(tx, ty, scale * 0.15f * (1f - trap.timer / trap.duration), p)
                }
                4 -> { // SONIC DECOY
                    p.color = (alpha shl 24) or (GameColors.YELLOW and 0xFFFFFF)
                    val r = scale * 0.03f
                    c.drawCircle(tx, ty, r, p)
                    val waveR = r + (gs.timeSinceStart * 2f % 1f) * scale * 0.05f
                    c.drawCircle(tx, ty, waveR, p)
                }
            }
        }

        // Sniper charge sight: a short dotted trajectory from the probe toward the current aim direction.
        if (gs.controls.currentWeapon == 2 && gs.controls.isSniperCharging) {
            val charge = (gs.controls.sniperCharge / 1.5f).coerceIn(0f, 1f)
            p.style = Paint.Style.FILL
            p.color = ((120 + (charge * 135).toInt()) shl 24) or (GameColors.YELLOW and 0xFFFFFF)
            for (dot in 1..7) {
                val distance = scale * 0.075f * dot
                val size = scale * (0.004f + charge * 0.004f) * (1f - dot * 0.07f)
                c.drawCircle(
                    screenPlayerX + gs.controls.aimDirX * distance,
                    screenPlayerY + gs.controls.aimDirY * distance,
                    size,
                    p
                )
            }
        }

        pGlow.color = GameColors.PULSE
        for (i in 0 until gs.maxSpikes) {
            if (gs.spikeActive[i]) {
                val sx = gs.spikeX[i] - gs.cameraX
                val sy = gs.spikeY[i] - gs.cameraY

                when (gs.spikeType[i]) {
                    2 -> { // SNIPER BEAM
                        val arcRoute = com.appsbyalok.echohunter.data.SaveManager.isNodeUnlocked("sniper_arc")
                        pGlow.color = if (arcRoute) GameColors.CLARITY else GameColors.RED
                        pGlow.strokeWidth = scale * 0.012f * com.appsbyalok.echohunter.data.UpgradeSystem.getSniperBeamWidthMultiplier() * (gs.spikeLife[i] / 0.6f)
                        val tailX = sx - (gs.spikeVx[i] * 0.06f)
                        val tailY = sy - (gs.spikeVy[i] * 0.06f)
                        if (arcRoute) {
                            val dx = sx - tailX; val dy = sy - tailY
                            val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                            val px = -dy / length; val py = dx / length
                            arrowPath.reset(); arrowPath.moveTo(tailX, tailY)
                            for (step in 1..3) {
                                val t = step / 4f
                                val wobble = sin(gs.timeSinceStart * 35f + i * 4f + step) * scale * 0.018f
                                arrowPath.lineTo(tailX + dx * t + px * wobble, tailY + dy * t + py * wobble)
                            }
                            arrowPath.lineTo(sx, sy)
                            c.drawPath(arrowPath, pGlow)
                        } else c.drawLine(sx, sy, tailX, tailY, pGlow)
                        p.style = Paint.Style.FILL; p.color = pGlow.color
                        c.drawCircle(sx, sy, pGlow.strokeWidth * 0.7f, p)
                    }

                    1 -> { // SHOTGUN SPREAD
                        pGlow.color = GameColors.OVERCLOCK
                        pGlow.strokeWidth = scale * 0.015f * (gs.spikeLife[i] / 0.4f)
                        arrowPath.reset()
                        arrowPath.moveTo(sx, sy - scale * 0.012f)
                        arrowPath.lineTo(sx + scale * 0.01f, sy + scale * 0.012f)
                        arrowPath.lineTo(sx - scale * 0.01f, sy + scale * 0.012f)
                        arrowPath.close()
                        p.style = Paint.Style.FILL; p.color = GameColors.OVERCLOCK; c.drawPath(arrowPath, p)
                    }

                    3 -> { // ENEMY PROJECTILE
                        p.style = Paint.Style.FILL; p.color = GameColors.RED
                        c.drawCircle(sx, sy, scale * 0.014f, p)
                        pGlow.color = GameColors.RED; pGlow.strokeWidth = scale * 0.004f
                        c.drawCircle(sx, sy, scale * 0.024f, pGlow)
                    }

                    else -> { // BLASTER BOLT
                        pGlow.color = GameColors.PULSE
                        pGlow.strokeWidth = scale * 0.008f * (gs.spikeLife[i] / 0.4f)
                        val tailX = sx - (gs.spikeVx[i] * 0.02f)
                        val tailY = sy - (gs.spikeVy[i] * 0.02f)
                        c.drawLine(sx, sy, tailX, tailY, pGlow)
                        p.style = Paint.Style.FILL; p.color = GameColors.PULSE
                        c.drawCircle(sx, sy, scale * 0.009f, p)
                    }
                }
            }
        }

        // --- FIX: Enemies draw in Gameplay AND Core Merge states ---
        if (gs.state.isGameplay) enemySys.drawEntities(c, gs, viewportW, scale)

        effectSys.drawTrails(c, gs.cameraX, gs.cameraY, scale, currentPlayerColor)
        effectSys.drawSonarPings(c, gs.cameraX, gs.cameraY, scale)
        effectSys.drawElectricArcs(c, gs.cameraX, gs.cameraY, scale)

        if (gs.isOverclocked) {
            effectSys.drawLightning(c, screenPlayerX, screenPlayerY, scale)
        }

        val shouldDrawPlayer = gs.playerIframe <= 0f || ((gs.timeSinceStart * 15).toInt() % 2 == 0)
        if (shouldDrawPlayer) {
            val playerRadius = scale * 0.015f
            val alpha = if (gs.isCamouflaged) 0x33 else 0xFF

            // Camouflage distortion effect
            if (gs.isCamouflaged && Random.nextFloat() < 0.15f) {
                p.style = Paint.Style.STROKE
                p.color = 0x4400FFFF
                p.strokeWidth = scale * 0.002f
                val offX = (Random.nextFloat() - 0.5f) * scale * 0.04f
                val offY = (Random.nextFloat() - 0.5f) * scale * 0.04f
                c.drawCircle(screenPlayerX + offX, screenPlayerY + offY, playerRadius * 2.5f, p)
            }

            p.style = Paint.Style.FILL
            p.color = (alpha shl 24) or (currentPlayerColor and 0xFFFFFF)
            c.drawCircle(screenPlayerX, screenPlayerY, playerRadius, p)

            p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.003f
            if (gs.shieldTimer > 0f) {
                p.color = (alpha shl 24) or (GameColors.SHIELD and 0xFFFFFF)
                p.strokeWidth = scale * 0.006f
                c.drawCircle(
                    screenPlayerX,
                    screenPlayerY,
                    playerRadius * 3f + sin(gs.timeSinceStart * 10f) * scale * 0.005f,
                    p
                )
                p.strokeWidth = scale * 0.003f
            } else {
                p.color = (alpha shl 24) or (currentPlayerColor and 0xFFFFFF)
            }
            c.drawCircle(screenPlayerX, screenPlayerY, playerRadius * 2f, p)
        }

            // --- BOMB TARGET ---
        if (gs.bombTargetX > -1000f) {
            val sx = gs.bombTargetX - gs.cameraX
            val sy = gs.bombTargetY - gs.cameraY
            
            p.style = Paint.Style.STROKE
            p.strokeWidth = scale * 0.005f
            val colorBase = if ((gs.timeSinceStart * 10).toInt() % 2 == 0) GameColors.RED else GameColors.YELLOW
            p.color = colorBase
            
            val r = scale * 0.05f + sin(gs.timeSinceStart * 8f) * scale * 0.015f
            c.drawRect(sx - r, sy - r, sx + r, sy + r, p)
            c.drawRect(sx - r * 0.5f, sy - r * 0.5f, sx + r * 0.5f, sy + r * 0.5f, p)
            
            // Corners for a "Lock-on" look
            val cs = r * 0.4f
            c.drawLine(sx - r, sy - r, sx - r + cs, sy - r, p)
            c.drawLine(sx - r, sy - r, sx - r, sy - r + cs, p)
            c.drawLine(sx + r, sy - r, sx + r - cs, sy - r, p)
            c.drawLine(sx + r, sy - r, sx + r, sy - r + cs, p)
            c.drawLine(sx - r, sy + r, sx - r + cs, sy + r, p)
            c.drawLine(sx - r, sy + r, sx - r, sy + r - cs, p)
            c.drawLine(sx + r, sy + r, sx + r - cs, sy + r, p)
            c.drawLine(sx + r, sy + r, sx + r, sy + r - cs, p)

            pText.textSize = scale * 0.025f
            pText.color = colorBase
            pText.textAlign = Paint.Align.CENTER
            c.drawText("UPLINK NODE", sx, sy - r * 1.5f, pText)
        }

        effectSys.drawParticles(c, gs.cameraX, gs.cameraY, scale)
        effectSys.drawFloatingTexts(c, gs.cameraX, gs.cameraY, scale)

        // To draw Arrow
        drawArrow(c, scale, gs, viewportW, viewportH)
    }

    private fun drawCore(
        c: Canvas,
        scale: Float,
        gs: GameState,
        targetW: Float,
        screenPlayerX: Float,
        screenPlayerY: Float,
    ) {
        val screenCoreX = gs.coreX - gs.cameraX
        val screenCoreY = gs.coreY - gs.cameraY

        // --- 1. DETECT LEVEL FEATURES ---
        val config = com.appsbyalok.echohunter.data.LevelEngine.getLevelConfig(gs.currentLevel)
        val isDefense =
            config.features.contains(com.appsbyalok.echohunter.data.LevelFeature.DEFENSE) && gs.gameMode == GameModeId.CAMPAIGN
        val isEscape = config.features.contains(com.appsbyalok.echohunter.data.LevelFeature.ESCAPE) && gs.gameMode == GameModeId.CAMPAIGN

        // --- 2. SCREEN OFF-BOUNDS ARROW INDICATOR (STORY MODE GATEWAY ONLY) ---
        if (screenCoreX > targetW - scale * 0.1f && gs.state == AppStateId.CORE_MERGE) {
            val arrowX = targetW - scale * 0.06f
            val alpha = ((sin(gs.timeSinceStart * 10f) + 1f) / 2f * 155 + 100).toInt()

            p.color = (alpha shl 24) or 0xFFFF00
            p.style = Paint.Style.FILL

            arrowPath.reset()
            arrowPath.moveTo(arrowX - scale * 0.04f, screenCoreY - scale * 0.03f)
            arrowPath.lineTo(arrowX + scale * 0.02f, screenCoreY)
            arrowPath.lineTo(arrowX - scale * 0.04f, screenCoreY + scale * 0.03f)
            arrowPath.lineTo(arrowX - scale * 0.02f, screenCoreY)
            arrowPath.close()
            c.drawPath(arrowPath, p)

            pText.color = (alpha shl 24) or 0xFFFF00
            pText.textSize = scale * 0.035f
            pText.textAlign = Paint.Align.RIGHT

            val dist = ((screenCoreX - targetW) / scale * 10).toInt()
            if (dist != lastCoreDist) {
                lastCoreDist = dist
                coreDistStr = context.getString(R.string.ui_core_signal, dist)
            }
            c.drawText(coreDistStr, arrowX - scale * 0.05f, screenCoreY + scale * 0.01f, pText)
            return // If arrow is visible, skip base structure rendering
        }

        // --- 3. PREMIUM CORE BASE RENDERING MATRIX ---
        if (gs.coreRadius > 0f) {
            // NEW: Core is always at least 30% visible as a beacon
            val coreAlphaMult = if (gs.isDarknessLevel) {
                // If core is within sonar/aura, it's 1.0f, otherwise 0.3f beacon
                val dx = gs.coreX - gs.px
                val dy = gs.coreY - gs.py
                val d2 = dx * dx + dy * dy
                val hitByPulse = (gs.pulse && d2 in gs.innerRSq..gs.outerRSq)
                if (hitByPulse || d2 < gs.passiveAuraRadiusSq) 1.0f else 0.3f
            } else 1.0f

            val baseAlphaInt = (coreAlphaMult * 255).toInt()

            when {
                // A. DEFENSE MODE VISUALS (Purple Pulsing Shield & HP Bar)
                isDefense && gs.state != AppStateId.CORE_MERGE -> {
                    // Pulsing Shield Effect around the Core
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = scale * 0.015f
                    p.color = (baseAlphaInt shl 24) or (GameColors.SHIELD and 0xFFFFFF)
                    c.drawCircle(
                        screenCoreX,
                        screenCoreY,
                        gs.coreRadius + (sin(gs.timeSinceStart * 10f) * scale * 0.02f),
                        p
                    )

                    // Core Base Body
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = scale * 0.01f
                    p.color = (baseAlphaInt shl 24) or (GameColors.YELLOW and 0xFFFFFF)
                    c.drawCircle(screenCoreX, screenCoreY, gs.coreRadius, p)

                    p.style = Paint.Style.FILL
                    p.color = (baseAlphaInt shl 24) or (GameColors.CLARITY and 0xFFFFFF)
                    c.drawCircle(screenCoreX, screenCoreY, gs.coreRadius * 0.4f, p)

                    // HEALTH BAR LAYER (Purely for Active Defense Levels)
                    p.style = Paint.Style.FILL
                    val hpBarW = scale * 0.18f
                    val hpBarH = scale * 0.02f
                    val hpY = screenCoreY - gs.coreRadius - scale * 0.05f

                    // HP Bar BG
                    p.color = 0xFF440000.toInt()
                    c.drawRect(
                        screenCoreX - hpBarW / 2,
                        hpY,
                        screenCoreX + hpBarW / 2,
                        hpY + hpBarH,
                        p
                    )

                    // HP Bar Color shifting
                    p.color = when {
                        gs.coreHp > gs.coreMaxHp * 0.5f -> GameColors.HP
                        gs.coreHp > gs.coreMaxHp * 0.25f -> GameColors.YELLOW
                        else -> GameColors.RED
                    }
                    val currentHpW = hpBarW * (max(0f, gs.coreHp.toFloat()) / gs.coreMaxHp)
                    c.drawRect(
                        screenCoreX - hpBarW / 2,
                        hpY,
                        screenCoreX - hpBarW / 2 + currentHpW,
                        hpY + hpBarH,
                        p
                    )

                    // Cyber Border Frame
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = scale * 0.004f
                    p.color = GameColors.TEXT
                    c.drawRect(
                        screenCoreX - hpBarW / 2,
                        hpY,
                        screenCoreX + hpBarW / 2,
                        hpY + hpBarH,
                        p
                    )
                }

                // B. ESCAPE MODE VISUALS (Exit Portal Portal Logic)
                (isEscape || (gs.gameMode == GameModeId.TRAINING && gs.tutorialGateOpen)) && gs.state != AppStateId.CORE_MERGE -> {
                    if (gs.escapeGateActive) {
                        // Active Portal (Green Neon Pulse)
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = scale * 0.015f
                        p.color = GameColors.HP
                        c.drawCircle(
                            screenCoreX,
                            screenCoreY,
                            gs.coreRadius + (sin(gs.timeSinceStart * 10f) * scale * 0.02f),
                            p
                        )

                        p.style = Paint.Style.FILL
                        p.color = GameColors.PULSE
                        c.drawCircle(screenCoreX, screenCoreY, gs.coreRadius * 0.5f, p)
                    } else {
                        // Locked Portal (Dim Red / Inactive Structure)
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = scale * 0.01f
                        p.color = 0xFF550000.toInt()
                        c.drawCircle(screenCoreX, screenCoreY, gs.coreRadius, p)

                        p.style = Paint.Style.FILL
                        p.color = 0xFF330000.toInt()
                        c.drawCircle(screenCoreX, screenCoreY, gs.coreRadius * 0.3f, p)
                    }
                }

                // C. STANDARD MODE CORE / STORY MODE MERGE CORE (Default Layout)
                else -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = scale * 0.015f
                    p.color = GameColors.YELLOW
                    c.drawCircle(
                        screenCoreX,
                        screenCoreY,
                        gs.coreRadius + sin(gs.timeSinceStart * 5f) * scale * 0.03f,
                        p
                    )

                    p.style = Paint.Style.FILL
                    p.color = GameColors.CLARITY
                    c.drawCircle(screenCoreX, screenCoreY, gs.coreRadius * 0.4f, p)
                }
            }

            // --- 5. DOTTED DATA EXTRACTOR TETHER (Story Mode Merges) ---
            if (gs.state == AppStateId.CORE_MERGE) {
                val dx = screenCoreX - screenPlayerX
                val dy = screenCoreY - screenPlayerY
                val dist = sqrt(dx * dx + dy * dy)

                if (dist > scale * 0.2f) {
                    pDash.style = Paint.Style.STROKE
                    pDash.strokeWidth = scale * 0.005f
                    c.drawLine(screenPlayerX, screenPlayerY, screenCoreX, screenCoreY, pDash)
                }
            }
        }
    }

    private fun drawArrow(c: Canvas, scale: Float, gs: GameState, targetW: Float, targetH: Float) {
        val config = com.appsbyalok.echohunter.data.LevelEngine.getLevelConfig(gs.currentLevel)
        var targetX = -1f
        var targetY = -1f
        var arrowColor = GameColors.HP
        var shouldDraw = false

        // 0. BOSS TRACKING (Highest Priority)
        if (gs.bossActive) {
            targetX = gs.bossX; targetY = gs.bossY
            arrowColor = GameColors.BOSS
            shouldDraw = true
        }
        // 1. STORY MODE / BOSS MERGE
        else if (gs.state == AppStateId.CORE_MERGE) {
            targetX = gs.coreX; targetY = gs.coreY
            arrowColor = GameColors.YELLOW
            shouldDraw = true
        }
        // 2. ESCAPE MODE (When Portal is Active)
        else if (config.features.contains(com.appsbyalok.echohunter.data.LevelFeature.ESCAPE) && gs.escapeGateActive) {
            targetX = gs.coreX; targetY = gs.coreY
            arrowColor = GameColors.HP
            shouldDraw = true
        }
        // 3. DEFENSE MODE (Tracks the core)
        else if (config.features.contains(com.appsbyalok.echohunter.data.LevelFeature.DEFENSE) && gs.gameMode == GameModeId.CAMPAIGN) {
            targetX = gs.coreX; targetY = gs.coreY
            arrowColor = GameColors.SHIELD // Neon Purple
            shouldDraw = true
        }
        // 4. ELIMINATION MODE (Tracks the closest Red HVT)
        else if (config.features.contains(com.appsbyalok.echohunter.data.LevelFeature.ELIMINATION) && gs.gameMode == GameModeId.CAMPAIGN) {
            var minDist = Float.MAX_VALUE
            // Find the closest Type 3 enemy
            for (i in 0 until enemySys.n) {
                if (enemySys.vis[i] > 0.02f && enemySys.type[i] == 3) {
                    val dx = enemySys.ex[i] - gs.px
                    val dy = enemySys.ey[i] - gs.py
                    val distSq = dx * dx + dy * dy
                    if (distSq < minDist) {
                        minDist = distSq
                        targetX = enemySys.ex[i]
                        targetY = enemySys.ey[i]
                    }
                }
            }
            if (minDist != Float.MAX_VALUE) {
                arrowColor = GameColors.RED
                shouldDraw = true
            }
        }

        // IF THERE IS A TARGET, DRAW ARROW
        if (shouldDraw) {
            val dx = targetX - gs.px
            val dy = targetY - gs.py
            val distSq = dx * dx + dy * dy
            val hideRadius = min(targetW, targetH) * 0.4f

            // Only show when target is away from player's screen
            if (distSq > hideRadius * hideRadius) {
                val angle = kotlin.math.atan2(dy, dx)
                val arrowDist = min(targetW, targetH) * 0.35f // Distance from screen center

                val arrowScreenX = targetW / 2f + cos(angle) * arrowDist
                val arrowScreenY = targetH / 2f + sin(angle) * arrowDist

                val alpha = (abs(sin(gs.timeSinceStart * 5f)) * 155 + 100).toInt()

                p.style = Paint.Style.FILL
                p.color = (alpha shl 24) or (arrowColor and 0xFFFFFF)

                // Triangle Shape
                arrowPath.reset()
                arrowPath.moveTo(
                    arrowScreenX + cos(angle) * scale * 0.04f,
                    arrowScreenY + sin(angle) * scale * 0.04f
                )
                arrowPath.lineTo(
                    arrowScreenX + cos(angle + 2.5f) * scale * 0.03f,
                    arrowScreenY + sin(angle + 2.5f) * scale * 0.03f
                )
                arrowPath.lineTo(
                    arrowScreenX + cos(angle - 2.5f) * scale * 0.03f,
                    arrowScreenY + sin(angle - 2.5f) * scale * 0.03f
                )
                arrowPath.close()
                c.drawPath(arrowPath, p)

                // Distance Text
                pText.color = (alpha shl 24) or (arrowColor and 0xFFFFFF)
                pText.textSize = scale * 0.035f
                pText.textAlign = Paint.Align.CENTER
                val distStr = (sqrt(distSq) / scale * 10).toInt()
                c.drawText("$distStr M", arrowScreenX, arrowScreenY - scale * 0.04f, pText)
            }
        }
    }
}
