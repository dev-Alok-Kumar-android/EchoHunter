package com.appsbyalok.echohunter.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ToneGenerator
import android.view.MotionEvent
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.data.UpgradeSystem
import com.appsbyalok.echohunter.data.UpgradeType
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.ui.components.UIMenuButton
import com.appsbyalok.echohunter.ui.components.UIMenuCard
import com.appsbyalok.echohunter.ui.components.UIMenuMetrics
import com.appsbyalok.echohunter.ui.components.UIMenuScreenChrome
import com.appsbyalok.echohunter.ui.components.UIScrollView
import com.appsbyalok.echohunter.utils.EchoAudioManager
import com.appsbyalok.echohunter.utils.GameColors
import kotlin.math.max

class UIDecompiler {
    private val p = Paint().apply { isAntiAlias = true }
    private val pText = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val scroller = UIScrollView()
    private val chrome = UIMenuScreenChrome()
    private val card = UIMenuCard()
    private val closeButton = UIMenuButton()

    private var expandedType: UpgradeType? = null
    private val buyButtons = mutableMapOf<UpgradeType, RectF>()
    private val cardRects = mutableMapOf<UpgradeType, RectF>()
    private val tempRect = RectF()
    private var hitOnDown = -1
    private var hitTypeOnDown: UpgradeType? = null
    private var listTop = 0f
    private var listBottom = 0f

    fun hasAffordableAction(): Boolean {
        if (UpgradeSystem.catalog.isEmpty()) return false
        branches.forEach { branch ->
            branch.third.forEach { type ->
                val config = UpgradeSystem.catalog[type] ?: return@forEach
                val cost = UpgradeSystem.getNextLevelCost(type)
                if (UpgradeSystem.getLevel(type) < config.maxLevel && SaveManager.dataCoinsKB >= cost) {
                    return true
                }
            }
        }
        return false
    }

    private val branches = listOf(
        Triple(
            "--- [ ARCHITECT ] ---", "CORE LOGIC & SYSTEM DATA", listOf(
                UpgradeType.DATA_MAGNET,
                UpgradeType.COMPRESSION_ALGO,
                UpgradeType.DATA_SYNDICATE,
                UpgradeType.OVERCLOCK_DUR,
                UpgradeType.OPTIC_SENSORS
            )
        ), Triple(
            "--- [ ENFORCER ] ---", "TACTICAL COMBAT & OFFENSE", listOf(
                UpgradeType.CRIT_CHANCE,
                UpgradeType.KINETIC_OVERLOAD,
                UpgradeType.MULTITHREAD_SPIKES,
                UpgradeType.COMBO_EXTENDER
            )
        ), Triple(
            "--- [ GHOST ] ---", "TACTICAL & SONAR RECON", listOf(
                UpgradeType.PULSE_FREQUENCY,
                UpgradeType.TRAP_COOLDOWN,
                UpgradeType.GHOST_PROTOCOL,
                UpgradeType.SONAR_RANGE,
                UpgradeType.SILENT_SONAR,
                UpgradeType.SONAR_DUR,
                UpgradeType.RESONANCE_CHAMBER
            )
        ), Triple(
            "--- [ FIRMWARE ] ---", "MARKET-RESTRICTED PATCHES", listOf(
                UpgradeType.PATCH_OVERCLOCK_REGEN,
                UpgradeType.PATCH_HEALTH_SIPHON,
                UpgradeType.PATCH_SHIELD_BURST,
                UpgradeType.PATCH_CRIT_VAMP,
                UpgradeType.PATCH_DATA_OVERFLOW,
                UpgradeType.PATCH_COMBO_SHIELD
            )
        )
    )

