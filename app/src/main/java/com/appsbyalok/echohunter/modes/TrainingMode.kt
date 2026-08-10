package com.appsbyalok.echohunter.modes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.engine.AppStateId
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.systems.updateCameraLogic

class TrainingMode : GameModeStrategy {
    override val modeId: Int = 2

    override fun getIntroLines(): IntArray = StoryProtocol.trainingIntroLines

    override fun updateCameraAndMovement(dt: Float, gs: GameState, width: Float, height: Float, scale: Float) {
        // Use the centralized camera engine with lead disabled for tutorials
        gs.updateCameraLogic(dt, width, height, leadMult = 0.0f)
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
        val p = Paint().apply { isAntiAlias = true }
        val rect = RectF()
        
        // --- 1. HIGHLIGHT ENABLED ACTIONS ---
        val highlightPulse = (kotlin.math.sin(System.currentTimeMillis() * 0.008f) * 0.5f + 0.5f)
        gs.hudLayout.controls.forEach { resolved ->
            if (resolved.control.action in gs.tutorialEnabledActions) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = scale * 0.012f
                p.color = com.appsbyalok.echohunter.utils.GameColors.YELLOW
                p.alpha = (highlightPulse * 200).toInt()
                
                val pulseRadius = resolved.radius * (1.1f + highlightPulse * 0.2f)
                c.drawCircle(resolved.x, resolved.y, pulseRadius, p)
                
                // Directional Arrow (if applicable)
                if (System.currentTimeMillis() % 1000 < 500) {
                   p.style = Paint.Style.FILL
                   val arrowSize = scale * 0.02f
                   val arrowY = resolved.y - resolved.radius - scale * 0.05f - highlightPulse * scale * 0.02f
                   c.drawCircle(resolved.x, arrowY, arrowSize * 0.3f, p)
                }
            }
        }

        // --- 2. DIEGETIC SKIP BUTTONS ---
        val btnW = scale * 0.16f
        val btnH = scale * 0.05f
        val pad = scale * 0.025f
        
        pText.textSize = scale * 0.02f
        pText.textAlign = Paint.Align.CENTER
        pText.typeface = Typeface.MONOSPACE
        
        // Skip Current Step
        val skipX = width - btnW - pad
        val skipY = gs.hudLayout.safeInsetTop + scale * 0.02f
        rect.set(skipX, skipY, skipX + btnW, skipY + btnH)
        
        p.style = Paint.Style.FILL; p.color = 0xAA0D0D0D.toInt()
        c.drawRect(rect, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x88FFFFFF.toInt()
        c.drawRect(rect, p)
        // Neon Left Bar
        p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt()
        c.drawRect(rect.left, rect.top, rect.left + scale * 0.005f, rect.bottom, p)
        
        pText.color = 0xFFFFFFFF.toInt()
        c.drawText("SKIP_STEP", rect.centerX() + scale * 0.005f, rect.centerY() + pText.textSize * 0.35f, pText)

        // Skip All
        val skipAllY = skipY + btnH + pad * 0.4f
        rect.set(skipX, skipAllY, skipX + btnW, skipAllY + btnH)
        
        p.style = Paint.Style.FILL; p.color = 0xAA0D0D0D.toInt()
        c.drawRect(rect, p)
        p.style = Paint.Style.STROKE; p.color = 0x88FF4444.toInt()
        c.drawRect(rect, p)
        // Neon Left Bar (Red)
        p.style = Paint.Style.FILL; p.color = 0xFFFF4444.toInt()
        c.drawRect(rect.left, rect.top, rect.left + scale * 0.005f, rect.bottom, p)
        
        pText.color = 0xFFFF4444.toInt()
        c.drawText("SKIP_ALL", rect.centerX() + scale * 0.005f, rect.centerY() + pText.textSize * 0.35f, pText)
        
        // Store button bounds for touch detection
        gs.tutorialSkipStepRect.set(skipX, skipY, skipX + btnW, skipY + btnH)
        gs.tutorialSkipAllRect.set(skipX, skipAllY, skipX + btnW, skipAllY + btnH)
    }
}
