package com.appsbyalok.echohunter.view.renderers

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.engine.GameModeId
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.input.AttackMode
import com.appsbyalok.echohunter.input.HudAction
import com.appsbyalok.echohunter.input.HudVisualType
import com.appsbyalok.echohunter.input.ResolvedHudControl
import com.appsbyalok.echohunter.utils.GameColors
import kotlin.math.cos
import kotlin.math.sin

class HUDRenderer(private val context: Context) {
    private val p = Paint().apply { isAntiAlias = true }
    private val pText = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val rectPopup = RectF()

    fun drawHUD(c: Canvas, scale: Float, gs: GameState, targetW: Float, targetH: Float) {
        gs.modeStrategy.drawModeSpecificHUD(context, c, gs, targetW, targetH, scale, pText)

        // --- GLITCH OVERLAY (Low Integrity) ---
        drawIntegrityGlitches(c, targetW, targetH, scale)

        // --- 0. TOP OVERLAY (Gradient & HP Bar) ---
        drawTopOverlay(c, scale, gs, targetW)

        // --- 1. STATS & STATUS ---
        drawHealthAndScore(c, scale, gs)
        drawTopStatus(c, scale, gs, targetW)

        // --- 2. MOVEMENT JOYSTICK ---
        if (gs.controls.isMoveJoyActive) {
            p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.01f; p.color = 0x55FFFFFF
            c.drawCircle(gs.touch.moveBaseX, gs.touch.moveBaseY, scale * 0.15f, p)
            p.style = Paint.Style.FILL; p.color = 0xAAFFFFFF.toInt()
            c.drawCircle(gs.touch.moveKnobX, gs.touch.moveKnobY, scale * 0.05f, p)
        }

        // --- 3. MANUAL AIM TOUCHPAD (Visual Boundary) ---
        val manualAimUnlocked = SaveManager.isNodeUnlocked("sys_aim_manual")
        if (manualAimUnlocked && gs.controls.activeAttackMode == AttackMode.MANUAL_AIM &&
            (gs.gameMode != GameModeId.TRAINING || HudAction.ATTACK in gs.tutorialEnabledActions)) {
            p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.001f; p.color = 0x11FF0000
            if (gs.hudLayout.manualAimMode == com.appsbyalok.echohunter.input.MovementMode.STATIC) {
                c.drawCircle(gs.hudLayout.manualAimX, gs.hudLayout.manualAimY, gs.hudLayout.manualAimRadius, p)
            } else {
                c.drawRect(gs.hudLayout.manualAimZone, p)
            }
        }

        // --- 4. ACTION BUTTONS ---
        var resolvedAtk: ResolvedHudControl? = null
        var resolvedTrap: ResolvedHudControl? = null
        var resolvedSonar: ResolvedHudControl? = null

        gs.hudLayout.controls.forEach { resolved ->
            if (gs.gameMode == GameModeId.TRAINING && resolved.control.action !in gs.tutorialEnabledActions) return@forEach
            when (resolved.control.action) {
                HudAction.ATTACK -> {
                    resolvedAtk = resolved
                    drawAttackUI(c, scale, gs, resolved)
                }
                HudAction.OVERCLOCK -> drawOverclockButton(c, gs, resolved)
                HudAction.TRAP -> {
                    resolvedTrap = resolved
                    drawTrapButton(c, gs, resolved)
                }
                HudAction.SONAR -> if (gs.isDarknessLevel || StoryProtocol.isBlackoutActive) {
                    resolvedSonar = resolved
                    drawSonarButton(c, gs, resolved)
                }
                HudAction.PAUSE -> drawPauseButton(c, resolved)
            }
        }

        // --- 5. RADIAL MENUS (Upper Arc Distribution) ---
        if (gs.controls.isWeaponMenuOpen && (gs.gameMode != GameModeId.TRAINING || HudAction.ATTACK in gs.tutorialEnabledActions)) {
            if (SaveManager.isNodeUnlocked("sys_carry_w")) {
                val weapons = SaveManager.unlockedWeapons
                val labels = weapons.map { id ->
                    when (id) {
                        0 -> "BLAST"
                        1 -> "SPIKE"
                        2 -> "SLUG"
                        else -> "WPN"
                    }
                }.toTypedArray()
                val colors = weapons.map { id ->
                    when (id) {
                        0 -> GameColors.YELLOW
                        1 -> GameColors.TEXT
                        2 -> GameColors.PULSE
                        else -> GameColors.TEXT
                    }
                }.toIntArray()

                drawRadialMenu(c, scale, resolvedAtk?.x ?: gs.hudLayout.atkX, resolvedAtk?.y ?: gs.hudLayout.atkY, 
                    labels, colors, gs.controls.selectedWeaponIdx)
            }
        }
        if (gs.controls.isTrapMenuOpen && (gs.gameMode != GameModeId.TRAINING || HudAction.TRAP in gs.tutorialEnabledActions)) {
            if (SaveManager.isNodeUnlocked("sys_carry_t")) {
                val traps = SaveManager.unlockedTraps
                val labels = traps.map { id ->
                    when (id) {
                        1 -> "DECOY"
                        2 -> "EMP"
                        0 -> "CAMO"
                        else -> "TRAP"
                    }
                }.toTypedArray()
                val colors = traps.map { id ->
                    when (id) {
                        1 -> GameColors.OVERCLOCK
                        2 -> GameColors.SHIELD
                        0 -> GameColors.TEXT
                        else -> GameColors.TEXT
                    }
                }.toIntArray()

                drawRadialMenu(c, scale, resolvedTrap?.x ?: gs.hudLayout.trapX, resolvedTrap?.y ?: gs.hudLayout.trapY, 
                    labels, colors, gs.controls.selectedTrapIdx)
            }
        }
        if (gs.controls.isSonarMenuOpen && (gs.gameMode != GameModeId.TRAINING || HudAction.SONAR in gs.tutorialEnabledActions)) {
            if (SaveManager.isNodeUnlocked("sys_aim_auto")) {
                drawRadialMenu(c, scale, resolvedSonar?.x ?: gs.hudLayout.pulseX, resolvedSonar?.y ?: gs.hudLayout.pulseY, arrayOf("MANUAL", "LOCK"), intArrayOf(GameColors.TEXT, GameColors.SHIELD), gs.controls.selectedSonarIdx)
            }
        }

        // --- 6. PAUSE & STORY POPUPS ---
        drawStoryPopups(c, scale, targetW)

        // --- 7. CAMERA FOCUS INDICATOR ---
        if (gs.cameraFocusWeight > 0.5f) {
            drawFocusIndicator(c, scale, targetW, targetH)
        }

    }

