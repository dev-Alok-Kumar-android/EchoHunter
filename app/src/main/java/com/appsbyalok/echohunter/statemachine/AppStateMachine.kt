package com.appsbyalok.echohunter.statemachine

import android.graphics.Canvas
import android.media.ToneGenerator
import android.view.MotionEvent
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.input.AttackMode
import com.appsbyalok.echohunter.utils.EchoAudioManager
import com.appsbyalok.echohunter.view.GameView

/**
 * Common lifecycle and interaction interface for all application states.
 *
 * Implementations define state-specific behavior for updating frame logic, rendering graphics,
 * handling touch events, and responding to back navigation.
 */
interface IAppState {
    /** Called when transitioning into this state. */
    fun onEnter(gs: GameState)

    /** Called when transitioning away from this state. */
    fun onExit(gs: GameState)

    /** Updates state logic given elapsed frame delta time [dt]. */
    fun update(dt: Float, gs: GameState, width: Float, height: Float, scale: Float)

    /** Renders state graphics onto canvas [c]. */
    fun draw(c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, dt: Float)

    /** Handles raw touch input events mapped to virtual screen dimensions. */
    fun onTouch(e: MotionEvent, vx: Float, vy: Float, action: Int, gs: GameState, scale: Float, targetW: Float, targetH: Float): Boolean

    /** Processes device or system back navigation events. */
    fun onBackPressed(gs: GameState): Boolean
}

/**
 * Controller responsible for switching and delegating lifecycle events to active [IAppState] instances.
 */
class AppStateManager(val view: GameView, private val gs: GameState) {
    var currentState: IAppState? = null
        private set

    val mainMenuState = MainMenuState(this)
    val gameplayState = GameplayState(this)
    val pauseState = PauseState(this)
    val helpState = HelpState(this)
    val storyState = StoryCutsceneState(this)
    val victoryState = VictoryState(this)
    val subMenuState = SubMenuState(this)

    fun changeState(newState: IAppState) {
        currentState?.onExit(gs)
        currentState = newState
        currentState?.onEnter(gs)
    }

    fun update(dt: Float, width: Float, height: Float, scale: Float) {
        currentState?.update(dt, gs, width, height, scale)
    }

    fun draw(c: Canvas, width: Float, height: Float, scale: Float, dt: Float) {
        currentState?.draw(c, gs, width, height, scale, dt)
    }

    fun onTouch(e: MotionEvent, vx: Float, vy: Float, action: Int, scale: Float, targetW: Float, targetH: Float): Boolean {
        return currentState?.onTouch(e, vx, vy, action, gs, scale, targetW, targetH) ?: false
    }

    fun onBackPressed(): Boolean {
        return currentState?.onBackPressed(gs) ?: false
    }
}

// ---------------------------------------------------------
// STATE CLASSES
// ---------------------------------------------------------

class MainMenuState(private val manager: AppStateManager) : IAppState {
    override fun onEnter(gs: GameState) {
        manager.view.uiMainMenu.disconnect()
    }
    override fun onExit(gs: GameState) {}
    override fun update(dt: Float, gs: GameState, width: Float, height: Float, scale: Float) {
        manager.view.uiMainMenu.update(
            dt, width, height, manager.view, manager.view.effectSys, gs, manager.view.onMenuRoute,
            manager.view.uiDecompiler.hasAffordableAction(),
            manager.view.uiArsenal.hasAffordableAction()
        )
        manager.view.worldRenderer.updateDashEffect(scale)
    }
    override fun draw(c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, dt: Float) {
        c.drawColor(0xFF050A0F.toInt()) // Deep Dark Background
        manager.view.worldRenderer.drawGrid(c, scale, gs, width, height, showSpawners = false)
        manager.view.uiMainMenu.draw(c, scale, gs, width, height, manager.view.effectSys)
    }
    override fun onTouch(e: MotionEvent, vx: Float, vy: Float, action: Int, gs: GameState, scale: Float, targetW: Float, targetH: Float): Boolean {
        return manager.view.uiMainMenu.onTouch(vx, vy, action, scale,
            manager.view, gs, manager.view.onDifficultyToggle, manager.view.onHelpOpen,
            manager.view.onMenuRoute)
    }
    override fun onBackPressed(gs: GameState): Boolean = false
}

