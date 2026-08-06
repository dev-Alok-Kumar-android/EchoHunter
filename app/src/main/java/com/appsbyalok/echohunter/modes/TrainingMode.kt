package com.appsbyalok.echohunter.modes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.appsbyalok.echohunter.engine.AppStateId
import com.appsbyalok.echohunter.engine.GameState

class TrainingMode : GameModeStrategy {
    override val modeId: Int = 2

    override fun getIntroLines(): IntArray = intArrayOf()

    override fun updateCameraAndMovement(dt: Float, gs: GameState, width: Float, height: Float, scale: Float) {
        gs.updatePlayerMovement(dt, width, height, scale)
        
        // Simple linear movement
        gs.cameraX = gs.px - gs.getViewportW(width, height) / 2f
        gs.cameraY = gs.py - gs.getViewportH(width, height) / 2f
    }

    override fun checkProgression(
        context: Context,
        gs: GameState,
        scale: Float,
        onTriggerBoss: (Int, Float) -> Unit,
        onSetStory: (IntArray, AppStateId) -> Unit
    ) {
        // Tutorial progression is handled by TutorialObjective
    }

    override fun getEnemySpawnPosition(gs: GameState, width: Float, height: Float, scale: Float): Pair<Float, Float> {
        return Pair(gs.px + width * 0.4f, gs.py)
    }

    override fun drawModeSpecificWorld(c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, p: Paint) {
        // No specific world elements for now
    }

    override fun drawModeSpecificHUD(context: Context, c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, pText: Paint) {
        // Skip Buttons for Tutorial
        val p = Paint().apply { isAntiAlias = true }
        val rect = RectF()
        val btnW = scale * 0.18f
        val btnH = scale * 0.06f
        val pad = scale * 0.03f
        
        pText.textSize = scale * 0.025f
        pText.textAlign = Paint.Align.CENTER
        
        // Skip Current Step
        val skipX = width - btnW - pad
        val skipY = scale * 0.15f
        rect.set(skipX, skipY, skipX + btnW, skipY + btnH)
        
        p.style = Paint.Style.FILL; p.color = 0x88000000.toInt()
        c.drawRoundRect(rect, 10f, 10f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(rect, 10f, 10f, p)
        
        pText.color = 0xFFFFFFFF.toInt()
        c.drawText("SKIP STEP", rect.centerX(), rect.centerY() + scale * 0.008f, pText)

        // Skip All
        val skipAllY = skipY + btnH + pad * 0.5f
        rect.set(skipX, skipAllY, skipX + btnW, skipAllY + btnH)
        
        p.style = Paint.Style.FILL; p.color = 0x88AA0000.toInt()
        c.drawRoundRect(rect, 10f, 10f, p)
        p.style = Paint.Style.STROKE; p.color = 0xFFFF4444.toInt()
        c.drawRoundRect(rect, 10f, 10f, p)
        
        pText.color = 0xFFFF4444.toInt()
        c.drawText("SKIP ALL", rect.centerX(), rect.centerY() + scale * 0.008f, pText)
        
        // Store button bounds for touch detection
        gs.tutorialSkipStepRect.set(skipX, skipY, skipX + btnW, skipY + btnH)
        gs.tutorialSkipAllRect.set(skipX, skipAllY, skipX + btnW, skipAllY + btnH)
    }
}
