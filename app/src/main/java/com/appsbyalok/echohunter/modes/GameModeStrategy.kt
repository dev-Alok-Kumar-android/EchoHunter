package com.appsbyalok.echohunter.modes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.appsbyalok.echohunter.engine.AppStateId
import com.appsbyalok.echohunter.engine.GameState

// Interface for Strategy Pattern
interface GameModeStrategy {
    val modeId: Int

    // Resource IDs
    fun getIntroLines(): IntArray

    fun updateCameraAndMovement(dt: Float, gs: GameState, width: Float, height: Float, scale: Float)

    // Wave progress, Sector progress, ya Popup show
    fun checkProgression(
        context: Context,
        gs: GameState,
        scale: Float,
        onTriggerBoss: (Int, Float) -> Unit,
        onSetStory: (IntArray, AppStateId) -> Unit
    )

    fun getEnemySpawnPosition(gs: GameState, width: Float, height: Float, scale: Float): Pair<Float, Float>

    fun drawModeSpecificWorld(c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, p: Paint)

    fun drawModeSpecificHUD(context: Context, c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, pText: Paint)
}