    private fun drawOverclockButton(c: Canvas, gs: GameState, resolved: ResolvedHudControl) {
        val ready = gs.overclockMeter >= 100f || gs.isOverclocked
        val progress = when {
            gs.isOverclocked -> gs.overclockMeter / 100f
            !ready -> 1f - gs.overclockMeter / 100f
            else -> 0f
        }
        drawActionButton(c, resolved.x, resolved.y, resolved.radius, "OVR", if (gs.controls.isOverclockPressed) GameColors.CLARITY else if (ready) GameColors.OVERCLOCK else 0xFF777777.toInt(), ready, progress, 0f, resolved.control.visualType)
    }

    private fun drawTrapButton(c: Canvas, gs: GameState, resolved: ResolvedHudControl) {
        val maxCooldown = 8f * com.appsbyalok.echohunter.data.UpgradeSystem.getTrapCooldownMultiplier()
        val progress = if (gs.trapCooldownTimer > 0f) gs.trapCooldownTimer / maxCooldown else 0f
        drawActionButton(c, resolved.x, resolved.y, resolved.radius, "TRAP", if (gs.controls.isTrapPressed) GameColors.CLARITY else if (gs.trapCooldownTimer <= 0f) GameColors.YELLOW else 0xFF777777.toInt(), false, progress, gs.trapCooldownTimer, resolved.control.visualType)
    }

