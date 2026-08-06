package com.appsbyalok.echohunter.engine

import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import com.appsbyalok.echohunter.data.LevelEngine
import com.appsbyalok.echohunter.data.LevelFeature
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.data.UpgradeSystem
import com.appsbyalok.echohunter.input.ControlsState
import com.appsbyalok.echohunter.input.HUDLayout
import com.appsbyalok.echohunter.input.HudAction
import com.appsbyalok.echohunter.input.TouchState
import com.appsbyalok.echohunter.modes.CampaignMode
import com.appsbyalok.echohunter.modes.GameModeStrategy
import com.appsbyalok.echohunter.modes.IGameObjective
import com.appsbyalok.echohunter.modes.StandardObjective
import com.appsbyalok.echohunter.modes.StoryMode
import com.appsbyalok.echohunter.modes.TrainingMode
import com.appsbyalok.echohunter.utils.EchoAudioManager
import kotlin.math.max
import kotlin.math.min

class GameState {
    var activeObjective: IGameObjective = StandardObjective() // Current goal the player needs to fulfill
    var modeStrategy: GameModeStrategy = CampaignMode() // Logic handler for the active game mode
    var gameMode = GameModeId.CAMPAIGN // Identifier for current game mode (Campaign, Story, Training)
        set(value) {
            field = value
            modeStrategy = when (value) {
                GameModeId.STORY -> StoryMode()
                GameModeId.TRAINING -> TrainingMode()
                else -> CampaignMode()
            }
        }

    var levelStartTime = 0f // Timestamp of when the current level started
    var levelClearTime = 0f // Captured time when level was successfully cleared

    var state = AppStateId.STORY_INTRO // Start with intro logs
    var difficulty = DifficultyLevel.NORMAL // Selected difficulty level (Normal, Hard)
    var stateTimer = 0f // General timer for state-specific durations
    var nextStateAfterStory = AppStateId.MENU // After intro logs, go to Menu
    var timeSinceStart = 0f // Total elapsed time since the game session began

    var selectedStoryAct = 0 // Index of the currently selected story chapter

    val hudLayout = HUDLayout()
    val controls = ControlsState()
    val touch = TouchState()

    // --- GLOBAL SNACK BAR / TOAST SYSTEM ---
    var globalMessage = "" // Current message text to display in the global snackbar
    var globalMessageTimer = 0f // Remaining duration for the global message display
    var globalMessageActionLabel = "" // Optional action button label
    var globalMessageAction: (() -> Unit)? = null // Optional action callback

    fun showGlobalMessage(msg: String, duration: Float = 2f, actionLabel: String = "", action: (() -> Unit)? = null) {
        Log.d("TAG", "showGlobalMessage called: $msg")
        globalMessage = msg
        globalMessageTimer = duration
        globalMessageActionLabel = actionLabel
        globalMessageAction = action
    }

    // --- AUTOPILOT & DOUBLE TAP ---
    var isAutoPilotActive = false // Whether the AI is currently controlling player movement
    var autoPilotTimer = 0f // Duration or cooldown tracking for autopilot mode

    // NAYA: MOD MENU FLAGS
    var modGodMode = false // Cheat flag for player invincibility
    var modInfiniteOvr = false // Cheat flag for unlimited overclock meter
    var modFullVisibility = false // Cheat flag to remove visibility restrictions (fog of war)
    var modInfinityTraps = false // Cheat flag for unlimited traps

    var gridMap: Array<IntArray>? = null // 2D layout representing walls and walkable areas
    var wallVisMap: Array<FloatArray>? = null // Tracks persistence of wall visibility
    var tileSize = 100f // Size of each grid cell in world units
    var mapWidth = 0f // Total width of the current map
    var mapHeight = 0f // Total height of the current map

    var px = 0f // Player's current world X position
    var py = 0f // Player's current world Y position

    var lastFacingX = 1f // The horizontal direction the player last moved towards
    var lastFacingY = 0f // The vertical direction the player last moved towards

    var lastIntentionalAimX = 1f // Stores the last direction the player aimed manually
    var lastIntentionalAimY = 0f

