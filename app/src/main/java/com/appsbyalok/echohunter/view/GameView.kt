package com.appsbyalok.echohunter.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ToneGenerator
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import com.appsbyalok.echohunter.MainActivity
import com.appsbyalok.echohunter.R
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.data.UpgradeSystem
import com.appsbyalok.echohunter.engine.AppStateId
import com.appsbyalok.echohunter.engine.DifficultyLevel
import com.appsbyalok.echohunter.engine.GameEngine
import com.appsbyalok.echohunter.engine.GameModeId
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.input.AttackMode
import com.appsbyalok.echohunter.input.TouchController
import com.appsbyalok.echohunter.navigation.NavManager
import com.appsbyalok.echohunter.statemachine.AppStateManager
import com.appsbyalok.echohunter.systems.CollisionSystem
import com.appsbyalok.echohunter.systems.EffectSystem
import com.appsbyalok.echohunter.systems.EnemySystem
import com.appsbyalok.echohunter.systems.GlitchBossBehavior
import com.appsbyalok.echohunter.systems.GuardianBossBehavior
import com.appsbyalok.echohunter.systems.OmegaBossBehavior
import com.appsbyalok.echohunter.systems.SpawnerSystem
import com.appsbyalok.echohunter.systems.StalkerBossBehavior
import com.appsbyalok.echohunter.systems.UltimaBossBehavior
import com.appsbyalok.echohunter.systems.triggerCinematicFocus
import com.appsbyalok.echohunter.ui.UIArchives
import com.appsbyalok.echohunter.ui.UIArsenal
import com.appsbyalok.echohunter.ui.UIDecompiler
import com.appsbyalok.echohunter.ui.UIHelpMenu
import com.appsbyalok.echohunter.ui.UIMainMenu
import com.appsbyalok.echohunter.ui.UINanoOS
import com.appsbyalok.echohunter.ui.UITerminal
import com.appsbyalok.echohunter.utils.EchoAudioManager
import com.appsbyalok.echohunter.utils.GameColors
import com.appsbyalok.echohunter.view.renderers.HUDRenderer
import com.appsbyalok.echohunter.view.renderers.MenuRenderer
import com.appsbyalok.echohunter.view.renderers.WorldRenderer
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Custom Android [View] hosting the game loop, surface rendering, and touch input processing.
 *
 * Bridges core subsystems (engine, state manager, renderers, audio, HUD) with Android view lifecycle callbacks,
 * handling surface scaling, aspect ratio fitting, frame rate timing, and save state persistence.
 */
class GameView(context: Context) : View(context) {

    val gs = GameState()
    internal val effectSys = EffectSystem()
    internal val enemySys = EnemySystem()
    internal val spawnerSys = SpawnerSystem(enemySys, effectSys)
    internal val collisionSys = CollisionSystem(gs, effectSys, enemySys, spawnerSys)

    val engine = GameEngine(gs, effectSys, enemySys, spawnerSys, collisionSys, context)

    internal val worldRenderer = WorldRenderer(context, effectSys, enemySys)
    internal val hudRenderer = HUDRenderer(context)
    internal val menuRenderer = MenuRenderer(context)
    internal val uiMainMenu = UIMainMenu(context)
    internal val uiHelpMenu = UIHelpMenu(context)
    internal val uiDecompiler = UIDecompiler()
    internal val uiArsenal = UIArsenal()
    internal val uiNanoOS = UINanoOS()
    internal val uiArchives = UIArchives()
    internal val uiTerminal = UITerminal()
    internal val uiSettings = com.appsbyalok.echohunter.ui.UISettings()
    internal val uiMainFrame = com.appsbyalok.echohunter.ui.UIMainFrame()
    internal val touchController = TouchController(gs)

    internal var storyStep = 0
    internal var currentStoryLines = StoryProtocol.storyIntroLines

    // --- THE APP STATE MANAGER HOOK ---
    internal val stateManager = AppStateManager(this, gs)

    var gameScale = 1f
    var lastFrameTime = System.nanoTime()

