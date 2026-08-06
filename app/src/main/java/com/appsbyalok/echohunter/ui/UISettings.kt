package com.appsbyalok.echohunter.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ToneGenerator
import android.view.MotionEvent
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.input.AttackMode
import com.appsbyalok.echohunter.ui.components.UIMenuButton
import com.appsbyalok.echohunter.ui.components.UIMenuCard
import com.appsbyalok.echohunter.ui.components.UIScrollView
import com.appsbyalok.echohunter.utils.EchoAudioManager
import com.appsbyalok.echohunter.utils.GameColors

class UISettings {
    private val p = Paint().apply { isAntiAlias = true }
    private val pText = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val backButton = UIMenuButton()
    private var layoutEditorOpen = false
    private val layoutEditor = UILayoutEditor()
    private val optionCard = UIMenuCard()
    private val optionRects = Array(10) { RectF() }
    private var hitOnDown = -1

    private val scroller = UIScrollView()

    private var showWipeConfirm = false
    private val confirmDialogRect = RectF()
    private val confirmYesRect = RectF()
    private val confirmNoRect = RectF()

    private val options = arrayOf(
        "SFX AUDIO",
        "HAPTIC FEEDBACK",
        "VISUAL EFFECTS",
        "AUTO-NEXT LEVEL",
        "DEFAULT ATTACK MODE",
        "DEFAULT WEAPON",
        "DEFAULT TRAP",
        "SCREEN ROTATION",
        "HUD LAYOUT",
        "WIPE ALL DATA"
    )

    fun draw(c: Canvas, targetW: Float, targetH: Float, scale: Float, gs: GameState) {
        c.drawColor(0xEE050A0F.toInt())
        if (layoutEditorOpen) {
            layoutEditor.draw(c, targetW, targetH, scale, p, pText)
            return
        }

        val isPortrait = targetH > targetW
        val insetT = SaveManager.lastInsetTop
        val insetR = SaveManager.lastInsetRight
        val insetB = SaveManager.lastInsetBottom

        // --- HEADER (Fixed) ---
        pText.textAlign = Paint.Align.CENTER
        pText.textSize = scale * 0.08f
        pText.color = GameColors.PULSE
        pText.isFakeBoldText = true
        pText.setShadowLayer(10f, 0f, 0f, GameColors.PULSE)
        c.drawText("SYSTEM CONFIG", targetW / 2f, scale * 0.12f + insetT, pText)
        pText.clearShadowLayer()
        pText.isFakeBoldText = false

        // Subtitle line
        p.style = Paint.Style.STROKE
        p.strokeWidth = scale * 0.002f
        p.color = GameColors.CLARITY
        c.drawLine(targetW*0.25f, scale * 0.14f + insetT, targetW*0.75f, scale * 0.14f + insetT, p)

        // --- SCROLLABLE CONTENT ---
        val startY = scale * 0.22f + insetT
        val itemH = scale * 0.11f
        val gap = scale * 0.02f
        val itemW = if (isPortrait) targetW * 0.85f else targetW * 0.6f
        val startX = (targetW - itemW) / 2f

        val totalContentHeight = options.size * (itemH + gap)
        val viewportHeight = targetH - startY - (scale * 0.15f) - insetB // Reserve space for Return button

        scroller.viewport.set(0f, startY - gap, targetW, startY - gap + viewportHeight)
        scroller.begin(c)

        for (i in options.indices) {
            val rect = optionRects[i]
            val top = i * (itemH + gap) + gap
            rect.set(startX, top, startX + itemW, top + itemH)

            val isLocked = when(i) {
                4 -> !SaveManager.isNodeUnlocked("sys_aim_manual") && !SaveManager.isNodeUnlocked("sys_aim_auto")
                5 -> !SaveManager.isNodeUnlocked("sys_carry_w") && SaveManager.unlockedWeapons.size <= 1
                6 -> !SaveManager.isNodeUnlocked("sys_carry_t") && SaveManager.unlockedTraps.size <= 1
                else -> false
            }

            val isPressed = (hitOnDown == i) && !scroller.isDragging && !scroller.isDraggingScrollbar && !isLocked
            optionCard.draw(
                c = c,
                rect = rect,
                scale = scale,
                paint = p,
                pressed = isPressed,
                fillColor = 0xFF0A1520.toInt(),
                strokeColor = if (isLocked) 0x44AAAAAA else if (i == 9) GameColors.RED else GameColors.PULSE,
                activeStrokeColor = if (isLocked) 0x44AAAAAA else if (i == 9) GameColors.RED else GameColors.PULSE,
                radius = scale * 0.02f
            )

            pText.textAlign = Paint.Align.LEFT
            pText.textSize = scale * 0.038f
            pText.color = if (isLocked) 0x88AAAAAA.toInt() else GameColors.CLARITY
            c.drawText(options[i], rect.left + scale * 0.04f, rect.centerY() + scale * 0.015f, pText)

            // Value / Toggle State
            pText.textAlign = Paint.Align.RIGHT
            val valueText = if (isLocked) "LOCKED" else when (i) {
                0 -> if (SaveManager.isSoundEnabled) "ON" else "OFF"
                1 -> if (SaveManager.isVibrationEnabled) "ON" else "OFF"
                2 -> if (SaveManager.isEffectsEnabled) "ON" else "OFF"
                3 -> if (SaveManager.isAutoNextLevelEnabled) "ON" else "OFF"
                4 -> gs.controls.activeAttackMode.name.replace("_", " ")
                5 -> when(gs.controls.currentWeapon) { 1 -> "SHOTGUN"; 2 -> "SNIPER"; else -> "SPIKE DRIVER" }
                6 -> when(gs.controls.currentTrap) { 0 -> "CAMOUFLAGE"; 1 -> "DECOY"; 2 -> "EMP MINE"; else -> "NONE" }
                7 -> when(SaveManager.screenOrientation) {
                    0 -> "AUTO ROTATE"
                    1 -> "PORTRAIT"
                    2 -> "LANDSCAPE"
                    else -> "DEVICE DEFAULT"
                }
                8 -> "EDIT"
                9 -> "DANGER"
                else -> ""
            }
            pText.color = if (isLocked) 0x88AAAAAA.toInt() else if (valueText == "OFF" || i == 9) GameColors.RED else GameColors.PULSE
            c.drawText(valueText, rect.right - scale * 0.04f, rect.centerY() + scale * 0.015f, pText)
        }
        scroller.end(c, totalContentHeight + gap, scale, insetR)

        // --- BACK BUTTON (Fixed at bottom) ---
        val btnW = scale * 0.3f
        val btnH = scale * 0.08f
        backButton.set(targetW / 2f - btnW / 2f, targetH - btnH - scale * 0.04f - insetB, targetW / 2f + btnW / 2f, targetH - scale * 0.04f - insetB)
        backButton.draw(
            c = c,
            scale = scale,
            paint = p,
            textPaint = pText,
            label = "RETURN",
            pressed = hitOnDown == 10,
            fillColor = 0x00000000,
            strokeColor = GameColors.CLARITY,
            textColor = GameColors.CLARITY,
            radius = scale * 0.01f
        )

        if (showWipeConfirm) {
            drawWipeConfirm(c, targetW, targetH, scale)
        }
    }