    val maxSpikes = 12 // Maximum number of active projectiles (spikes) allowed
    val spikeX = FloatArray(maxSpikes) // X positions of projectiles
    val spikeY = FloatArray(maxSpikes) // Y positions of projectiles
    val spikeVx = FloatArray(maxSpikes) // X velocities of projectiles
    val spikeVy = FloatArray(maxSpikes) // Y velocities of projectiles
    val spikeLife = FloatArray(maxSpikes) // Remaining life/duration of projectiles
    val spikeActive = BooleanArray(maxSpikes) // Active status of projectile slots
    val spikeType = IntArray(maxSpikes) // Type identifier for different projectile effects
    val spikeDamage = FloatArray(maxSpikes) // Base damage of each projectile
    val spikeArcTriggered = BooleanArray(maxSpikes) // Arc Conduit chains once per sniper shot

    var elimTargetsKilled = 0
    var elimTargetsRequired = 0

    var coreHp = 10 // Current health of the core being defended
    var coreMaxHp = 10 // Maximum possible health of the core
    var defWaveCurrent = 1 // Current wave number in defense mode
    var defWaveMax = 1 // Total waves to survive in defense mode
    var defWaveState = 0 // Defense phase state (0: Buffer, 1: Active, 2: Cooldown)
    var defWaveTimer = 0f // Timer for defense wave transitions
    var defEnemiesToSpawn = 0 // Number of enemies remaining to spawn in the current wave
    var defEnemiesAlive = 0 // Number of active enemies currently in the defense arena

    var escapeGateActive = false // Whether the level exit portal is currently available

    // --- GLOBAL SPAWNER NODES ---
    var spawnerNodes = mutableListOf<com.appsbyalok.echohunter.systems.SpawnNode>()

    var attackCooldown = 0f // Time remaining before the next attack can be performed
    var trapCooldownTimer = 0f // Time remaining before the next trap can be deployed
    var sonarTimer = 0f // Time remaining before next Sonar Ping

    var globalSonarAlert = false // Flag for high-priority sonar-detected threats
    var localAttackAlert = false // Flag for immediate proximity threats

    class ActiveTrap(
        val type: Int,
        var x: Float,
        var y: Float,
        var timer: Float,
        val duration: Float,
        val rangeMultiplier: Float = 1.0f
    )
    val activeTraps = mutableListOf<ActiveTrap>()

    val isCamouflaged: Boolean get() = activeTraps.any { it.type == 0 }
    val isDecoyActive: Boolean get() = activeTraps.any { it.type == 1 || it.type == 4 }
    val decoyX: Float get() = activeTraps.firstOrNull { it.type == 1 || it.type == 4 }?.x ?: 0f
    val decoyY: Float get() = activeTraps.firstOrNull { it.type == 1 || it.type == 4 }?.y ?: 0f

    var hp = 3 // Current health points of the player
    val maxHp: Int get() = 3 + UpgradeSystem.getBonusMaxHp() // Derived maximum health including upgrades
    var isTouching = false // General flag for any screen touch interaction

    var pulse = false // Whether a sonar pulse is currently propagating
    var pulseR = 0f // Current radius of the expanding sonar pulse
    var cooldownTimer = 0f // General purpose timer for ability cooldowns
    var visionClarity = 1.0f // Visual factor affecting how much of the map is visible

    // --- DARKNESS LOGIC ---
    val isDarknessLevel: Boolean get() {
        if (gameMode == GameModeId.STORY) return false // in Story mode full visibility also
        val config = LevelEngine.getLevelConfig(currentLevel)
        return config.features.contains(LevelFeature.DARKNESS)
    }

    val targetClarity: Float get() = if (isDarknessLevel || StoryProtocol.isBlackoutActive) 0.15f else 1.0f

    var shieldTimer = 0f // Remaining duration of active invulnerability shield
    private var shieldRechargeTimer = 0f // Progress toward the next passive shield refresh
    var playerIframe = 0f // Temporary invincibility period after being hit

    var overclockMeter = 0f // Current charge percentage of the overclock ability
    var overclockTimer = 0f // Remaining duration of the active overclock state
    var showOverclockTextTimer = 0f // Timer for displaying the "OVERCLOCK" UI announcement
    val isOverclocked: Boolean get() = overclockTimer > 0f // Derived status of being in overclock mode

    var score: Long = 0L // Player's total accumulated score
    var combo = 0 // Current streak of consecutive hits or actions
    var wave = 1 // Current wave number in survival or campaign phases