class GameplayState(private val manager: AppStateManager) : IAppState {
    override fun onEnter(gs: GameState) {}
    override fun onExit(gs: GameState) {}
    override fun update(dt: Float, gs: GameState, width: Float, height: Float, scale: Float) {
        manager.view.uiHelpMenu.update(dt)
    }
    override fun draw(c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, dt: Float) {
        c.drawColor(0xFF050A0F.toInt()) // Base Background

        c.save()
        // --- HUD ISOLATION: Apply Camera Transforms ONLY to the World ---
        
        // 1. Zoom Logic
        val worldZoom = gs.getCameraZoom(width, height)
        if (worldZoom != 1.0f) {
            c.scale(worldZoom, worldZoom)
        } else if (gs.state == 9) {
            val zoom = 1f + (gs.mergeTimer * 0.5f)
            c.scale(zoom, zoom, gs.coreX - gs.cameraX, gs.coreY - gs.cameraY)
        }

        // 2. Shake Logic
        if (gs.shakeAmount > 0f) {
            val dx = (kotlin.random.Random.nextFloat() - 0.5f) * gs.shakeAmount
            val dy = (kotlin.random.Random.nextFloat() - 0.5f) * gs.shakeAmount
            c.translate(dx, dy)
        }

        manager.view.worldRenderer.drawGrid(c, scale, gs, width, height)
        manager.view.worldRenderer.drawMaze(c, gs, scale, width, height)
        manager.view.worldRenderer.drawGamePlay(c, scale, gs, width, height)
        
        c.restore()

        // --- HUD remains stable (not affected by c.restore() above) ---
        if (gs.empFlashTimer <= 0.8f && gs.state != 9) manager.view.hudRenderer.drawHUD(c, scale, gs, width, height)
        if (gs.showOverclockTextTimer > 0f) manager.view.hudRenderer.renderOverclockText(c, scale, width, height)
    }
    override fun onTouch(e: MotionEvent, vx: Float, vy: Float, action: Int, gs: GameState, scale: Float, targetW: Float, targetH: Float): Boolean {
        return manager.view.touchController.handleTouch(e, 0f, 0f)
    }
    override fun onBackPressed(gs: GameState): Boolean {
        manager.view.pauseGame()
        return true
    }
}

class PauseState(private val manager: AppStateManager) : IAppState {
    private var hitOnDown = -1