    internal val navManager = NavManager(gs)

    // --- Callbacks for State Machine & UI ---
    internal var lastExitTapTime = 0L

    internal val onAppClose: () -> Unit = {
        if (navManager.isStackEmpty()) {
            if (gs.state == AppStateId.MENU) {
                val now = System.currentTimeMillis()
                if (now - lastExitTapTime < 2000) {
                    (context as? MainActivity)?.finish()
                } else {
                    lastExitTapTime = now
                    gs.showGlobalMessage("TERMINATING SESSION...\nPress BACK again or use override to exit.", 2.5f, "EXIT NOW") {
                        (context as? MainActivity)?.finish()
                    }
                }
            } else {
                disconnectCable()
            }
        } else {
            val target = navManager.popPreviousState()
            if (target == null || target == gs.state || (target == AppStateId.MENU && gs.state.isSubMenu)) disconnectCable()
            else changeState(target!!, pushToHistory = false)
        }
    }
    internal val onArchiveSelect: (Int) -> Unit = { lvl -> startGame(GameModeId.CAMPAIGN, lvl) }
    internal val onHelpOpen: () -> Unit = { changeState(AppStateId.HELP) }
    internal val onHelpClose: () -> Unit = { onAppClose() }
    internal val onWipeData: () -> Unit = {
        gs.resetGame()
        disconnectCable()
    }
    internal val onOrientationChange: () -> Unit = {
        (context as? MainActivity)?.applyOrientation()
    }
    internal val onDifficultyToggle: () -> Unit = {
        if (SaveManager.isHardModeUnlocked) {
            gs.difficulty = if (gs.difficulty == DifficultyLevel.NORMAL) DifficultyLevel.HARD else DifficultyLevel.NORMAL
            EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 100)
        } else {
            EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 100)
        }
    }
    internal val onMenuRoute: (Int) -> Unit = { route ->
        when (route) {
            0 -> { // 0 = Sandbox -> Campaign Archives
                gs.gameMode = GameModeId.CAMPAIGN
                changeState(AppStateId.ARCHIVES)
            }
            1 -> {
                // Route 1 now goes to Mainframe/Simulations Hub
                uiMainFrame.reset()
                changeState(AppStateId.MAINFRAME) // State 15: UIMainFrame
            }
            2 ->{// 2 = Nano-OS -> OS Menu
                changeState(AppStateId.NANO_OS)
            }
            151 -> { // Deep link to Act selection details
                uiMainFrame.openActDetails(0) // Act 1
                changeState(AppStateId.MAINFRAME)
            }
            100 -> { // 100 = Training Route
                gs.gameMode = GameModeId.TRAINING // TrainingMode
                startGame(GameModeId.TRAINING, 1) // Mode 2, Level 1
            }
            101 -> { // 101 = Story Route (Act 1)
                gs.selectedStoryAct = 0
                startGame(GameModeId.STORY, 1) // Start at Level 1
            }
            102 -> { // Act 2
                gs.selectedStoryAct = 1
                startGame(GameModeId.STORY, 16) // Starts from level 16
            }
            103 -> { // Act 3
                gs.selectedStoryAct = 2
                startGame(GameModeId.STORY, 31) // Starts from level 31
            }
            104 -> { // Act 1 (Corrupted)
                gs.selectedStoryAct = 0
                gs.difficulty = DifficultyLevel.HARD
                startGame(GameModeId.STORY, 1)
            }
            105 -> { // Act 2 (Corrupted)
                gs.selectedStoryAct = 1
                gs.difficulty = DifficultyLevel.HARD
                startGame(GameModeId.STORY, 16)
            }
            106 -> { // Act 3 (Corrupted)
                gs.selectedStoryAct = 2
                gs.difficulty = DifficultyLevel.HARD
                startGame(GameModeId.STORY, 31)
            }
        }
    }
    internal val onDisconnect: () -> Unit = { disconnectCable() }


    private val pAlert = Paint().apply {
        color = GameColors.YELLOW
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val rectToast = RectF()
    private val rectToastAction = RectF()

    init {
        EchoAudioManager.init()

        // Attach Engine Logic
        engine.onChangeState = { s -> changeState(s) }
        engine.onDamage = { s -> takeDamage(s) }
        engine.onScore = { s -> addScore(s) }
        engine.onCoreUnlock = { p -> handleCoreUnlock(p) }
        engine.onBossTrigger = { t, s -> triggerBoss(t, s) }
        engine.onStoryState = { lines, nextState ->
            currentStoryLines = lines
            storyStep = 0
            gs.nextStateAfterStory = nextState
            changeState(AppStateId.STORY_MID)
        }

        touchController.onPauseClicked = { pauseGame() }
        touchController.onPulseTriggered = { triggerPulseAction() }

        // --- INIT STATE MANAGER ---
        syncStateToManager()
    }

    fun startGame(mode: GameModeId, level: Int) {
        gs.gameMode = mode
        gs.currentLevel = level
        gs.resetGame()

        if (mode == GameModeId.CAMPAIGN) {
            SaveManager.incrementLevelAttempts(level, gs.difficulty == DifficultyLevel.HARD)
        }
        
        // Restore persistent combat/loadout preferences after resetGame clears transient state.
        gs.controls.activeAttackMode = AttackMode.fromInt(SaveManager.activeAttackMode)
        gs.controls.currentWeapon = SaveManager.activeWeapon
        gs.controls.currentTrap = SaveManager.activeTrap
        gs.isAutoPilotActive = SaveManager.isAutoPilotEnabled
        gs.autoPilotTimer = if (gs.isAutoPilotActive) 600f else 0f

        effectSys.reset()
        enemySys.respawnAll(gs)
        engine.generateLevelMaze(width.toFloat(), height.toFloat(), gameScale)
        uiMainMenu.disconnect()
        lastFrameTime = System.nanoTime()
        EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 50)


        if (mode == GameModeId.STORY) {
            currentStoryLines = gs.modeStrategy.getIntroLines()
            storyStep = 0
            gs.nextStateAfterStory = AppStateId.PLAYING
            changeState(AppStateId.STORY_INTRO, pushToHistory = false)
        } else {
            changeState(AppStateId.PLAYING, pushToHistory = false)
        }
    }

    fun disconnectCable() {
        clearRunTransientEffects()
        uiMainMenu.disconnect()
        navManager.clearHistory()
        changeState(AppStateId.MENU, pushToHistory = false)
        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 100)
    }

    fun returnToArchives() {
        clearRunTransientEffects()
        
        // Return to Hub (15) if Story/Training, else Archives (11)
        if (gs.gameMode == GameModeId.STORY || gs.gameMode == GameModeId.TRAINING) {
            uiMainFrame.reset()
            changeState(AppStateId.MAINFRAME, pushToHistory = false)
        } else {
            changeState(AppStateId.ARCHIVES, pushToHistory = false)
        }
        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 100)
    }

    private fun cleanupLevelEffects() {
        if (!StoryProtocol.isGlitchActive) {
            gs.shakeAmount = 0f
            gs.chromaticIntensity = 0f
            gs.sectorFlash = 0f
        }
    }

    /** Clears effects that belong to an abandoned run and must never bleed into menu UI. */
    private fun clearRunTransientEffects() {
        cleanupLevelEffects()
        gs.isLevelCleared = false
        gs.winDelayTimer = 0f
        gs.slowMoTimer = 0f
        gs.damageFlash = 0f
        gs.sectorFlash = 0f
        gs.empFlashTimer = 0f
        gs.whiteFlash = 0f
        gs.shakeAmount = 0f
        gs.chromaticIntensity = 0f
        gs.shockwaveActive = false
    }

    fun pauseGame() {
        if (gs.state.isGameplay) {
            changeState(AppStateId.PAUSE, pushToHistory = false)
            EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 100)
        }
    }

    fun resetGame() {
        val cm = gs.gameMode
        val cl = gs.currentLevel
        startGame(cm, cl)
    }

    fun changeState(newState: AppStateId, pushToHistory: Boolean = true) {
        // Safety: Reset touch controller whenever state changes to prevent hardlocked HUD elements
        if (gs.state != newState) {
            if (pushToHistory) navManager.pushCurrentState()
            touchController.reset()
        }
        gs.state = newState
        gs.stateTimer = 0f
        syncStateToManager()
    }

    // --- State Switcher ---
    private fun syncStateToManager() {
        val newStateObj = when (gs.state) {
            AppStateId.MENU -> stateManager.mainMenuState
            AppStateId.PLAYING, AppStateId.CORE_MERGE, AppStateId.PERFECT_END_ZOOM -> stateManager.gameplayState
            AppStateId.PAUSE -> stateManager.pauseState
            AppStateId.HELP -> stateManager.helpState
            AppStateId.STORY_GAMEOVER, AppStateId.STORY_INTRO, AppStateId.STORY_ENDING, AppStateId.STORY_MID -> stateManager.storyState
            AppStateId.VICTORY -> stateManager.victoryState
            AppStateId.DECOMPILER, AppStateId.ARCHIVES, AppStateId.ARSENAL, AppStateId.NANO_OS, AppStateId.MAINFRAME, AppStateId.SETTINGS, AppStateId.TERMINAL -> stateManager.subMenuState
        }
        if (stateManager.currentState != newStateObj) {
            stateManager.changeState(newStateObj)
        }
    }

    private fun takeDamage(scale: Float) {
        if (gs.gameMode == GameModeId.TRAINING || gs.isLevelCleared) return
        if (gs.modGodMode && gs.hp <= 1) {
            StoryProtocol.showIngameMessage("MOD: GOD MODE PREVENTED DEATH", 1.5f)
            return
        }

        gs.hp--
        gs.tookDamageInLevel = true
        gs.combo = 0
        gs.comboBreakTimer = 1.0f
        gs.overclockMeter -= gs.overclockMeter * 0.25f
        gs.playerIframe = 1.5f
        gs.damageFlash = 1.0f
        gs.shakeAmount = scale * 0.08f
        gs.chromaticIntensity = 1.0f
        
        // --- Damage Hardware Integrity ---
        val damagedNodeId = SaveManager.damageRandomUnlockedNode(10f)
        if (damagedNodeId != null && Math.random() < 0.3) {
            StoryProtocol.showIngameMessage("SYSTEM_ERROR: $damagedNodeId INTEGRITY DROP", 1.2f)
        }

        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 150)
        StoryProtocol.showIngameMessage(R.string.msg_damage_detected, 1.5f)

        if (gs.hp <= 0) {
            SaveManager.addData(gs.collectedDataKB)
            SaveManager.saveRunResult(gs.score)
            if (gs.gameMode == GameModeId.STORY) SaveManager.updateStoryStreak(false, gs.difficulty == DifficultyLevel.HARD, gs.selectedStoryAct)

            val config = com.appsbyalok.echohunter.data.LevelEngine.getLevelConfig(gs.currentLevel)
            StoryProtocol.isGlitchActive =
                config.features.contains(com.appsbyalok.echohunter.data.LevelFeature.BOSS) ||
                    config.features.contains(com.appsbyalok.echohunter.data.LevelFeature.ELIMINATION) ||
                    gs.difficulty == DifficultyLevel.HARD

            currentStoryLines = StoryProtocol.badEndingLines
            storyStep = 0
            gs.nextStateAfterStory = AppStateId.MENU
            changeState(AppStateId.STORY_GAMEOVER)
        }
    }

    private fun addScore(points: Long) {
        gs.score += points
//        if (gs.score > SaveManager.highScore) {
//            // Can trigger a small sound effect for high score
//        }
    }

    private fun handleCoreUnlock(perfectEnd: Boolean) {
        var finalReward = gs.collectedDataKB
        if (gs.gameMode == GameModeId.STORY) {
            val currentStreak = if (gs.difficulty == DifficultyLevel.HARD) SaveManager.currentHardStreak else SaveManager.currentStoryStreak
            val mul = when (currentStreak) { 0, 1 -> 1.0; 2 -> 1.25; 3 -> 1.50; else -> 2.0 }
            finalReward = (finalReward * mul).toLong()
            SaveManager.updateStoryStreak(true, gs.difficulty == DifficultyLevel.HARD, gs.selectedStoryAct)

            if (SaveManager.unlockedStoryStreak >= 3) {
                StoryProtocol.showIngameMessage("ADMIN: \"TRACE COMPLETED. ENGAGING BLACKOUT.\"", 5f)
                EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_ALERT, 1000)
            } else {
                StoryProtocol.showIngameMessage("PAYLOAD ACCESSED. EXTRACTING...", 4f)
            }
        } else {
            StoryProtocol.showIngameMessage("SYSTEM CORE UNLOCKED. FOLLOW THE SIGNAL.", 4f)
        }

        SaveManager.addData(finalReward)
        SaveManager.saveRunResult(gs.score)

        gs.isPerfectEnd = perfectEnd
        gs.coreRadius = gameScale * 0.15f
        if (gs.coreX <= 0f || gs.coreY <= 0f) {
            gs.coreX = gs.px + gameScale * 0.5f
            gs.coreY = gs.py
        }
        for (i in 0 until enemySys.n) {
            enemySys.ex[i] = -5000f
            enemySys.ey[i] = -5000f
            enemySys.vis[i] = 0f
        }
        changeState(AppStateId.CORE_MERGE)
    }

    private fun triggerBoss(type: Int, scale: Float) {
        gs.bossActive = true
        var bType = type
        if (gs.currentLevel % 100 == 0) bType = 5 // Force Ultra Boss on level 100/200/etc
        
        gs.bossType = bType
        gs.bossLockTimer = 1.0f 

        val behavior = when (bType) {
            1 -> GuardianBossBehavior
            2 -> StalkerBossBehavior
            3 -> GlitchBossBehavior
            4 -> OmegaBossBehavior
            5 -> UltimaBossBehavior
            else -> GuardianBossBehavior
        }
        
        val config = com.appsbyalok.echohunter.data.LevelEngine.getLevelConfig(gs.currentLevel, gs.difficulty.id)
        val bossScaling = com.appsbyalok.echohunter.data.LevelEngine.getSaturatedValue(gs.currentLevel, 0f, 475f, 300f)

        // Difficulty-based Boss HP scaling
        val difficultyHpMult = if (gs.difficulty == DifficultyLevel.HARD) 1.2f else 0.7f
        gs.bossHp = ((25 + bossScaling) * behavior.baseHpMult * config.hpMultiplier * difficultyHpMult).toInt()
        gs.bossMaxHp = gs.bossHp
        var safeX = gs.px + scale * 1.2f
        var safeY = gs.py
        gs.gridMap?.let { grid ->
            val minDistanceSq = (scale * 0.8f) * (scale * 0.8f)
            var found = false
            repeat(101) {
                if (!found) {
                    val rx = Random.nextInt(1, grid.size - 1)
                    val ry = Random.nextInt(1, grid[0].size - 1)
                    if (grid[rx][ry] != com.appsbyalok.echohunter.data.MazeGenerator.WALL) {
                        val tryX = rx * gs.tileSize + gs.tileSize / 2f
                        val tryY = ry * gs.tileSize + gs.tileSize / 2f
                        val dx = tryX - gs.px
                        val dy = tryY - gs.py
                        if (dx * dx + dy * dy > minDistanceSq) {
                            safeX = tryX
                            safeY = tryY
                            found = true
                        }
                    }
                }
            }
        }
        gs.bossX = safeX
        gs.bossY = safeY
        gs.isBossRage = false
        gs.shakeAmount = scale * 0.12f // Stronger shake on boss spawn
        gs.damageFlash = 0.3f
        gs.chromaticIntensity = 0.5f

        // --- CENTRALIZED CINEMATIC FOCUS ---
        gs.triggerCinematicFocus(safeX, safeY, zoom = 1.4f, duration = 1.5f, hitStop = 0.2f)
        gs.shakeAmount = scale * 0.15f // Intense vibration on boss arrival

        StoryProtocol.startBossIntro(bType)
        gs.showGlobalMessage(behavior.spawnMessage, 4f)
        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_ALERT, 400)
        enemySys.spawnSwarmIfNeeded(gs, scale)
    }

    private fun triggerPulseAction() {
        if (gs.cooldownTimer <= 0f) {
            gs.pulse = true
            gs.pulseR = 0f
            gs.cooldownTimer = 0.25f * UpgradeSystem.getPulseCooldownMultiplier()
            if (gs.isDarknessLevel) {
                gs.visionClarity = max(0.0f, gs.visionClarity - 0.25f)
            }
            gs.globalSonarAlert = true
            EchoAudioManager.playSound(ToneGenerator.TONE_SUP_CONFIRM, 150)
        } else {
            EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 100)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gameScale = min(w, h).toFloat()
        uiMainMenu.initLayout(w.toFloat(), h.toFloat())
        worldRenderer.updateDashEffect(gameScale)

        resolveHudLayout(w.toFloat(), h.toFloat())
    }

    fun resolveHudLayout(targetW: Float = width.toFloat(), targetH: Float = height.toFloat()) {
        if (targetW <= 0f || targetH <= 0f) return

        // Apply Safe Area Insets (Notch Handling from SaveManager)
        val insetL = SaveManager.lastInsetLeft
        val insetR = SaveManager.lastInsetRight
        val insetT = SaveManager.lastInsetTop
        val insetB = SaveManager.lastInsetBottom

        // Sync with HUDLayout for other systems
        gs.hudLayout.safeInsetLeft = insetL
        gs.hudLayout.safeInsetRight = insetR
        gs.hudLayout.safeInsetTop = insetT
        gs.hudLayout.safeInsetBottom = insetB

        val isPortrait = targetH > targetW
        gs.hudLayout.resolve(SaveManager.loadHudLayoutProfile(isPortrait), targetW, targetH, gameScale)
        gs.touch.moveBaseX = gs.hudLayout.movementX
        gs.touch.moveBaseY = gs.hudLayout.movementY
        gs.touch.moveKnobX = gs.touch.moveBaseX
        gs.touch.moveKnobY = gs.touch.moveBaseY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = ((now - lastFrameTime) / 1000000000.0).toFloat().coerceAtMost(0.05f)
        lastFrameTime = now

        engine.update(dt, width.toFloat(), height.toFloat(), gameScale)

        // StateManager updates UI logic
        stateManager.update(dt, width.toFloat(), height.toFloat(), gameScale)

        // --- MODULAR DRAW (No more giant when block!) ---
        stateManager.draw(canvas, width.toFloat(), height.toFloat(), gameScale, dt)

        drawTransientOverlays(canvas, dt)
        worldRenderer.drawCRTOverlay(canvas, gs, width.toFloat(), height.toFloat())

        // Global Overlay Message (Universal Toast - Restored Top Bar Style)
        // Global Overlay Message (Universal Terminal Snackbar)
        if (gs.globalMessageTimer > 0f) {
            val alpha = (min(1f, gs.globalMessageTimer * 2f) * 255).toInt().coerceIn(0, 255)
            val isPortrait = height > width
            
            // Text Configuration
            pAlert.textSize = gameScale * 0.032f
            pAlert.typeface = Typeface.MONOSPACE
            pAlert.style = Paint.Style.FILL
            
            val textPadding = gameScale * 0.04f
            val hasAction = gs.globalMessageActionLabel.isNotEmpty()
            
            // Layout Calculations
            val boxW = if (isPortrait) width * 0.92f else width * 0.65f
            val actionAreaWidth = if (hasAction) gameScale * 0.22f else 0f
            val maxTextWidth = boxW - textPadding * 2 - actionAreaWidth
            
            // Wrap text into lines
            val wrappedLines = mutableListOf<String>()
            val rawLines = gs.globalMessage.split("\n")
            outer@for (rawLine in rawLines) {
                var remaining = rawLine
                while (remaining.isNotEmpty()) {
                    val count = pAlert.breakText(remaining, true, maxTextWidth, null)
                    if (count <= 0) break
                    
                    var actualCount = count
                    if (count < remaining.length) {
                        // Try to find the last space before count to avoid breaking words
                        val lastSpace = remaining.substring(0, count).lastIndexOf(' ')
                        if (lastSpace != -1 && lastSpace > 0) {
                            actualCount = lastSpace + 1
                        }
                    }
                    
                    wrappedLines.add(remaining.substring(0, actualCount).trim())
                    remaining = remaining.substring(actualCount).trim()
                    if (wrappedLines.size >= 3) break@outer // Max 3 lines
                }
            }
            
            val lineHeight = pAlert.textSize * 1.3f
            val boxH = max(gameScale * 0.1f, wrappedLines.size * lineHeight + textPadding * 2)
            val boxX = width / 2f
            val boxY = gs.hudLayout.safeInsetTop + gameScale * 0.02f // Positioned at top to avoid joysticks

            val rect = rectToast.apply {
                set(boxX - boxW/2f, boxY, boxX + boxW/2f, boxY + boxH)
            }

            // Draw Background (Terminal Dark)
            pAlert.color = (alpha shl 24) or 0x0D0D0D
            canvas.drawRect(rect, pAlert)
            
            // Neon Left Accent Bar
            pAlert.color = (alpha shl 24) or (GameColors.CLARITY and 0xFFFFFF)
            canvas.drawRect(rect.left, rect.top, rect.left + gameScale * 0.008f, rect.bottom, pAlert)

            // Border (Subtle Cyan)
            pAlert.style = Paint.Style.STROKE
            pAlert.strokeWidth = 1f
            pAlert.color = (alpha / 2 shl 24) or (GameColors.CLARITY and 0xFFFFFF)
            canvas.drawRect(rect, pAlert)

            // Render Text Lines
            pAlert.style = Paint.Style.FILL
            pAlert.textAlign = Paint.Align.LEFT
            pAlert.color = (alpha shl 24) or (GameColors.CLARITY and 0xFFFFFF)
            wrappedLines.forEachIndexed { index, line ->
                canvas.drawText(line, rect.left + textPadding, rect.top + textPadding + pAlert.textSize * 0.8f + index * lineHeight, pAlert)
            }

            // Draw Action Button (if present)
            if (hasAction) {
                val btnW = actionAreaWidth - textPadding
                val btnH = gameScale * 0.065f
                rectToastAction.set(rect.right - btnW - textPadding, rect.centerY() - btnH/2f, rect.right - textPadding, rect.centerY() + btnH/2f)
                
                pAlert.style = Paint.Style.FILL
                pAlert.color = (alpha shl 24) or (GameColors.YELLOW and 0xFFFFFF)
                canvas.drawRoundRect(rectToastAction, 4f, 4f, pAlert)
                
                pAlert.color = (alpha shl 24) or 0x000000
                pAlert.textAlign = Paint.Align.CENTER
                pAlert.textSize = gameScale * 0.026f
                pAlert.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                canvas.drawText(gs.globalMessageActionLabel, rectToastAction.centerX(), rectToastAction.centerY() + pAlert.textSize * 0.35f, pAlert)
            } else {
                rectToastAction.setEmpty()
            }
            pAlert.textAlign = Paint.Align.CENTER // Reset for other UI elements

            gs.globalMessageTimer -= dt
        } else {
            rectToastAction.setEmpty()
        }

        invalidate()
    }

    fun drawTransientOverlays(canvas: Canvas, dt: Float) {
        // Transient effects are run-scoped. Menus must not render a paused game frame.
        if (!gs.state.isGameplay && gs.state != AppStateId.VICTORY) return

        if (gs.damageFlash > 0f) {
            canvas.drawColor((gs.damageFlash * 100).toInt() shl 24 or 0xFF0000)
            gs.damageFlash = max(0f, gs.damageFlash - dt * 2f)
        }
        if (gs.sectorFlash > 0f) {
            canvas.drawColor((gs.sectorFlash * 80).toInt() shl 24 or 0x00FFFF)
            gs.sectorFlash = max(0f, gs.sectorFlash - dt * 1.5f)
        }
        if (gs.empFlashTimer > 0f && Random.nextDouble() < 0.3) {
            canvas.drawColor((220 shl 24) or 0x050505)
        }
        if (gs.whiteFlash > 0f) {
            canvas.drawColor((min(255, (gs.whiteFlash * 255).toInt()) shl 24) or 0xFFFFFF)
        }

        // Victory Overlay Feedback
        if (gs.isLevelCleared && gs.winDelayTimer > 0f) {
            val progress = (1.5f - gs.winDelayTimer) / 1.5f // 0.0 to 1.0
            
            // 1. Full screen success tint (Subtle Cyan/Green)
            canvas.drawColor(((progress * 40).toInt() shl 24) or 0x00FFCC)

            // 2. Big Center Text with Scale-up Effect
            pAlert.textSize = gameScale * 0.12f
            pAlert.color = (GameColors.CLARITY and 0xFFFFFF) or (255 shl 24)
            pAlert.style = Paint.Style.FILL
            pAlert.textAlign = Paint.Align.CENTER
            
            val centerX = width / 2f
            val centerY = height / 2f
            
            val textScale = 0.8f + (progress * 0.2f)
            canvas.save()
            canvas.scale(textScale, textScale, centerX, centerY)
            canvas.drawText("MISSION", centerX, centerY - pAlert.textSize * 0.5f, pAlert)
            canvas.drawText("ACCOMPLISHED", centerX, centerY + pAlert.textSize * 0.5f, pAlert)
            canvas.restore()

            // 3. Moving Scanline effect
            val lineY = (progress * height * 1.5f) % height
            pAlert.strokeWidth = gameScale * 0.01f
            pAlert.color = 0x8800FFCC.toInt()
            canvas.drawLine(0f, lineY, width.toFloat(), lineY, pAlert)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        if (gs.globalMessageTimer > 0f && !rectToastAction.isEmpty) {
            if (rectToastAction.contains(x, y) && event.action == MotionEvent.ACTION_UP) {
                gs.globalMessageAction?.invoke()
                gs.globalMessageTimer = 0f
                return true
            }
        }

        // --- MODULAR TOUCH (TouchController aur menus handle) ---
        return stateManager.onTouch(event, x, y, event.action, gameScale, width.toFloat(), height.toFloat())
    }


    fun handleBackPressed(): Boolean {
        val handled = stateManager.onBackPressed()
        if (!handled && gs.state == AppStateId.MENU) {
            onAppClose()
            return true
        }
        return handled
    }

    fun saveState(outState: Bundle) {
        gs.saveState(outState)
        outState.putIntArray("currentStoryLines", currentStoryLines)
        outState.putInt("storyStep", storyStep)
        navManager.saveState(outState)
    }

    fun restoreState(savedInstanceState: Bundle) {
        gs.restoreState(savedInstanceState)
        savedInstanceState.getIntArray("currentStoryLines")?.let { currentStoryLines = it }
        storyStep = savedInstanceState.getInt("storyStep", 0)
        navManager.restoreState(savedInstanceState)
        syncStateToManager()
    }

    // --- LIFECYCLE ---
    fun onPause() {
        pauseGame()
        val b = Bundle()
        gs.saveState(b)
        // Ensure you call shared preferences saving if implemented
    }

    fun onResume() {
        lastFrameTime = System.nanoTime()
    }
}
