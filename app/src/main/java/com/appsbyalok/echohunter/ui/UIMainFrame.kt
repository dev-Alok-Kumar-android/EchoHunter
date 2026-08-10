package com.appsbyalok.echohunter.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ToneGenerator
import android.view.MotionEvent
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.ui.components.UIMenuButton
import com.appsbyalok.echohunter.ui.components.UIMenuCard
import com.appsbyalok.echohunter.ui.components.UIScrollView
import com.appsbyalok.echohunter.utils.EchoAudioManager
import com.appsbyalok.echohunter.utils.GameColors

class UIMainFrame {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }

    private val scroller = UIScrollView()
    private val menuCard = UIMenuCard()
    private val closeButton = UIMenuButton()
    private val initiateButton = UIMenuButton()
    private val abortButton = UIMenuButton()

    // Screen State
    // 0: Main Hub (3 Cards), 1: Cyber-Heist Act Selection (6 Memory Cards), 2: Act Details View
    var screenMode = 0
    var selectedActIndex = -1

    private val screenHistory = mutableListOf<Int>()

    fun reset() {
        screenMode = 0
        selectedActIndex = -1
        scroller.scrollY = 0f
        screenHistory.clear()
    }

    fun openActDetails(actIndex: Int) {
        pushHistory()
        screenMode = 2
        selectedActIndex = actIndex
    }

    private fun pushHistory() {
        if (screenHistory.isEmpty() || screenHistory.last() != screenMode) {
            screenHistory.add(screenMode)
        }
    }

    // Main Hub Cards
    private val hubCardRects = Array(3) { RectF() }
    private val hubTitles = arrayOf("TRAINING PROTOCOL", "DATA ARCHIVES", "CYBER-HEIST OPERATIONS")
    private val hubSubs = arrayOf("System Calibration (Recommended)", "Simulation Labyrinths (Sandbox)", "The 6-Act Aegis Master Heist")

    // Story Mode Acts (6 Memories)
    private val actCardRects = Array(6) { RectF() }
    private val actTitles = arrayOf(
        "ACT I: INITIAL BREACH",
        "ACT II: ENCRYPTION",
        "ACT III: THE CORE",
        "ACT IV: THE TRAP",
        "ACT V: SABOTAGE",
        "ACT VI: THE ECLIPSE"
    )
    private val actSubs = arrayOf(
        "Levels 01 - 15 (Baseline Firewall)",
        "Levels 16 - 30 (Botnet Network)",
        "Levels 31 - 50 (Security Grid)",
        "Levels 01 - 15 (Corrupted Servers)",
        "Levels 16 - 30 (CEO Personal Cloud)",
        "Levels 31 - 50 (Syndicate Master Core)"
    )

    private val actDetails = arrayOf(
        "BREACH THE AEGIS CORRIDORS. EXTRACT ALPHA KEY DECRYPTION CODES. SURVIVE COLD PURGE PROTOCOLS.",
        "DISMANTLE BOTNET SERVERS. REMOVE INCIDENTS OF HARDWARE SPYWARE INFECTIONS. ELIMINATE SATELLITE SENSORS.",
        "INFILTRATE ELENA ROSSI'S SECURITY LABYRINTH. TARGET AND SECURE THE AEGIS OMEGA KEY AT ALL COSTS.",
        "WARNING: CORRUPTED DATA. AEGIS HAS CORNERED YOUR HANDSHAKE. BREAK THROUGH THE BLOOD-RED firewall SECTOR.",
        "INFILTRATE CEO MARCUS VANCE'S PRIVATE SERVERS. SYSTEMATICALLY SABOTAGE THE FIRMWARE ARCHITECTURE.",
        "THE FINAL STRIKE. EXECUTE SYSTEM ECLIPSE PROTOCOLS TO DESTROY THE AEGIS GLOBAL CORE PERMANENTLY."
    )

    private val tempRect = RectF()
    private var hitOnDown = -1
    private var downX = 0f
    private var downY = 0f

    fun draw(c: Canvas, width: Float, height: Float, gs: GameState, scale: Float, dt: Float) {
        // Clear screen with custom background slate
        c.drawColor(0xFF050A0F.toInt())

        val safeTop = gs.hudLayout.safeInsetTop
        val safeBottom = gs.hudLayout.safeInsetBottom

        // Draw header on top
        pText.textAlign = Paint.Align.CENTER
        pText.textSize = scale * 0.065f
        pText.color = GameColors.CLARITY
        pText.isFakeBoldText = true
        val headerY = safeTop + scale * 0.12f
        c.drawText("MAINFRAME CONTROL", width / 2f, headerY, pText)

        pText.textSize = scale * 0.022f
        pText.isFakeBoldText = false
        pText.color = if (!SaveManager.isGameTutorialCompleted) GameColors.YELLOW else GameColors.HP
        val statusText = if (!SaveManager.isGameTutorialCompleted)
            "STATUS: SYSTEM CALIBRATION REQUIRED" else "STATUS: SIMULATION PROTOCOLS STABLE"
        c.drawText(statusText, width / 2f, headerY + scale * 0.04f, pText)

        when (screenMode) {
            0 -> drawMainHub(c, width, height, scale, safeBottom, headerY)
            1 -> drawActSelection(c, width, height, scale, safeBottom, headerY, dt)
            2 -> drawActDetails(c, width, height, scale, headerY)
        }
    }

    private fun drawMainHub(c: Canvas, width: Float, height: Float, scale: Float, safeBottom: Float, headerY: Float) {
        val contentW = width * 0.88f
        val startX = (width - contentW) / 2f
        val cardH = scale * 0.16f
        val gap = scale * 0.04f
        val startY = headerY + scale * 0.12f

        for (i in 0..2) {
            val rect = hubCardRects[i]
            rect.set(startX, startY + i * (cardH + gap), startX + contentW, startY + i * (cardH + gap) + cardH)

            val isLocked = (i == 2 && !SaveManager.isStoryModeUnlocked)
            val isCurrent = when (i) {
                0 -> !SaveManager.isGameTutorialCompleted
                1 -> SaveManager.isGameTutorialCompleted && SaveManager.unlockedStoryStreak >= 3
                2 -> !isLocked && SaveManager.unlockedStoryStreak < 3
                else -> false
            }

            menuCard.draw(
                c = c,
                rect = rect,
                scale = scale,
                paint = p,
                active = isCurrent,
                pressed = hitOnDown == i,
                fillColor = if (isLocked) 0xFF101010.toInt() else 0xFF0A1520.toInt(),
                strokeColor = if (isLocked) Color.DKGRAY else 0x5500FFFF,
                activeStrokeColor = GameColors.PULSE,
                radius = scale * 0.02f
            )

            val padding = scale * 0.05f
            pText.textAlign = Paint.Align.LEFT

            // Title
            pText.textSize = scale * 0.042f
            pText.color = if (isLocked) Color.GRAY else Color.WHITE
            c.drawText(hubTitles[i], rect.left + padding, rect.top + scale * 0.065f, pText)

            // Subtitle
            pText.textSize = scale * 0.026f
            if (isLocked) {
                pText.color = GameColors.YELLOW
                c.drawText("LOCKED: SYSTEM LEVEL 15 REQUIRED", rect.left + padding, rect.top + scale * 0.11f, pText)
            } else {
                pText.color = GameColors.TEXT
                c.drawText(hubSubs[i], rect.left + padding, rect.top + scale * 0.11f, pText)
            }

            // Onboarding Indicator
            pText.textAlign = Paint.Align.RIGHT
            pText.textSize = scale * 0.022f
            if (i == 0) {
                if (SaveManager.isGameTutorialCompleted) {
                    pText.color = GameColors.HP
                    c.drawText("STABLE", rect.right - padding, rect.bottom - scale * 0.03f, pText)
                } else {
                    // 1. Text Change & Simple Bounce Animation
                    pText.color = GameColors.YELLOW
                    val bounce = if ((System.currentTimeMillis() % 600) > 300) scale * 0.005f else 0f
                    c.drawText(">>> TAP TO BEGIN", rect.right - padding - bounce, rect.bottom - scale * 0.03f, pText)

                    // 2. Tutorial Pulse Effect (Yellow glowing box expanding outwards)
                    val pulse = (System.currentTimeMillis() % 1000) / 1000f
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = scale * 0.006f
                    p.color = GameColors.YELLOW
                    p.alpha = (255 * (1f - pulse)).toInt()
                    val expand = pulse * scale * 0.04f
                    tempRect.set(rect.left - expand, rect.top - expand, rect.right + expand, rect.bottom + expand)
                    c.drawRoundRect(tempRect, scale * 0.02f, scale * 0.02f, p)
                    p.alpha = 255
                }
            } else if (i == 2) {
                if (isLocked) {
                    pText.color = GameColors.RED
                    c.drawText("UNAUTHORIZED", rect.right - padding, rect.bottom - scale * 0.03f, pText)
                } else {
                    pText.color = GameColors.PULSE
                    c.drawText("6 OPERATIONS", rect.right - padding, rect.bottom - scale * 0.03f, pText)
                }
            }
        }

        // Back button to NanoOS
        val btnW = scale * 0.5f
        val btnH = scale * 0.08f
        val btnY = height - btnH - scale * 0.05f - safeBottom
        closeButton.set(width / 2f - btnW / 2f, btnY, width / 2f + btnW / 2f, btnY + btnH)
        closeButton.draw(c, scale, p, pText, "DISCONNECT", hitOnDown == 10,
            0xFF220505.toInt(), GameColors.RED, GameColors.RED, scale * 0.01f, scale * 0.035f)
    }

    private fun drawActSelection(c: Canvas, width: Float, height: Float, scale: Float, safeBottom: Float, headerY: Float, dt: Float) {
        val contentW = width * 0.9f
        val startX = (width - contentW) / 2f

        val listTop = headerY + scale * 0.08f
        val listBottom = height - scale * 0.18f - safeBottom
        scroller.viewport.set(0f, listTop, width, listBottom)
        scroller.updatePhysics(dt)
        scroller.begin(c)

        val cardH = scale * 0.15f
        val gap = scale * 0.03f
        var currentY = gap

        for (i in 0..5) {
            // Decoupling: Acts 4-6 (indices 3-5) are hidden until Act 3 is beaten
            if (i >= 3 && SaveManager.unlockedStoryStreak < 3) continue

            val isUnlocked = isActUnlocked(i)
            val rect = actCardRects[i]
            rect.set(startX, currentY, startX + contentW, currentY + cardH)

            val bgColor = if (isUnlocked) (if (i >= 3) 0xFF1A0A05.toInt() else 0xFF0A1520.toInt()) else 0xFF101010.toInt()
            val strokeColor = if (isUnlocked) (if (i >= 3) GameColors.RED else GameColors.PULSE) else Color.DKGRAY

            menuCard.draw(
                c = c,
                rect = rect,
                scale = scale,
                paint = p,
                active = isUnlocked, // Highlight all unlocked acts as requested
                pressed = hitOnDown == i,
                fillColor = bgColor,
                strokeColor = strokeColor,
                activeStrokeColor = if (i >= 3) GameColors.RED else GameColors.HP,
                radius = scale * 0.02f
            )

            val padding = scale * 0.05f
            pText.textAlign = Paint.Align.LEFT

            // Title
            pText.textSize = scale * 0.042f
            pText.color = if (isUnlocked) Color.WHITE else Color.GRAY
            c.drawText(actTitles[i], rect.left + padding, rect.top + scale * 0.065f, pText)

            // Subtitle
            pText.textSize = scale * 0.026f
            if (isUnlocked) {
                pText.color = GameColors.TEXT
                c.drawText(actSubs[i], rect.left + padding, rect.top + scale * 0.105f, pText)
            } else {
                pText.color = GameColors.YELLOW
                val req = when (i) {
                    1 -> "REQUIREMENT: COMPLETE ACT I"
                    2 -> "REQUIREMENT: COMPLETE ACT II"
                    3 -> "REQUIREMENT: COMPLETE ACT III"
                    4 -> "REQUIREMENT: COMPLETE ACT IV"
                    5 -> "REQUIREMENT: COMPLETE ACT V"
                    else -> "LOCKED"
                }
                c.drawText(req, rect.left + padding, rect.top + scale * 0.105f, pText)
            }

            // Status Badge
            pText.textAlign = Paint.Align.RIGHT
            pText.textSize = scale * 0.022f
            val (tag, tagColor) = when {
                !isUnlocked -> "LOCKED" to GameColors.RED
                else -> "ACTIVE" to GameColors.HP // Green "ACTIVE" for all playable acts
            }
            pText.color = tagColor
            c.drawText(tag, rect.right - padding, rect.bottom - scale * 0.03f, pText)

            currentY += cardH + gap
        }

        scroller.end(c, currentY + scale * 0.1f, scale, 0f)

        // Return to Hub main screen
        val btnW = scale * 0.5f
        val btnH = scale * 0.08f
        val btnY = height - btnH - scale * 0.05f - safeBottom
        closeButton.set(width / 2f - btnW / 2f, btnY, width / 2f + btnW / 2f, btnY + btnH)
        closeButton.draw(c, scale, p, pText, "BACK TO HUB", hitOnDown == 10,
            0xFF1A1A1A.toInt(), Color.GRAY, Color.WHITE, scale * 0.01f, scale * 0.035f)
    }

    private fun drawActDetails(c: Canvas, width: Float, height: Float, scale: Float, headerY: Float) {
        val i = selectedActIndex
        if (i == -1) return

        val boxW = width * 0.88f
        val boxH = height * 0.52f
        val boxX = (width - boxW) / 2f
        val boxY = headerY + scale * 0.10f

        val rect = RectF(boxX, boxY, boxX + boxW, boxY + boxH)

        p.style = Paint.Style.FILL
        p.color = if (i >= 3) 0xFF150505.toInt() else 0xFF051015.toInt()
        c.drawRoundRect(rect, scale * 0.03f, scale * 0.03f, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = scale * 0.005f
        p.color = if (i >= 3) GameColors.RED else GameColors.PULSE
        c.drawRoundRect(rect, scale * 0.03f, scale * 0.03f, p)

        val padding = scale * 0.05f

        // Title
        pText.textAlign = Paint.Align.LEFT
        pText.textSize = scale * 0.052f
        pText.color = if (i >= 3) GameColors.RED else GameColors.CLARITY
        pText.isFakeBoldText = true
        c.drawText(actTitles[i], rect.left + padding, rect.top + scale * 0.08f, pText)
        pText.isFakeBoldText = false

        // Subtitle
        pText.textSize = scale * 0.028f
        pText.color = GameColors.TEXT
        c.drawText(actSubs[i], rect.left + padding, rect.top + scale * 0.13f, pText)

        // Description / Lore Paragraph
        pText.textSize = scale * 0.024f
        pText.color = Color.WHITE
        val textPaint = android.text.TextPaint(pText)
        val layoutW = (boxW - padding * 2).toInt()
        @Suppress("DEPRECATION")
        val layout = android.text.StaticLayout(actDetails[i], textPaint, layoutW, android.text.Layout.Alignment.ALIGN_NORMAL, 1.2f, 0f, false)

        c.save()
        c.translate(rect.left + padding, rect.top + scale * 0.18f)
        layout.draw(c)
        c.restore()

        // Telemetry details (Streak status)
        pText.textAlign = Paint.Align.LEFT
        pText.textSize = scale * 0.024f
        pText.color = GameColors.YELLOW

        val bestStreak = if (i >= 3) SaveManager.unlockedHardStreak else SaveManager.unlockedStoryStreak
        val currentStreak = if (i >= 3) SaveManager.currentHardStreak else SaveManager.currentStoryStreak

        val targetStreakText = if (i >= 3) {
            "CURRENT RETALIATION STREAK: $currentStreak  |  BEST RECORD: $bestStreak"
        } else {
            "CURRENT DECRYPTION STREAK: $currentStreak  |  BEST RECORD: $bestStreak"
        }
        c.drawText(targetStreakText, rect.left + padding, rect.bottom - scale * 0.05f, pText)

        // Action Buttons
        val btnW = scale * 0.38f
        val btnH = scale * 0.08f
        val btnY = rect.bottom + scale * 0.05f

        abortButton.set(rect.centerX() - btnW - scale * 0.03f, btnY, rect.centerX() - scale * 0.03f, btnY + btnH)
        initiateButton.set(rect.centerX() + scale * 0.03f, btnY, rect.centerX() + btnW + scale * 0.03f, btnY + btnH)

        abortButton.draw(c, scale, p, pText, "ABORT", hitOnDown == 20,
            0xFF1A1A1A.toInt(), Color.GRAY, Color.WHITE, scale * 0.01f, scale * 0.032f)

        initiateButton.draw(c, scale, p, pText, "START STREAK", hitOnDown == 21,
            if (i >= 3) 0xFF330505.toInt() else 0xFF052515.toInt(),
            if (i >= 3) GameColors.RED else GameColors.HP,
            if (i >= 3) GameColors.RED else GameColors.HP,
            scale * 0.01f, scale * 0.032f)
    }

    private fun isActUnlocked(index: Int): Boolean {
        if (!SaveManager.isStoryModeUnlocked) return false
        return when (index) {
            0 -> true // Act 1 baseline
            1 -> SaveManager.unlockedStoryStreak >= 1
            2 -> SaveManager.unlockedStoryStreak >= 2
            3 -> SaveManager.unlockedStoryStreak >= 3 // Act 4 (Hard 1)
            4 -> SaveManager.unlockedStoryStreak >= 3 && SaveManager.unlockedHardStreak >= 1
            5 -> SaveManager.unlockedStoryStreak >= 3 && SaveManager.unlockedHardStreak >= 2
            else -> false
        }
    }

    fun onTouch(x: Float, y: Float, action: Int, scale: Float, gs: GameState, onRoute: (Int) -> Unit, onBack: () -> Unit): Boolean {
        // Only let scroller consume if it's already dragging or confirmed movement.
        val scrollerResult = if (screenMode == 1) scroller.onTouch(x, y, action, scale) else false
        
        if (screenMode == 1 && (scroller.isDragging || scroller.isDraggingScrollbar)) {
            hitOnDown = -1
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                hitOnDown = when (screenMode) {
                    0 -> {
                        when {
                            closeButton.contains(x, y) -> 10
                            else -> {
                                var hit = -1
                                for (i in 0..2) {
                                    if (hubCardRects[i].contains(x, y)) {
                                        hit = i
                                        break
                                    }
                                }
                                hit
                            }
                        }
                    }
                    1 -> {
                        when {
                            closeButton.contains(x, y) -> 10
                            else -> {
                                var hit = -1
                                    val localY = (y - scroller.viewport.top) - scroller.scrollY
                                    for (i in 0..5) {
                                        if (actCardRects[i].contains(x, localY)) {
                                            hit = i
                                            break
                                        }
                                    }
                                hit
                            }
                        }
                    }
                    2 -> {
                        when {
                            abortButton.contains(x, y) -> 20
                            initiateButton.contains(x, y) -> 21
                            else -> -1
                        }
                    }
                    else -> -1
                }
            }
            MotionEvent.ACTION_UP -> {
                // If scroller moved significantly during this touch, ignore the click
                if (screenMode == 1 && (scroller.hasMovedSignificantly || scrollerResult)) {
                    hitOnDown = -1
                    return true
                }

                if (hitOnDown != -1) {
                    val hitOnUp = when (screenMode) {
                        0 -> {
                            when {
                                closeButton.contains(x, y) -> 10
                                else -> {
                                    var hit = -1
                                    for (i in 0..2) {
                                        if (hubCardRects[i].contains(x, y)) {
                                            hit = i
                                            break
                                        }
                                    }
                                    hit
                                }
                            }
                        }
                        1 -> {
                            when {
                                closeButton.contains(x, y) -> 10
                                else -> {
                                    var hit = -1
                        val localY = (y - scroller.viewport.top) - scroller.scrollY
                        for (i in 0..5) {
                            if (actCardRects[i].contains(x, localY)) {
                                hit = i
                                break
                            }
                        }
                                    hit
                                }
                            }
                        }
                        2 -> {
                            when {
                                abortButton.contains(x, y) -> 20
                                initiateButton.contains(x, y) -> 21
                                else -> -1
                            }
                        }
                        else -> -1
                    }

                    if (hitOnUp == hitOnDown) {
                        handleSelection(hitOnUp, gs, onRoute, onBack)
                    }
                }
                hitOnDown = -1
            }
        }
        return true
    }

    private fun handleSelection(hitId: Int, gs: GameState, onRoute: (Int) -> Unit, onBack: () -> Unit) {
        when (screenMode) {
            0 -> {
                when (hitId) {
                    0 -> { // Training
                        EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 100)
                        onRoute(100)
                    }
                    1 -> { // Archives (Sandbox level select)
                        EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 100)
                        onRoute(0) // 0 goes to Archives in GameView
                    }
                    2 -> { // Story Act selection menu
                        if (SaveManager.isStoryModeUnlocked) {
                            EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 100)
                            pushHistory()
                            screenMode = 1
                        } else {
                            EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 100)
                            gs.showGlobalMessage("UNAUTHORIZED: SYSTEM LEVEL 15 REQUIRED", 2f)
                        }
                    }
                    10 -> { // Disconnect back to Nano-OS
                        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 100)
                        onBack()
                    }
                }
            }
            1 -> {
                when (hitId) {
                    10 -> { // Back to Hub screen
                        EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 100)
                        if (!popHistory()) screenMode = 0
                    }
                    in 0..5 -> {
                        if (isActUnlocked(hitId)) {
                            EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 100)
                            pushHistory()
                            selectedActIndex = hitId
                            screenMode = 2
                        } else {
                            EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 100)
                        }
                    }
                }
            }
            2 -> {
                when (hitId) {
                    20 -> { // Abort - back to selection menu
                        EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 100)
                        if (!popHistory()) screenMode = 1
                    }
                    21 -> { // Initiate cyber-heist!
                        EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 120)
                        val selectedAct = selectedActIndex
                        // Map 0-5 index to 101-106 route
                        onRoute(101 + selectedAct)
                    }
                }
            }
        }
    }

    private fun popHistory(): Boolean {
        if (screenHistory.isNotEmpty()) {
            screenMode = screenHistory.removeAt(screenHistory.size - 1)
            return true
        }
        return false
    }

    fun handleBackPressed(): Boolean {
        return popHistory()
    }
}