    override fun onEnter(gs: GameState) {}
    override fun onExit(gs: GameState) {
        hitOnDown = -1
    }
    override fun update(dt: Float, gs: GameState, width: Float, height: Float, scale: Float) {
        manager.view.uiHelpMenu.update(dt)
    }
    override fun draw(c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, dt: Float) {
        manager.gameplayState.draw(c, gs, width, height, scale, dt)
        manager.view.menuRenderer.drawPause(c, scale, gs, width, height)
    }
    override fun onTouch(e: MotionEvent, vx: Float, vy: Float, action: Int, gs: GameState, scale: Float, targetW: Float, targetH: Float): Boolean {
        if (action == MotionEvent.ACTION_DOWN) {
            hitOnDown = when {
                manager.view.menuRenderer.pauseAimRect.contains(vx, vy) -> 1
                manager.view.menuRenderer.pauseDiscRect.contains(vx, vy) -> 2
                manager.view.menuRenderer.pauseAutoRect.contains(vx, vy) -> 3
                manager.view.menuRenderer.pauseRestartRect.contains(vx, vy) -> 4
                manager.view.menuRenderer.pauseResumeRect.contains(vx, vy) || (vx > targetW - scale * 0.15f && vy < scale * 0.15f) -> 5
                else -> -1
            }
        }

        if (action == MotionEvent.ACTION_UP && gs.stateTimer > 0.2f) {
            val hitOnUp = when {
                manager.view.menuRenderer.pauseAimRect.contains(vx, vy) -> 1
                manager.view.menuRenderer.pauseDiscRect.contains(vx, vy) -> 2
                manager.view.menuRenderer.pauseAutoRect.contains(vx, vy) -> 3
                manager.view.menuRenderer.pauseRestartRect.contains(vx, vy) -> 4
                manager.view.menuRenderer.pauseResumeRect.contains(vx, vy) || (vx > targetW - scale * 0.15f && vy < scale * 0.15f) -> 5
                else -> -1
            }

            if (hitOnUp != -1 && hitOnUp == hitOnDown) {
                when (hitOnUp) {
                    1 -> {
                        val modes = AttackMode.entries.toTypedArray()
                        val currentIdx = gs.controls.activeAttackMode.ordinal
                        
                        var nextIdx = (currentIdx + 1) % modes.size
                        
                        // Hardware check for Logic Aim modes
                        for (attempt in 0 until 3) {
                            val nextMode = modes[nextIdx]
                            val isLocked = when (nextMode) {
                                AttackMode.AUTO_AIM -> !SaveManager.isAutoAimUnlocked
                                AttackMode.MANUAL_AIM -> !SaveManager.isManualAimUnlocked
                                else -> false
                            }
                            
                            if (!isLocked) break
                            nextIdx = (nextIdx + 1) % modes.size
                        }

                        val finalMode = modes[nextIdx]
                        if (finalMode != gs.controls.activeAttackMode) {
                            gs.controls.activeAttackMode = finalMode
                            SaveManager.setAttackMode(nextIdx)
                            EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 100)
                        } else {
                            gs.showGlobalMessage("LOGIC CORE UPGRADE REQUIRED.", 1.2f)
                            EchoAudioManager.playSound(ToneGenerator.TONE_PROP_NACK, 100)
                        }
                    }
                    2 -> manager.view.disconnectCable()
                    3 -> {
                        gs.isAutoPilotActive = !gs.isAutoPilotActive
                        SaveManager.setAutoPilotEnabled(gs.isAutoPilotActive)
                        gs.autoPilotTimer = if (gs.isAutoPilotActive) 600f else 0f
                        gs.showGlobalMessage(if (gs.isAutoPilotActive) "AUTOPILOT ENGAGED." else "AUTOPILOT DISENGAGED.", 1.5f)
                        EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 100)
                    }
                    4 -> {
                        EchoAudioManager.playSound(ToneGenerator.TONE_SUP_CONFIRM, 150)
                        manager.view.resetGame()
                    }
                    5 -> {
                        val returnState = if (gs.state == 8 || (gs.coreX > 0f && gs.bossHp <= 0 && gs.gameMode == 1 && gs.currentSector >= 8)) 8 else 1
                        manager.view.changeState(returnState, pushToHistory = false)
                        manager.view.lastFrameTime = System.nanoTime()
                    }
                }
            }
            hitOnDown = -1
        }
        return true
    }
    override fun onBackPressed(gs: GameState): Boolean {
        manager.view.onAppClose()
        return true
    }
}

class HelpState(private val manager: AppStateManager) : IAppState {
    override fun onEnter(gs: GameState) {}
    override fun onExit(gs: GameState) {}
    override fun update(dt: Float, gs: GameState, width: Float, height: Float, scale: Float) {
        manager.view.uiHelpMenu.update(dt)
    }
    override fun draw(c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, dt: Float) {
        manager.view.uiHelpMenu.draw(c, scale, width, height, gs)
    }
    override fun onTouch(e: MotionEvent, vx: Float, vy: Float, action: Int, gs: GameState, scale: Float, targetW: Float, targetH: Float): Boolean {
        return manager.view.uiHelpMenu.onTouch(vx, vy, action, scale, gs, manager.view, manager.view.effectSys, manager.view.onHelpClose)
    }
    override fun onBackPressed(gs: GameState): Boolean {
        manager.view.onAppClose()
        return true
    }
}

class VictoryState(private val manager: AppStateManager) : IAppState {
    private var hitOnDown = -1

