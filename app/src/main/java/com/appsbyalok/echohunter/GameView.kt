package com.appsbyalok.echohunter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.media.ToneGenerator
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// The main loop that manages Input and Rendering, delegating logic to internal systems
class GameView(context: Context) : View(context) {

    // --- Systems & State ---
    private val gs = GameState()
    private val effectSys = EffectSystem()
    private val enemySys = EnemySystem()
    private val collisionSys = CollisionSystem(gs, effectSys, enemySys)

    // --- PAINTS ---
    private val p = Paint().apply { isAntiAlias = true }
    private val pGlow = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    private val pText = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val firewallPath = Path()

    // --- GC Optimizations ---
    private val pauseRect = RectF()
    private var lastScore = -1; private var scoreStr = "DATA: 0 TB"
    private var lastCombo = -1; private var comboStr = ""
    private var lastSector = -1; private var sectorStr = ""; private var targetStr = ""
    private var lastWave = -1; private var waveStr = ""

    private val menuTitles = arrayOf("ENDLESS WAVES", "STORY: SECTOR CLEANUP", "FIREWALL BREACH")
    private val menuTitlesPressed = arrayOf("> ENDLESS WAVES <", "> STORY: SECTOR CLEANUP <", "> FIREWALL BREACH <")
    private val menuSubs = arrayOf(
        "Survive infinite waves. Boss every 5 waves.",
        "Hunt 5 Mutating Sector Guardians.",
        "Treadmill Escape. Dodge gates and firewall."
    )

    private var pressedMenuIndex = -1
    private var lastFrameTime = System.nanoTime()
    private var isInitialized = false

    init {
        EchoAudioManager.init()
    }

    fun saveState(b: Bundle) = gs.saveState(b)
    fun restoreState(b: Bundle) = gs.restoreState(b)

    fun pauseGame() {
        if (gs.state == 1) changeState(2)
    }

    private fun changeState(newState: Int) {
        gs.state = newState
        gs.stateTimer = 0f
        pressedMenuIndex = -1
    }

    fun handleBackPressed(): Boolean {
        return when (gs.state) {
            5, 7 -> { changeState(0); true }
            1 -> { changeState(2); true }
            2, 3, 4, 6 -> { changeState(0); true }
            else -> false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gs.px = w / 2f; gs.py = h / 2f
        gs.targetPx = gs.px; gs.targetPy = gs.py
        gs.baseWorldSpeed = w * 0.2f

        enemySys.respawnAll(gs, w.toFloat(), h.toFloat())

        // Init obstacles
        var startX = w.toFloat()
        for (i in 0 until gs.obsCount) {
            startX += (w * 0.6f)
            gs.obsX[i] = startX
            gs.randomizeObstacle(i, h.toFloat())
        }
        isInitialized = true
    }

    private fun resetGame() {
        gs.resetGame()
        effectSys.reset()

        var startX = width.toFloat()
        for (i in 0 until gs.obsCount) {
            startX += (width * 0.6f)
            gs.obsX[i] = startX
            gs.randomizeObstacle(i, height.toFloat())
        }

        enemySys.respawnAll(gs, width.toFloat(), height.toFloat())

        gs.targetPx = width / 2f; gs.targetPy = height / 2f
        gs.px = gs.targetPx; gs.py = gs.targetPy
        changeState(1)
        lastFrameTime = System.nanoTime()
    }

    private var currentStoryLines = StoryProtocol.storyIntroLines
    private var storyStep = 0

    private fun setStoryState(lines: Array<String>, nextSt: Int) {
        currentStoryLines = lines
        storyStep = 0
        gs.nextStateAfterStory = nextSt
        changeState(if (nextSt == 6 || nextSt == 0 && gs.hp <= 0) 4 else 7)
        if (nextSt == 6) changeState(6)
        gs.timeSinceStart = 0f
    }

    private fun addScore(points: Int) {
        gs.score += points
    }

    private fun handleDamage(scale: Float) {
        gs.hp--
        gs.combo = 0
        gs.comboBreakTimer = 1.0f
        gs.damageFlash = 1f
        gs.shakeAmount = scale * 0.08f
        gs.playerIframe = 1.5f
        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 150)
        StoryProtocol.showIngameMessage("SYSTEM DAMAGE DETECTED", 1.5f)
        if (gs.hp <= 0) {
            SaveManager.addData(gs.score)
            changeState(4)
        }
    }