    private fun drawWipeConfirm(c: Canvas, targetW: Float, targetH: Float, scale: Float) {
        c.drawColor(0xAA000000.toInt())
        val dw = scale * 0.75f
        val dh = scale * 0.38f
        confirmDialogRect.set(targetW/2f - dw/2f, targetH/2f - dh/2f, targetW/2f + dw/2f, targetH/2f + dh/2f)

        p.style = Paint.Style.FILL
        p.color = 0xFF0A1520.toInt()
        c.drawRoundRect(confirmDialogRect, scale * 0.02f, scale * 0.02f, p)

        p.style = Paint.Style.STROKE
        p.color = GameColors.RED
        p.strokeWidth = scale * 0.005f
        c.drawRoundRect(confirmDialogRect, scale * 0.02f, scale * 0.02f, p)

        pText.textAlign = Paint.Align.CENTER
        pText.textSize = scale * 0.045f
        pText.color = GameColors.CLARITY
        c.drawText("WIPE ALL DATA?", targetW/2f, confirmDialogRect.top + scale * 0.1f, pText)

        pText.textSize = scale * 0.028f
        pText.color = GameColors.RED
        c.drawText("THIS ACTION IS PERMANENT", targetW/2f, confirmDialogRect.top + scale * 0.16f, pText)

        val bw = scale * 0.25f
        val bh = scale * 0.08f
        confirmNoRect.set(targetW/2f - bw - scale * 0.02f, confirmDialogRect.bottom - bh - scale * 0.05f, targetW/2f - scale * 0.02f, confirmDialogRect.bottom - scale * 0.05f)
        confirmYesRect.set(targetW/2f + scale * 0.02f, confirmDialogRect.bottom - bh - scale * 0.05f, targetW/2f + bw + scale * 0.02f, confirmDialogRect.bottom - scale * 0.05f)

        // No
        p.style = Paint.Style.FILL
        p.color = if (hitOnDown == 21) 0xFF1A3040.toInt() else 0xFF102530.toInt()
        c.drawRoundRect(confirmNoRect, scale * 0.01f, scale * 0.01f, p)
        pText.color = GameColors.CLARITY
        c.drawText("CANCEL", confirmNoRect.centerX(), confirmNoRect.centerY() + scale * 0.012f, pText)

        // Yes
        p.style = Paint.Style.FILL
        p.color = if (hitOnDown == 20) 0xFF401010.toInt() else 0xFF300A0A.toInt()
        c.drawRoundRect(confirmYesRect, scale * 0.01f, scale * 0.01f, p)
        pText.color = GameColors.RED
        c.drawText("WIPE", confirmYesRect.centerX(), confirmYesRect.centerY() + scale * 0.012f, pText)
    }

    fun update(dt: Float) {
        scroller.updatePhysics(dt)
    }

