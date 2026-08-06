package com.appsbyalok.echohunter.input

import android.media.ToneGenerator
import android.view.MotionEvent
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.engine.GameModeId
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.utils.EchoAudioManager

class TouchController(private val gs: GameState) {
    var onPauseClicked: (() -> Unit)? = null
    var onPulseTriggered: (() -> Unit)? = null

    fun handleTouch(
        e: MotionEvent,
        offsetX: Float,
        offsetY: Float
    ): Boolean {
        val action = e.actionMasked
        val pointerIndex = e.actionIndex
        val pointerId = e.getPointerId(pointerIndex)
        val vx = e.getX(pointerIndex) - offsetX
        val vy = e.getY(pointerIndex) - offsetY
        gs.touch.lastTouchX = vx
        gs.touch.lastTouchY = vy

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                reset() // Force clear ghost touches on fresh start
                if (gs.state.isGameplay) onDown(pointerId, vx, vy)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (gs.state.isGameplay) onDown(pointerId, vx, vy)
            }
            MotionEvent.ACTION_MOVE -> {
                if (gs.state.isGameplay) onMove(e, offsetX, offsetY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onUp(pointerId, action, vx, vy)
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    fun reset() {
        gs.touch.moveTouchId = -1
        gs.touch.attackTouchId = -1
        gs.touch.manualAimTouchId = -1
        gs.touch.trapTouchId = -1
        gs.touch.sonarTouchId = -1

        gs.controls.isMoveJoyActive = false
        gs.controls.moveDirX = 0f
        gs.controls.moveDirY = 0f
        gs.controls.isAttackTouching = false
        gs.controls.manualAimActive = false
        gs.controls.isTrapPressed = false
        gs.controls.isSonarPressed = false
        gs.controls.isOverclockPressed = false
        gs.controls.attackTapQueued = false
    }

    private fun onDown(pointerId: Int, x: Float, y: Float) {
        if (gs.gameMode == GameModeId.TRAINING) {
            if (gs.tutorialSkipStepRect.contains(x, y)) return
            if (gs.tutorialSkipAllRect.contains(x, y)) return
        }
        val layout = gs.hudLayout
        layout.controlAt(x, y)?.takeUnless {
            gs.gameMode == GameModeId.TRAINING && it.control.action !in gs.tutorialEnabledActions
        }?.let { resolved ->
            layout.setActionAnchor(resolved)
            when (resolved.control.action) {
                HudAction.PAUSE -> onPauseClicked?.invoke()
                HudAction.SONAR -> if (gs.isDarknessLevel || StoryProtocol.isBlackoutActive) {
                    gs.touch.sonarTouchId = pointerId
                    gs.controls.isSonarPressed = true
                    gs.controls.sonarTouchX = x
                    gs.controls.sonarTouchY = y
                }
                HudAction.OVERCLOCK -> {
                    if (gs.overclockMeter >= 100f && !gs.isOverclocked) {
                        gs.controls.isOverclockPressed = true
                        EchoAudioManager.playSound(ToneGenerator.TONE_SUP_CONFIRM, 150)
                    } else {
                        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 50)
                    }
                }
                HudAction.TRAP -> {
                    gs.touch.trapTouchId = pointerId
                    gs.controls.isTrapPressed = true
                    gs.controls.trapTouchX = x
                    gs.controls.trapTouchY = y
                }
                HudAction.ATTACK -> {
                    if (resolved.control.inputBehavior == HudInputBehavior.TAP) {
                        gs.controls.attackTapQueued = true
                    } else {
                        gs.touch.attackTouchId = pointerId
                        gs.controls.isAttackTouching = true
                        gs.controls.attackTouchX = x
                        gs.controls.attackTouchY = y
                    }
                    if (gs.controls.activeAttackMode == AttackMode.MANUAL_AIM) {
                        gs.touch.manualAimTouchId = pointerId
                        gs.controls.manualAimActive = true // Force active in manual mode if touching button
                        gs.touch.manualAimBaseX = resolved.x
                        gs.touch.manualAimBaseY = resolved.y
                        gs.touch.manualAimCurrentX = x
                        gs.touch.manualAimCurrentY = y
                    }
                }
            }
            return
        }

        if (gs.touch.moveTouchId == -1 && layout.isMovementHit(x, y)) {
            gs.touch.moveTouchId = pointerId
            gs.controls.isMoveJoyActive = true
            if (layout.movementMode == MovementMode.STATIC) {
                gs.touch.moveBaseX = layout.movementX
                gs.touch.moveBaseY = layout.movementY
            } else {
                gs.touch.moveBaseX = x
                gs.touch.moveBaseY = y
            }
            gs.touch.moveCurrentX = x
            gs.touch.moveCurrentY = y
            return
        }

        if ((gs.gameMode != GameModeId.TRAINING || HudAction.ATTACK in gs.tutorialEnabledActions) &&
            gs.controls.activeAttackMode == AttackMode.MANUAL_AIM &&
            layout.isManualAimHit(x, y) && gs.touch.manualAimTouchId == -1) {
            gs.touch.manualAimTouchId = pointerId
            gs.controls.manualAimActive = true
            gs.touch.manualAimBaseX = if (layout.manualAimMode == MovementMode.STATIC) layout.manualAimX else x
            gs.touch.manualAimBaseY = if (layout.manualAimMode == MovementMode.STATIC) layout.manualAimY else y
            gs.touch.manualAimCurrentX = x
            gs.touch.manualAimCurrentY = y
        }
    }

    private fun onMove(e: MotionEvent, offsetX: Float, offsetY: Float) {
        for (i in 0 until e.pointerCount) {
            val x = e.getX(i) - offsetX
            val y = e.getY(i) - offsetY
            when (e.getPointerId(i)) {
                gs.touch.manualAimTouchId -> if (gs.controls.manualAimActive) {
                    gs.touch.manualAimCurrentX = x
                    gs.touch.manualAimCurrentY = y
                }
                gs.touch.moveTouchId -> {
                    gs.touch.moveCurrentX = x
                    gs.touch.moveCurrentY = y
                }
                gs.touch.attackTouchId -> {
                    gs.controls.attackTouchX = x
                    gs.controls.attackTouchY = y
                }
                gs.touch.trapTouchId -> {
                    gs.controls.trapTouchX = x
                    gs.controls.trapTouchY = y
                }
                gs.touch.sonarTouchId -> {
                    gs.controls.sonarTouchX = x
                    gs.controls.sonarTouchY = y
                }
            }
        }
    }

    private fun onUp(pointerId: Int, action: Int, x: Float, y: Float) {
        if (gs.gameMode == GameModeId.TRAINING) {
            if (gs.tutorialSkipStepRect.contains(x, y)) {
                (gs.activeObjective as? com.appsbyalok.echohunter.modes.TrainingObjective)?.skipStep(gs)
            } else if (gs.tutorialSkipAllRect.contains(x, y)) {
                (gs.activeObjective as? com.appsbyalok.echohunter.modes.TrainingObjective)?.skipAll(gs)
            }
        }

        if (pointerId == gs.touch.manualAimTouchId) {
            gs.controls.manualAimActive = false
            gs.touch.manualAimTouchId = -1
        }
        if (pointerId == gs.touch.moveTouchId) {
            gs.touch.moveTouchId = -1
            gs.controls.isMoveJoyActive = false
            gs.controls.moveDirX = 0f
            gs.controls.moveDirY = 0f
        }
        if (pointerId == gs.touch.attackTouchId) {
            gs.controls.attackTouchX = x
            gs.controls.attackTouchY = y
            gs.touch.attackTouchId = -1
            gs.controls.isAttackTouching = false
        }
        if (pointerId == gs.touch.trapTouchId) {
            gs.controls.trapTouchX = x
            gs.controls.trapTouchY = y
            gs.touch.trapTouchId = -1
            if (gs.controls.isTrapPressed && !gs.controls.isTrapMenuOpen && gs.trapCooldownTimer <= 0f) gs.controls.trapRequested = true
            gs.controls.isTrapPressed = false
        }
        if (pointerId == gs.touch.sonarTouchId) {
            gs.controls.sonarTouchX = x
            gs.controls.sonarTouchY = y
            gs.touch.sonarTouchId = -1
            gs.controls.isSonarPressed = false
        }
        if (action == MotionEvent.ACTION_UP) {
            reset() // Global cleanup on last finger up
        }
    }
}