    private fun drawSonarButton(c: Canvas, gs: GameState, resolved: ResolvedHudControl) {
        val maxCooldown = 3f * com.appsbyalok.echohunter.data.UpgradeSystem.getPulseCooldownMultiplier()
        val progress = if (gs.sonarTimer > 0f) gs.sonarTimer / maxCooldown else 0f
        drawActionButton(c, resolved.x, resolved.y, resolved.radius, "SONAR", if (gs.controls.isSonarPressed || gs.controls.isAutoSonarLocked) GameColors.CLARITY else if (gs.sonarTimer <= 0f) GameColors.PULSE else 0xFF777777.toInt(), gs.controls.isAutoSonarLocked, progress, gs.sonarTimer, resolved.control.visualType)
    }

    private fun drawPauseButton(c: Canvas, resolved: ResolvedHudControl) {
        p.style = Paint.Style.STROKE
        p.color = GameColors.YELLOW
        p.strokeWidth = resolved.radius * 0.06f
        val radius = if (resolved.control.visualType == HudVisualType.COMPACT) resolved.radius * 0.65f else resolved.radius * 0.8f
        c.drawCircle(resolved.x, resolved.y, radius, p)
        p.style = Paint.Style.FILL
        c.drawRect(resolved.x - resolved.radius * 0.13f, resolved.y - resolved.radius * 0.24f, resolved.x - resolved.radius * 0.04f, resolved.y + resolved.radius * 0.24f, p)
        c.drawRect(resolved.x + resolved.radius * 0.04f, resolved.y - resolved.radius * 0.24f, resolved.x + resolved.radius * 0.13f, resolved.y + resolved.radius * 0.24f, p)
    }

    private fun drawHealthAndScore(c: Canvas, scale: Float, gs: GameState) {
        pText.textAlign = Paint.Align.LEFT
        pText.textSize = scale * 0.035f
        pText.color = GameColors.CLARITY
        
        val statsY = scale * 0.15f // Status Bar Margin
        c.drawText("DATA: ${SaveManager.formatDataString(gs.collectedDataKB)}", scale * 0.05f, statsY, pText)

        pText.color = GameColors.YELLOW
        c.drawText("SCORE: ${gs.score}", scale * 0.05f, statsY + scale * 0.05f, pText)
        if (gs.combo > 1) {
            pText.color = GameColors.OVERCLOCK
            c.drawText("COMBO x${gs.combo}", scale * 0.05f, statsY + scale * 0.10f, pText)
        }
    }

