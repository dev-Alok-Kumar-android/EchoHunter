package com.appsbyalok.echohunter.ui.archives

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.appsbyalok.echohunter.data.LevelEngine
import com.appsbyalok.echohunter.data.LevelFeature
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.utils.GameColors
import java.util.Locale

class ArchiveDetailView {
    val detailBoxRect = RectF()
    val detailPlayRect = RectF()
    val detailBackRect = RectF()
    val autoNextRect = RectF()
    val autoPilotRect = RectF()

    private var scanAnim = 0f

    fun draw(c: Canvas, width: Float, height: Float, scale: Float, level: Int, hitOnDown: Int, p: Paint, pText: Paint, gs: com.appsbyalok.echohunter.engine.GameState) {
        c.drawColor(0xCC000000.toInt()) // Dim background

        scanAnim = (scanAnim + 0.05f) % 2f
        val scanY = if (scanAnim < 1f) detailBoxRect.top + (detailBoxRect.height() * scanAnim)
                    else detailBoxRect.bottom - (detailBoxRect.height() * (scanAnim - 1f))

        val isLandscape = width > height
        val safeLeft = gs.hudLayout.safeInsetLeft
        val safeRight = gs.hudLayout.safeInsetRight
        val safeTop = gs.hudLayout.safeInsetTop
        val safeBottom = gs.hudLayout.safeInsetBottom

        val dw = if (isLandscape) minOf(width * 0.92f - (safeLeft + safeRight), scale * 1.8f) else minOf(width * 0.92f, scale * 1.2f)
        val dh = if (isLandscape) minOf(height * 0.90f - (safeTop + safeBottom), scale * 1.0f) else minOf(height * 0.88f, scale * 1.5f)
        
        val usableCenterX = safeLeft + (width - safeLeft - safeRight) / 2f
        val usableCenterY = safeTop + (height - safeTop - safeBottom) / 2f
        detailBoxRect.set(usableCenterX - dw / 2f, usableCenterY - dh / 2f, usableCenterX + dw / 2f, usableCenterY + dh / 2f)

        // Panel Shadow & Border
        p.style = Paint.Style.FILL; p.color = 0xFF050508.toInt()
        c.drawRoundRect(detailBoxRect, scale * 0.02f, scale * 0.02f, p)
        p.style = Paint.Style.STROKE; p.color = GameColors.COOLANT; p.strokeWidth = scale * (if (isLandscape) 0.003f else 0.005f)
        c.drawRoundRect(detailBoxRect, scale * 0.02f, scale * 0.02f, p)

        // Diegetic Scan Line
        p.color = 0x2200FFFF; p.strokeWidth = scale * 0.002f
        c.drawLine(detailBoxRect.left, scanY, detailBoxRect.right, scanY, p)
        
        var y = detailBoxRect.top + scale * (if (isLandscape) 0.06f else 0.08f)
        
        // Header Ribbon
        val headH = scale * (if (isLandscape) 0.05f else 0.06f)
        p.style = Paint.Style.FILL; p.color = 0x3300FFFF
        c.drawRect(detailBoxRect.left, y - headH * 0.7f, detailBoxRect.right, y + headH * 0.3f, p)
        
        pText.textSize = scale * (if (isLandscape) 0.045f else 0.05f); pText.color = GameColors.PULSE
        pText.textAlign = Paint.Align.CENTER
        pText.isFakeBoldText = true
        c.drawText("PROTOCOL DECODED: $level", detailBoxRect.centerX(), y, pText)
        pText.isFakeBoldText = false

        y += scale * (if (isLandscape) 0.04f else 0.05f)
        val rating = calculateThreatRating(level)
        pText.textSize = scale * (if (isLandscape) 0.024f else 0.026f); pText.color = 0xFFAAAAAA.toInt()
        c.drawText("THREAT ASSESSMENT: ", detailBoxRect.centerX() - scale * 0.06f, y, pText)
        pText.color = when(rating) {
            "CRITICAL", "EXTREME" -> GameColors.RED
            "HIGH" -> GameColors.YELLOW
            else -> GameColors.HP
        }
        c.drawText(rating, detailBoxRect.centerX() + scale * 0.14f, y, pText)

        if (isLandscape) {
            val leftColX = detailBoxRect.left + dw * 0.26f
            val rightColX = detailBoxRect.left + dw * 0.74f
            val contentY = y + scale * 0.08f
            drawDetailStats(c, level, leftColX, contentY, scale, pText)
            drawDetailRecords(c, level, rightColX, contentY, scale, pText)
        } else {
            y += scale * 0.08f
            drawDetailStats(c, level, detailBoxRect.centerX(), y, scale, pText)
            y += scale * 0.22f
            drawDetailRecords(c, level, detailBoxRect.centerX(), y, scale, pText)
        }

        // Lore/Tactics with Auto-Wrap
        y = detailBoxRect.bottom - scale * (if (isLandscape) 0.35f else 0.48f)
        pText.textSize = scale * (if (isLandscape) 0.02f else 0.022f); pText.color = 0x88FFFFFF.toInt()
        pText.textAlign = Paint.Align.CENTER
        c.drawText("TACTICAL ANALYSIS:", detailBoxRect.centerX(), y, pText)
        y += scale * 0.03f
        
        pText.color = GameColors.CLARITY
        pText.textSize = scale * (if (isLandscape) 0.019f else 0.021f)
        pText.textAlign = Paint.Align.LEFT
        val tactics = getTacticalLore(level)
        val textPaint = android.text.TextPaint(pText)
        val alignment = android.text.Layout.Alignment.ALIGN_CENTER
        val layoutWidth = (dw - scale * 0.15f).toInt()
        @Suppress("DEPRECATION")
        val staticLayout = android.text.StaticLayout(tactics, textPaint, layoutWidth, alignment, 1.1f, 0f, false)
        
        c.save()
        c.translate(detailBoxRect.centerX() - layoutWidth / 2f, y)
        staticLayout.draw(c)
        c.restore()
        pText.textAlign = Paint.Align.CENTER

        // Reward Preview
        y += staticLayout.height + scale * 0.035f
        val config = LevelEngine.getLevelConfig(level)
        val reward = config.clearRewardKB
        pText.textSize = scale * (if (isLandscape) 0.022f else 0.024f); pText.color = GameColors.HP
        pText.textAlign = Paint.Align.CENTER
        c.drawText("DATA BOUNTY: ${SaveManager.formatDataString(reward)}", detailBoxRect.centerX(), y, pText)

        // Pre-flight Toggles
        y = detailBoxRect.bottom - scale * (if (isLandscape) 0.21f else 0.25f)
        val toggleW = dw * (if (isLandscape) 0.25f else 0.38f)
        val toggleH = scale * (if (isLandscape) 0.05f else 0.06f)
        autoNextRect.set(detailBoxRect.centerX() - toggleW - scale * 0.02f, y, detailBoxRect.centerX() - scale * 0.02f, y + toggleH)
        autoPilotRect.set(detailBoxRect.centerX() + scale * 0.02f, y, detailBoxRect.centerX() + toggleW + scale * 0.02f, y + toggleH)

        // Draw Auto Next Toggle
        val isNextOn = SaveManager.isAutoNextLevelEnabled
        p.style = Paint.Style.FILL; p.color = if (hitOnDown == -103) 0x3300AAFF else 0x11000000
        c.drawRoundRect(autoNextRect, scale * 0.005f, scale * 0.005f, p)
        p.style = Paint.Style.STROKE; p.color = if (isNextOn) GameColors.HP else 0x44FFFFFF
        p.strokeWidth = scale * 0.002f
        c.drawRoundRect(autoNextRect, scale * 0.005f, scale * 0.005f, p)
        pText.textSize = scale * 0.018f; pText.color = if (isNextOn) GameColors.HP else 0xFFAAAAAA.toInt()
        pText.textAlign = Paint.Align.CENTER
        c.drawText("${if (isNextOn) "☒" else "☐"} AUTO-NEXT", autoNextRect.centerX(), autoNextRect.centerY() + scale * 0.006f, pText)

        // Draw Auto Pilot Toggle
        val isPilotOn = gs.isAutoPilotActive
        p.style = Paint.Style.FILL; p.color = if (hitOnDown == -104) 0x3300AAFF else 0x11000000
        c.drawRoundRect(autoPilotRect, scale * 0.005f, scale * 0.005f, p)
        p.style = Paint.Style.STROKE; p.color = if (isPilotOn) GameColors.HP else 0x44FFFFFF
        c.drawRoundRect(autoPilotRect, scale * 0.005f, scale * 0.005f, p)
        pText.color = if (isPilotOn) GameColors.HP else 0xFFAAAAAA.toInt()
        c.drawText("${if (isPilotOn) "☒" else "☐"} AUTOPILOT", autoPilotRect.centerX(), autoPilotRect.centerY() + scale * 0.006f, pText)

        // Buttons
        val btnW = dw * 0.4f
        val btnH = scale * 0.08f
        detailBackRect.set(detailBoxRect.centerX() - btnW - scale * 0.02f, detailBoxRect.bottom - btnH - scale * 0.04f, detailBoxRect.centerX() - scale * 0.02f, detailBoxRect.bottom - scale * 0.04f)
        detailPlayRect.set(detailBoxRect.centerX() + scale * 0.02f, detailBoxRect.bottom - btnH - scale * 0.04f, detailBoxRect.centerX() + btnW + scale * 0.02f, detailBoxRect.bottom - scale * 0.04f)

        // Back Btn
        p.style = Paint.Style.FILL; p.color = if (hitOnDown == -101) 0x44FFFFFF else 0x1AFFFFFF
        c.drawRoundRect(detailBackRect, scale * 0.01f, scale * 0.01f, p)
        p.style = Paint.Style.STROKE; p.color = 0xFF888888.toInt(); p.strokeWidth = scale * 0.003f
        c.drawRoundRect(detailBackRect, scale * 0.01f, scale * 0.01f, p)
        pText.color = 0xFFFFFFFF.toInt(); pText.textSize = scale * 0.025f
        c.drawText("ABORT", detailBackRect.centerX(), detailBackRect.centerY() + scale * 0.008f, pText)

        // Play Btn
        p.style = Paint.Style.FILL; p.color = if (hitOnDown == -102) 0x4400FF00 else 0x1A00FF00
        c.drawRoundRect(detailPlayRect, scale * 0.01f, scale * 0.01f, p)
        p.style = Paint.Style.STROKE; p.color = GameColors.HP; p.strokeWidth = scale * 0.004f
        c.drawRoundRect(detailPlayRect, scale * 0.01f, scale * 0.01f, p)
        pText.color = GameColors.HP
        c.drawText("INITIATE", detailPlayRect.centerX(), detailPlayRect.centerY() + scale * 0.008f, pText)
    }