    fun draw(c: Canvas, targetW: Float, targetH: Float, scale: Float, dt: Float) {
        val metrics = UIMenuMetrics(targetW, targetH, scale)
        val insetT = metrics.insetTop
        val insetB = metrics.insetBottom
        val insetL = metrics.insetLeft
        val insetR = metrics.insetRight

        chrome.drawBackground(c, metrics, p, bgColor = 0xEE020502.toInt())

        scroller.updatePhysics(dt)

        // --- Header ---
        pText.textAlign = Paint.Align.CENTER
        pText.textSize = scale * 0.08f
        pText.color = GameColors.HP
        pText.setShadowLayer(15f, 0f, 0f, GameColors.HP)
        // Check if device is landscape and has a side notch that might cover the center
        val centerShift = if (targetW > targetH && (insetL > 0 || insetR > 0)) {
            // In landscape, if there's a notch, the physical center might be obscured
            // but usually notches are on the sides. If the user means a center punch-hole
            // in landscape (top of screen), we need more vertical clearance.
            insetT + scale * 0.18f
        } else {
            insetT + scale * 0.15f
        }

        c.drawText("DECOMPILER v4.0", targetW / 2f, centerShift, pText)
        pText.clearShadowLayer()

        pText.textSize = scale * 0.035f
        pText.color = GameColors.CLARITY
        c.drawText(
            "AVAILABLE DATA: ${SaveManager.formatDataString(SaveManager.dataCoinsKB)}",
            targetW / 2f,
            centerShift + scale * 0.06f,
            pText
        )

        // --- Tier Labels & List ---
        listTop = centerShift + scale * 0.11f
        listBottom = targetH - insetB - scale * 0.14f

        scroller.viewport.set(0f, listTop, targetW, listBottom)
        scroller.begin(c)

        var currentY =
            scale * 0.03f // Start relative to viewport top (scroller handles viewport translation)
        val marginX = scale * 0.05f + max(insetL, insetR)

        buyButtons.clear()
        cardRects.clear()

        for ((branchName, branchDesc, types) in branches) {
            // --- ENHANCED BRANCH HEADER ---
            val ribbonW = targetW - 2 * marginX
            val ribbonH = scale * 0.07f
            val headY = currentY + scale * 0.01f

            // Background Ribbon
            p.style = Paint.Style.FILL
            p.color = 0x15FFFFFF and GameColors.PULSE // Faint tint
            c.drawRect(
                targetW / 2f - ribbonW / 2f, headY, targetW / 2f + ribbonW / 2f, headY + ribbonH, p
            )

            // Side Accents
            p.style = Paint.Style.FILL; p.color = GameColors.PULSE
            c.drawRect(
                targetW / 2f - ribbonW / 2f,
                headY,
                targetW / 2f - ribbonW / 2f + scale * 0.01f,
                headY + ribbonH,
                p
            )
            c.drawRect(
                targetW / 2f + ribbonW / 2f - scale * 0.01f,
                headY,
                targetW / 2f + ribbonW / 2f,
                headY + ribbonH,
                p
            )

            pText.textAlign = Paint.Align.CENTER
            pText.textSize = scale * 0.042f
            pText.color = GameColors.PULSE
            pText.isFakeBoldText = true
            c.drawText(branchName, targetW / 2f, headY + scale * 0.048f, pText)
            pText.isFakeBoldText = false

            pText.textSize = scale * 0.022f
            pText.color = GameColors.YELLOW
            pText.alpha = 180
            c.drawText(
                "SUBSYSTEM: $branchDesc", targetW / 2f, headY + ribbonH + scale * 0.025f, pText
            )
            pText.alpha = 255

            currentY += scale * 0.13f

            for (type in types) {
                val config = UpgradeSystem.catalog[type] ?: continue
                val isExpanded = expandedType == type

                // Dimensions & Widths
                val cardWidth = targetW - 2 * marginX
                val internalPadding = scale * 0.04f
                val textWidth = cardWidth - internalPadding * 2f

                val btnW = scale * 0.26f
                val btnH = scale * 0.14f
                val maxHeaderW = cardWidth - btnW - scale * 0.12f // Gap for button

                pText.textSize = scale * 0.028f
                val funcH =
                    measureWrappedTextHeight("FUNCTION: ${config.descStr}", textWidth, pText)
                val tactH =
                    measureWrappedTextHeight("TACTICAL: ${config.usageStr}", textWidth, pText)
                val loreH = measureWrappedTextHeight("LOG: \"${config.loreStr}\"", textWidth, pText)

                val currentItemH = if (isExpanded) {
                    scale * 0.28f + funcH + tactH + loreH + scale * 0.12f
                } else scale * 0.22f

                val itemRect = RectF(
                    marginX, currentY, targetW - marginX, currentY + currentItemH - scale * 0.02f
                )
                cardRects[type] = itemRect

                val currentLvl = UpgradeSystem.getLevel(type)
                val isMaxed = currentLvl >= config.maxLevel
                val cost = UpgradeSystem.getNextLevelCost(type)
                val canAfford = !isMaxed && SaveManager.dataCoinsKB >= cost

                card.draw(
                    c = c,
                    rect = itemRect,
                    scale = scale,
                    paint = p,
                    active = isExpanded,
                    pressed = hitOnDown == 3 && hitTypeOnDown == type,
                    fillColor = if (isMaxed) 0xFF051105.toInt() else 0xFF0A0A0A.toInt(),
                    activeFillColor = 0xFF0D180D.toInt(),
                    strokeColor = if (isMaxed) 0xFF004400.toInt() else if (canAfford) GameColors.PULSE else 0xFF444444.toInt(),
                    activeStrokeColor = GameColors.HP,
                    radius = scale * 0.01f
                )

                // Inject Button (Top Right)
                val btnRect = RectF(
                    itemRect.right - btnW - scale * 0.02f,
                    itemRect.top + scale * 0.02f,
                    itemRect.right - scale * 0.02f,
                    itemRect.top + btnH + scale * 0.02f
                )

                // Highlight first software upgrade
                if (!SaveManager.isFirstSoftwareUpgradeDone && canAfford && !isMaxed) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = scale * 0.012f
                    p.color = GameColors.HP
                    val pulse = (System.currentTimeMillis() % 1000) / 1000f
                    p.alpha = (255 * (1f - pulse)).toInt()
                    val pad = scale * 0.01f + pulse * scale * 0.02f
                    tempRect.set(btnRect.left - pad, btnRect.top - pad, btnRect.right + pad, btnRect.bottom + pad)
                    c.drawRoundRect(tempRect, scale * 0.01f, scale * 0.01f, p)
                    p.alpha = 255
                }

                // Only register click for button if not maxed
                if (!isMaxed) buyButtons[type] = btnRect

                // Draw Title (Dynamic resizing & Truncation)
                pText.textAlign = Paint.Align.LEFT
                pText.color = if (isMaxed) GameColors.HP else GameColors.CLARITY
                var titleSize = scale * 0.042f
                pText.textSize = titleSize

                var titleText = "> ${config.nameStr}"
                while (pText.measureText(titleText) > maxHeaderW && titleSize > scale * 0.03f) {
                    titleSize -= 1f
                    pText.textSize = titleSize
                }
                // Final truncation check if still too long
                if (pText.measureText(titleText) > maxHeaderW) {
                    while (titleText.length > 5 && pText.measureText("$titleText...") > maxHeaderW) {
                        titleText = titleText.dropLast(1)
                    }
                    titleText += "..."
                }
                c.drawText(titleText, marginX + internalPadding, currentY + scale * 0.055f, pText)

                pText.textSize = scale * 0.025f
                pText.color = 0xFF888888.toInt()
                c.drawText(
                    "Ver: $currentLvl/${config.maxLevel}",
                    marginX + internalPadding,
                    currentY + scale * 0.09f,
                    pText
                )

                if (isExpanded) {
                    // Expanded Details (Starts below button)
                    var nextY = currentY + scale * 0.20f

                    pText.textSize = scale * 0.028f
                    pText.color = GameColors.CLARITY
                    nextY = drawWrappedText(
                        c,
                        "FUNCTION: ${config.descStr}",
                        marginX + internalPadding,
                        nextY,
                        textWidth,
                        pText
                    )

                    pText.color = GameColors.YELLOW
                    nextY += scale * 0.015f
                    nextY = drawWrappedText(
                        c,
                        "TACTICAL: ${config.usageStr}",
                        marginX + scale * 0.04f,
                        nextY,
                        textWidth,
                        pText
                    )

                    pText.color = 0xFF666666.toInt()
                    nextY += scale * 0.015f
                    drawWrappedText(
                        c,
                        "LOG: \"${config.loreStr}\"",
                        marginX + scale * 0.04f,
                        nextY,
                        textWidth,
                        pText
                    )

                    // Bottom Status
                    pText.color = GameColors.HP
                    pText.textSize = scale * 0.024f
                    pText.textAlign = Paint.Align.LEFT
                    val statsText =
                        if (isMaxed) "SYSTEM FULLY OPTIMIZED." else "UPGRADE TO INJECT NEXT FIRMWARE LAYER."
                    c.drawText(
                        statsText, marginX + scale * 0.04f, itemRect.bottom - scale * 0.035f, pText
                    )
                } else {
                    // Collapsed Description
                    pText.textSize = scale * 0.026f
                    pText.color = 0xFFAAAAAA.toInt()
                    var shortDesc = config.descStr
                    if (pText.measureText(shortDesc) > maxHeaderW) {
                        while (shortDesc.length > 5 && pText.measureText("$shortDesc...") > maxHeaderW) {
                            shortDesc = shortDesc.dropLast(1)
                        }
                        shortDesc += "..."
                    }
                    c.drawText(shortDesc, marginX + scale * 0.04f, currentY + scale * 0.13f, pText)

                    pText.textSize = scale * 0.022f
                    pText.color = 0xFF555555.toInt()
                    c.drawText(
                        if (isMaxed) "MAX LEVEL REACHED" else "TAP FOR DETAILS",
                        marginX + scale * 0.04f,
                        currentY + scale * 0.17f,
                        pText
                    )
                }

                // Draw Button (ONLY IF NOT MAXED OR NOT EXPANDED to avoid overlap and redundancy)
                if (!isMaxed || !isExpanded) {
                    val isPressed = hitOnDown == 2 && hitTypeOnDown == type

                    p.style = Paint.Style.FILL
                    val btnBaseColor = if (isMaxed) 0xFF002200.toInt()
                    else if (canAfford) 0xFF002222.toInt()
                    else 0xFF220000.toInt()

                    p.color = if (isPressed) GameColors.mixColors(
                        btnBaseColor, 0xFFFFFFFF.toInt(), 0.3f
                    ) else btnBaseColor
                    c.drawRoundRect(btnRect, scale * 0.005f, scale * 0.005f, p)

                    p.style = Paint.Style.STROKE
                    p.color =
                        if (isMaxed) GameColors.HP else if (canAfford) GameColors.PULSE else GameColors.RED
                    c.drawRoundRect(btnRect, scale * 0.005f, scale * 0.005f, p)

                    pText.textAlign = Paint.Align.CENTER
                    pText.color = p.color
                    if (isPressed) pText.setShadowLayer(8f, 0f, 0f, p.color)
                    pText.textSize = scale * 0.028f

                    val btnLabel =
                        if (isMaxed) "OPTIMIZED" else "COMPILE\n${SaveManager.formatDataString(cost)}"
                    val lines = btnLabel.split("\n")
                    if (lines.size == 1) {
                        c.drawText(
                            lines[0], btnRect.centerX(), btnRect.centerY() + scale * 0.01f, pText
                        )
                    } else {
                        c.drawText(
                            lines[0], btnRect.centerX(), btnRect.centerY() - scale * 0.01f, pText
                        )
                        pText.textSize = scale * 0.024f
                        c.drawText(
                            lines[1], btnRect.centerX(), btnRect.centerY() + scale * 0.025f, pText
                        )
                    }
                    pText.clearShadowLayer()
                }

                currentY += currentItemH
            }
            currentY += scale * 0.05f // Branch spacing
        }