    override fun onEnter(gs: GameState) {}
    override fun onExit(gs: GameState) {
        hitOnDown = -1
    }
    override fun update(dt: Float, gs: GameState, width: Float, height: Float, scale: Float) {
        if (SaveManager.isAutoNextLevelEnabled && gs.gameMode == 0 && gs.stateTimer > 2.0f) {
            val nextLevel = if (gs.currentLevel == Int.MAX_VALUE) Int.MAX_VALUE else gs.currentLevel + 1
            manager.view.startGame(0, nextLevel)
        }
    }
    override fun draw(c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, dt: Float) {
        manager.gameplayState.draw(c, gs, width, height, scale, dt)
        manager.view.menuRenderer.drawLevelVictory(c, scale, gs, width, height)
    }
    override fun onTouch(e: MotionEvent, vx: Float, vy: Float, action: Int, gs: GameState, scale: Float, targetW: Float, targetH: Float): Boolean {
        if (action == MotionEvent.ACTION_DOWN) {
            hitOnDown = when {
                manager.view.menuRenderer.victoryNextRect.contains(vx, vy) -> 1
                manager.view.menuRenderer.victoryHomeRect.contains(vx, vy) -> 2
                else -> -1
            }
        }

        if (action == MotionEvent.ACTION_UP && gs.stateTimer > 0.3f) {
            val hitOnUp = when {
                manager.view.menuRenderer.victoryNextRect.contains(vx, vy) -> 1
                manager.view.menuRenderer.victoryHomeRect.contains(vx, vy) -> 2
                else -> -1
            }

            if (hitOnUp != -1 && hitOnUp == hitOnDown) {
                if (hitOnUp == 1) {
                    EchoAudioManager.playSound(ToneGenerator.TONE_SUP_CONFIRM, 150)
                    val nextLevel = if (gs.currentLevel == Int.MAX_VALUE) Int.MAX_VALUE else gs.currentLevel + 1
                    manager.view.startGame(0, nextLevel)
                    return true
                } else {
                    manager.view.returnToArchives()
                    return true
                }
            }
            hitOnDown = -1
        }
        return true
    }
    override fun onBackPressed(gs: GameState): Boolean {
        manager.view.returnToArchives()
        return true
    }
}

class StoryCutsceneState(private val manager: AppStateManager) : IAppState {
    private var downY = -1f

    override fun onEnter(gs: GameState) {}
    override fun onExit(gs: GameState) {
        downY = -1f
    }
    override fun update(dt: Float, gs: GameState, width: Float, height: Float, scale: Float) {
        manager.view.uiHelpMenu.update(dt)
    }
    override fun draw(c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, dt: Float) {
        c.drawColor(0xFF050A0F.toInt())
        val lines = if (gs.state == 4) StoryProtocol.badEndingLines else manager.view.currentStoryLines
        manager.view.storyStep = manager.view.menuRenderer.drawStory(c, lines, scale, gs, width, height, manager.view.storyStep)
    }
    override fun onTouch(e: MotionEvent, vx: Float, vy: Float, action: Int, gs: GameState, scale: Float, targetW: Float, targetH: Float): Boolean {
        if (action == MotionEvent.ACTION_DOWN) {
            downY = vy
        }
        if (action == MotionEvent.ACTION_UP) {
            val activeLines = if (gs.state == 4) StoryProtocol.badEndingLines else manager.view.currentStoryLines
            
            if (manager.view.storyStep < activeLines.size) {
                manager.view.storyStep = activeLines.size
                return true
            } 
            
            if (vy > targetH * 0.5f && downY > targetH * 0.5f) {
                if (gs.stateTimer < 0.5f) return true
                when (gs.state) {
                    5, 7 -> {
                        // Intro or Mid-story -> Start Gameplay
                        val targetState = if (gs.nextStateAfterStory != -1) gs.nextStateAfterStory else 1
                        manager.view.changeState(targetState)
                    }
                    6 -> {
                        // Ending -> Save Progress and back to Menu
                        if (gs.isPerfectEnd) {
                            SaveManager.addData(10000) // Perfect Bonus
                            SaveManager.updateStoryStreak(true, gs.difficulty == 1, gs.selectedStoryAct)
                        } else {
                            SaveManager.addData(2000) // Neutral Bonus
                        }
                        manager.view.disconnectCable()
                    }
                    else -> manager.view.disconnectCable()
                }
            }
            downY = -1f
        }
        return true
    }
    override fun onBackPressed(gs: GameState): Boolean {
        if (gs.state == 5 || gs.state == 7) {
            manager.view.onAppClose()
            return true
        }
        return true
    }
}