    private fun drawDetailStats(c: Canvas, lvl: Int, centerX: Float, y: Float, scale: Float, pText: Paint) {
        val normAttempts = SaveManager.getLevelAttempts(lvl, false)
        val hardAttempts = SaveManager.getLevelAttempts(lvl, true)
        val normTime = SaveManager.getLevelTime(lvl, false)
        val hardTime = SaveManager.getLevelTime(lvl, true)
        val normStars = SaveManager.getLevelStars(lvl, false)
        val hardStars = SaveManager.getLevelStars(lvl, true)

        pText.textSize = scale * 0.024f; pText.color = 0xFFAAAAAA.toInt()
        pText.textAlign = Paint.Align.CENTER
        c.drawText("OPERATIONAL TELEMETRY", centerX, y, pText)

        val col1 = centerX - scale * 0.22f
        var sy = y + scale * 0.05f

        pText.textSize = scale * 0.018f; pText.textAlign = Paint.Align.LEFT
        pText.color = 0x88FFFFFF.toInt()
        c.drawText("MODE", col1, sy, pText)
        c.drawText("ATTEMPTS", col1 + scale * 0.12f, sy, pText)
        c.drawText("BEST", col1 + scale * 0.28f, sy, pText)
        c.drawText("RATING", col1 + scale * 0.42f, sy, pText)

        sy += scale * 0.04f
        pText.color = GameColors.CLARITY
        c.drawText("NORM", col1, sy, pText)
        c.drawText("$normAttempts", col1 + scale * 0.12f, sy, pText)
        val ntStr = if (normStars == 0 || normTime <= 0f) "---" else String.format(Locale.ENGLISH, "%.1fs", normTime)
        c.drawText(ntStr, col1 + scale * 0.28f, sy, pText)
        pText.color = GameColors.YELLOW
        c.drawText("★".repeat(normStars), col1 + scale * 0.42f, sy, pText)

        sy += scale * 0.04f
        pText.color = if (SaveManager.isHardModeUnlocked) GameColors.RED else 0x44FFFFFF
        c.drawText("HARD", col1, sy, pText)
        if (SaveManager.isHardModeUnlocked) {
            c.drawText("$hardAttempts", col1 + scale * 0.12f, sy, pText)
            val htStr = if (hardStars == 0 || hardTime <= 0f) "---" else String.format(Locale.ENGLISH, "%.1fs", hardTime)
            c.drawText(htStr, col1 + scale * 0.28f, sy, pText)
            pText.color = GameColors.YELLOW
            c.drawText("★".repeat(hardStars), col1 + scale * 0.42f, sy, pText)
        } else {
            c.drawText("LOCKED", col1 + scale * 0.12f, sy, pText)
        }
        pText.textAlign = Paint.Align.CENTER
    }