    private fun drawTopOverlay(c: Canvas, scale: Float, gs: GameState, targetW: Float) {
        // 1. Subtle Top Gradient Fade for visibility
        val gradientH = scale * 0.22f // Reduced height to match tighter layout
        p.shader = LinearGradient(0f, 0f, 0f, gradientH, 0xCC000000.toInt(), 0, Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        c.drawRect(0f, 0f, targetW, gradientH, p)
        p.shader = null

        // 2. Full Width HP Bar at the absolute top (Status Bar area)
        val hpH = scale * 0.02f
        val hpGap = scale * 0.005f
        val totalGap = hpGap * (gs.maxHp - 1)
        val hpW = (targetW - totalGap) / gs.maxHp
        
        p.style = Paint.Style.FILL
        for(i in 0 until gs.maxHp) {
            p.color = if (i < gs.hp) GameColors.HP else 0xFF333333.toInt()
            c.drawRect(i * (hpW + hpGap), 0f, i * (hpW + hpGap) + hpW, hpH, p)
        }
    }

    private fun drawTopStatus(c: Canvas, scale: Float, gs: GameState, targetW: Float) {
        var currentY = scale * 0.08f // Start higher up
        pText.textAlign = Paint.Align.CENTER

        if (gs.isAutoPilotActive) {
            pText.color = GameColors.PULSE; pText.textSize = scale * 0.025f
            c.drawText("AUTOPILOT ACTIVE", targetW / 2f, currentY, pText)
            currentY += scale * 0.03f
        }

        // --- FIRMWARE PATCH INDICATORS ---
        val patchIconSize = scale * 0.035f
        val patchGap = scale * 0.01f
        var activePatches = 0
        if (com.appsbyalok.echohunter.data.UpgradeSystem.hasHealthSiphonPatch()) activePatches++
        if (com.appsbyalok.echohunter.data.UpgradeSystem.hasShieldBurstPatch()) activePatches++
        if (com.appsbyalok.echohunter.data.UpgradeSystem.hasOverclockRegenPatch()) activePatches++
        if (com.appsbyalok.echohunter.data.UpgradeSystem.hasCritVampPatch()) activePatches++
        if (com.appsbyalok.echohunter.data.UpgradeSystem.hasDataOverflowPatch()) activePatches++
        if (com.appsbyalok.echohunter.data.UpgradeSystem.hasComboShieldPatch()) activePatches++

        if (activePatches > 0) {
            val totalPatchesW = activePatches * patchIconSize + (activePatches - 1) * patchGap
            var startX = targetW / 2f - totalPatchesW / 2f
            val patchY = currentY

            fun drawPatchIcon(label: String, color: Int) {
                p.style = Paint.Style.FILL; p.color = color
                rectPopup.set(startX, patchY, startX + patchIconSize, patchY + patchIconSize)
                c.drawRoundRect(rectPopup, scale * 0.005f, scale * 0.005f, p)
                pText.textSize = scale * 0.02f; pText.color = 0xFFFFFFFF.toInt()
                c.drawText(label, rectPopup.centerX(), rectPopup.centerY() + scale * 0.007f, pText)
                startX += patchIconSize + patchGap
            }

            if (com.appsbyalok.echohunter.data.UpgradeSystem.hasHealthSiphonPatch()) drawPatchIcon("H", GameColors.HP)
            if (com.appsbyalok.echohunter.data.UpgradeSystem.hasShieldBurstPatch()) drawPatchIcon("S", GameColors.SHIELD)
            if (com.appsbyalok.echohunter.data.UpgradeSystem.hasOverclockRegenPatch()) drawPatchIcon("O", GameColors.OVERCLOCK)
            if (com.appsbyalok.echohunter.data.UpgradeSystem.hasCritVampPatch()) drawPatchIcon("V", GameColors.RED)
            if (com.appsbyalok.echohunter.data.UpgradeSystem.hasDataOverflowPatch()) drawPatchIcon("D", GameColors.YELLOW)
            if (com.appsbyalok.echohunter.data.UpgradeSystem.hasComboShieldPatch()) drawPatchIcon("C", GameColors.PULSE)

            currentY += patchIconSize + scale * 0.025f
        } else {
            currentY += scale * 0.02f
        }

        // BOSS UI replaces Objective Bar when active
        if (gs.bossActive && gs.bossHp > 0) {
            drawBossUI(c, scale, gs, targetW, currentY)
        } else if (gs.objectiveLabel.isNotEmpty()) {
            // Priority: Draw Objective Text and Progress
            pText.color = GameColors.YELLOW; pText.textSize = scale * 0.028f
            c.drawText(gs.objectiveLabel, targetW / 2f, currentY, pText)
            currentY += scale * 0.02f

            val barW = scale * 0.5f 
            val barH = scale * 0.012f
            val barX = targetW / 2f - barW / 2f

            // Objective Progress Bar
            p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.002f; p.color = 0x88FFCC00.toInt()
            c.drawRect(barX, currentY, barX + barW, currentY + barH, p)
            p.style = Paint.Style.FILL; p.color = GameColors.YELLOW
            c.drawRect(barX, currentY, barX + barW * gs.objectiveProgress, currentY + barH, p)
        }
    }

    private fun drawAttackUI(c: Canvas, scale: Float, gs: GameState, resolved: ResolvedHudControl) {
        val atkX = resolved.x
        val atkY = resolved.y
        val radius = resolved.radius
        val mode = gs.controls.activeAttackMode

        when (mode) {
            AttackMode.DIRECTIONAL -> {
                val isCharging = gs.controls.currentWeapon == 2 && gs.controls.isSniperCharging
                val color = if (isCharging) GameColors.YELLOW else if (gs.controls.attackRequested) GameColors.TEXT else GameColors.RED
                drawActionButton(c, atkX, atkY, radius, "ATK", color, false, visualType = resolved.control.visualType)
                drawDirectionalAimFeedback(c, scale, gs, atkX, atkY, radius, color, isCharging)
            }
            AttackMode.AUTO_AIM -> {
                val isCharging = gs.controls.currentWeapon == 2 && gs.controls.isSniperCharging
                val baseColor = if (gs.controls.attackRequested) GameColors.OVERCLOCK else GameColors.SHIELD
                val color = if (isCharging) GameColors.RED else baseColor
                
                drawActionButton(c, atkX, atkY, radius, "", color, false, visualType = resolved.control.visualType)
                
                // Charge Indicator Ring for Sniper
                if (isCharging) {
                    p.style = Paint.Style.STROKE; p.strokeWidth = radius * 0.1f; p.color = GameColors.YELLOW
                    val chargeRect = RectF(atkX - radius * 0.9f, atkY - radius * 0.9f, atkX + radius * 0.9f, atkY + radius * 0.9f)
                    c.drawArc(chargeRect, -90f, 360f * (gs.controls.sniperCharge / 1.5f), false, p)
                }

                // Technical Tracking Icon
                p.style = Paint.Style.STROKE; p.strokeWidth = radius * 0.05f; p.color = color
                c.drawCircle(atkX, atkY, radius * 0.35f, p)
                c.drawLine(atkX - radius * 0.55f, atkY, atkX - radius * 0.2f, atkY, p)
                c.drawLine(atkX + radius * 0.55f, atkY, atkX + radius * 0.2f, atkY, p)
                c.drawLine(atkX, atkY - radius * 0.55f, atkX, atkY - radius * 0.2f, p)
                c.drawLine(atkX, atkY + radius * 0.55f, atkX, atkY + radius * 0.2f, p)
                p.style = Paint.Style.FILL; c.drawCircle(atkX, atkY, radius * 0.08f, p)
            }
            AttackMode.MANUAL_AIM -> {
                // Joystick Base Ring (Draw at base touch position if active, else at button position)
                val bx = if (gs.controls.manualAimActive) gs.touch.manualAimBaseX else atkX
                val by = if (gs.controls.manualAimActive) gs.touch.manualAimBaseY else atkY
                
                p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.008f; p.color = 0x44FF0000
                c.drawCircle(bx, by, scale * 0.15f, p)
                
                // Draw Base Button Ring (No Label)
                drawActionButton(c, atkX, atkY, radius, "", GameColors.RED, false, visualType = resolved.control.visualType)

                // Knob position
                val kx = if (gs.controls.manualAimActive) gs.touch.manualAimKnobX else atkX
                val ky = if (gs.controls.manualAimActive) gs.touch.manualAimKnobY else atkY
                
                // RESTORED JOYSTICK KNOB
                p.style = Paint.Style.FILL; p.color = 0xAAFF0000.toInt()
                c.drawCircle(kx, ky, radius * 0.6f, p)
                p.style = Paint.Style.STROKE; p.color = GameColors.RED; p.strokeWidth = scale * 0.006f
                c.drawCircle(kx, ky, radius * 0.6f, p)
                
                p.style = Paint.Style.FILL; p.color = GameColors.HP
                c.drawCircle(kx, ky, radius * 0.15f, p)

                if (gs.controls.currentWeapon == 2 && gs.controls.isSniperCharging) {
                    p.style = Paint.Style.STROKE; p.strokeWidth = radius * 0.1f; p.color = GameColors.YELLOW
                    val chargeRect = RectF(atkX - radius * 0.9f, atkY - radius * 0.9f, atkX + radius * 0.9f, atkY + radius * 0.9f)
                    c.drawArc(chargeRect, -90f, 360f * (gs.controls.sniperCharge / 1.5f), false, p)
                }
            }
        }
    }

    private fun drawDirectionalAimFeedback(c: Canvas, scale: Float, gs: GameState, x: Float, y: Float, radius: Float, color: Int, isCharging: Boolean) {
        val dx = gs.controls.aimDirX
        val dy = gs.controls.aimDirY
        p.style = Paint.Style.STROKE
        p.strokeWidth = radius * 0.06f
        p.color = color
        c.drawLine(x, y, x + dx * radius * 0.68f, y + dy * radius * 0.68f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(x + dx * radius * 0.72f, y + dy * radius * 0.72f, radius * 0.12f, p)
        if (isCharging) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = radius * 0.1f
            p.color = GameColors.YELLOW
            val chargeRect = RectF(x - radius * 0.9f, y - radius * 0.9f, x + radius * 0.9f, y + radius * 0.9f)
            c.drawArc(chargeRect, -90f, 360f * (gs.controls.sniperCharge / 1.5f), false, p)
        }
    }

    private fun drawRadialMenu(c: Canvas, scale: Float, cx: Float, cy: Float, labels: Array<String>, colors: IntArray, selectedIdx: Int) {
        val radius = scale * 0.25f
        val count = labels.size
        // Radial Menu Angle (Matches InputSystem logic)
        val startAngle = 140f
        val arcRange = 170f
        val step = if (count > 1) arcRange / (count) else 0f
        val offset = step / 2f

        p.style = Paint.Style.FILL; p.color = 0x88000000.toInt()
        c.drawCircle(cx, cy, radius + scale * 0.1f, p)

        for (i in 0 until count) {
            val angleDeg = startAngle + i * step + offset
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val lx = cx + cos(angleRad).toFloat() * radius
            val ly = cy + sin(angleRad).toFloat() * radius
            val isSelected = (i == selectedIdx)
            val color = colors[i % colors.size]

            if (isSelected) {
                p.style = Paint.Style.FILL; p.color = color; p.alpha = 180
                c.drawCircle(lx, ly, scale * 0.09f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.015f; p.alpha = 255
                c.drawCircle(lx, ly, scale * 0.09f, p)
            } else {
                p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.006f; p.color = 0xCCFFFFFF.toInt()
                c.drawCircle(lx, ly, scale * 0.07f, p)
            }
            pText.textAlign = Paint.Align.CENTER; pText.textSize = scale * 0.035f
            pText.color = if (isSelected) color else 0xDDFFFFFF.toInt()
            c.drawText(labels[i], lx, ly + pText.textSize * 0.35f, pText)
        }
    }

    private fun drawActionButton(c: Canvas, x: Float, y: Float, radius: Float, label: String, color: Int, isAutoLocked: Boolean, cooldownProgress: Float = 0f, rawTime: Float = 0f, visualType: HudVisualType = HudVisualType.STANDARD) {
        val visualRadius = radius * if (visualType == HudVisualType.COMPACT) 0.68f else 0.85f
        p.style = Paint.Style.STROKE; p.strokeWidth = radius * 0.06f; p.color = color
        c.drawCircle(x, y, visualRadius, p)
        
        // Indicator for Ready/Auto-Locked Mode (Glow Ring)
        if (isAutoLocked) {
            p.strokeWidth = radius * 0.03f
            p.alpha = (150 + sin(System.currentTimeMillis() * 0.01).toFloat() * 100).toInt().coerceIn(0, 255)
            c.drawCircle(x, y, visualRadius * 1.12f, p)
            p.alpha = 255
        }

        p.style = Paint.Style.FILL; p.color = (0x33 shl 24) or (color and 0xFFFFFF)
        c.drawCircle(x, y, visualRadius, p)
        
        // Cooldown/Progress Radial Sweep
        if (cooldownProgress > 0f) {
            p.color = (0x66 shl 24) or (color and 0xFFFFFF)
            val rect = RectF(x - visualRadius, y - visualRadius, x + visualRadius, y + visualRadius)
            c.drawArc(rect, -90f, 360f * cooldownProgress, true, p)
        }

        pText.textAlign = Paint.Align.CENTER
        if (cooldownProgress > 0f && rawTime > 0f) {
            // Draw Timer Text
            pText.color = 0xFFFFFFFF.toInt()
            pText.textSize = radius * 0.5f
            val timeStr = "%.1fs".format(rawTime)
            c.drawText(timeStr, x, y + pText.textSize * 0.35f, pText)
        } else {
            // Draw Label
            pText.color = color
            if (visualType != HudVisualType.COMPACT) {
                pText.textSize = radius * if (visualType == HudVisualType.LABELED) 0.32f else 0.38f
                c.drawText(label, x, y + pText.textSize * 0.35f, pText)
            }
        }
    }

    private fun drawStoryPopups(c: Canvas, scale: Float, targetW: Float) {
        val msgTimer = StoryProtocol.popupTimer
        if (msgTimer <= 0f) return
        
        val msgText = if (StoryProtocol.typewriterText != null) {
            StoryProtocol.getScrambledTypewriterText()
        } else {
            StoryProtocol.currentPopupText ?: ""
        }

        if (msgText.isBlank()) return

        val alpha = if (msgTimer < 0.5f) (msgTimer * 2 * 255).toInt().coerceIn(0, 255) else 255
        pText.textAlign = Paint.Align.CENTER; pText.textSize = scale * 0.045f
        val maxW = targetW * 0.8f
        val wrappedLines = mutableListOf<String>()
        msgText.split("\n").forEach { p ->
            var line = ""
            p.split(" ").forEach { w ->
                if (pText.measureText("$line $w") > maxW) { wrappedLines.add(line); line = w } else line = if(line.isEmpty()) w else "$line $w"
            }
            wrappedLines.add(line)
        }

        val startY = c.height * 0.28f
        val lh = pText.descent() - pText.ascent()
        val padding = scale * 0.05f
        
        var maxWidth = 0f
        wrappedLines.forEach { maxWidth = maxWidth.coerceAtLeast(pText.measureText(it)) }
        
        rectPopup.set(targetW/2f - maxWidth/2f - padding, startY + pText.ascent() - padding, 
                     targetW/2f + maxWidth/2f + padding, startY + (wrappedLines.size - 1) * lh + pText.descent() + padding)
        
        p.style = Paint.Style.FILL; p.color = (alpha * 0.85f).toInt() shl 24; c.drawRoundRect(rectPopup, 25f, 25f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.006f; p.color = (alpha shl 24) or (GameColors.YELLOW and 0xFFFFFF)
        c.drawRoundRect(rectPopup, 25f, 25f, p)
        
        pText.color = (alpha shl 24) or (GameColors.YELLOW and 0xFFFFFF)
        wrappedLines.forEachIndexed { i, l -> c.drawText(l, targetW/2f, startY + i * lh, pText) }
    }

    private fun drawBossUI(c: Canvas, scale: Float, gs: GameState, targetW: Float, topY: Float) {
        val barW = scale * 0.55f
        val barH = scale * 0.02f
        val barX = (targetW - barW) / 2f
        val barY = topY + scale * 0.01f

        gs.hudLayout.bossHpRect.set(barX, barY, barX + barW, barY + barH)

        // Label (Smaller for Top Placement)
        pText.textAlign = Paint.Align.CENTER; pText.textSize = scale * 0.022f; pText.color = GameColors.RED
        c.drawText("HOSTILE CORE DETECTED", targetW / 2f, topY, pText)

        // Main Bar
        p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.004f; p.color = GameColors.RED
        c.drawRect(gs.hudLayout.bossHpRect, p)
        
        val hpPercent = if (gs.bossMaxHp > 0) gs.bossHp.toFloat() / gs.bossMaxHp else 0f
        p.style = Paint.Style.FILL
        c.drawRect(barX, barY, barX + barW * hpPercent, barY + barH, p)

        // Low HP Glitch Effect
        if (hpPercent < 0.25f && System.currentTimeMillis() % 300 < 150) {
            p.color = 0xAAFFFFFF.toInt()
            c.drawRect(barX, barY, barX + barW * hpPercent, barY + barH, p)
        }
    }

    private fun drawFocusIndicator(c: Canvas, scale: Float, targetW: Float, targetH: Float) {
        val size = scale * 0.1f
        val pad = scale * 0.05f
        p.style = Paint.Style.STROKE; p.strokeWidth = scale * 0.008f; p.color = GameColors.YELLOW
        
        // Corner brackets indicating focus
        // Top Left
        c.drawLine(pad, pad, pad + size, pad, p)
        c.drawLine(pad, pad, pad, pad + size, p)
        // Top Right
        c.drawLine(targetW - pad, pad, targetW - pad - size, pad, p)
        c.drawLine(targetW - pad, pad, targetW - pad, pad + size, p)
        // Bottom Left
        c.drawLine(pad, targetH - pad, pad + size, targetH - pad, p)
        c.drawLine(pad, targetH - pad, pad, targetH - pad - size, p)
        // Bottom Right
        c.drawLine(targetW - pad, targetH - pad, targetW - pad - size, targetH - pad, p)
        c.drawLine(targetW - pad, targetH - pad, targetW - pad, targetH - pad - size, p)

        pText.textAlign = Paint.Align.CENTER; pText.textSize = scale * 0.02f; pText.color = GameColors.YELLOW
        if (System.currentTimeMillis() % 1000 < 500) {
            c.drawText("TARGET LOCKED", targetW / 2f, pad + scale * 0.03f, pText)
        }
    }

    fun renderOverclockText(c: Canvas, scale: Float, targetW: Float, targetH: Float) {
        val alpha = (128 + 127 * sin(System.currentTimeMillis() * 0.01)).toInt().coerceIn(0, 255)
        pText.textSize = scale * 0.08f; pText.textAlign = Paint.Align.CENTER; pText.color = (alpha shl 24) or (GameColors.OVERCLOCK and 0xFFFFFF)
        c.drawText("OVERRIDE ACCEPTED", targetW / 2f, targetH / 2f - scale * 0.2f, pText)
        pText.textSize = scale * 0.03f
        c.drawText("SYSTEM PERFORMANCE AT 200%", targetW / 2f, targetH / 2f - scale * 0.12f, pText)
    }

    private fun drawIntegrityGlitches(c: Canvas, w: Float, h: Float, scale: Float) {
        val criticalNodes = listOf("core", "sys_aim_manual", "d_plating")
        var avgIntegrity = 0f
        var count = 0
        criticalNodes.forEach { id ->
            if (SaveManager.isNodeUnlocked(id)) {
                avgIntegrity += SaveManager.getIntegrity(id)
                count++
            }
        }
        
        if (count == 0) return
        val integrity = avgIntegrity / count
        if (integrity > 70f) return

        val intensity = (100f - integrity) / 100f // 0.3 to 1.0
        val time = System.currentTimeMillis()
        
        if (time % 1000 < intensity * 500) {
            p.style = Paint.Style.FILL
            p.color = (intensity * 40).toInt() shl 24 or (GameColors.RED and 0xFFFFFF)
            
            // Random horizontal glitch bars
            for (i in 0 until (intensity * 5).toInt() + 1) {
                val gy = (Math.random() * h).toFloat()
                val gh = (Math.random() * scale * 0.05f).toFloat()
                c.drawRect(0f, gy, w, gy + gh, p)
            }
            
            // Random text noise
            if (Math.random() < intensity * 0.2) {
                pText.textSize = scale * 0.02f
                pText.color = 0xAAFF0000.toInt()
                pText.textAlign = Paint.Align.LEFT
                c.drawText("CRITICAL_HARDWARE_FAILURE", (Math.random() * w * 0.5f).toFloat(), (Math.random() * h).toFloat(), pText)
            }
        }
    }
}