        scroller.end(c, currentY, scale, insetR)

        // --- DISCONNECT BUTTON ---
        closeButton.set(
            targetW / 2f - scale * 0.2f,
            targetH - insetB - scale * 0.12f,
            targetW / 2f + scale * 0.2f,
            targetH - insetB - scale * 0.03f
        )
        closeButton.draw(
            c = c,
            scale = scale,
            paint = p,
            textPaint = pText,
            label = "DISCONNECT",
            pressed = hitOnDown == 1,
            fillColor = 0xFF330000.toInt(),
            strokeColor = GameColors.RED,
            textColor = GameColors.RED,
            radius = scale * 0.02f,
            textSize = scale * 0.04f
        )
    }

    private var touchDownX = 0f
    private var touchDownY = 0f

    fun onTouch(
        x: Float,
        y: Float,
        action: Int,
        scale: Float,
        gs: GameState,
        onBack: () -> Unit,
    ): Boolean {
        scroller.onTouch(x, y, action, scale)
        if (scroller.isDragging || scroller.isDraggingScrollbar) {
            hitOnDown = -1
            hitTypeOnDown = null
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = x
                touchDownY = y
                hitOnDown = when {
                    closeButton.contains(x, y) -> 1
                    else -> 0
                }

                hitTypeOnDown = null
                if (hitOnDown == 0 && scroller.viewport.contains(x, y)) {
                    val localX = x - scroller.viewport.left
                    val localY = y - (scroller.viewport.top + scroller.scrollY)
                    for ((type, rect) in buyButtons) {
                        if (rect.contains(localX, localY)) {
                            hitTypeOnDown = type
                            hitOnDown = 2
                            break
                        }
                    }
                    if (hitTypeOnDown == null) {
                        for ((type, rect) in cardRects) {
                            if (rect.contains(localX, localY)) {
                                hitTypeOnDown = type
                                hitOnDown = 3
                                break
                            }
                        }
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - touchDownX
                val dy = y - touchDownY
                val distSq = dx * dx + dy * dy
                val threshold = scale * scale * 0.05f

                if (hitOnDown != -1 && (scroller.isDragging || distSq > threshold)) {
                    hitOnDown = -1
                    hitTypeOnDown = null
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!scroller.isDragging && !scroller.isDraggingScrollbar && hitOnDown != -1) {
                    val hitOnUp = when {
                        closeButton.contains(x, y) -> 1
                        else -> 0
                    }

                    var hitTypeOnUp: UpgradeType? = null
                    var upType = 0
                    if (hitOnUp == 0 && scroller.viewport.contains(x, y)) {
                        val localX = x - scroller.viewport.left
                        val localY = y - (scroller.viewport.top + scroller.scrollY)
                        for ((type, rect) in buyButtons) {
                            if (rect.contains(localX, localY)) {
                                hitTypeOnUp = type
                                upType = 2
                                break
                            }
                        }
                        if (hitTypeOnUp == null) {
                            for ((type, rect) in cardRects) {
                                if (rect.contains(localX, localY)) {
                                    hitTypeOnUp = type
                                    upType = 3
                                    break
                                }
                            }
                        }
                    }

                    if (hitOnUp == 1 && hitOnDown == 1) {
                        EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 100)
                        onBack()
                    } else if (upType != 0 && upType == hitOnDown && hitTypeOnUp == hitTypeOnDown) {
                        if (upType == 2) {
                            if (UpgradeSystem.purchaseUpgrade(hitTypeOnUp!!)) {
                                SaveManager.setFirstSoftwareUpgradeDone(true)
                                EchoAudioManager.playSound(ToneGenerator.TONE_SUP_CONFIRM, 150)
                                gs.showGlobalMessage("SCRIPT INJECTED.\nFIRMWARE OVERRIDDEN.", 2f)
                            } else {
                                EchoAudioManager.playSound(
                                    ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 100
                                )
                                gs.showGlobalMessage(
                                    "ERROR: INSUFFICIENT DATA.\nREQUIRE MORE KILOBYTES.", 2f
                                )
                            }
                        } else { // upType == 3
                            expandedType = if (expandedType == hitTypeOnUp) null else hitTypeOnUp
                            EchoAudioManager.playSound(ToneGenerator.TONE_CDMA_PIP, 50)
                        }
                    }
                }
                hitOnDown = -1
                hitTypeOnDown = null
            }

            MotionEvent.ACTION_CANCEL -> {
                hitOnDown = -1
                hitTypeOnDown = null
            }
        }
        return true
    }

    private fun measureWrappedTextHeight(text: String, maxWidth: Float, paint: Paint): Float {
        val words = text.split(" ")
        var line = ""
        var lines = 0
        val lineHeight = paint.textSize * 1.3f
        for (word in words) {
            val testLine = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(testLine) > maxWidth) {
                if (line.isNotEmpty()) lines++
                line = word
            } else {
                line = testLine
            }
        }
        if (line.isNotEmpty()) lines++
        return lines * lineHeight
    }

    private fun drawWrappedText(
        c: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        paint: Paint,
    ): Float {
        val words = text.split(" ")
        var line = ""
        var curY = y
        val lineHeight = paint.textSize * 1.3f
        for (word in words) {
            val testLine = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(testLine) > maxWidth) {
                if (line.isNotEmpty()) {
                    c.drawText(line, x, curY, paint)
                    curY += lineHeight
                }
                line = word
            } else {
                line = testLine
            }
        }
        if (line.isNotEmpty()) {
            c.drawText(line, x, curY, paint)
            curY += lineHeight
        }
        return curY
    }
}