    private fun drawDetailRecords(c: Canvas, lvl: Int, centerX: Float, y: Float, scale: Float, pText: Paint) {
        val records = SaveManager.getLevelRecords(lvl)
        val config = LevelEngine.getLevelConfig(lvl)
        pText.textSize = scale * 0.024f; pText.color = GameColors.PULSE
        pText.textAlign = Paint.Align.CENTER
        c.drawText("SYSTEM ACHIEVEMENTS", centerX, y, pText)

        val bestTime = SaveManager.getLevelTime(lvl)
        val targetTime = config.parTime

        val recordList = listOf(
            "NO DAMAGE" to (records and 1 != 0),
            "PERFECT CLEAR" to (records and 2 != 0),
            "MANUAL AIM" to (records and 16 != 0),
            "NO SONAR" to (records and 8 != 0),
            "NO AUTOPILOT" to (records and 64 != 0),
            "SPEED RUN" to (bestTime > 0f && bestTime <= targetTime)
        )

        var ry = y + scale * 0.05f
        pText.textSize = scale * 0.02f
        recordList.chunked(2).forEach { pair ->
            val left = pair[0]
            val right = pair.getOrNull(1)
            pText.textAlign = Paint.Align.LEFT
            pText.color = if (left.second) GameColors.HP else 0x44FFFFFF
            c.drawText("${if (left.second) "✓" else "□"} ${left.first}", centerX - scale * 0.22f, ry, pText)
            if (right != null) {
                pText.color = if (right.second) GameColors.HP else 0x44FFFFFF
                c.drawText("${if (right.second) "✓" else "□"} ${right.first}", centerX + scale * 0.05f, ry, pText)
            }
            ry += scale * 0.04f
        }
        pText.textAlign = Paint.Align.CENTER
    }