    private fun triggerBoss(type: Int, scale: Float) {
        gs.bossActive = true; gs.bossType = type; gs.bossMaxHp = 3 + gs.wave + gs.currentSector
        gs.bossHp = gs.bossMaxHp; gs.bossX = gs.cameraX + width + scale * 0.1f; gs.bossY = height / 2f
        gs.shakeAmount = scale * 0.08f; gs.damageFlash = 0.3f

        // SLOW-MO LOGIC
        StoryProtocol.startBossIntro(type)
        StoryProtocol.showIngameMessage(StoryProtocol.currentBossName, 4f)
        gs.slowMoTimer = 3.0f // 3 second slow motion

        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_ALERT, 400)
        enemySys.spawnSwarmIfNeeded(gs, width.toFloat(), height.toFloat())
    }

    // ---------- MAIN LOOP ----------
    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        var dt = (now - lastFrameTime) / 1_000_000_000f
        lastFrameTime = now
        if (dt > 0.05f) dt = 0.05f

        updateLogic(dt)
        renderScene(c, dt)
        invalidate()
    }

    private fun updateLogic(dt: Float) {
        gs.timeSinceStart += dt
        gs.stateTimer += dt
        StoryProtocol.update(dt)

        if (gs.state != 1 || !isInitialized) return

        val wF = width.toFloat(); val hF = height.toFloat(); val scale = min(width, height).toFloat()

        gs.updateTimers(dt)
        gs.updatePlayerMovement(dt, hF, scale)
        gs.updateCameraAndFirewall(dt, wF, scale)

        gs.updateVisibilityMath(scale, max(width, height) * 0.75f)
        gs.updatePulseRadius(dt, max(width, height) * 0.75f)

        effectSys.recordTrail(gs.px, gs.py)
        effectSys.update(dt, scale)

        enemySys.updateEnemies(dt, gs, wF, hF, scale)
        enemySys.updateBoss(dt, gs, wF,scale)
        enemySys.updatePowerups(dt, gs, wF, hF)

        collisionSys.checkCollisions(wF, hF, scale, ::handleDamage, ::addScore) { perfectEnd ->
            SaveManager.addData(gs.score)
            if (perfectEnd) setStoryState(StoryProtocol.storyPerfectEnding, 6)
            else setStoryState(StoryProtocol.storyNeutralEnding, 6)
        }

        checkModeProgression(scale)
        handleAudioBeats(dt)
    }

    private fun checkModeProgression(scale: Float) {
        if (gs.gameMode == 0 && gs.score > gs.wave * 50 && !gs.bossActive ) {
            gs.wave++; gs.sectorFlash = 0.5f
            StoryProtocol.showIngameMessage(StoryProtocol.endlessPopups[Random.nextInt(StoryProtocol.endlessPopups.size)])
            if (gs.wave % 5 == 0) triggerBoss(Random.nextInt(0, 5), scale)
        } else if (gs.gameMode == 1) {
            if (gs.score >= gs.sectorTarget && !gs.bossActive) {
                when(gs.currentSector) {
                    1 -> triggerBoss(0, scale)
                    2 -> setStoryState(StoryProtocol.storyMidLines, 1).also { triggerBoss(1, scale) }
                    3 -> { StoryProtocol.showIngameMessage("SECTOR CORRUPTED."); triggerBoss(2, scale) }
                    4 -> { StoryProtocol.showIngameMessage("SYSTEM FAILING."); triggerBoss(3, scale) }
                    5 -> triggerBoss(4, scale)
                }
            }
            if (Random.nextDouble() < 0.005) StoryProtocol.triggerRandomGlitch(gs.score, gs.gameMode)
        } else if (gs.gameMode == 2) {
            if (gs.score > 0 && gs.score % 100 == 0) {
                StoryProtocol.showIngameMessage(StoryProtocol.firewallPopups[Random.nextInt(StoryProtocol.firewallPopups.size)])
            }
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

    // ---------- RENDERING METHODS ----------
    private var sdx = 0f; private var sdy = 0f
    private fun renderScene(c: Canvas, dt: Float) {
        var currentBgColor = if (gs.difficulty == 1) 0xFF1A0505.toInt() else GameColors.BG
        if (StoryProtocol.isGlitchActive && Random.nextDouble() > 0.8) currentBgColor = 0xFF2A0000.toInt()
        c.drawColor(currentBgColor)

        if (!isInitialized) return

        if (gs.shakeAmount > 1f || StoryProtocol.isGlitchActive) {
            val totalShake = gs.shakeAmount + (if(StoryProtocol.isGlitchActive) 10f else 0f)
            sdx = (Random.nextFloat() - 0.5f) * totalShake
            sdy = (Random.nextFloat() - 0.5f) * totalShake
            c.translate(sdx, sdy)
            gs.shakeAmount *= 0.85f
        }

        drawGrid(c)

        when (gs.state) {
            5, 7, 6 -> drawStory(c, currentStoryLines)
            4 -> drawStory(c, StoryProtocol.badEndingLines)
            0 -> drawMenu(c)
            1 -> drawGamePlay(c, dt)
            2 -> drawPause(c)
            3 -> drawHelp(c)
        }

        if (gs.damageFlash > 0.05f) {
            c.drawColor(((gs.damageFlash * 100).toInt() shl 24) or (GameColors.RED and 0xFFFFFF))
            gs.damageFlash *= (1f - 5f * dt)
        }
        if (gs.sectorFlash > 0.05f) {
            c.drawColor(((gs.sectorFlash * 80).toInt() shl 24) or (GameColors.PULSE and 0xFFFFFF))
            gs.sectorFlash *= (1f - 5f * dt)
        }
        if (gs.empFlashTimer > 0f && Random.nextDouble() < 0.3) {
            c.drawColor(Color.argb(220, 5, 5, 5))
        }

        if (gs.shakeAmount > 1f || StoryProtocol.isGlitchActive) c.translate(-sdx, -sdy)
    }

    private fun drawGrid(c: Canvas) {
        p.color = if (gs.difficulty == 1) 0xFF330A0A.toInt() else GameColors.GRID; p.strokeWidth = 2f
        val gap = min(width, height) / 8f
        val offsetX = -(gs.cameraX % gap)
        var i = -gap + offsetX
        while (i < width + gap) { c.drawLine(i, 0f, i, height.toFloat(), p); i += gap }
        var j = -gap
        while (j < height + gap) { c.drawLine(0f, j, width.toFloat(), j, p); j += gap }
    }

    private fun drawGamePlay(c: Canvas, dt: Float) {
        val scale = min(width, height).toFloat()
        val currentPlayerColor = if (gs.isOverclocked) GameColors.OVERCLOCK else GameColors.PULSE
        val screenPlayerX = gs.px - gs.cameraX
        val wF = width.toFloat()

        // Passive Aura
        p.style = Paint.Style.FILL; p.color = 0x1A00FFFF
        c.drawCircle(screenPlayerX, gs.py, scale * 0.12f + sin(gs.timeSinceStart * 3f) * scale * 0.01f, p)

        // Firewall Elements (Game Mode 2)
        if (gs.gameMode == 2) {
            firewallPath.reset(); firewallPath.moveTo(0f, 0f)
            var yPos = 0f
            while (yPos <= height) {
                val waveOffset = sin(yPos * 0.02f + gs.timeSinceStart * 10f) * (scale * 0.05f)
                firewallPath.lineTo(gs.firewallOffset + waveOffset, yPos)
                yPos += 20f
            }
            firewallPath.lineTo(0f, height.toFloat()); firewallPath.close()

            p.style = Paint.Style.FILL; p.color = 0xAAFF0000.toInt()
            c.drawPath(firewallPath, p)

            for (i in 0 until gs.obsCount) {
                val screenObsX = gs.obsX[i] - gs.cameraX; val obsW = scale * 0.05f
                val isDanger = gs.obsType[i] == 1
                p.color = if (isDanger) (if (gs.difficulty == 1) 0xFF330A0A.toInt() else GameColors.GRID) else 0xFF0A330A.toInt()
                c.drawRect(screenObsX, 0f, screenObsX + obsW, gs.obsGapY[i], p)
                c.drawRect(screenObsX, gs.obsGapY[i] + gs.obsGapSize[i], screenObsX + obsW, height.toFloat(), p)

                p.color = if (isDanger) GameColors.RED else GameColors.HP
                c.drawRect(screenObsX, gs.obsGapY[i] - 10f, screenObsX + obsW, gs.obsGapY[i], p)
                c.drawRect(screenObsX, gs.obsGapY[i] + gs.obsGapSize[i], screenObsX + obsW, gs.obsGapY[i] + gs.obsGapSize[i] + 10f, p)
            }
        }

        // Radar Pulse Ring
        if (gs.pulse) {
            val alpha = (255 * (1f - (gs.pulseR / (max(width, height) * 0.75f)))).toInt()
            val colorGlow = if (StoryProtocol.isGlitchActive) GameColors.RED else if (gs.isOverclocked) GameColors.OVERCLOCK else if (gs.visionClarity > 0.3f) GameColors.PULSE else 0xFF006666.toInt()
            pGlow.color = Color.argb(max(0, alpha), Color.red(colorGlow), Color.green(colorGlow), Color.blue(colorGlow))
            pGlow.strokeWidth = scale * 0.008f
            c.drawCircle(screenPlayerX, gs.py, gs.pulseR, pGlow)
        }

        enemySys.drawEntities(c, gs, wF, scale)

        val pdx = (gs.cameraX + gs.targetPx) - gs.px
        val pdy = gs.targetPy - gs.py
        if (pdx * pdx + pdy * pdy > (scale*dt*scale*dt)) {
            pGlow.color = Color.argb(100, Color.red(currentPlayerColor), Color.green(currentPlayerColor), Color.blue(currentPlayerColor))
            pGlow.strokeWidth = scale * 0.002f
            c.drawLine(screenPlayerX, gs.py, gs.targetPx, gs.targetPy, pGlow)
        }

        effectSys.drawTrails(c, gs.cameraX, scale, currentPlayerColor)

        // Player Dot
        val shouldDrawPlayer = gs.playerIframe <= 0f || ((gs.timeSinceStart * 15).toInt() % 2 == 0)
        if (shouldDrawPlayer) {
            val playerRadius = scale * 0.015f
            p.style = Paint.Style.FILL; p.color = currentPlayerColor
            c.drawCircle(screenPlayerX, gs.py, playerRadius, p)

            p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.003f
            if (gs.shieldTimer > 0f) {
                p.color = GameColors.SHIELD; p.strokeWidth = scale * 0.006f
                c.drawCircle(screenPlayerX, gs.py, playerRadius * 3f + sin(gs.timeSinceStart * 10f) * scale * 0.005f, p)
                p.strokeWidth = scale * 0.003f
            } else p.color = currentPlayerColor
            c.drawCircle(screenPlayerX, gs.py, playerRadius * 2f, p)
        }

        effectSys.drawParticles(c, scale)
        effectSys.drawFloatingTexts(c, scale)

        if (gs.showOverclockTextTimer > 0f) {
            pText.color = GameColors.OVERCLOCK; pText.textSize = scale * 0.06f; pText.textAlign = Paint.Align.CENTER
            pText.alpha = (min(1f, gs.showOverclockTextTimer) * 255).toInt()
            pText.setShadowLayer(10f, 0f, 0f, GameColors.RED)
            c.drawText("OVERCLOCK READY!", width / 2f, height * 0.45f, pText)
            pText.alpha = 255; pText.clearShadowLayer()
        }

        if (gs.empFlashTimer <= 0.8f) drawHUD(c, scale, gs.isOverclocked)
    }

    // --- ENHANCED HUD REDESIGN (UX/UI Fixes) ---
    private fun drawHUD(c: Canvas, scale: Float, isOverclocked: Boolean) {
        val topMargin = scale * 0.06f
        val edgeMargin = scale * 0.05f

        // --- 1. TOP RIGHT: Pause Button ---
        val pauseSize = scale * 0.1f
        pauseRect.set(width - edgeMargin - pauseSize, edgeMargin, width - edgeMargin, edgeMargin + pauseSize)
        p.style = Paint.Style.FILL
        p.color = 0x33FFFFFF // Translucent background for better UX
        c.drawRoundRect(pauseRect, scale*0.02f, scale*0.02f, p)
        pText.color = GameColors.CLARITY; pText.textSize = scale*0.04f; pText.textAlign = Paint.Align.CENTER
        c.drawText("||", pauseRect.centerX(), pauseRect.centerY() + scale*0.015f, pText)

        // --- 2. TOP LEFT: Score & Data (Clean & Grouped) ---
        pText.textAlign = Paint.Align.LEFT
        if (gs.score != lastScore) { scoreStr = "DATA: ${gs.score} TB"; lastScore = gs.score }
        pText.color = GameColors.PULSE; pText.textSize = scale * 0.055f
        pText.setShadowLayer(10f, 0f, 0f, GameColors.PULSE)
        c.drawText(scoreStr, edgeMargin, topMargin + scale * 0.02f, pText)
        pText.clearShadowLayer()

        pText.color = 0xFFAAAAAA.toInt(); pText.textSize = scale * 0.035f
        c.drawText("BANKED: ${SaveManager.totalData} TB", edgeMargin, topMargin + scale * 0.07f, pText)

        // --- 3. TOP CENTER: Mission / Sector Status ---
        pText.textAlign = Paint.Align.CENTER
        val centerY = topMargin + scale * 0.02f
        if (gs.gameMode == 1) {
            if (gs.currentSector != lastSector) {
                sectorStr = "SECTOR ${gs.currentSector}"; targetStr = "TARGET: ${gs.sectorTarget} TB"
                lastSector = gs.currentSector
            }
            pText.color = GameColors.CLARITY; pText.textSize = scale * 0.04f
            c.drawText(sectorStr, width / 2f, centerY, pText)

            pText.textSize = scale * 0.03f
            pText.color = if (gs.bossActive) GameColors.RED else GameColors.YELLOW
            val subText = if (gs.bossActive) "WARNING: GUARDIAN ACTIVE" else targetStr
            if (gs.bossActive) pText.setShadowLayer(10f, 0f, 0f, GameColors.RED)
            c.drawText(subText, width / 2f, centerY + scale * 0.04f, pText)
            pText.clearShadowLayer()
        } else if (gs.gameMode == 0) {
            if (gs.wave != lastWave) { waveStr = "WAVE ${gs.wave}"; lastWave = gs.wave }
            pText.color = GameColors.CLARITY; pText.textSize = scale * 0.04f
            c.drawText(waveStr, width / 2f, centerY, pText)
        }

        // --- 4. RIGHT SIDE: HP & System Meters ---
        val barW = scale * 0.06f; val barH = scale * 0.015f; val gap = scale * 0.008f
        val metersTopY = edgeMargin + pauseSize + gap * 3
        val metersRightX = width - edgeMargin

        // A. HP Blocks (Right-aligned under pause)
        p.style = Paint.Style.FILL
        for (i in 0 until gs.maxHp) {
            p.color = if (i < gs.hp) (if (gs.hp == 1 && sin(gs.timeSinceStart * 15f) > 0) GameColors.RED else GameColors.HP) else 0xFF333333.toInt()
            val rx = metersRightX - (i + 1) * (barW + gap) + gap
            c.drawRect(rx, metersTopY, rx + barW, metersTopY + barH, p)
        }

        val totalMeterW = (gs.maxHp * (barW + gap)) - gap
        val meterLeftX = metersRightX - totalMeterW

        // B. Vision (Clarity) Bar
        val cRy = metersTopY + barH + gap
        p.color = 0xFF333333.toInt(); c.drawRect(meterLeftX, cRy, metersRightX, cRy + barH, p)
        p.color = GameColors.CLARITY; c.drawRect(meterLeftX, cRy, meterLeftX + totalMeterW * min(1f, gs.visionClarity), cRy + barH, p)

        // C. Overclock Bar
        val ocRy = cRy + barH + gap
        p.color = 0xFF333333.toInt(); c.drawRect(meterLeftX, ocRy, metersRightX, ocRy + barH * 1.5f, p)
        p.color = if (isOverclocked && sin(gs.timeSinceStart * 20f) > 0) GameColors.CLARITY else GameColors.OVERCLOCK
        c.drawRect(meterLeftX, ocRy, meterLeftX + totalMeterW * (gs.overclockMeter / 100f), ocRy + barH * 1.5f, p)

        // HUD Labels
        pText.textAlign = Paint.Align.RIGHT; pText.textSize = scale * 0.025f; pText.color = GameColors.TEXT
        c.drawText("VIS", meterLeftX - gap, cRy + barH * 0.9f, pText)
        c.drawText("OVR", meterLeftX - gap, ocRy + barH * 1.2f, pText)

        // --- 5. CENTER SCREEN DYNAMIC FEEDBACK (Combo & Warnings) ---
        if (gs.combo > 1) {
            pText.textAlign = Paint.Align.CENTER
            val bounce = sin(gs.timeSinceStart * 15f) * scale * 0.005f
            pText.textSize = scale * 0.065f + bounce
            val comboColor = when { gs.combo >= 15 -> GameColors.OVERCLOCK; gs.combo >= 8 -> GameColors.YELLOW; else -> GameColors.PULSE }
            pText.color = comboColor; pText.setShadowLayer(25f, 0f, 0f, comboColor)
            if (gs.combo != lastCombo) { comboStr = "COMBO x${gs.combo}"; lastCombo = gs.combo }

            // Placed visibly in upper-mid screen, away from top UI
            c.drawText(comboStr, width / 2f, height * 0.28f, pText)
            pText.clearShadowLayer()
        }

        if (gs.comboBreakTimer > 0f) {
            pText.textAlign = Paint.Align.CENTER; pText.textSize = scale * 0.05f; pText.color = GameColors.RED
            pText.alpha = (gs.comboBreakTimer * 255).toInt()
            c.drawText("COMBO BROKEN", width / 2f, height * 0.35f, pText)
            pText.alpha = 255
        }

        if (StoryProtocol.popupTimer > 0f) {
            pText.textAlign = Paint.Align.CENTER; pText.textSize = scale * 0.05f; pText.color = GameColors.RED
            pText.alpha = min(255, (StoryProtocol.popupTimer * 255).toInt())
            pText.setShadowLayer(20f, 0f, 0f, GameColors.RED)
            val textX = width / 2f + if(StoryProtocol.isGlitchActive) (Random.nextFloat()-0.5f)*10f else 0f
            c.drawText(StoryProtocol.currentPopup, textX, height * 0.42f, pText)
            pText.alpha = 255; pText.clearShadowLayer()
        }
    }

    private fun drawStory(c: Canvas, lines: Array<String>) {
        val scale = min(width, height).toFloat()
        if (gs.timeSinceStart > (storyStep + 1) * 1.5f && storyStep < lines.size) {
            storyStep++
            EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 50)
        }
        pText.textAlign = Paint.Align.LEFT; pText.textSize = scale * 0.04f
        pText.color = when (gs.state) {
            4 -> GameColors.RED
            6 -> if (currentStoryLines === StoryProtocol.storyPerfectEnding) GameColors.YELLOW else GameColors.HP
            else -> GameColors.PULSE
        }
        var y = height * 0.3f
        val maxLinesToDraw = min(storyStep, lines.size)
        for (i in 0 until maxLinesToDraw) {
            c.drawText(lines[i], width * 0.1f, y, pText)
            y += scale * 0.08f
        }
        if (storyStep >= lines.size && gs.stateTimer > 1.5f) {
            val alpha = ((sin(gs.timeSinceStart * 5.0) + 1) / 2 * 155 + 100).toInt()
            pText.color = Color.argb(alpha, 255, 255, 255); pText.textAlign = Paint.Align.CENTER
            c.drawText("TAP BOTTOM HALF TO CONTINUE", width / 2f, height * 0.85f, pText)
        }
    }

    private fun drawMenu(c: Canvas) {
        val scale = min(width, height).toFloat()
        pText.color = if (gs.difficulty == 0) GameColors.TEXT else GameColors.RED
        pText.textAlign = Paint.Align.LEFT; pText.textSize = scale * 0.045f
        pText.setShadowLayer(15f, 0f, 0f, (if (gs.difficulty == 0) GameColors.PULSE else GameColors.RED))
        c.drawText(if(gs.difficulty == 0) "[ MODE: EASY ]" else "[ MODE: HARD ]", scale * 0.05f, scale * 0.1f, pText)
        pText.clearShadowLayer()

        pText.color = GameColors.TEXT; pText.textAlign = Paint.Align.RIGHT
        pText.setShadowLayer(15f, 0f, 0f, GameColors.PULSE)
        c.drawText("HELP [?]", width - scale * 0.05f, scale * 0.1f, pText)
        pText.clearShadowLayer()

        pText.textAlign = Paint.Align.CENTER; pText.textSize = scale * 0.15f; pText.letterSpacing = 0.05f
        pText.color = GameColors.PULSE; pText.setShadowLayer(30f, 0f, 0f, GameColors.PULSE)
        c.drawText("ECHO", width / 2f, height * 0.22f, pText)
        pText.color = GameColors.RED; pText.setShadowLayer(30f, 0f, 0f, GameColors.RED)
        c.drawText("HUNTER", width / 2f, height * 0.32f, pText)
        pText.clearShadowLayer()

        pText.textSize = scale * 0.035f; pText.color = 0xFFAAAAAA.toInt(); pText.letterSpacing = 0.15f
        c.drawText("- SELECT PROTOCOL MISSION -", width / 2f, height * 0.44f, pText)
        pText.letterSpacing = 0f

        val startY = height * 0.50f; val btnHeight = height * 0.11f; val gap = height * 0.03f
        for (i in 0..2) {
            val btnTop = startY + i * (btnHeight + gap); val btnCenterY = btnTop + btnHeight / 2f
            val isPressed = (pressedMenuIndex == i)
            pText.textSize = if (isPressed) scale * 0.055f else scale * 0.045f
            pText.color = if (isPressed) GameColors.CLARITY else GameColors.PULSE
            pText.setShadowLayer(if(isPressed) 25f else 8f, 0f, 0f, GameColors.PULSE)
            c.drawText(if (isPressed) menuTitlesPressed[i] else menuTitles[i], width / 2f, btnCenterY - scale * 0.01f, pText)
            pText.clearShadowLayer()
            pText.textSize = scale * 0.03f
            pText.color = if (isPressed) GameColors.PULSE else 0xFF888888.toInt()
            c.drawText(menuSubs[i], width / 2f, btnCenterY + scale * 0.04f, pText)
        }
    }

    private fun drawHelp(c: Canvas) {
        val scale = min(width, height).toFloat()
        pText.textAlign = Paint.Align.CENTER; pText.textSize = scale * 0.08f; pText.color = GameColors.PULSE
        pText.setShadowLayer(20f, 0f, 0f, GameColors.PULSE)
        c.drawText("ECHO PROTOCOL", width / 2f, height * 0.12f, pText)
        pText.clearShadowLayer()

        pText.color = GameColors.TEXT; pText.textSize = scale * 0.035f
        val lh = scale * 0.055f; var sy = height * 0.20f
        c.drawText("Tap anywhere to Pulse. Drag anywhere to Move.", width / 2f, sy, pText); sy += lh
        c.drawText("Pulsing reveals enemies and builds Combos!", width / 2f, sy, pText); sy += lh
        c.drawText("Rapid pulsing causes blurry vision.", width / 2f, sy, pText); sy += lh
        sy += lh

        pText.color = GameColors.YELLOW; c.drawText("YELLOW: Lost Data Fragments", width / 2f, sy, pText); sy += lh
        pText.color = GameColors.COOLANT; c.drawText("BLUE: System Memory Shard (Slows Firewall)", width / 2f, sy, pText); sy += lh
        pText.color = GameColors.RED; c.drawText("RED: Corrupted AI Entity (AVOID!)", width / 2f, sy, pText); sy += lh
        sy += lh
        pText.color = GameColors.OVERCLOCK; c.drawText("--- OVERCLOCK METER ---", width / 2f, sy, pText); sy += lh
        pText.color = GameColors.TEXT
        c.drawText("Build combos to fill the meter.", width / 2f, sy, pText); sy += lh
        c.drawText("When full, ram enemies to DESTROY them!", width / 2f, sy, pText)

        val alpha = ((sin(gs.timeSinceStart * 5.0) + 1) / 2 * 155 + 100).toInt()
        pText.color = Color.argb(alpha, 255, 255, 255); pText.setShadowLayer(10f, 0f, 0f, GameColors.CLARITY)
        c.drawText("[ TAP TO RETURN ]", width / 2f, height * 0.90f, pText)
        pText.clearShadowLayer()
    }

    private fun drawPause(c: Canvas) {
        val scale = min(width, height).toFloat()
        pText.textAlign = Paint.Align.CENTER; pText.textSize = scale * 0.1f; pText.color = GameColors.TEXT
        pText.setShadowLayer(20f, 0f, 0f, GameColors.PULSE)
        c.drawText("SYSTEM PAUSED", width / 2f, height * 0.4f, pText)
        pText.clearShadowLayer()

        pText.textSize = scale * 0.05f
        val alpha = ((sin(gs.timeSinceStart * 5.0) + 1) / 2 * 155 + 100).toInt()
        pText.color = Color.argb(alpha, 0, 255, 255); pText.setShadowLayer(15f, 0f, 0f, GameColors.PULSE)
        c.drawText("> RESUME <", width / 2f, height * 0.6f, pText)
        pText.clearShadowLayer()

        pText.color = GameColors.RED; pText.alpha = 255; pText.setShadowLayer(15f, 0f, 0f, GameColors.RED)
        c.drawText("> MAIN MENU <", width / 2f, height * 0.75f, pText)
        pText.clearShadowLayer()
    }

    private fun triggerPulseAction() {
        val scale = min(width, height).toFloat()
        if (gs.cooldownTimer <= 0f) {
            gs.pulse = true; gs.pulseR = 0f; gs.cooldownTimer = 0.25f
            gs.visionClarity = max(0.0f, gs.visionClarity - 0.25f)

            val pts = 1 + gs.combo
            addScore(pts)
            gs.combo++
            if (gs.combo > gs.maxCombo) gs.maxCombo = gs.combo

            val floatingColor = when {
                gs.combo >= 15 -> GameColors.OVERCLOCK
                gs.combo >= 8 -> GameColors.YELLOW
                else -> GameColors.PULSE
            }
            effectSys.spawnFloatingText(gs.px - gs.cameraX, gs.py - (scale * 0.05f), pts, floatingColor)

            if (gs.combo % 10 == 0) EchoAudioManager.playSound(ToneGenerator.TONE_SUP_CONFIRM, 150)
            else EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 50)

            if (gs.overclockTimer <= 0f) {
                val multiplier = 1f + (gs.combo * 0.1f)
                gs.overclockMeter = min(100f, gs.overclockMeter + (pts * 1.5f * multiplier))
                if (gs.overclockMeter >= 100f) {
                    gs.overclockTimer = 5f
                    EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_ALERT, 200)
                    gs.shakeAmount = scale * 0.08f; gs.sectorFlash = 0.5f; gs.showOverclockTextTimer = 2.0f
                }
            }
        } else {
            if(gs.combo > 5) {
                gs.comboBreakTimer = 1.0f
                EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 100)
            }
            gs.combo = 0
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val scale = min(width, height).toFloat()
        val action = e.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> gs.isTouching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gs.isTouching = false
        }

        when (gs.state) {
            5, 7, 4, 6 -> if (action == MotionEvent.ACTION_UP) {
                if (gs.stateTimer < 1.5f) return true
                if (e.y < height / 2f) return true
                if (storyStep < currentStoryLines.size) storyStep = currentStoryLines.size
                else {
                    if (gs.state == 7) changeState(gs.nextStateAfterStory)
                    else changeState(0)
                }
            }

            0 -> {
                val startY = height * 0.50f; val btnHeight = height * 0.11f; val gap = height * 0.03f
                when (action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        pressedMenuIndex = -1
                        for (i in 0..2) {
                            val btnTop = startY + i * (btnHeight + gap)
                            if (e.y in btnTop..(btnTop + btnHeight) && e.x > width * 0.05f && e.x < width * 0.95f) {
                                pressedMenuIndex = i; break
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (e.x < scale * 0.35f && e.y < scale * 0.15f) {
                            gs.difficulty = 1 - gs.difficulty
                            EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 50)
                        }
                        else if (e.x > width * 0.7f && e.y < height * 0.2f) changeState(3)
                        else if (pressedMenuIndex != -1) {
                            gs.gameMode = pressedMenuIndex
                            when(gs.gameMode) {
                                0 -> setStoryState(StoryProtocol.endlessIntroLines, 1)
                                1 -> setStoryState(StoryProtocol.storyIntroLines, 1)
                                2 -> setStoryState(StoryProtocol.firewallIntroLines, 1)
                            }
                            resetGame()
                            EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 50)
                        }
                        pressedMenuIndex = -1
                    }
                    MotionEvent.ACTION_CANCEL -> pressedMenuIndex = -1
                }
                return true
            }

            3 -> if (action == MotionEvent.ACTION_UP) changeState(0)

            2 -> { // --- ENHANCED PAUSE UX FIX ---
                val edgeMargin = scale * 0.05f
                val pauseSize = scale * 0.1f
                val isClickingPause = e.x > width - edgeMargin - pauseSize && e.y < edgeMargin + pauseSize

                if (action == MotionEvent.ACTION_UP && gs.stateTimer > 0.2f) {
                    if (e.y > height * 0.68f) {
                        changeState(0) // Menu
                    } else if (isClickingPause || (e.y > height * 0.5f && e.y < height * 0.68f)) {
                        changeState(1) // Resume
                        lastFrameTime = System.nanoTime()
                    }
                }
            }

            1 -> {
                // Adjust Pause Hitbox for new location
                val edgeMargin = scale * 0.05f
                val pauseSize = scale * 0.1f
                val isClickingPause = e.x > width - edgeMargin - pauseSize && e.y < edgeMargin + pauseSize

                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    if (!isClickingPause) {
                        if (StoryProtocol.areControlsInverted) {
                            val screenPlayerX = gs.px - gs.cameraX
                            gs.targetPx = screenPlayerX - (e.x - screenPlayerX)
                            gs.targetPy = gs.py - (e.y - gs.py)
                        } else {
                            gs.targetPx = e.x; gs.targetPy = e.y
                        }
                        val playerRadius = scale * 0.015f
                        if (gs.targetPy < playerRadius) gs.targetPy = playerRadius
                        if (gs.targetPy > height - playerRadius) gs.targetPy = height - playerRadius
                    }
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    if (!isClickingPause) triggerPulseAction()
                }
                if (action == MotionEvent.ACTION_UP && isClickingPause) {
                    pauseGame()
                    return true
                }
            }
        }
        return true
    }
}