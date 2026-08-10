package com.appsbyalok.echohunter.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.ui.terminal.CatCommand
import com.appsbyalok.echohunter.ui.terminal.ClearCommand
import com.appsbyalok.echohunter.ui.terminal.CommandContext
import com.appsbyalok.echohunter.ui.terminal.CommandRegistry
import com.appsbyalok.echohunter.ui.terminal.ConfigCommand
import com.appsbyalok.echohunter.ui.terminal.DateCommand
import com.appsbyalok.echohunter.ui.terminal.DebugCommand
import com.appsbyalok.echohunter.ui.terminal.DirCommand
import com.appsbyalok.echohunter.ui.terminal.EchoCommand
import com.appsbyalok.echohunter.ui.terminal.ExitCommand
import com.appsbyalok.echohunter.ui.terminal.FixCommand
import com.appsbyalok.echohunter.ui.terminal.GlitchCommand
import com.appsbyalok.echohunter.ui.terminal.HelpCommand
import com.appsbyalok.echohunter.ui.terminal.HelpTechCommand
import com.appsbyalok.echohunter.ui.terminal.InputHandler
import com.appsbyalok.echohunter.ui.terminal.LevelCommand
import com.appsbyalok.echohunter.ui.terminal.MarketCommand
import com.appsbyalok.echohunter.ui.terminal.OutputMode
import com.appsbyalok.echohunter.ui.terminal.RebootCommand
import com.appsbyalok.echohunter.ui.terminal.ResetTutorialCommand
import com.appsbyalok.echohunter.ui.terminal.ScanCommand
import com.appsbyalok.echohunter.ui.terminal.StoryLogCommand
import com.appsbyalok.echohunter.ui.terminal.SudoCommand
import com.appsbyalok.echohunter.ui.terminal.SysInfoCommand
import com.appsbyalok.echohunter.ui.terminal.UICommand
import com.appsbyalok.echohunter.ui.terminal.VerCommand
import com.appsbyalok.echohunter.ui.terminal.WhoamiCommand
import com.appsbyalok.echohunter.utils.EchoAudioManager
import com.appsbyalok.echohunter.utils.GameColors
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class UITerminal {
    private val pText = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val pBg = Paint().apply { isAntiAlias = true }

    private val lines = mutableListOf<String>() // Raw lines history
    private val wrappedLines = mutableListOf<String>() // Wrapped lines for display
    private val typewriterQueue = StringBuilder()
    private val lineByLineQueue = mutableListOf<String>()
    private var lastTypeTime = 0L
    private var lastLineTime = 0L
    private val TYPE_DELAY: Long
        get() = when (SaveManager.typewriterSpeed) {
            1 -> 60L
            2 -> 40L
            3 -> 20L
            4 -> 10L
            5 -> 2L
            else -> 20L
        }
    private val LINE_DELAY = 150L // ms per line

    private var activeSubSession: InputHandler? = null
    
    private val tokenRegex = Regex("(?<=\\s)|(?=\\s)")
    private var currentInput = ""
    
    private var scrollOffset = 0
    private var lastTouchY = 0f
    private var isDragging = false
    private val scrollToBottomRect = RectF()
    private var maxTextWidth = 0f
    private var lastW = 0f
    private var lastH = 0f
    private var lastScale = 0f

    private val files = mapOf(
        "ROOT_TERM.SH" to "1.2 KB",
        "MAINFRAME.SYS" to "8.4 KB",
        "ECHO_PROTOCOL.CFG" to "4.0 KB",
        "USER_DATA.LOG" to "128 KB",
        "PROJECT_ECHO.DOC" to "64 KB",
        "REDACTED_MSG.TXT" to "2.1 KB",
        "KERNEL_DUMP.BIN" to "1024 KB",
        "SECTOR_MAP.IMG" to "5.5 MB"
    )
    private val keyRects = mutableMapOf<String, RectF>()
    private val activePointers = mutableMapOf<Int, String>() // PointerID -> KeyName
    private val pressedKeys = mutableSetOf<String>() // Currently pressed keys (for visual feedback)
    
    private val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    private val handler = Handler(Looper.getMainLooper())
    private val delRunnable = object : Runnable {
        override fun run() {
            if (currentInput.isNotEmpty()) {
                currentInput = currentInput.substring(0, currentInput.length - 1)
                EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 30)
                handler.postDelayed(this, 100)
            } else {
                handler.removeCallbacks(this)
            }
        }
    }
    
    private var isSymbolMode = false
    private var isShiftActive = false
    private var isCapsLock = false
    private var isCtrlActive = false
    private var isAltActive = false
    private var isKeyboardVisible = true
    private var lastShiftTapTime = 0L
    private var shiftWasUsedAsModifier = false
    private var ctrlWasUsedAsModifier = false
    private var altWasUsedAsModifier = false
    private var isPendingExit = false
    private var exitCallback: (() -> Unit)? = null

    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    
    // Autocomplete state for TAB cycling
    private var autocompleteMatches = listOf<String>()
    private var autocompleteIndex = -1
    private var lastGeneratedInput = ""
    private var lastManualInput = ""

    // Alphabetic Keyboard Layout
    private val kbAlpha = listOf(
        listOf("CTRL", "ALT", "HELP", "HIDE"),
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
        listOf("SHIFT", "Z", "X", "C", "V", "B", "N", "M", "DEL"),
        listOf("?123", "TAB", "SPACE", "ENTER")
    )

    // Symbols Keyboard Layout
    private val kbSymbol = listOf(
        listOf("CTRL", "ALT", "HELP", "HIDE"),
        listOf("+", "-", "*", "/", "=", "_", ".", ",", "?", "!"),
        listOf("[", "]", "(", ")", "{", "}", "<", ">", "\\"),
        listOf("@", "#", "$", "%", "^", "&", "|", "'", "\""),
        listOf("SHIFT", "~", ":", ";", "...", "DEL"),
        listOf("ABC", "TAB", "SPACE", "ENTER")
    )

    init {
        // Register all commands
        CommandRegistry.register(HelpCommand())
        CommandRegistry.register(HelpTechCommand())
        CommandRegistry.register(ClearCommand())
        CommandRegistry.register(ExitCommand())
        CommandRegistry.register(DirCommand())
        CommandRegistry.register(DateCommand())
        CommandRegistry.register(VerCommand())
        CommandRegistry.register(WhoamiCommand())
        CommandRegistry.register(ScanCommand())
        CommandRegistry.register(CatCommand())
        CommandRegistry.register(EchoCommand())
        CommandRegistry.register(SudoCommand())
        CommandRegistry.register(RebootCommand())
        CommandRegistry.register(DebugCommand())
        CommandRegistry.register(SysInfoCommand())
        CommandRegistry.register(ConfigCommand())
        CommandRegistry.register(LevelCommand())
        CommandRegistry.register(StoryLogCommand())
        CommandRegistry.register(MarketCommand())
        CommandRegistry.register(FixCommand())
        CommandRegistry.register(GlitchCommand())
        CommandRegistry.register(UICommand())
        CommandRegistry.register(ResetTutorialCommand())

        addLines("NANO-OS [Version 4.2.0-ROOT]")
        addLines("(c) $currentYear ECHO CORP. ALL RIGHTS RESERVED.")
        addLines("")
        addLines("TERMINAL INITIALIZED. TYPE 'HELP' FOR CMDS.")
    }

    private fun clearScreen() {
        lines.clear()
        wrappedLines.clear()
        typewriterQueue.setLength(0)
        lineByLineQueue.clear()
        scrollOffset = 0
    }

    private fun addLines(text: String, mode: OutputMode = OutputMode.TYPEWRITER) {
        when (mode) {
            OutputMode.INSTANT -> {
                val splitLines = text.split("\n")
                lines.addAll(splitLines)
                if (maxTextWidth > 0f) {
                    pText.textSize = lastScale * 0.03f
                    for (line in splitLines) {
                        wrappedLines.addAll(wrapLine(line, pText, maxTextWidth))
                    }
                } else {
                    wrappedLines.addAll(splitLines)
                }
            }
            OutputMode.TYPEWRITER -> {
                typewriterQueue.append(text).append("\n")
            }
            OutputMode.LINE_BY_LINE -> {
                lineByLineQueue.addAll(text.split("\n"))
            }
        }

        // Limit raw history to 200 lines
        if (lines.size > 200) {
            repeat(lines.size - 200) { lines.removeAt(0) }
        }

        // Limit wrapped lines for display performance
        if (wrappedLines.size > 500) {
            val toRemove = wrappedLines.size - 500
            repeat(toRemove) { wrappedLines.removeAt(0) }
            scrollOffset = (scrollOffset - toRemove).coerceAtLeast(0)
        }
    }

    private fun processQueues() {
        val now = System.currentTimeMillis()

        // Professional Exit: Trigger only after typewriter finishes
        if (isPendingExit && typewriterQueue.isEmpty() && lineByLineQueue.isEmpty()) {
            isPendingExit = false
            handler.postDelayed({
                clearScreen()
                exitCallback?.invoke()
            }, 600)
            return
        }

        // 1. Process Line by Line (higher priority for systemic feel)
        if (lineByLineQueue.isNotEmpty() && now - lastLineTime >= LINE_DELAY) {
            val line = lineByLineQueue.removeAt(0)
            addLines(line, OutputMode.INSTANT)
            lastLineTime = now
            if (scrollOffset == 0) rebuildWrappedLines()
            return // Process one thing at a time
        }

        // 2. Process Typewriter
        if (typewriterQueue.isNotEmpty() && now - lastTypeTime >= TYPE_DELAY) {
            val charsToProcess = ((now - lastTypeTime) / TYPE_DELAY).toInt().coerceAtMost(typewriterQueue.length)
            
            run charLoop@{
                repeat(charsToProcess) {
                    if (typewriterQueue.isEmpty()) return@charLoop
                    val char = typewriterQueue[0]
                    typewriterQueue.deleteCharAt(0)
                    
                    if (char == '\n') {
                        lines.add("")
                        wrappedLines.add("")
                    } else {
                        if (lines.isEmpty()) lines.add("")
                        val lastRaw = lines.last()
                        lines[lines.size - 1] = lastRaw + char
                        
                        if (maxTextWidth > 0f) {
                            pText.textSize = lastScale * 0.03f
                            if (wrappedLines.isEmpty()) wrappedLines.add("")
                            val lastWrapped = wrappedLines.last()
                            if (pText.measureText(lastWrapped + char) <= maxTextWidth) {
                                wrappedLines[wrappedLines.size - 1] = lastWrapped + char
                            } else {
                                wrappedLines.add(char.toString())
                            }
                        } else {
                            if (wrappedLines.isEmpty()) wrappedLines.add("")
                            wrappedLines[wrappedLines.size - 1] = wrappedLines.last() + char
                        }
                    }
                }
            }
            lastTypeTime = now
            if (scrollOffset == 0) rebuildWrappedLines()
        }
    }

    private var lastTheme = ""
    private var lastFontSize = ""

    private fun rebuildWrappedLines() {
        wrappedLines.clear()
        if (maxTextWidth <= 0f) {
            wrappedLines.addAll(lines)
        } else {
            updateStyle(lastScale)
            for (line in lines) {
                wrappedLines.addAll(wrapLine(line, pText, maxTextWidth))
            }
        }
        
        if (wrappedLines.size > 500) {
            repeat(wrappedLines.size - 500) { wrappedLines.removeAt(0) }
        }
    }

    private fun wrapLine(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        if (maxWidth <= 0f) return listOf(text)
        
        val result = mutableListOf<String>()
        val tokens = text.split(tokenRegex)
        var currentLine = StringBuilder()

        for (token in tokens) {
            val testLine = currentLine.toString() + token
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine.append(token)
            } else {
                if (currentLine.isNotEmpty()) {
                    result.add(currentLine.toString())
                    currentLine = StringBuilder()
                }
                
                // Skip leading space on new line
                if (token.isBlank() && token != "\n") continue
                
                if (paint.measureText(token) > maxWidth) {
                    var start = 0
                    while (start < token.length) {
                        val count = paint.breakText(token, start, token.length, true, maxWidth, null)
                        if (count <= 0) break
                        result.add(token.substring(start, start + count))
                        start += count
                    }
                } else {
                    currentLine.append(token)
                }
            }
        }
        if (currentLine.isNotEmpty()) result.add(currentLine.toString())
        return result
    }

    private fun getKeyWeight(key: String): Float {
        return when (key) {
            "SPACE" -> 4.5f // Large space bar like Android
            "ENTER" -> 2.0f
            "CTRL", "ALT", "HELP", "EXIT" -> 2.5f
            "SHIFT", "DEL", "?123", "ABC", "TAB" -> 1.5f
            else -> 1f
        }
    }

    private fun updateStyle(scale: Float) {
        if (scale <= 0f) return
        val theme = SaveManager.terminalTheme
        val sizeStr = SaveManager.fontSize

        pText.color = when (theme) {
            "AMBER" -> 0xFFFFB000.toInt()
            "MATRIX" -> 0xFF00FF41.toInt()
            "CLARITY" -> 0xFF00E5FF.toInt()
            "BLOOD" -> 0xFFFF3D00.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

        val sizeMult = when (sizeStr) {
            "SMALL" -> 0.75f
            "LARGE" -> 1.35f
            else -> 1.0f
        }
        pText.textSize = scale * 0.03f * sizeMult
    }

    fun draw(c: Canvas, targetW: Float, targetH: Float, scale: Float) {
        processQueues()
        updateStyle(scale)
        val sizeChanged = targetW != lastW || scale != lastScale || 
                         SaveManager.fontSize != lastFontSize || SaveManager.terminalTheme != lastTheme
        
        if (sizeChanged) {
            lastW = targetW; lastH = targetH; lastScale = scale
            lastFontSize = SaveManager.fontSize
            lastTheme = SaveManager.terminalTheme
            rebuildWrappedLines()
        }
        
        c.drawColor(0xFF050505.toInt())

        val isLandscape = targetW > targetH
        val kbHeight = if (isKeyboardVisible) {
            if (isLandscape) targetH * 0.68f else targetH * 0.5f
        } else 0f
        val startY = targetH - kbHeight

        val padding = scale * 0.04f
        val lineHeight = pText.textSize * 1.5f
        
        val newMaxWidth = targetW - padding * 2
        if (sizeChanged || newMaxWidth != maxTextWidth) {
            maxTextWidth = newMaxWidth
            rebuildWrappedLines()
        }

        // Available height for terminal lines and prompt
        val availableHeight = startY - padding * 2 - lineHeight
        val dynamicMaxLines = (availableHeight / lineHeight).toInt().coerceAtLeast(1)

        // Draw Terminal Output
        pText.textAlign = Paint.Align.LEFT
        pText.color = if (StoryProtocol.isGlitchActive) GameColors.RED else GameColors.HP
        
        if (StoryProtocol.isGlitchActive) {
            pText.alpha = (150 + (Math.random() * 105).toInt())
        } else {
            pText.alpha = 255
        }

        val maxScroll = (wrappedLines.size - dynamicMaxLines).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxScroll)

        val startIndex = (wrappedLines.size - dynamicMaxLines - scrollOffset).coerceAtLeast(0)
        val endIndex = (wrappedLines.size - scrollOffset).coerceAtLeast(0)
        val visibleLines = if (wrappedLines.isNotEmpty()) wrappedLines.subList(startIndex, endIndex) else emptyList()

        var currY = padding + pText.textSize
        for (line in visibleLines) {
            c.drawText(line, padding, currY, pText)
            currY += lineHeight
        }

        // Draw Input Prompt (Only if at the bottom)
        if (scrollOffset == 0) {
            val blink = (System.currentTimeMillis() / 600) % 2 == 0L
            val promptStr = activeSubSession?.getPrompt() ?: ">"
            val prompt = "$promptStr $currentInput"
            pText.color = 0xFFFFFFFF.toInt()
            
            val promptY = currY.coerceAtMost(startY - padding * 0.5f)
            c.drawText(prompt, padding, promptY, pText)

            if (blink) {
                val promptWidth = pText.measureText(prompt)
                c.drawRect(padding + promptWidth + 2f, promptY - pText.textSize * 0.8f,
                           padding + promptWidth + pText.textSize * 0.6f, promptY + pText.textSize * 0.2f, pText)
            }
        }

        // Keyboard Toggle / Show Button (when hidden)
        val showBtnH = scale * 0.08f
        if (!isKeyboardVisible) {
            val showBtnW = scale * 0.2f
            val showBtnRect = RectF(targetW - showBtnW - padding, targetH - showBtnH - padding, targetW - padding, targetH - padding)
            pBg.color = 0xCC222222.toInt()
            c.drawRoundRect(showBtnRect, scale * 0.01f, scale * 0.01f, pBg)
            pText.color = 0xFFFFFFFF.toInt()
            pText.textAlign = Paint.Align.CENTER
            pText.textSize = scale * 0.025f
            c.drawText("SHOW KEY", showBtnRect.centerX(), showBtnRect.centerY() + pText.textSize * 0.35f, pText)
            
            // Store for touch
            keyRects["SHOW_KEY"] = showBtnRect
        }

        // Scroll to Bottom Button
        if (scrollOffset > 0) {
            val btnW = scale * 0.22f
            val btnH = scale * 0.07f
            
            // UX FIX: If keyboard is hidden, shift "BOTTOM" button up to avoid overlap with "SHOW KEY"
            val bottomShift = if (!isKeyboardVisible) (showBtnH + padding * 0.5f) else 0f
            val bottomAnchor = if (isKeyboardVisible) startY else targetH - bottomShift

            scrollToBottomRect.set(targetW - btnW - padding, bottomAnchor - btnH - padding, targetW - padding, bottomAnchor - padding)
            
            pBg.color = 0xCC111111.toInt()
            c.drawRoundRect(scrollToBottomRect, scale * 0.01f, scale * 0.01f, pBg)
            pBg.color = GameColors.PULSE
            pBg.style = Paint.Style.STROKE
            pBg.strokeWidth = 2f
            c.drawRoundRect(scrollToBottomRect, scale * 0.01f, scale * 0.01f, pBg)
            pBg.style = Paint.Style.FILL
            
            pText.textAlign = Paint.Align.CENTER
            pText.textSize = scale * 0.025f
            pText.color = GameColors.PULSE
            c.drawText("▼ BOTTOM", scrollToBottomRect.centerX(), scrollToBottomRect.centerY() + pText.textSize * 0.35f, pText)
        } else {
            scrollToBottomRect.setEmpty()
        }

        // Draw Keyboard
        if (isKeyboardVisible) {
            drawKeyboard(c, targetW, targetH, scale, kbHeight, startY)
        }
    }

    private fun drawKeyboard(c: Canvas, targetW: Float, targetH: Float, scale: Float, kbHeight: Float, startY: Float) {
        val currentKb = if (isSymbolMode) kbSymbol else kbAlpha
        val isLandscape = targetW > targetH
        val kbPaddingH = if (isLandscape) targetW * 0.18f else scale * 0.02f
        val kbPaddingV = scale * 0.02f
        val keyGap = scale * 0.01f

        val rows = currentKb.size
        val rowHeight = (kbHeight - kbPaddingV * 2 - keyGap * (rows - 1)) / rows

        keyRects.clear()

        for (r in 0 until rows) {
            val row = currentKb[r]
            val totalWeight = row.sumOf { getKeyWeight(it).toDouble() }.toFloat()
            val availableWidth = targetW - kbPaddingH * 2 - keyGap * (row.size - 1)
            val unitWidth = availableWidth / totalWeight

            val currY = startY + kbPaddingV + r * (rowHeight + keyGap)
            var currentX = kbPaddingH

            for (key in row) {
                val weight = getKeyWeight(key)
                val keyWidth = unitWidth * weight
                val rect = RectF(currentX, currY, currentX + keyWidth, currY + rowHeight)
                keyRects[key] = rect

                // Button colors
                val isPressed = activePointers.values.contains(key)
                pBg.color = when (key) {
                    "ENTER" -> 0xFF00AA00.toInt()
                    "EXIT" -> 0xFFAA0000.toInt()
                    "DEL" -> 0xFFCC3333.toInt()
                    "SCAN", "DIR", "HELP" -> 0xFF0055AA.toInt()
                    "SHIFT" -> if (isCapsLock) 0xFF00AAFF.toInt() else if (isShiftActive) 0xFF0088CC.toInt() else 0xFF444444.toInt()
                    "CTRL" -> if (isCtrlActive) 0xFF0088CC.toInt() else 0xFF444444.toInt()
                    "ALT" -> if (isAltActive) 0xFF0088CC.toInt() else 0xFF444444.toInt()
                    "TAB", "?123", "ABC" -> 0xFF444444.toInt()
                    "SPACE" -> 0xFF222222.toInt()
                    else -> 0xFF111111.toInt()
                }
                
                if (isPressed) {
                    // Darken/Highlight pressed key
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(pBg.color, hsv)
                    hsv[2] *= 0.7f 
                    pBg.color = android.graphics.Color.HSVToColor(hsv)
                }
                
                pBg.alpha = 240
                
                val visualRect = if (isPressed) {
                    RectF(rect.left + 2, rect.top + 2, rect.right - 2, rect.bottom - 2)
                } else rect

                c.drawRoundRect(visualRect, scale * 0.005f, scale * 0.005f, pBg)
                
                // Key Border/Shadow
                pBg.style = Paint.Style.STROKE
                pBg.color = 0xFF555555.toInt()
                pBg.strokeWidth = scale * 0.001f
                c.drawRoundRect(rect, scale * 0.005f, scale * 0.005f, pBg)
                pBg.style = Paint.Style.FILL
                
                pText.color = 0xFFFFFFFF.toInt()
                pText.textAlign = Paint.Align.CENTER
                pText.textSize = if (key.length > 2) rowHeight * 0.25f else rowHeight * 0.4f
                
                // Special Icons/Text
                val displayText = when(key) {
                    "SPACE" -> ""
                    "DEL" -> "⌫"
                    "ENTER" -> "⏎"
                    else -> {
                        if (key.length == 1 && key[0].isLetter()) {
                            if (isShiftActive || isCapsLock) key.uppercase() else key.lowercase()
                        } else key
                    }
                }
                c.drawText(displayText, rect.centerX(), rect.centerY() + pText.textSize * 0.35f, pText)
                
                currentX += keyWidth + keyGap
            }
        }
    }

    fun onTouch(event: MotionEvent, scale: Float, gs: GameState, context: Context, onExit: () -> Unit, onStartGame: (com.appsbyalok.echohunter.engine.GameModeId, Int) -> Unit): Boolean {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (scrollToBottomRect.contains(x, y)) {
                    activePointers[pointerId] = "SCROLL_TO_BOTTOM"
                    return true
                }

                if (!isKeyboardVisible) {
                    val showRect = keyRects["SHOW_KEY"]
                    if (showRect != null && showRect.contains(x, y)) {
                        activePointers[pointerId] = "SHOW_KEY"
                        return true
                    }
                }

                // Dragging start check (above keyboard area)
                val kbHeight = if (isKeyboardVisible) (if (lastW > lastH) lastH * 0.68f else lastH * 0.5f) else 0f
                val kbStartY = lastH - kbHeight
                
                if (y < kbStartY || !isKeyboardVisible) {
                    isDragging = true
                    lastTouchY = y
                    activePointers[pointerId] = "DRAG_AREA"
                    return true
                }

                for ((key, rect) in keyRects) {
                    if (rect.contains(x, y)) {
                        activePointers[pointerId] = key
                        pressedKeys.add(key)
                        
                        // Modifier Tracking: If we press a key while a modifier is held, mark it as used
                        if (key != "SHIFT" && key != "CTRL" && key != "ALT") {
                            if (activePointers.values.contains("SHIFT")) shiftWasUsedAsModifier = true
                            if (activePointers.values.contains("CTRL")) ctrlWasUsedAsModifier = true
                            if (activePointers.values.contains("ALT")) altWasUsedAsModifier = true
                        } else {
                            // Reset tracking when a modifier itself is pressed down
                            when (key) {
                                "SHIFT" -> shiftWasUsedAsModifier = false
                                "CTRL" -> ctrlWasUsedAsModifier = false
                                "ALT" -> altWasUsedAsModifier = false
                            }
                        }

                        if (key == "DEL") {
                            EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 50)
                            if (currentInput.isNotEmpty()) currentInput = currentInput.substring(0, currentInput.length - 1)
                            handler.removeCallbacks(delRunnable)
                            handler.postDelayed(delRunnable, 500)
                        }
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && activePointers[pointerId] == "DRAG_AREA") {
                    val deltaY = y - lastTouchY
                    val lineHeight = (scale * 0.03f) * 1.5f
                    if (abs(deltaY) >= lineHeight) {
                        scrollOffset += (deltaY / lineHeight).toInt()
                        lastTouchY = y
                    }
                    return true
                }
                
                // Update pressed state for all active pointers
                for (i in 0 until event.pointerCount) {
                    val pId = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)
                    val originalKey = activePointers[pId]
                    if (originalKey != null && originalKey != "DRAG_AREA") {
                        val rect = keyRects[originalKey]
                        if (rect != null) {
                            if (rect.contains(px, py)) pressedKeys.add(originalKey)
                            else pressedKeys.remove(originalKey)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val keyOnDown = activePointers.remove(pointerId)
                if (keyOnDown != null) {
                    pressedKeys.remove(keyOnDown)
                    if (keyOnDown == "DEL") handler.removeCallbacks(delRunnable)

                    var keyOnUp: String? = null
                    if (scrollToBottomRect.contains(x, y)) keyOnUp = "SCROLL_TO_BOTTOM"
                    else if (!isKeyboardVisible && keyRects["SHOW_KEY"]?.contains(x, y) == true) keyOnUp = "SHOW_KEY"
                    else {
                        for ((key, rect) in keyRects) {
                            if (rect.contains(x, y)) {
                                keyOnUp = key
                                break
                            }
                        }
                    }

                    if (keyOnUp == keyOnDown) {
                        when (keyOnUp) {
                            "SCROLL_TO_BOTTOM" -> {
                                scrollOffset = 0
                                EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 50)
                            }
                            "SHOW_KEY" -> {
                                isKeyboardVisible = true
                                EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 50)
                            }
                            "DRAG_AREA" -> {}
                            "SHIFT" -> {
                                if (!shiftWasUsedAsModifier) {
                                    EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 50)
                                    handleKeyPress(keyOnUp, gs, context, onExit, onStartGame)
                                }
                                shiftWasUsedAsModifier = false
                            }
                            "CTRL" -> {
                                if (!ctrlWasUsedAsModifier) {
                                    EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 50)
                                    handleKeyPress(keyOnUp, gs, context, onExit, onStartGame)
                                }
                                ctrlWasUsedAsModifier = false
                            }
                            "ALT" -> {
                                if (!altWasUsedAsModifier) {
                                    EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 50)
                                    handleKeyPress(keyOnUp, gs, context, onExit, onStartGame)
                                }
                                altWasUsedAsModifier = false
                            }
                            else -> {
                                if (keyOnUp != "DEL") {
                                    EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 50)
                                    handleKeyPress(keyOnUp, gs, context, onExit, onStartGame)
                                }
                            }
                        }
                    }
                }
                if (activePointers.isEmpty()) isDragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointers.clear()
                pressedKeys.clear()
                isDragging = false
                handler.removeCallbacks(delRunnable)
            }
        }
        return true
    }

    private fun handleKeyPress(key: String, gs: GameState, context: Context, onExit: () -> Unit, onStartGame: (com.appsbyalok.echohunter.engine.GameModeId, Int) -> Unit) {
        if (key != "TAB" && key != "SHIFT" && key != "CTRL" && key != "ALT") {
            autocompleteMatches = emptyList()
            autocompleteIndex = -1
            lastGeneratedInput = ""
        }
        when (key) {
            "ENTER" -> {
                if (currentInput.isNotEmpty()) {
                    if (commandHistory.isEmpty() || commandHistory.last() != currentInput) {
                        commandHistory.add(currentInput)
                    }
                    historyIndex = commandHistory.size
                    processCommand(currentInput, gs, context, onExit, onStartGame)
                    currentInput = ""
                }
                isShiftActive = false
                isCtrlActive = false
                isAltActive = false
            }
            "EXIT" -> processCommand("EXIT", gs, context, onExit, onStartGame)
            "HIDE" -> {
                isKeyboardVisible = false
                isShiftActive = false
                isCtrlActive = false
                isAltActive = false
            }
            "SPACE" -> currentInput += " "
            "TAB" -> handleAutocomplete(gs)
            "?123", "ABC" -> {
                isSymbolMode = !isSymbolMode
                isShiftActive = false
                isCtrlActive = false
                isAltActive = false
            }
            "SHIFT" -> {
                val now = System.currentTimeMillis()
                if (now - lastShiftTapTime < 300) {
                    isCapsLock = !isCapsLock
                    isShiftActive = false
                } else {
                    isShiftActive = !isShiftActive
                    if (isShiftActive) isCapsLock = false
                }
                lastShiftTapTime = now
            }
            "CTRL" -> isCtrlActive = !isCtrlActive
            "ALT" -> isAltActive = !isAltActive
            "SCAN", "DIR", "HELP" -> processCommand(key, gs, context, onExit, onStartGame)
            "..." -> currentInput += "..."
            else -> {
                val ctrl = isCtrlActive || activePointers.values.contains("CTRL")
                val alt = isAltActive || activePointers.values.contains("ALT")
                
                if (ctrl) {
                    when (key.uppercase()) {
                        "C" -> currentInput = ""
                        "L" -> clearScreen()
                        "Q" -> {
                            if (activeSubSession != null) {
                                activeSubSession = null
                                addLines(">> SUB-SESSION TERMINATED. RETURNED TO ROOT.", OutputMode.INSTANT)
                            } else {
                                processCommand("EXIT", gs, context, onExit, onStartGame)
                            }
                        }
                        "P" -> if (commandHistory.isNotEmpty()) {
                            historyIndex = (historyIndex - 1).coerceAtLeast(0)
                            currentInput = commandHistory[historyIndex]
                        }
                        "N" -> if (commandHistory.isNotEmpty()) {
                            historyIndex = (historyIndex + 1).coerceAtMost(commandHistory.size)
                            currentInput = if (historyIndex < commandHistory.size) commandHistory[historyIndex] else ""
                        }
                    }
                    isCtrlActive = false
                } else if (alt) {
                    when (key) {
                        "." -> if (commandHistory.isNotEmpty()) {
                            val lastCmd = commandHistory.last()
                            val lastArg = lastCmd.trim().split(" ").last()
                            currentInput += lastArg
                        }
                    }
                    isAltActive = false
                } else {
                    if (currentInput.length < 40) {
                        var char = key
                        if (char.length == 1 && char[0].isLetter()) {
                            val isShift = isShiftActive || isCapsLock || activePointers.values.contains("SHIFT")
                            char = if (isShift) char.uppercase() else char.lowercase()
                        }
                        currentInput += char
                    }
                    if (isShiftActive) isShiftActive = false
                }
            }
        }
    }

    private fun handleAutocomplete(gs: GameState) {
        if (currentInput.isEmpty()) return

        // Cycle through existing matches if input hasn't changed manually
        if (autocompleteMatches.isNotEmpty() && currentInput == lastGeneratedInput) {
            autocompleteIndex = (autocompleteIndex + 1) % autocompleteMatches.size
            val nextMatch = autocompleteMatches[autocompleteIndex]
            
            val parts = lastManualInput.split(" ").filter { it.isNotEmpty() }.toMutableList()
            if (lastManualInput.endsWith(" ")) {
                parts.add(nextMatch)
            } else {
                if (parts.isNotEmpty()) parts[parts.size - 1] = nextMatch
                else parts.add(nextMatch)
            }
            
            currentInput = parts.joinToString(" ")
            if (parts.size == 1) currentInput += " " // Add space after command
            
            lastGeneratedInput = currentInput
            return
        }

        // Search for new matches
        val isTrailingSpace = currentInput.endsWith(" ")
        val parts = currentInput.trim().split(" ").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        
        lastManualInput = currentInput
        val mainCmdName = parts[0].uppercase()
        val command = CommandRegistry.getCommand(mainCmdName)

        if (parts.size == 1 && !isTrailingSpace) {
            // Autocomplete Command Name
            val prefix = parts[0].uppercase()
            val matches = CommandRegistry.getAllCommands(gs)
                .flatMap { listOf(it.name) + it.aliases }
                .filter { it.startsWith(prefix) }
                .distinct()
                .sorted()
            
            if (matches.isNotEmpty()) {
                autocompleteMatches = matches
                autocompleteIndex = 0
                currentInput = matches[0] + " "
                lastGeneratedInput = currentInput
            }
        } else if (command != null && command.isUnlocked(gs)) {
            // Autocomplete Subcommands/Arguments
            val subArgs = if (isTrailingSpace) parts.drop(1) + "" else parts.drop(1)
            val matches = command.getSuggestions(subArgs, gs)
            
            if (matches.isNotEmpty()) {
                autocompleteMatches = matches
                autocompleteIndex = 0
                
                val newParts = parts.toMutableList()
                if (isTrailingSpace) {
                    newParts.add(matches[0])
                } else {
                    newParts[newParts.size - 1] = matches[0]
                }
                
                currentInput = newParts.joinToString(" ")
                lastGeneratedInput = currentInput
            }
        }
    }

    private fun tryCalculate(cmd: String): String? {
        val expression = cmd.replace(" ", "").replace("X", "*", ignoreCase = true).replace("÷", "/")
        if (!expression.any { it.isDigit() } || !expression.any { "+-*/()".contains(it) }) return null

        // Implicit multiplication: 3( -> 3*(, )6 -> )*6, )( -> )*(
        val sb = StringBuilder()
        for (i in expression.indices) {
            sb.append(expression[i])
            if (i < expression.length - 1) {
                val curr = expression[i]
                val next = expression[i + 1]
                if ((curr.isDigit() && next == '(') || (curr == ')' && next.isDigit()) || (curr == ')' && next == '(')) {
                    sb.append('*')
                }
            }
        }
        val finalExpr = sb.toString()
        var pos = -1
        var ch = -1

        fun nextChar() { ch = if (++pos < finalExpr.length) finalExpr[pos].code else -1 }
        fun eat(charToEat: Int): Boolean {
            if (ch == charToEat) { nextChar(); return true }
            return false
        }

        fun parseExpression(): Double {
            fun parseFactor(): Double {
                if (eat('+'.code)) return parseFactor()
                if (eat('-'.code)) return -parseFactor()
                val x: Double
                val startPos = pos
                if (eat('('.code)) {
                    x = parseExpression()
                    eat(')'.code)
                } else if (ch in '0'.code..'9'.code || ch == '.'.code) {
                    while (ch in '0'.code..'9'.code || ch == '.'.code) nextChar()
                    x = finalExpr.substring(startPos, pos).toDouble()
                } else throw RuntimeException()
                return x
            }
            fun parseTerm(): Double {
                var x = parseFactor()
                while (true) {
                    if (eat('*'.code)) x *= parseFactor()
                    else if (eat('/'.code)) {
                        val d = parseFactor()
                        if (d == 0.0) throw ArithmeticException("DIV/0")
                        x /= d
                    } else return x
                }
            }
            var x = parseTerm()
            while (true) {
                if (eat('+'.code)) x += parseTerm()
                else if (eat('-'.code)) x -= parseTerm()
                else return x
            }
        }

        return try {
            nextChar()
            val result = parseExpression()
            if (pos < finalExpr.length) return null
            val s = result.toString()
            if (s.endsWith(".0")) s.substringBefore(".0")
            else String.format(Locale.US, "%.3f", result).trimEnd('0').trimEnd('.')
        } catch (_: ArithmeticException) { "ERR: DIV/0" } catch (_: Exception) { null }
    }

    private fun processCommand(cmd: String, gs: GameState, androidContext: Context, onExit: () -> Unit, onStartGame: (com.appsbyalok.echohunter.engine.GameModeId, Int) -> Unit) {
        val promptStr = activeSubSession?.getPrompt() ?: ">"
        addLines("$promptStr $cmd\n", OutputMode.INSTANT)
        
        // Mark terminal as used once any command is processed
        if (!SaveManager.isFirstTerminalUsed && cmd.trim().isNotEmpty()) {
            SaveManager.setFirstTerminalUsed(true)
        }

        // Logical splitting: & (and/then)
        if (cmd.contains(" & ")) {
            cmd.split(" & ").forEach { processCommand(it.trim(), gs, androidContext, onExit, onStartGame) }
            return
        }

        // Sub-session handling
        val sub = activeSubSession
        if (sub != null) {
            val context = CommandContext(
                gameState = gs,
                terminalLines = lines,
                onExit = onExit,
                files = files,
                onAddLines = { t, m -> addLines(t, m) },
                onClear = { clearScreen() },
                onStartGame = onStartGame,
                androidContext = androidContext
            )
            val result = sub.handleInput(cmd, context)
            if (result.output != null) addLines(result.output, result.mode)
            
            // Sub-session determines persistence. If result.subSession is null, we exit.
            activeSubSession = result.subSession
            return
        }

        // 1. Math Calculation Check (Special Fallback)
        val calcResult = tryCalculate(cmd)
        if (calcResult != null) {
            addLines("= $calcResult", OutputMode.INSTANT)
            return
        }

        val parts = cmd.trim().split(" ").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        
        val mainCmdName = parts[0].uppercase()
        val args = if (parts.size > 1) parts.drop(1) else emptyList()

        if (mainCmdName == "EXIT" || mainCmdName == "QUIT") {
            isPendingExit = true
            exitCallback = onExit
        }
        
        val command = CommandRegistry.getCommand(mainCmdName)
        
        if (command != null) {
            if (command.isUnlocked(gs)) {
                val context = CommandContext(
                    gameState = gs,
                    terminalLines = lines,
                    onExit = onExit,
                    files = files,
                    onAddLines = { t, m -> addLines(t, m) },
                    onClear = { clearScreen() },
                    onStartGame = onStartGame,
                    androidContext = androidContext
                )
                val result = command.execute(args, context)

                if (result.output != null) {
                    addLines(result.output, result.mode)
                }
                
                if (result.subSession != null) {
                    activeSubSession = result.subSession
                }
            } else {
                addLines("ERR: ACCESS DENIED. COMMAND LOCKED.", OutputMode.INSTANT)
            }
        } else {
            addLines("ERR: UNKNOWN COMMAND '$mainCmdName'", OutputMode.INSTANT)
        }
        
        // Auto-scroll to bottom on command execution
        scrollOffset = 0
    }
}
