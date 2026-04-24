package com.appsbyalok.echohunter

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Handles purely visual features like particles, player trails, and floating text
class EffectSystem {
    private val p = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val pText = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    // Particles Data (Zero Allocation arrays)
    private val pn = 40
    private val pxA = FloatArray(pn); private val pyA = FloatArray(pn)
    private val pvxA = FloatArray(pn); private val pvyA = FloatArray(pn)
    private val pLife = FloatArray(pn)

    // Player Trail Data
    private val trailLength = 15
    private val trailX = FloatArray(trailLength); private val trailY = FloatArray(trailLength)
    private var trailIdx = 0

    // Floating Text Data
    private val ftn = 10
    private val ftX = FloatArray(ftn); private val ftY = FloatArray(ftn)
    private val ftLife = FloatArray(ftn); private val ftColor = IntArray(ftn)
    private val ftStr = Array(ftn) { "" }

    fun reset() {
        for (i in 0 until trailLength) { trailX[i] = 0f; trailY[i] = 0f }
        for (i in 0 until pn) { pLife[i] = 0f }
        for (i in 0 until ftn) { ftLife[i] = 0f }
    }

    fun recordTrail(px: Float, py: Float) {
        trailX[trailIdx] = px; trailY[trailIdx] = py
        trailIdx = (trailIdx + 1) % trailLength
    }

    fun spawnParticles(x: Float, y: Float, colorMode: Int, scale: Float) {
        for (i in 0 until pn) {
            if (pLife[i] <= 0) {
                pxA[i] = x; pyA[i] = y
                val angle = Random.nextDouble() * Math.PI * 2
                val speed = scale * 0.5f + Random.nextDouble() * (scale * 1.0f)
                pvxA[i] = (cos(angle) * speed).toFloat()
                pvyA[i] = (sin(angle) * speed).toFloat()
                pLife[i] = 1f
                if (colorMode == 1) pLife[i] = 2f
                if (colorMode == 2) pLife[i] = 3f
                if (Random.nextDouble() > 0.8) break
            }
        }
    }

    fun spawnFloatingText(x: Float, y: Float, value: Int, color: Int) {
        for (i in 0 until ftn) {
            if (ftLife[i] <= 0f) {
                ftX[i] = x; ftY[i] = y; ftLife[i] = 1f; ftColor[i] = color
                ftStr[i] = "+$value"
                break
            }
        }
    }

    fun update(dt: Float, scale: Float) {
        for (i in 0 until pn) {
            if (pLife[i] > 0) {
                pxA[i] += pvxA[i] * dt
                pyA[i] += pvyA[i] * dt
                pLife[i] -= 1f * dt
            }
        }
        for (i in 0 until ftn) {
            if (ftLife[i] > 0f) {
                ftY[i] -= scale * 0.1f * dt
                ftLife[i] -= 1f * dt
            }
        }
    }

    fun drawTrails(c: Canvas, cameraX: Float, scale: Float, currentPlayerColor: Int) {
        p.style = Paint.Style.FILL
        for (i in 0 until trailLength) {
            val tx = trailX[(trailIdx + i) % trailLength] - cameraX
            val ty = trailY[(trailIdx + i) % trailLength]
            if (tx + cameraX != 0f || ty != 0f) {
                val isOverclocked = currentPlayerColor == GameColors.OVERCLOCK
                val trailSize = if (isOverclocked) scale * 0.02f else scale * 0.012f
                p.color = ((255 * (i.toFloat() / trailLength)).toInt() shl 24) or (currentPlayerColor and 0xFFFFFF)
                c.drawCircle(tx, ty, trailSize * (i.toFloat() / trailLength), p)
            }
        }
    }

    fun drawParticles(c: Canvas, scale: Float) {
        p.style = Paint.Style.FILL
        for (i in 0 until pn) {
            if (pLife[i] > 0) {
                val pColor = when {
                    pLife[i] > 2f -> GameColors.BOSS
                    pLife[i] > 1f -> GameColors.OVERCLOCK
                    else -> GameColors.RED
                }
                val alphaAlpha = pLife[i] - pLife[i].toInt()
                p.color = ((alphaAlpha * 255).toInt() shl 24) or (pColor and 0xFFFFFF)
                c.drawCircle(pxA[i], pyA[i], alphaAlpha * (scale * 0.008f), p)
            }
        }
    }

    fun drawFloatingTexts(c: Canvas, scale: Float) {
        for (i in 0 until ftn) {
            if (ftLife[i] > 0f) {
                pText.color = ftColor[i]
                pText.textSize = scale * 0.04f
                pText.alpha = (ftLife[i] * 255).toInt()
                pText.setShadowLayer(5f, 0f, 0f, ftColor[i])
                c.drawText(ftStr[i], ftX[i], ftY[i], pText)
                pText.clearShadowLayer()
            }
        }
    }
}