    private fun calculateThreatRating(lvl: Int): String {
        val mask = LevelEngine.getFeaturesMask(lvl)
        val config = LevelEngine.getLevelConfig(lvl)
        val score = (config.speedMultiplier * 0.3f + config.hpMultiplier * 0.2f + config.spawnRateMultiplier * 0.3f + config.aiIntelligence * 0.2f)
        
        if (lvl % 100 == 0) return "EXTREME"
        if (mask and (1 shl LevelFeature.BOSS.id) != 0) return "CRITICAL"
        
        return when {
            score < 1.3f -> "LOW"
            score < 2.2f -> "MEDIUM"
            score < 3.5f -> "HIGH"
            score < 6.0f -> "CRITICAL"
            else -> "EXTREME"
        }
    }

    private fun getTacticalLore(lvl: Int): String {
        val mask = LevelEngine.getFeaturesMask(lvl)
        val config = LevelEngine.getLevelConfig(lvl)
        
        return when {
            mask and (1 shl LevelFeature.BOSS.id) != 0 -> "HIGH-VALUE TARGET DETECTED. AVOID DIRECT CONFRONTATION WITHOUT FULL CHARGE."
            mask and (1 shl LevelFeature.DARKNESS.id) != 0 -> "VISUAL SENSORS IMPAIRED. RELY ON SONAR PINGS FOR NAVIGATION."
            mask and (1 shl LevelFeature.MAZE.id) != 0 -> "COMPLEX GEOMETRY DETECTED. ENTRAPMENT RISK IS NOMINAL."
            config.speedMultiplier > 1.5f -> "HIGH-SPEED HOSTILES DETECTED. OVERCLOCKING RECOMMENDED."
            config.spawnRateMultiplier > 2.0f -> "NUMERICAL DISADVANTAGE CONFIRMED. DEPLOY TRAPS TO CONTROL CROWDS."
            else -> "STANDARD DATA COLLECTION PROTOCOL. MINIMAL RESISTANCE EXPECTED."
        }
    }
}