class SubMenuState(private val manager: AppStateManager) : IAppState {
    override fun onEnter(gs: GameState) {}
    override fun onExit(gs: GameState) {}
    override fun update(dt: Float, gs: GameState, width: Float, height: Float, scale: Float) {
        when (gs.state) {
            16 -> manager.view.uiSettings.update(dt)
            else -> manager.view.uiHelpMenu.update(dt)
        }
    }
    override fun draw(c: Canvas, gs: GameState, width: Float, height: Float, scale: Float, dt: Float) {
        c.drawColor(0xFF050A0F.toInt())
        when (gs.state) {
            10 -> manager.view.uiDecompiler.draw(c, width, height, scale, dt)
            11 -> manager.view.uiArchives.draw(c, width, height, gs, scale, dt)
            13 -> manager.view.uiArsenal.draw(c, width, height, scale, gs)
            14 -> manager.view.uiNanoOS.draw(c, width, height, scale, gs.timeSinceStart)
            15 -> manager.view.uiMainFrame.draw(c, width, height, gs, scale, dt)
            17 -> manager.view.uiTerminal.draw(c, width, height, scale)
            16 -> manager.view.uiSettings.draw(c, width, height, scale, gs)
        }
    }
    override fun onTouch(e: MotionEvent, vx: Float, vy: Float, action: Int, gs: GameState, scale: Float, targetW: Float, targetH: Float): Boolean {
        return when (gs.state) {
            10 -> manager.view.uiDecompiler.onTouch(vx, vy, action, scale, gs, manager.view.onAppClose)
            11 -> manager.view.uiArchives.onTouch(vx, vy, action,
                gs, scale, manager.view.onArchiveSelect, manager.view.onAppClose)
            13 -> manager.view.uiArsenal.onTouch(vx, vy, action, gs, manager.view.onAppClose)
            14 -> manager.view.uiNanoOS.onTouch(vx, vy, action, scale, { appIndex ->
                when (appIndex) {
                    0 -> manager.view.changeState(10)
                    1 -> manager.view.changeState(13)
                    2 -> {
                        manager.view.uiMainFrame.reset()
                        manager.view.changeState(15) // State 15: UIMainFrame (Simulation Control)
                    }
                    3 -> manager.view.changeState(17)
                    4 -> manager.view.changeState(16)
                }
            }, manager.view.onDisconnect)
            15 -> manager.view.uiMainFrame.onTouch(vx, vy, action, scale, gs, { route -> manager.view.onMenuRoute(route) }, { manager.view.onAppClose() })
            17 -> manager.view.uiTerminal.onTouch(e, scale, gs, manager.view.context, manager.view.onAppClose)
            16 -> manager.view.uiSettings.onTouch(e, vx, vy, action, scale, targetW, targetH, gs, manager.view.onAppClose, manager.view.onWipeData, manager.view.onOrientationChange) { manager.view.resolveHudLayout() }
            else -> true
        }
    }
    override fun onBackPressed(gs: GameState): Boolean {
        if (gs.state == 13 && manager.view.uiArsenal.handleBack()) return true
        if (gs.state == 16 && manager.view.uiSettings.handleBack { manager.view.resolveHudLayout() }) return true
        if (gs.state == 15 && manager.view.uiMainFrame.handleBackPressed()) return true
        if (gs.state == 10 || gs.state == 11 || gs.state == 13 || gs.state == 15 || gs.state == 17 || gs.state == 16 || gs.state == 14) {
            manager.view.onAppClose()
        }
        return true
    }
}