    fun handleBack(onHudLayoutApplied: () -> Unit): Boolean {
        if (!layoutEditorOpen) return false
        layoutEditor.requestClose(onHudLayoutApplied) { layoutEditorOpen = false }
        return true
    }

    fun onTouch(e: MotionEvent, vx: Float, vy: Float, action: Int, scale: Float, targetW: Float, targetH: Float, gs: GameState, onClose: () -> Unit, onWipe: () -> Unit, onOrientChange: () -> Unit, onHudLayoutApplied: () -> Unit): Boolean {
        if (layoutEditorOpen) {
            return layoutEditor.onTouch(e, targetW, targetH, scale, onHudLayoutApplied) {
                layoutEditorOpen = false
            }
        }

        if (showWipeConfirm) {
            // ... (keep wipe confirm logic as is, it's overlay)
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    hitOnDown = -1
                    if (confirmYesRect.contains(vx, vy)) hitOnDown = 20
                    if (confirmNoRect.contains(vx, vy)) hitOnDown = 21
                }
                MotionEvent.ACTION_UP -> {
                    if (hitOnDown == 20 && confirmYesRect.contains(vx, vy)) {
                        SaveManager.clearAllData()
                        SaveManager.resetTutorials()
                        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 200)
                        showWipeConfirm = false
                        onWipe()
                    } else if (hitOnDown == 21 && confirmNoRect.contains(vx, vy)) {
                        showWipeConfirm = false
                        EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 100)
                    }
                    hitOnDown = -1
                }
            }
            return true
        }

        scroller.onTouch(vx, vy, action, scale)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                hitOnDown = -1
                
                if (backButton.contains(vx, vy)) {
                    hitOnDown = 10
                } else {
                    // Adjust for scroll when checking hits
                    val adjustedVy = vy - scroller.viewport.top - scroller.scrollY
                    val adjustedVx = vx - scroller.viewport.left
                    for (i in options.indices) {
                        if (optionRects[i].contains(adjustedVx, adjustedVy)) {
                            hitOnDown = i
                            break
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!scroller.isDragging && !scroller.isDraggingScrollbar && hitOnDown != -1) {
                    if (hitOnDown == 10 && backButton.contains(vx, vy)) {
                        EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 100)
                        onClose()
                    } else if (hitOnDown < options.size) {
                        // Check with adjusted coordinates for items
                        val adjustedVy = vy - scroller.viewport.top - scroller.scrollY
                        val adjustedVx = vx - scroller.viewport.left
                        if (optionRects[hitOnDown].contains(adjustedVx, adjustedVy)) {
                            EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 100)
                            when (hitOnDown) {
                                0 -> SaveManager.setSoundEnabled(!SaveManager.isSoundEnabled)
                                1 -> SaveManager.setVibrationEnabled(!SaveManager.isVibrationEnabled)
                                2 -> SaveManager.setEffectsEnabled(!SaveManager.isEffectsEnabled)
                                3 -> SaveManager.setAutoNextLevel(!SaveManager.isAutoNextLevelEnabled)
                                4 -> {
                                    val modes = AttackMode.entries
                                    var nextIdx = (gs.controls.activeAttackMode.id + 1) % modes.size
                                    
                                    // Hardware check for Logic Aim modes
                                    fun AttackMode.isLocked(): Boolean = when (this) {
                                        AttackMode.AUTO_AIM -> !SaveManager.isAutoAimUnlocked
                                        AttackMode.MANUAL_AIM -> !SaveManager.isManualAimUnlocked
                                        else -> false
                                    }

                                    run search@{
                                        repeat(modes.size) {
                                            if (!modes[nextIdx].isLocked()) return@search
                                            nextIdx = (nextIdx + 1) % modes.size
                                        }
                                    }

                                    gs.controls.activeAttackMode = modes[nextIdx]
                                    SaveManager.setAttackMode(nextIdx)
                                }
                                5 -> {
                                    val unlocked = SaveManager.unlockedWeapons
                                    if (unlocked.size > 1) {
                                        val currentIndex = unlocked.indexOf(gs.controls.currentWeapon)
                                        val next = unlocked[(currentIndex + 1) % unlocked.size]
                                        gs.controls.currentWeapon = next
                                        SaveManager.setActiveWeapon(next)
                                    }
                                }
                                6 -> {
                                    val unlocked = SaveManager.unlockedTraps
                                    if (unlocked.size > 1) {
                                        val currentIndex = unlocked.indexOf(gs.controls.currentTrap)
                                        val next = unlocked[(currentIndex + 1) % unlocked.size]
                                        gs.controls.currentTrap = next
                                        SaveManager.setActiveTrap(next)
                                    }
                                }
                                7 -> {
                                    val next = (SaveManager.screenOrientation + 1) % 4
                                    SaveManager.setScreenOrientation(next)
                                    onOrientChange()
                                }
                                8 -> {
                                    layoutEditorOpen = true
                                }
                                9 -> {
                                    showWipeConfirm = true
                                }
                            }
                        }
                    }
                }
                hitOnDown = -1
            }
        }
        return true
    }
}