    var currentLevel = 1 // Index of the level being played
    var collectedDataKB = 0L // Amount of narrative "data" resource collected
    var isLevelCleared = false // Flag indicating if the level objective is complete
        set(value) {
            if (value && !field) {
                winDelayTimer = 1.5f // Delay victory screen to let the player see the final moment
                if (gameMode != GameModeId.STORY) slowMoTimer = 2.0f // Cinematic slow-mo for non-story modes
                whiteFlash = 0.5f    // Screen flash on win
                sectorFlash = 0.6f   // Greenish system success flash
                chromaticIntensity = 1.2f 
                
                // Trigger a celebratory shockwave
                shockwaveActive = true
                shockwaveX = px
                shockwaveY = py
                shockwaveR = 0f

                showGlobalMessage(">>> MISSION ACCOMPLISHED: SYSTEM SECURED <<<", 2.5f)
                EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP2, 200)

                levelClearTime = timeSinceStart - levelStartTime
                val totalDurationSeconds = levelClearTime
                val minutes = (totalDurationSeconds / 60).toInt()
                val seconds = (totalDurationSeconds % 60).toInt()

                val pilotType = if (isAutoPilotActive) "AUTOPILOT" else "MANUAL_PLAYER"
                val targetScore =
                    LevelEngine.getLevelConfig(currentLevel).targetScore

                // Android Studio Logcat logs
                Log.d(
                    "ECHO_HUNTER_PERF",
                    ">>> LEVEL $currentLevel CLEARED BY [$pilotType] | TIME ELAPSED: ${minutes}m ${seconds}s (${totalDurationSeconds} seconds) <<<"
                )
                Log.d(
                    "ECHO_HUNTER_PERF",
                    "Current Score: $score, Max Score: $targetScore, Combo: $combo, Wave: $wave, Sector: $currentSector, Current Level: $currentLevel , Current Time: ${System.currentTimeMillis()}"
                )
            }
            field = value
        }

    var comboBreakTimer = 0f // Grace period before the combo counter resets
    var regenTimer = 0f // Passive Regen Timer
    var currentSector = 1 // Current subsection of the level (e.g., Sector 1 of 3)
    var sectorTarget = 30 // Objective target required to clear the current sector

    var cameraX = 0f // Current X offset of the game camera
    var cameraY = 0f // Current Y offset of the game camera
    var camLeadX = 0f // Smoothed camera lead offset (X)
    var camLeadY = 0f // Smoothed camera lead offset (Y)
    var cameraZoom = 1.0f // Current zoom factor (1.0 = normal)
    var targetZoom = 1.0f // Target zoom factor for smooth lerping
    var cameraFocusX = -1f // Optional focus point (e.g. Boss)
    var cameraFocusY = -1f 
    var cameraFocusWeight = 0f // 0.0 = Player, 1.0 = Focus Point

    // Camera + viewport single source of truth. The effective zoom never lets the
    // visible world grow larger than the current map.
    fun getCameraZoom(screenWidth: Float, screenHeight: Float): Float {
        val safeZoom = cameraZoom.coerceAtLeast(0.01f)
        val mapW = if (mapWidth > 0f) mapWidth else (gridMap?.size ?: 0) * tileSize
        val mapH = if (mapHeight > 0f) mapHeight else (gridMap?.getOrNull(0)?.size ?: 0) * tileSize
        val minZoomX = if (mapW > 0f) screenWidth / mapW else 0f
        val minZoomY = if (mapH > 0f) screenHeight / mapH else 0f
        return max(safeZoom, max(minZoomX, minZoomY))
    }

    fun getViewportW(screenWidth: Float, screenHeight: Float): Float =
        screenWidth / getCameraZoom(screenWidth, screenHeight)

    fun getViewportH(screenWidth: Float, screenHeight: Float): Float =
        screenHeight / getCameraZoom(screenWidth, screenHeight)

    var pvx = 0f // Player velocity X
    var pvy = 0f // Player velocity Y

    var damageFlash = 0f // Visual effect timer for screen flash when taking damage
    var sectorFlash = 0f // Visual effect timer for sector transitions
    var shakeAmount = 0f // Intensity of the camera shake effect
    var empFlashTimer = 0f // Timer for the visual flash triggered by EMPs
    var timeScale = 1.0f // Global speed multiplier for game logic (e.g., 0.5f for half-speed)
    var slowMoTimer = 0f // Remaining duration for slow-motion effects
    var lastDt = 0.016f // Delta time of the last frame

    var hitStopTimer = 0f // Duration to freeze the game momentarily for impact feedback

    var isBlackoutActive = false
    var tutorialEnabledActions: Set<HudAction> = HudAction.entries.toSet()
    var tutorialHighlightedEnemyIndex = -1
    var tutorialGateOpen = false
    var isPerfectEnd = false // Flag if the level was completed without taking damage
    var tookDamageInLevel = false // Track if any damage was taken in the current level
    var coreX = 0f // Target X position for the end-level core sequence
    var coreY = 0f // Target Y position for the end-level core sequence
    var coreRadius = 0f // Visual radius of the end-level core
    var mergeTimer = 0f // Timer for the level-completion "merging" cinematic

    // Bomb Mode
    var bombTargetX = -9999f
    var bombTargetY = -9999f
    var whiteFlash = 0f // Intensity of the screen-clearing white flash effect
    var winDelayTimer = 0f // Delay before showing victory screen

    val tutorialSkipStepRect = android.graphics.RectF()
    val tutorialSkipAllRect = android.graphics.RectF()

    var chromaticIntensity = 0f // Intensity of the chromatic aberration post-processing effect
    var shockwaveR = 0f // Current radius of a visual shockwave effect
    var shockwaveX = 0f // World X origin of a shockwave
    var shockwaveY = 0f // World Y origin of a shockwave
    var shockwaveActive = false // Whether a shockwave effect is currently being rendered

    var bossDeathTimer = 0f // Timer for the boss's defeat cinematic
    var bossDeathX = 0f // World X position where the boss was defeated
    var bossDeathY = 0f // World Y position where the boss was defeated
    var isBossRage = false // Whether the boss is in its high-intensity "rage" phase

    var bossAttackTimer = 0f // Timer to manage boss attack patterns
    var bossAttackState = 0 // Current attack state (0: Idle, 1: Charging, 2: Executing)
    var bossAttackCounter = 0 // Counter for consecutive attacks (e.g., double jump)
    var bossZ = 0f // Height offset for "Jump" attacks

    var isEnemyNear = false // Proximity flag for general enemy presence
    var isEnemyVeryNear = false // Proximity flag for immediate enemy threats
    var radarPingTimer = 0f // Timer for the periodic radar UI pulse
    var heartbeatTimer = 0f // Timer for the proximity-based audio "heartbeat" effect

    var bossActive = false // Whether a boss is currently present in the level
    var bossHp = 0 // Current health points of the boss
    var bossMaxHp = 0 // Maximum possible health of the boss
    var bossX = -1000f // World X position of the boss
    var bossY = -1000f // World Y position of the boss
    var bossVx = 0f // Boss Velocity X
    var bossVy = 0f // Boss Velocity Y
    var bossIframe = 0f // Boss's temporary invulnerability period
    var bossType = 0 // Identifier for the type of boss encountered
    var bossVis = 1.0f // Visual alpha/visibility factor for the boss
    var bossLockTimer = 0f // Timer to force auto-aim on boss when it first appears

    // --- OBJECTIVE UI HELPER ---
    var objectiveTimer = 0f
    var objectiveProgress = 0f // 0.0 to 1.0
    var objectiveLabel = ""

    var innerRSq = 0f // Squared inner radius for optimized shader visibility calculations
    var outerRSq = 0f // Squared outer radius for optimized shader visibility calculations
    var passiveAuraRadiusSq = 0f // Squared radius of the player's permanent visibility light
    var fadeMultiplier = 1f // Overall ambient darkness factor applied to the scene

    fun saveState(b: Bundle) {
        b.putInt("state", state.id)
        b.putInt("difficulty", difficulty.id)
        b.putLong("score", score)
        b.putInt("gameMode", gameMode.id)
        b.putInt("hp", hp)
        b.putInt("nextStateAfterStory", nextStateAfterStory.id)
        b.putFloat("timeSinceStart", timeSinceStart)
        b.putInt("currentSector", currentSector)
        b.putInt("sectorTarget", sectorTarget)
        b.putInt("wave", wave)
        b.putFloat("cameraX", cameraX)
        b.putFloat("cameraY", cameraY)
        b.putBoolean("bossActive", bossActive)
        b.putInt("bossType", bossType)
        b.putInt("bossHp", bossHp)
        b.putInt("bossMaxHp", bossMaxHp)
        b.putFloat("bossVis", bossVis)
        b.putInt("currentLevel", currentLevel)
        b.putLong("collectedDataKB", collectedDataKB)
        b.putBoolean("isPerfectEnd", isPerfectEnd)
        b.putFloat("coreX", coreX)
        b.putFloat("coreY", coreY)
        b.putFloat("coreRadius", coreRadius)
        b.putFloat("mergeTimer", mergeTimer)
        b.putInt("coreHp", coreHp)
        b.putInt("coreMaxHp", coreMaxHp)
        b.putBoolean("escapeGateActive", escapeGateActive)
    }

    fun restoreState(b: Bundle) {
        state = AppStateId.fromInt(b.getInt("state", AppStateId.STORY_INTRO.id))
        difficulty = DifficultyLevel.fromInt(b.getInt("difficulty", DifficultyLevel.NORMAL.id))
        score = b.getLong("score", 0)
        gameMode = GameModeId.fromInt(b.getInt("gameMode", GameModeId.CAMPAIGN.id))
        hp = b.getInt("hp", 3)
        nextStateAfterStory = AppStateId.fromInt(b.getInt("nextStateAfterStory", AppStateId.MENU.id))
        timeSinceStart = b.getFloat("timeSinceStart", 0f)
        currentSector = b.getInt("currentSector", 1)
        sectorTarget = b.getInt("sectorTarget", 30)
        wave = b.getInt("wave", 1)
        cameraX = b.getFloat("cameraX", 0f)
        cameraY = b.getFloat("cameraY", 0f)
        bossActive = b.getBoolean("bossActive", false)
        bossType = b.getInt("bossType", 0)
        bossHp = b.getInt("bossHp", 0)
        bossMaxHp = b.getInt("bossMaxHp", 0)
        bossVis = b.getFloat("bossVis", 1.0f)
        currentLevel = b.getInt("currentLevel", 1)
        collectedDataKB = b.getLong("collectedDataKB", 0L)
        isPerfectEnd = b.getBoolean("isPerfectEnd", false)
        coreX = b.getFloat("coreX", 0f)
        coreY = b.getFloat("coreY", 0f)
        coreRadius = b.getFloat("coreRadius", 0f)
        mergeTimer = b.getFloat("mergeTimer", 0f)
        coreHp = b.getInt("coreHp", 10)
        coreMaxHp = b.getInt("coreMaxHp", 10)
        escapeGateActive = b.getBoolean("escapeGateActive", false)
    }

    fun resetGame() {
        score = 0; combo = 0; wave = 1
        hp = maxHp
        isBlackoutActive = false
        tutorialEnabledActions = HudAction.entries.toSet()
        tutorialHighlightedEnemyIndex = -1
        tutorialGateOpen = false
        tookDamageInLevel = false
        collectedDataKB = 0L
        isLevelCleared = false

        visionClarity = 1.0f; shieldTimer = 0f; shieldRechargeTimer = 0f; playerIframe = 0f
        overclockMeter = 0f; overclockTimer = 0f
        cameraX = 0f
        cameraY = 0f
        camLeadX = 0f
        camLeadY = 0f
        cameraZoom = 1.0f
        targetZoom = 1.0f
        cameraFocusWeight = 0f
        cameraFocusX = -1f
        cameraFocusY = -1f
        currentSector = 1; sectorTarget = 30; bossActive = false
        empFlashTimer = 0f; comboBreakTimer = 0f

        attackCooldown = 0f
        trapCooldownTimer = 0f
        sonarTimer = 0f
        activeTraps.clear()

        for (i in 0 until maxSpikes) spikeActive[i] = false

        StoryProtocol.popupTimer = 0f
        StoryProtocol.isGlitchActive = false
        StoryProtocol.areControlsInverted = false

        val config = LevelEngine.getLevelConfig(currentLevel)
        activeObjective = when {
            gameMode == GameModeId.TRAINING -> com.appsbyalok.echohunter.modes.TrainingObjective()
            gameMode == GameModeId.STORY -> com.appsbyalok.echohunter.modes.StoryObjective()
            config.features.contains(LevelFeature.BOMB) -> com.appsbyalok.echohunter.modes.BombObjective()
            config.features.contains(LevelFeature.ESCAPE) -> com.appsbyalok.echohunter.modes.EscapeObjective()
            config.features.contains(LevelFeature.DEFENSE) -> com.appsbyalok.echohunter.modes.DefenseObjective()
            config.features.contains(LevelFeature.ELIMINATION) -> com.appsbyalok.echohunter.modes.EliminationObjective()
            config.features.contains(LevelFeature.CLEAN_SWEEP) -> com.appsbyalok.echohunter.modes.CleanSweepObjective()
            else -> StandardObjective()
        }

        coreHp = 10
        coreMaxHp = 10

        escapeGateActive = false

        timeScale = 1.0f
        slowMoTimer = 0f
        hitStopTimer = 0f
        whiteFlash = 0f
        mergeTimer = 0f
        chromaticIntensity = 0f
        shockwaveActive = false
        bossDeathTimer = 0f
        bossVis = 1.0f

        objectiveTimer = 0f
        objectiveProgress = 0f
        bombTargetX = -9999f
        bombTargetY = -9999f

        // --- RESET INPUT & TOUCH STATE (Thorough) ---
        controls.isMoveJoyActive = false
        controls.moveDirX = 0f
        controls.moveDirY = 0f
        
        touch.moveBaseX = 0f
        touch.moveBaseY = 0f
        touch.moveCurrentX = 0f
        touch.moveCurrentY = 0f
        touch.moveKnobX = 0f
        touch.moveKnobY = 0f
        touch.moveTouchId = -1
        
        controls.isAttackTouching = false
        controls.attackRequested = false
        controls.attackPullDist = 0f
        controls.manualAimActive = false
        touch.attackTouchId = -1
        touch.manualAimTouchId = -1
        touch.manualAimBaseX = 0f
        touch.manualAimBaseY = 0f
        touch.manualAimCurrentX = 0f
        touch.manualAimCurrentY = 0f
        touch.manualAimKnobX = 0f
        touch.manualAimKnobY = 0f

        controls.isTrapPressed = false
        controls.trapRequested = false
        controls.isOverclockPressed = false
        controls.isSonarPressed = false
        controls.isAutoSonarLocked = false
        touch.trapTouchId = -1
        touch.sonarTouchId = -1

        // Reset Menu States
        controls.isWeaponMenuOpen = false
        controls.isTrapMenuOpen = false
        controls.isSonarMenuOpen = false
        controls.selectedWeaponIdx = -1
        controls.selectedTrapIdx = -1
        controls.selectedSonarIdx = -1

        // Reset UI & Internal Timers
        showOverclockTextTimer = 0f
        globalMessage = ""
        globalMessageTimer = 0f
        bossLockTimer = 0f
        defEnemiesToSpawn = 0
        defEnemiesAlive = 0
        elimTargetsKilled = 0

        // Reset Pulse & Visuals
        pulse = false
        pulseR = 0f
        lastFacingX = 1f
        lastFacingY = 0f
        isAutoPilotActive = false

        StoryProtocol.isBlackoutActive = false
        spawnerNodes.clear()

        levelStartTime = timeSinceStart
        levelClearTime = 0f
    }

    fun updateTimers(dt: Float, scale: Float) {
        if (playerIframe > 0f) playerIframe -= dt

        // --- PASSIVE REGEN LOGIC ---
        val regenInterval = UpgradeSystem.getRegenInterval()
        if (regenInterval > 0f && hp < maxHp && state == AppStateId.PLAYING) {
            regenTimer += dt
            if (regenTimer >= regenInterval) {
                hp = min(maxHp, hp + 1)
                regenTimer = 0f
            }
        } else {
            regenTimer = 0f
        }

        // --- COMBO DECAY LOGIC ---
        if (combo > 0 && comboBreakTimer <= 0f && state == AppStateId.PLAYING) {
            // Decays combo slowly if not in action, but doesn't drop to 0 instantly if it's very high
            if (combo > 50) combo -= 1 else combo = 0
            
            // Short grace period before next decay
            comboBreakTimer = 0.5f
        }

        if (showOverclockTextTimer > 0f) showOverclockTextTimer -= dt
        if (comboBreakTimer > 0f) comboBreakTimer -= dt
        if (shieldTimer > 0f) shieldTimer -= dt
        if (shieldTimer <= 0f && playerIframe <= 0f && state == AppStateId.PLAYING) {
            shieldRechargeTimer += dt
            val targetTime = 5f * UpgradeSystem.getShieldRecoveryMultiplier()
            if (shieldRechargeTimer >= targetTime) {
                shieldTimer = UpgradeSystem.getShieldMaxDuration()
                shieldRechargeTimer = 0f
            }
        } else {
            shieldRechargeTimer = 0f
        }
        if (bossIframe > 0f) bossIframe -= dt
        if (cooldownTimer > 0f) cooldownTimer -= dt
        if (empFlashTimer > 0f) empFlashTimer -= dt
        if (slowMoTimer > 0f) slowMoTimer -= dt
        if (whiteFlash > 0f && state != AppStateId.PERFECT_END_ZOOM) whiteFlash = max(0f, whiteFlash - dt)
        if (bossDeathTimer > 0f) bossDeathTimer -= dt
        if (bossLockTimer > 0f) bossLockTimer -= dt
        if (attackCooldown > 0f) attackCooldown -= dt
        if (sonarTimer > 0f) sonarTimer -= dt
        if (winDelayTimer > 0f) winDelayTimer -= dt
        if (shakeAmount > 0f) {
            if (state == AppStateId.VICTORY) shakeAmount = 0f // Stop shaking in Victory state
            else shakeAmount -= dt * scale * 0.5f
        }

        val target = targetClarity
        val visionUpdateRate = if (difficulty == DifficultyLevel.HARD) 0.04f else 0.8f
        if (visionClarity < target) {
            visionClarity = min(target, visionClarity + dt * visionUpdateRate)
        } else if (visionClarity > target) {
            visionClarity = max(target, visionClarity - dt)
        }

        if (chromaticIntensity > 0f) chromaticIntensity = max(0f, chromaticIntensity - dt * 2f)
        if (shockwaveActive) {
            shockwaveR += scale * 3f * dt
            if (shockwaveR > scale * 1.5f) shockwaveActive = false
        }

        if (overclockTimer > 0f) {
            overclockTimer -= dt
            val maxOcTime = 5f + UpgradeSystem.getBonusOverclockTime()
            overclockMeter = (overclockTimer / maxOcTime) * 100f
            if (overclockTimer <= 0f) EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 100)
        } else if (overclockMeter > 0f && overclockMeter < 100f) {
            val drainSpeed = if (difficulty == DifficultyLevel.NORMAL) 5f else 10f
            val patchMult = if (UpgradeSystem.hasOverclockRegenPatch()) 1.25f else 1.0f
            overclockMeter = max(0f, overclockMeter - drainSpeed * dt * patchMult)
        }
    }

    fun updatePulseRadius(dt: Float, maxRad: Float) {
        if (pulse) {
            pulseR += maxRad * 2.5f * dt
            if (pulseR > maxRad) {
                pulse = false; pulseR = 0f
            }
        }
    }

    fun updatePlayerMovement(dt: Float, width: Float, height: Float, scale: Float) {
        val baseSpeed = scale * (if (isOverclocked) 1.2f else 0.8f)
        val pSpeed = baseSpeed * UpgradeSystem.getSpeedMultiplier()

        if (controls.moveDirX != 0f || controls.moveDirY != 0f) {
            lastFacingX = controls.moveDirX
            lastFacingY = controls.moveDirY
        }

        var vx = controls.moveDirX * pSpeed * dt
        var vy = controls.moveDirY * pSpeed * dt

        if (StoryProtocol.areControlsInverted) {
            vx = -vx
            vy = -vy
        }

        // Update velocity for AI prediction with smoothing to prevent jitter
        val targetPvx = vx / dt
        val targetPvy = vy / dt
        val smoothing = (dt * 15f).coerceAtMost(1.0f)
        pvx += (targetPvx - pvx) * smoothing
        pvy += (targetPvy - pvy) * smoothing

        val playerRadius = scale * 0.015f

        if (gridMap != null) {
            val nextPx = px + vx
            if (!isCollidingWithWall(nextPx, py, playerRadius)) px = nextPx

            val nextPy = py + vy
            if (!isCollidingWithWall(px, nextPy, playerRadius)) py = nextPy

            // FIX: Ensure player is strictly clamped to Map Bounds
            px = max(playerRadius, min(px, mapWidth - playerRadius))
            py = max(playerRadius, min(py, mapHeight - playerRadius))
        } else {
            val viewportW = getViewportW(width, height)
            val viewportH = getViewportH(width, height)
            px += vx
            py += vy
            if (px < cameraX + playerRadius) px = cameraX + playerRadius
            if (px > cameraX + viewportW - playerRadius) px = cameraX + viewportW - playerRadius
            if (py < cameraY + playerRadius) py = cameraY + playerRadius
            if (py > cameraY + viewportH - playerRadius) py = cameraY + viewportH - playerRadius
        }
    }

    fun isCollidingWithWall(cx: Float, cy: Float, radius: Float): Boolean {
        val grid = gridMap ?: return false
        val ts = tileSize
        val hitbox = radius * 0.6f

        val left = ((cx - hitbox) / ts).toInt()
        val right = ((cx + hitbox) / ts).toInt()
        val top = ((cy - hitbox) / ts).toInt()
        val bottom = ((cy + hitbox) / ts).toInt()

        for (x in left..right) {
            for (y in top..bottom) {
                if (x < 0 || x >= grid.size || y < 0 || y >= grid[0].size) return true
                if (grid[x][y] == 1) return true
            }
        }
        return false
    }

    fun updateCameraAndMovement(dt: Float, width: Float, height: Float, scale: Float) {
        modeStrategy.updateCameraAndMovement(dt, this, width, height, scale)
    }

    fun updateVisibilityMath(scale: Float, maxRad: Float, dt: Float) {
        val applyDarkness = isDarknessLevel || StoryProtocol.isBlackoutActive

        // Passive vision radius scales with OPTIC_SENSORS upgrade
        // Base radius is around 15% of screen, can grow with upgrades
        val baseAura = scale * 0.15f 
        val visionMult = UpgradeSystem.getVisionRadiusMultiplier()
        
        val passiveAuraRadius = if (modFullVisibility || !applyDarkness) scale * 100f else baseAura * visionMult
        passiveAuraRadiusSq = passiveAuraRadius * passiveAuraRadius

        // Fade multiplier (Background darkness intensity)
        fadeMultiplier = if (modFullVisibility || !applyDarkness) 1.0f else {
            val baseFade = if (difficulty == DifficultyLevel.HARD) 0.75f else 0.85f
            min(0.99f, baseFade + 0.15f * visionClarity)
        }

        val echoThickness = maxRad * 0.05f
        if (pulse) {
            val innerR = max(0f, pulseR - echoThickness)
            val outerR = pulseR + echoThickness
            innerRSq = innerR * innerR
            outerRSq = outerR * outerR

            // NEW: Update persistent wall visibility when pulse hits
            val grid = gridMap
            val vis = wallVisMap
            if (grid != null && vis != null) {
                val minX = max(0, ((px - outerR) / tileSize).toInt())
                val maxX = min(grid.size - 1, ((px + outerR) / tileSize).toInt())
                val minY = max(0, ((py - outerR) / tileSize).toInt())
                val maxY = min(grid[0].size - 1, ((py + outerR) / tileSize).toInt())

                for (x in minX..maxX) {
                    for (y in minY..maxY) {
                        val dx = x * tileSize + tileSize / 2f - px
                        val dy = y * tileSize + tileSize / 2f - py
                        val d2 = dx * dx + dy * dy
                        if (d2 in innerRSq..outerRSq) vis[x][y] = 1f
                    }
                }
            }
        } else {
            innerRSq = 0f
            outerRSq = 0f
        }

        // Decay wall visibility over time using Upgrade bonus
        val decayDuration = UpgradeSystem.getSonarDurationBonus()
        wallVisMap?.let { vis ->
            for (x in vis.indices) {
                for (y in vis[0].indices) {
                    if (vis[x][y] > 0f) vis[x][y] = max(0f, vis[x][y] - dt / decayDuration)
                }
            }
        }
    }
}
