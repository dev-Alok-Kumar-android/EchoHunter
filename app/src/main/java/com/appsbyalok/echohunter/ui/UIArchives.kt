package com.appsbyalok.echohunter.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ToneGenerator
import android.view.MotionEvent
import com.appsbyalok.echohunter.data.LevelEngine
import com.appsbyalok.echohunter.data.LevelFeature
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.engine.DifficultyLevel
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.ui.archives.ArchiveDetailView
import com.appsbyalok.echohunter.ui.archives.ArchiveFilterSystem
import com.appsbyalok.echohunter.ui.archives.FeatureFilterMode
import com.appsbyalok.echohunter.ui.components.UIMenuButton
import com.appsbyalok.echohunter.ui.components.UIScrollView
import com.appsbyalok.echohunter.utils.EchoAudioManager
import com.appsbyalok.echohunter.utils.GameColors
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max

class UIArchives {
    private val p = Paint().apply { isAntiAlias = true }
    private val pText = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val scroller = UIScrollView()

    private val levelButtons = mutableMapOf<Int, RectF>()
    private val buttonPool = mutableListOf<RectF>()
    private val closeButton = UIMenuButton()
    private val loadMoreRect = RectF()
    private val cachedList = mutableListOf<Int>()
    private val resultQueue = ConcurrentLinkedQueue<Int>()
    private var lastMaxLevel = -1

    @Volatile
    private var isScanning = false

    @Volatile
    private var activeScanId = 0

    @Volatile
    private var totalScanned = 0

    @Volatile
    private var totalMatches = 0

    @Volatile
    private var currentSector = 0
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private var scanFuture: Future<*>? = null

    @Volatile
    private var lastScannedLevel = -1

    @Volatile
    private var lastFinishedIndex = -1
    private val seenFeaturesMasks = Collections.synchronizedSet(HashSet<Int>())

    @Volatile
    private var sortedFinishedIds: List<Int>? = null

    private val autoNextBoxRect = RectF()
    private val autoPilotBoxRect = RectF()
    private var hitOnDown = -1
    private var listTop = 0f
    private var listBottom = 0f

    private val reusableRect = RectF()
    private val badgeRect = RectF()
    private val featureEnumValues = LevelFeature.entries.toTypedArray()

    private val filterSystem = ArchiveFilterSystem()
    private val detailView = ArchiveDetailView()

    private val filterToggleBtnRect = RectF()
    private val uniqueBoxRect = RectF()
    private val finishedBoxRect = RectF()
    private val sortBoxRect = RectF()
    private val filterModeRect = RectF()
    private val clearFiltersBoxRect = RectF()
    private val featureRects = mutableMapOf<LevelFeature, RectF>()
    private val featureRectPool = mutableListOf<RectF>()

    private var headerGradient: android.graphics.LinearGradient? = null
    private var footerGradient: android.graphics.LinearGradient? = null
    private var progressGradient: android.graphics.RadialGradient? = null
    private var lastGradientParams = ""
    private var totalStars = 0
    private var levelsCleared = 0

    // Detailed View State
    private var selectedDetailLevel = -1
    private var longPressStartTime = 0L
    private var longPressLevel = -1

    private data class ArchiveGridLayout(
        val boxSize: Float,
        val gap: Float,
        val columns: Int,
        val startX: Float,
    )

    private data class ArchiveGridCursor(var col: Int = 0, var row: Int = 0)

    private data class ArchiveLayoutMetrics(
        val isLandscape: Boolean,
        val isTablet: Boolean,
        val usableWidth: Float,
        val safeLeft: Float,
        val safeRight: Float,
        val safeTop: Float,
        val safeBottom: Float,
        val panelWidth: Float,
        val headerButtonTop: Float,
        val headerButtonHeight: Float,
        val filterToggleTop: Float,
        val filterToggleHeight: Float,
        val controlGap: Float,
        val expandedFilterTop: Float,
        val expandedFilterHeight: Float,
        val chipColumns: Int,
        val gridColumns: Int,
        val gridBoxSize: Float,
        val gridGap: Float,
        val footerHeight: Float,
    )

    private fun generateNodeList(append: Boolean = false) {
        val currentMax = SaveManager.maxCampaignLevel

        if (!append) {
            scanFuture?.cancel(true)
            cachedList.clear()
            resultQueue.clear()
            seenFeaturesMasks.clear()
            lastScannedLevel = -1
            lastFinishedIndex = -1
            lastMaxLevel = currentMax
            sortedFinishedIds = null
            totalScanned = 0
            totalMatches = 0
            currentSector = 0

            val stats = SaveManager.getGlobalStats()
            levelsCleared = stats.first
            totalStars = stats.second
        } else {
            if (isScanning) return

            val isDone = if (filterSystem.filterFinishedOnly) {
                val list = sortedFinishedIds
                list != null && lastFinishedIndex >= list.size - 1
            } else {
                if (filterSystem.sortDescending) {
                    lastScannedLevel != -1 && lastScannedLevel < 1
                } else {
                    lastScannedLevel != -1 && lastScannedLevel > currentMax
                }
            }
            if (isDone) return
        }

        val selectedMask =
            filterSystem.selectedFeatures.fold(0) { acc, feat -> acc or (1 shl feat.id) }
        val mode = filterSystem.featureFilterMode
        val uniqueOnly = filterSystem.filterUniqueOnly
        val finishedOnly = filterSystem.filterFinishedOnly
        val descending = filterSystem.sortDescending

        isScanning = true
        val scanId = ++activeScanId
        scanFuture = scanExecutor.submit {
            try {
                var chunkMatches = 0
                if (finishedOnly) {
                    var list = sortedFinishedIds
                    if (list == null) {
                        val finishedIds = SaveManager.getFinishedLevelIds()
                        list =
                            if (descending) finishedIds.sortedDescending() else finishedIds.sorted()
                        sortedFinishedIds = list
                    }
                    var i = lastFinishedIndex + 1

                    while (i < list.size && !Thread.currentThread().isInterrupted && chunkMatches < 200) {
                        if (activeScanId != scanId) return@submit
                        val lvl = list[i]
                        lastFinishedIndex = i
                        i++
                        if (lvl > currentMax) continue

                        totalScanned++
                        currentSector = lvl / 1000
                        val mask = LevelEngine.getFeaturesMask(lvl)
                        var match = true
                        if (selectedMask != 0) {
                            match = when (mode) {
                                FeatureFilterMode.ANY -> (mask and selectedMask) != 0
                                FeatureFilterMode.ALL -> (mask and selectedMask) == selectedMask
                                FeatureFilterMode.EXACT -> mask == selectedMask
                            }
                        }
                        if (match && uniqueOnly) {
                            if (seenFeaturesMasks.contains(mask)) match = false
                            else seenFeaturesMasks.add(mask)
                        }

                        if (match) {
                            resultQueue.add(lvl)
                            totalMatches++
                            chunkMatches++
                        }
                        if (chunkMatches % 10 == 0) Thread.sleep(1)
                    }
                } else {
                    val step = if (descending) -1 else 1
                    var lvl =
                        if (lastScannedLevel == -1) (if (descending) currentMax else 1) else lastScannedLevel

                    while ((if (descending) lvl >= 1 else lvl <= currentMax) && !Thread.currentThread().isInterrupted && chunkMatches < 200) {
                        if (activeScanId != scanId) return@submit
                        totalScanned++
                        currentSector = lvl / 1000
                        val mask = LevelEngine.getFeaturesMask(lvl)
                        var match = true
                        if (selectedMask != 0) {
                            match = when (mode) {
                                FeatureFilterMode.ANY -> (mask and selectedMask) != 0
                                FeatureFilterMode.ALL -> (mask and selectedMask) == selectedMask
                                FeatureFilterMode.EXACT -> mask == selectedMask
                            }
                        }
                        if (match && uniqueOnly) {
                            if (seenFeaturesMasks.contains(mask)) match = false
                            else seenFeaturesMasks.add(mask)
                        }

                        if (match) {
                            resultQueue.add(lvl)
                            totalMatches++
                            chunkMatches++
                        }

                        lvl += step
                        lastScannedLevel = lvl

                        if (totalScanned % 500 == 0) {
                            Thread.yield()
                            Thread.sleep(1)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (activeScanId == scanId) isScanning = false
            }
        }
    }

    private fun drawBackground(c: Canvas, width: Float, height: Float, scale: Float) {
        c.drawColor(0xEE050508.toInt())
        p.style = Paint.Style.STROKE
        p.strokeWidth = scale * 0.002f
        p.color = 0x0AFFFFFF
        var slY = 0f
        while (slY < height) {
            c.drawLine(0f, slY, width, slY, p)
            slY += scale * 0.012f
        }
    }

    private fun buildLayoutMetrics(
        width: Float,
        height: Float,
        scale: Float,
        gs: GameState,
    ): ArchiveLayoutMetrics {
        val isLandscape = width > height
        val safeLeft = gs.hudLayout.safeInsetLeft
        val safeRight = gs.hudLayout.safeInsetRight
        val safeTop = gs.hudLayout.safeInsetTop
        val safeBottom = gs.hudLayout.safeInsetBottom
        val usableWidth = width - safeLeft - safeRight

        val minSide = minOf(width, height)
        val aspect = minSide / max(width, height)
        val isTablet = minSide >= 760f && aspect >= 0.60f
        val panelWidth = minOf(
            usableWidth - scale * 0.04f, when {
                isTablet && isLandscape -> scale * 1.38f
                isTablet -> scale * 0.88f
                isLandscape -> scale * 1.05f
                else -> scale * 0.80f
            }
        )
        val headerButtonHeight =
            (if (isLandscape) scale * 0.06f else scale * 0.07f).coerceAtLeast(40f)
        val filterToggleHeight =
            (if (isLandscape) scale * 0.055f else scale * 0.068f).coerceAtLeast(40f)
        val controlGap = (scale * if (isLandscape) 0.012f else 0.016f).coerceAtLeast(8f)
        val headerButtonTop = 0f
        val filterToggleTop =
            if (isLandscape) scale * 0.16f else (scale * 0.30f).coerceAtLeast(safeTop + scale * 0.15f)
        val expandedFilterTop = filterToggleTop + filterToggleHeight + controlGap
        val expandedFilterHeight = if (isLandscape) scale * 0.062f else scale * 0.074f
        val chipColumns = when {
            isTablet && isLandscape -> 8
            isLandscape -> 7
            isTablet -> 6
            else -> 5
        }
        val gridGap = scale * if (isTablet) 0.03f else 0.035f
        val availableGridWidth = usableWidth - scale * 0.08f
        val idealGridBoxSize = scale * if (isTablet) 0.145f else 0.16f
        val gridColumns =
            ((availableGridWidth + gridGap) / (idealGridBoxSize + gridGap)).toInt().coerceIn(3, 12)
        val gridBoxSize = minOf(
            idealGridBoxSize, (availableGridWidth - (gridColumns - 1) * gridGap) / gridColumns
        )
        val footerHeight = (if (isLandscape) scale * 0.11f else scale * 0.14f).coerceAtLeast(64f)
            .coerceAtLeast(safeBottom + scale * 0.05f)

        return ArchiveLayoutMetrics(
            isLandscape = isLandscape,
            isTablet = isTablet,
            usableWidth = usableWidth,
            safeLeft = safeLeft,
            safeRight = safeRight,
            safeTop = safeTop,
            safeBottom = safeBottom,
            panelWidth = panelWidth,
            headerButtonTop = headerButtonTop,
            headerButtonHeight = headerButtonHeight,
            filterToggleTop = filterToggleTop,
            filterToggleHeight = filterToggleHeight,
            controlGap = controlGap,
            expandedFilterTop = expandedFilterTop,
            expandedFilterHeight = expandedFilterHeight,
            chipColumns = chipColumns,
            gridColumns = gridColumns,
            gridBoxSize = gridBoxSize,
            gridGap = gridGap,
            footerHeight = footerHeight
        )
    }

    private fun updateArchiveData(height: Float, scale: Float, metrics: ArchiveLayoutMetrics ) {
        val chipRows =
            ((featureEnumValues.size + metrics.chipColumns - 1) / metrics.chipColumns).coerceAtLeast(
                1
            ).coerceAtLeast(0) // Ensure it's not negative

        val expandedFilterContentHeight = metrics.expandedFilterHeight + metrics.controlGap + chipRows * (scale * 0.052f + metrics.controlGap)
        val expandedBottom = metrics.expandedFilterTop + expandedFilterContentHeight

        val collapsedTop = metrics.filterToggleTop + metrics.filterToggleHeight + metrics.controlGap * 2f
        val targetListTop = if (filterSystem.isFilterExpanded) (expandedBottom + metrics.controlGap) else collapsedTop

        val maxAllowedTop = height - metrics.footerHeight - scale * 0.20f // Give a bit more space
        listTop = targetListTop.coerceAtMost(maxAllowedTop).coerceAtLeast(scale * 0.1f)
        listBottom = height - metrics.footerHeight
        clearFiltersBoxRect.setEmpty()

        if (SaveManager.maxCampaignLevel != lastMaxLevel || (cachedList.isEmpty() && resultQueue.isEmpty() && lastScannedLevel == -1 && !isScanning)) generateNodeList(
            false
        )

        var consumed = 0
        while (resultQueue.isNotEmpty() && consumed < 50) {
            cachedList.add(resultQueue.poll()!!)
            consumed++
        }
    }

    private fun drawHeader(
        c: Canvas,
        width: Float,
        scale: Float,
        metrics: ArchiveLayoutMetrics,
    ) {
        val headerParams = "H:$listTop"
        if (headerGradient == null || lastGradientParams != headerParams) {
            headerGradient = android.graphics.LinearGradient(
                0f,
                0f,
                0f,
                listTop,
                0xFF001A1A.toInt(),
                0x00000000,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        p.style = Paint.Style.FILL; p.shader = headerGradient
        c.drawRect(0f, 0f, width, listTop, p); p.shader = null

        val centerX = metrics.safeLeft + metrics.usableWidth / 2f

        // --- ENHANCED HEADER ---
        pText.textSize = scale * if (metrics.isLandscape) 0.075f else 0.09f
        pText.color = GameColors.COOLANT
        pText.isFakeBoldText = true
        pText.setShadowLayer(20f, 0f, 0f, GameColors.COOLANT)
        val titleY =
            if (metrics.isLandscape) scale * 0.08f else (scale * 0.12f).coerceAtLeast(metrics.safeTop + scale * 0.06f)
        c.drawText("DATA ARCHIVES", centerX, titleY, pText)
        pText.clearShadowLayer()

        // Ribbon Line
        p.style = Paint.Style.STROKE
        p.strokeWidth = scale * 0.003f
        p.color = GameColors.COOLANT
        val lineW = scale * 0.35f
        c.drawLine(
            centerX - lineW, titleY + scale * 0.02f, centerX + lineW, titleY + scale * 0.02f, p
        )

        pText.isFakeBoldText = false
        pText.textSize = scale * if (metrics.isLandscape) 0.021f else 0.025f
        pText.color = GameColors.CLARITY
        val statsStr = String.format(
            Locale.ENGLISH, "NODES DECODED: %d  |  STARS COLLECTED: %d", levelsCleared, totalStars
        )
        c.drawText(
            statsStr, centerX, titleY + scale * (if (metrics.isLandscape) 0.045f else 0.055f), pText
        )
    }

    private fun drawFilters(
        c: Canvas,
        boxWidth: Float,
        scale: Float,
        metrics: ArchiveLayoutMetrics,
    ) {
        val centerX = metrics.safeLeft + metrics.usableWidth / 2f
        val filterRow2Y = metrics.filterToggleTop
        val filterRow2H = metrics.filterToggleHeight
        filterToggleBtnRect.set(
            centerX - boxWidth, filterRow2Y, centerX + boxWidth, filterRow2Y + filterRow2H
        )

        p.style = Paint.Style.FILL; p.color =
            if (filterSystem.isFilterExpanded) 0x4400AAFF else 0x1A000000
        c.drawRoundRect(filterToggleBtnRect, scale * 0.01f, scale * 0.01f, p)
        p.style = Paint.Style.STROKE; p.color =
            if (filterSystem.isFilterExpanded) GameColors.COOLANT else 0xFF888888.toInt()
        p.strokeWidth = scale * 0.003f
        c.drawRoundRect(filterToggleBtnRect, scale * 0.01f, scale * 0.01f, p)
        pText.textSize = scale * if (metrics.isLandscape) 0.023f else 0.028f; pText.color =
            if (filterSystem.isFilterExpanded) GameColors.COOLANT else 0xFFFFFFFF.toInt()
        val filterLabel =
            if (filterSystem.isFilterExpanded) "CLOSE FILTERS ▲" else "OPEN ARCHIVE FILTERS ▼"
        c.drawText(
            filterLabel,
            filterToggleBtnRect.centerX(),
            filterToggleBtnRect.centerY() + scale * 0.01f,
            pText
        )

        if (filterSystem.isFilterExpanded) {
            drawExpandedFilters(c, boxWidth, scale, metrics)
        } else {
            clearFilterRects()
        }
    }

    private fun drawExpandedFilters(
        c: Canvas,
        boxWidth: Float,
        scale: Float,
        metrics: ArchiveLayoutMetrics,
    ) {
        val centerX = metrics.safeLeft + metrics.usableWidth / 2f
        val row3Y = metrics.expandedFilterTop
        val row3H = metrics.expandedFilterHeight
        val gap = metrics.controlGap
        val activeFilters = filterSystem.hasActiveFilters()
        val controlCount = if (activeFilters) 5 else 4
        val controlW = (boxWidth * 2f - (controlCount - 1) * gap) / controlCount

        uniqueBoxRect.set(centerX - boxWidth, row3Y, centerX - boxWidth + controlW, row3Y + row3H)
        finishedBoxRect.set(
            uniqueBoxRect.right + gap, row3Y, uniqueBoxRect.right + gap + controlW, row3Y + row3H
        )
        sortBoxRect.set(
            finishedBoxRect.right + gap,
            row3Y,
            finishedBoxRect.right + gap + controlW,
            row3Y + row3H
        )
        filterModeRect.set(sortBoxRect.right + gap, row3Y, centerX + boxWidth, row3Y + row3H)
        if (activeFilters) {
            filterModeRect.set(
                sortBoxRect.right + gap, row3Y, sortBoxRect.right + gap + controlW, row3Y + row3H
            )
            clearFiltersBoxRect.set(
                filterModeRect.right + gap, row3Y, centerX + boxWidth, row3Y + row3H
            )
        }

        p.style = Paint.Style.FILL; p.color =
            if (filterSystem.filterUniqueOnly) 0x4400FF00 else 0x1A000000
        c.drawRoundRect(uniqueBoxRect, scale * 0.01f, scale * 0.01f, p)
        p.style = Paint.Style.STROKE; p.color =
            if (filterSystem.filterUniqueOnly) GameColors.HP else 0xFF888888.toInt()
        c.drawRoundRect(uniqueBoxRect, scale * 0.01f, scale * 0.01f, p)
        pText.textSize = scale * if (metrics.isLandscape) 0.018f else 0.022f; pText.color =
            if (filterSystem.filterUniqueOnly) GameColors.HP else 0xFFFFFFFF.toInt()
        c.drawText(
            "UNIQUE", uniqueBoxRect.centerX(), uniqueBoxRect.centerY() + scale * 0.008f, pText
        )

        p.style = Paint.Style.FILL; p.color =
            if (filterSystem.filterFinishedOnly) 0x4400FF00 else 0x1A000000
        c.drawRoundRect(finishedBoxRect, scale * 0.01f, scale * 0.01f, p)
        p.style = Paint.Style.STROKE; p.color =
            if (filterSystem.filterFinishedOnly) GameColors.HP else 0xFF888888.toInt()
        c.drawRoundRect(finishedBoxRect, scale * 0.01f, scale * 0.01f, p)
        c.drawText(
            "CLEARED", finishedBoxRect.centerX(), finishedBoxRect.centerY() + scale * 0.008f, pText
        )

        p.style = Paint.Style.FILL; p.color = 0x1A000000
        c.drawRoundRect(sortBoxRect, scale * 0.01f, scale * 0.01f, p)
        p.style = Paint.Style.STROKE; p.color = GameColors.PULSE
        c.drawRoundRect(sortBoxRect, scale * 0.01f, scale * 0.01f, p)
        pText.color = GameColors.PULSE
        c.drawText(
            if (filterSystem.sortDescending) "DESC" else "ASC",
            sortBoxRect.centerX(),
            sortBoxRect.centerY() + scale * 0.008f,
            pText
        )

        p.style = Paint.Style.FILL; p.color = 0x1A000000
        c.drawRoundRect(filterModeRect, scale * 0.01f, scale * 0.01f, p)
        p.style = Paint.Style.STROKE; p.color = GameColors.COOLANT
        c.drawRoundRect(filterModeRect, scale * 0.01f, scale * 0.01f, p)
        pText.color = GameColors.COOLANT
        c.drawText(
            filterSystem.featureFilterMode.label,
            filterModeRect.centerX(),
            filterModeRect.centerY() + scale * 0.008f,
            pText
        )

        if (activeFilters) {
            p.style = Paint.Style.FILL; p.color = if (hitOnDown == -12) 0x66330000 else 0x1A000000
            c.drawRoundRect(clearFiltersBoxRect, scale * 0.01f, scale * 0.01f, p)
            p.style = Paint.Style.STROKE; p.color = GameColors.RED
            c.drawRoundRect(clearFiltersBoxRect, scale * 0.01f, scale * 0.01f, p)
            pText.color = GameColors.RED
            c.drawText(
                "CLEAR",
                clearFiltersBoxRect.centerX(),
                clearFiltersBoxRect.centerY() + scale * 0.008f,
                pText
            )
        }

        drawFeatureChips(c, boxWidth, row3Y, row3H, scale, metrics)
    }

    private fun drawFeatureChips(c: Canvas, boxWidth: Float, row3Y: Float, row3H: Float, scale: Float, metrics: ArchiveLayoutMetrics) {
        val centerX = metrics.safeLeft + metrics.usableWidth / 2f
        val chipStartY = row3Y + row3H + metrics.controlGap
        val totalFilterWidth = boxWidth * 2f + scale * 0.03f
        val chipGap = metrics.controlGap * 0.75f
        val chipColumns = metrics.chipColumns
        val chipW = (totalFilterWidth - (chipColumns - 1) * chipGap) / chipColumns
        val chipH = scale * if (metrics.isLandscape) 0.047f else 0.055f

        featureEnumValues.forEachIndexed { index, feat ->
            val r = index / chipColumns
            val colInRow = index % chipColumns
            val cx = centerX - boxWidth + colInRow * (chipW + chipGap)
            val cy = chipStartY + r * (chipH + chipGap)

            val rect = featureRects.getOrPut(feat) { RectF() }
            rect.set(cx, cy, cx + chipW, cy + chipH)

            val isSelected = filterSystem.selectedFeatures.contains(feat)
            p.style = Paint.Style.FILL; p.color = if (isSelected) 0x6600AAFF else 0x1A000000
            c.drawRoundRect(rect, scale * 0.005f, scale * 0.005f, p)
            p.style = Paint.Style.STROKE; p.color =
            if (isSelected) GameColors.COOLANT else 0x44FFFFFF
            c.drawRoundRect(rect, scale * 0.005f, scale * 0.005f, p)

            pText.textSize = scale * if (metrics.isLandscape) 0.015f else 0.018f; pText.color =
            if (isSelected) GameColors.COOLANT else 0xFFCCCCCC.toInt()
            val maxNameLength = if (metrics.isLandscape) 6 else 7
            val shortName =
                if (feat.name.length > maxNameLength) feat.name.take(maxNameLength - 2) + ".." else feat.name
            c.drawText(shortName, rect.centerX(), rect.centerY() + scale * 0.007f, pText)
        }
    }

    private fun clearFilterRects() {
        if (featureRects.isNotEmpty()) {
            featureRectPool.addAll(featureRects.values)
            featureRects.clear()
        }
        uniqueBoxRect.setEmpty()
        finishedBoxRect.setEmpty()
        sortBoxRect.setEmpty()
        filterModeRect.setEmpty()
        clearFiltersBoxRect.setEmpty()
    }

    private fun createGridLayout(metrics: ArchiveLayoutMetrics): ArchiveGridLayout {
        val boxSize = metrics.gridBoxSize
        val gap = metrics.gridGap
        val columns = metrics.gridColumns
        val totalW = columns * boxSize + (columns - 1) * gap
        val startX = metrics.safeLeft + (metrics.usableWidth - totalW) / 2f
        return ArchiveGridLayout(
            boxSize = boxSize, gap = gap, columns = columns, startX = startX
        )
    }

    private fun drawEmptyState(
        c: Canvas,
        scale: Float,
        metrics: ArchiveLayoutMetrics,
    ) {
        if (cachedList.isEmpty() && (isScanning || resultQueue.isNotEmpty())) {
            val centerX = metrics.safeLeft + metrics.usableWidth / 2f
            pText.textSize = scale * 0.032f; pText.color = GameColors.COOLANT

            // Glitchy/Binary Empty State
            val glitch =
                if (System.currentTimeMillis() % 400 < 100) "P3N3TRATING_ARCHIV35..." else "PENETRATING ARCHIVES..."
            c.drawText(glitch, centerX, listTop + (listBottom - listTop) / 2f, pText)

            pText.textSize = scale * 0.018f; pText.color = 0x6600FFFF
            repeat(3) { i ->
                val noise =
                    (0..1).joinToString("  ") { (0..20).map { (0..1).random() }.joinToString("") }
                c.drawText(
                    noise,
                    centerX,
                    listTop + (listBottom - listTop) / 2f + scale * (0.04f + i * 0.02f),
                    pText
                )
            }

            pText.textSize = scale * 0.022f; pText.color = 0xAAFFFFFF.toInt()
            val totalScannedStr = String.format(Locale.ENGLISH, "%,d", totalScanned)
            c.drawText(
                "SCANNED: $totalScannedStr",
                centerX,
                listTop + (listBottom - listTop) / 2f + scale * 0.12f,
                pText
            )
        } else if (cachedList.isEmpty() && !isScanning) {
            val centerX = metrics.safeLeft + metrics.usableWidth / 2f
            pText.textSize = scale * 0.032f; pText.color = GameColors.RED
            c.drawText(
                "NO MATCHING PROTOCOLS FOUND", centerX, listTop + (listBottom - listTop) / 2f, pText
            )
        }
    }

    private fun drawScrollableContent(
        c: Canvas,
        width: Float,
        layout: ArchiveGridLayout,
        scale: Float,
        metrics: ArchiveLayoutMetrics,
        dt: Float,
    ): Int {
        val oldLevelButtons = HashMap(levelButtons)
        levelButtons.clear()
        val cursor = ArchiveGridCursor()

        scroller.viewport.set(0f, listTop, width, listBottom)
        scroller.updatePhysics(dt)
        scroller.begin(c)

        drawGrid(c, layout, oldLevelButtons, cursor, scale)
        buttonPool.addAll(oldLevelButtons.values); oldLevelButtons.clear()
        drawLoadMore(c, width, layout, cursor, scale)

        val totalHeight = (cursor.row + 1) * (layout.boxSize + layout.gap) + scale * 0.1f
        scroller.end(c, totalHeight, scale, metrics.safeRight)

        return cursor.row
    }

    private fun drawGrid(
        c: Canvas,
        layout: ArchiveGridLayout,
        oldLevelButtons: MutableMap<Int, RectF>,
        cursor: ArchiveGridCursor,
        scale: Float,
    ) {
        for (lvl in cachedList) {
            val cx = layout.startX + cursor.col * (layout.boxSize + layout.gap)
            val cy = cursor.row * (layout.boxSize + layout.gap) + layout.gap * 0.65f
            reusableRect.set(cx, cy, cx + layout.boxSize, cy + layout.boxSize)

            // Check visibility relative to viewport
            if (cy + layout.boxSize + scroller.scrollY >= 0 && cy + scroller.scrollY <= scroller.viewport.height()) {
                drawLevelCard(c, lvl, oldLevelButtons, layout.boxSize, scale)
            }

            cursor.col++
            if (cursor.col >= layout.columns) {
                cursor.col = 0
                cursor.row++
            }
        }
    }

    private fun drawLevelCard(
        c: Canvas,
        lvl: Int,
        oldLevelButtons: MutableMap<Int, RectF>,
        boxSize: Float,
        scale: Float,
    ) {
        val rect = oldLevelButtons.remove(lvl) ?: if (buttonPool.isNotEmpty()) buttonPool.removeAt(
            buttonPool.size - 1
        ) else RectF()
        rect.set(reusableRect)
        levelButtons[lvl] = rect

        val mask = LevelEngine.getFeaturesMask(lvl)
        val isNextNode = (lvl == SaveManager.maxCampaignLevel)
        var mixedColor = GameColors.GRID
        var colorCount = 0

        for (f in featureEnumValues) {
            if ((mask and (1 shl f.id)) != 0) {
                val featureColor = getFeatureColor(f)
                mixedColor = if (colorCount == 0) featureColor else GameColors.mixColors(
                    mixedColor, featureColor, 0.5f
                )
                colorCount++
            }
        }

        val finalBgColor = GameColors.mixColors(
            if (isNextNode) 0xFF052510.toInt() else if (hitOnDown == lvl) 0xFF222222.toInt() else GameColors.BG,
            mixedColor,
            0.25f
        )
        p.style = Paint.Style.FILL; p.color = finalBgColor
        c.drawRoundRect(reusableRect, scale * 0.015f, scale * 0.015f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth =
            if (hitOnDown == lvl) scale * 0.006f else scale * 0.003f
        p.color =
            if (isNextNode) GameColors.HP else if (hitOnDown == lvl) GameColors.CLARITY else GameColors.mixColors(
                0x22FFFFFF, mixedColor, 0.4f
            )
        c.drawRoundRect(reusableRect, scale * 0.015f, scale * 0.015f, p)

        drawLevelFeatureBadges(c, mask, colorCount, reusableRect, boxSize, finalBgColor)
        drawLevelText(c, lvl, colorCount, reusableRect, boxSize, scale)
    }

    private fun getFeatureColor(feature: LevelFeature) = when (feature) {
        LevelFeature.CLASSIC -> GameColors.PULSE
        LevelFeature.MAZE -> GameColors.TEXT
        LevelFeature.DARKNESS -> GameColors.DARKNESS
        LevelFeature.BOSS -> GameColors.BOSS
        LevelFeature.ESCAPE -> GameColors.YELLOW
        LevelFeature.ELIMINATION -> GameColors.RED
        LevelFeature.DEFENSE -> GameColors.SHIELD
        LevelFeature.SPECIAL -> GameColors.OVERCLOCK
        LevelFeature.ADMIN_BONUS -> GameColors.HP
        LevelFeature.BOMB -> 0xFFFF0000.toInt()
        LevelFeature.CLEAN_SWEEP -> GameColors.COOLANT
    }

    private fun drawLevelFeatureBadges(
        c: Canvas,
        mask: Int,
        colorCount: Int,
        rect: RectF,
        boxSize: Float,
        finalBgColor: Int,
    ) {
        if (colorCount <= 0) return
        var badgeSize = boxSize * 0.22f
        var badgeGap = boxSize * 0.04f
        var totalBadgesW = (colorCount * badgeSize) + ((colorCount - 1) * badgeGap)
        val maxAllowedW = boxSize * 0.88f
        if (totalBadgesW > maxAllowedW) {
            val shrinkFactor = maxAllowedW / totalBadgesW
            badgeSize *= shrinkFactor; badgeGap *= shrinkFactor
            totalBadgesW = (colorCount * badgeSize) + ((colorCount - 1) * badgeGap)
        }
        var currentBadgeX = rect.centerX() - (totalBadgesW / 2f)
        val badgeY = rect.bottom - badgeSize - (boxSize * 0.08f)
        for (f in featureEnumValues) {
            if ((mask and (1 shl f.id)) != 0) {
                p.color = getFeatureColor(f); badgeRect.set(
                    currentBadgeX, badgeY, currentBadgeX + badgeSize, badgeY + badgeSize
                )
                com.appsbyalok.echohunter.utils.LevelIcons.drawMicroIcon(
                    c, f, badgeRect, p, finalBgColor
                )
                currentBadgeX += badgeSize + badgeGap
            }
        }
    }

    private fun drawLevelText(
        c: Canvas,
        lvl: Int,
        colorCount: Int,
        rect: RectF,
        boxSize: Float,
        scale: Float,
    ) {
        pText.color = GameColors.CLARITY
        val lvlStr = lvl.toString()
        val maxTextWidth = boxSize * 0.82f
        pText.textSize = scale * 0.045f
        val measured = pText.measureText(lvlStr)
        if (measured > maxTextWidth) pText.textSize *= (maxTextWidth / measured)
        val textYOffset = if (colorCount > 5) boxSize * 0.14f else boxSize * 0.05f
        c.drawText(lvlStr, rect.centerX(), rect.centerY() - textYOffset, pText)
        val stars = SaveManager.getLevelStars(lvl)
        if (stars > 0) {
            pText.color = GameColors.YELLOW; pText.textSize = scale * 0.025f
            c.drawText("★".repeat(stars), rect.centerX(), rect.centerY() + boxSize * 0.15f, pText)
        }
    }

    private fun drawLoadMore(
        c: Canvas,
        width: Float,
        layout: ArchiveGridLayout,
        cursor: ArchiveGridCursor,
        scale: Float,
    ) {
        loadMoreRect.setEmpty()
        val isDone = if (filterSystem.filterFinishedOnly) {
            val list = sortedFinishedIds
            list != null && lastFinishedIndex >= list.size - 1
        } else {
            if (filterSystem.sortDescending) lastScannedLevel != -1 && lastScannedLevel < 1
            else lastScannedLevel != -1 && lastScannedLevel > SaveManager.maxCampaignLevel
        }
        if (!isDone || isScanning || resultQueue.isNotEmpty()) {
            val cx = layout.startX + cursor.col * (layout.boxSize + layout.gap)
            val cy = cursor.row * (layout.boxSize + layout.gap) + layout.gap * 0.65f
            loadMoreRect.set(cx, cy, cx + layout.boxSize, cy + layout.boxSize)

            if (cy + layout.boxSize + scroller.scrollY >= 0 && cy + scroller.scrollY <= scroller.viewport.height()) {
                if (!isScanning && resultQueue.isEmpty()) {
                    p.style = Paint.Style.FILL; p.color =
                        if (hitOnDown == -10) 0x4400AAFF else 0x1A000000
                    c.drawRoundRect(loadMoreRect, scale * 0.015f, scale * 0.015f, p)
                    p.style = Paint.Style.STROKE; p.color = GameColors.COOLANT; p.strokeWidth =
                        scale * 0.003f
                    c.drawRoundRect(loadMoreRect, scale * 0.015f, scale * 0.015f, p)
                    pText.color = GameColors.COOLANT; pText.textSize = scale * 0.025f
                    c.drawText(
                        "FETCH",
                        loadMoreRect.centerX(),
                        loadMoreRect.centerY() - scale * 0.01f,
                        pText
                    )
                    c.drawText(
                        "NEXT",
                        loadMoreRect.centerX(),
                        loadMoreRect.centerY() + scale * 0.02f,
                        pText
                    )
                } else {
                    p.style = Paint.Style.STROKE; p.color = 0x44FFFFFF; p.strokeWidth =
                        scale * 0.002f
                    c.drawRoundRect(loadMoreRect, scale * 0.015f, scale * 0.015f, p)
                    pText.color = 0x88FFFFFF.toInt(); pText.textSize = scale * 0.02f
                    c.drawText(
                        "DECODING...",
                        loadMoreRect.centerX(),
                        loadMoreRect.centerY() + scale * 0.007f,
                        pText
                    )
                }
            }
            cursor.row++
        } else if (cachedList.isNotEmpty()) {
            if (cursor.col > 0) cursor.row++
            cursor.row++
            val cy = (cursor.row + 0.5f) * (layout.boxSize + layout.gap)
            pText.color = 0x44FFFFFF; pText.textSize = scale * 0.025f
            c.drawText("── END OF ARCHIVES ──", width / 2f, cy, pText)
            cursor.row++
        }
    }

    private fun drawProgress(c: Canvas, scale: Float, metrics: ArchiveLayoutMetrics) {
        if (isScanning || resultQueue.isNotEmpty()) {
            p.style = Paint.Style.FILL
            val barH = scale * 0.008f
            val maxLvl = SaveManager.maxCampaignLevel
            val totalToScan =
                if (filterSystem.filterFinishedOnly) (sortedFinishedIds?.size ?: maxLvl) else maxLvl
            val scanProgress = if (totalToScan > 0) totalScanned.toFloat() / totalToScan else 0f

            val barLeft = metrics.safeLeft
            val barWidth = metrics.usableWidth
            val progressX = barLeft + barWidth * scanProgress

            p.color = 0x44FF0000; c.drawRect(
                barLeft, listTop, barLeft + barWidth, listTop + barH, p
            )
            p.color = 0xFFFF0000.toInt(); c.drawRect(barLeft, listTop, progressX, listTop + barH, p)

            val progParams = "P:$progressX:$listTop"
            if (progressGradient == null || lastGradientParams != progParams) {
                progressGradient = android.graphics.RadialGradient(
                    progressX,
                    listTop + barH / 2f,
                    scale * 0.05f,
                    0xFFFF0000.toInt(),
                    0x00FF0000,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            p.shader = progressGradient; c.drawCircle(
                progressX, listTop + barH / 2f, scale * 0.03f, p
            ); p.shader = null; lastGradientParams = progParams

            pText.textSize = scale * 0.018f; pText.color = 0xAAFFFFFF.toInt(); pText.textAlign =
                Paint.Align.RIGHT
            val sectorText = if (currentSector > 0) "SECTOR $currentSector | " else ""
            c.drawText(
                "${sectorText}MATCHES: $totalMatches  |  SCANNED: ${
                    String.format(
                        Locale.ENGLISH, "%,d", totalScanned
                    )
                }", barLeft + barWidth - scale * 0.02f, listTop - scale * 0.01f, pText
            )
            pText.textAlign = Paint.Align.CENTER
        }
    }

    private fun drawFooter(
        c: Canvas,
        width: Float,
        height: Float,
        scale: Float,
        metrics: ArchiveLayoutMetrics,
    ) {
        val footerParams = "F:$listBottom:$height"
        if (footerGradient == null || lastGradientParams != footerParams) {
            footerGradient = android.graphics.LinearGradient(
                0f,
                listBottom,
                0f,
                height,
                0x00000000,
                0xCC1A0000.toInt(),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        p.style = Paint.Style.FILL; p.shader = footerGradient; c.drawRect(
            0f, listBottom, width, height, p
        ); p.shader = null; lastGradientParams = footerParams

        val centerX = metrics.safeLeft + metrics.usableWidth / 2f
        val btnH = metrics.headerButtonHeight
        val btnY = height - metrics.controlGap - btnH - metrics.safeBottom
        val disconnectW = scale * 0.45f
        closeButton.set(centerX - disconnectW / 2f, btnY, centerX + disconnectW / 2f, btnY + btnH)
        closeButton.draw(
            c = c,
            scale = scale,
            paint = p,
            textPaint = pText,
            label = "DISCONNECT",
            pressed = hitOnDown == -2,
            fillColor = 0xFF220505.toInt(),
            strokeColor = GameColors.RED,
            textColor = GameColors.RED,
            radius = scale * 0.01f,
            textSize = scale * if (metrics.isLandscape) 0.025f else 0.03f
        )
    }

    private var fastPlayRequested = -1

    fun draw(c: Canvas, width: Float, height: Float, gs: GameState, scale: Float, dt: Float) {
        val metrics = buildLayoutMetrics(width, height, scale, gs)
        drawBackground(c, width, height, scale); updateArchiveData(height, scale, metrics)
        drawHeader(c, width, scale, metrics)
        val boxWidth = metrics.panelWidth / 2f
        drawFilters(c, boxWidth, scale, metrics)
        val layout = createGridLayout(metrics)
        drawEmptyState(c, scale, metrics)
        drawScrollableContent(c, width, layout, scale, metrics, dt)
        drawProgress(c, scale, metrics); drawFooter(c, width, height, scale, metrics)

        if (longPressLevel != -1 && selectedDetailLevel == -1) {
            if (System.currentTimeMillis() - longPressStartTime > 500L) {
                fastPlayRequested = longPressLevel
                longPressLevel = -1
                EchoAudioManager.playSound(ToneGenerator.TONE_PROP_ACK, 100)
            }
        }
        if (selectedDetailLevel != -1) {
            detailView.draw(c, width, height, scale, selectedDetailLevel, hitOnDown, p, pText, gs)
        }
    }

    private fun getHitId(x: Float, y: Float): Int {
        if (selectedDetailLevel != -1) {
            if (detailView.detailPlayRect.contains(x, y)) return -102
            if (detailView.detailBackRect.contains(x, y)) return -101
            if (detailView.autoNextRect.contains(x, y)) return -103
            if (detailView.autoPilotRect.contains(x, y)) return -104
            return -100
        }
        if (closeButton.contains(x, y)) return -2
        if (autoNextBoxRect.contains(x, y)) return -3
        if (autoPilotBoxRect.contains(x, y)) return -4
        if (filterToggleBtnRect.contains(x, y)) return -9
        if (loadMoreRect.contains(x, y)) return -10
        if (filterSystem.isFilterExpanded) {
            if (uniqueBoxRect.contains(x, y)) return -5
            if (finishedBoxRect.contains(x, y)) return -6
            if (sortBoxRect.contains(x, y)) return -7
            if (filterModeRect.contains(x, y)) return -11
            if (clearFiltersBoxRect.contains(x, y)) return -12
            for ((feat, rect) in featureRects) if (rect.contains(x, y)) return -20 - feat.id
        }
        if (scroller.viewport.contains(x, y)) {
            val localY = y - (scroller.viewport.top + scroller.scrollY)
            for ((lvl, rect) in levelButtons) if (rect.contains(x, localY)) return lvl
        }
        return -1
    }

    fun onTouch(
        x: Float,
        y: Float,
        action: Int,
        gs: GameState,
        scale: Float,
        onSelect: (Int) -> Unit,
        onBack: () -> Unit,
    ): Boolean {
        if (fastPlayRequested != -1) {
            val lvl = fastPlayRequested
            fastPlayRequested = -1
            SaveManager.incrementLevelAttempts(lvl, gs.difficulty == DifficultyLevel.HARD)
            onSelect(lvl)
            return true
        }

        if (selectedDetailLevel == -1) {
            if (scroller.onTouch(x, y, action, scale)) {
                hitOnDown = -1
                longPressLevel = -1
            }
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                hitOnDown = getHitId(x, y); if (hitOnDown > 0) {
                    longPressLevel = hitOnDown; longPressStartTime = System.currentTimeMillis()
                } else longPressLevel = -1
            }

            MotionEvent.ACTION_MOVE -> {
                if (scroller.isDragging || scroller.isDraggingScrollbar) {
                    hitOnDown = -1; longPressLevel = -1
                }
            }

            MotionEvent.ACTION_UP -> {
                longPressLevel = -1
                if (!scroller.isDragging && !scroller.isDraggingScrollbar && hitOnDown != -1) {
                    val hitOnUp = getHitId(x, y)
                    if (hitOnUp != -1 && hitOnUp == hitOnDown) {
                        when (hitOnUp) {
                            -102 -> {
                                val lvl = selectedDetailLevel; selectedDetailLevel =
                                    -1; SaveManager.incrementLevelAttempts(
                                    lvl, gs.difficulty == DifficultyLevel.HARD
                                ); onSelect(lvl)
                            }

                            -101 -> {
                                selectedDetailLevel =
                                    -1; EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 50)
                            }

                            -103 -> {
                                SaveManager.setAutoNextLevel(!SaveManager.isAutoNextLevelEnabled)
                                EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 50)
                            }

                            -104 -> {
                                val newState = !SaveManager.isAutoPilotEnabled
                                SaveManager.setAutoPilotEnabled(newState)
                                gs.isAutoPilotActive = newState
                                if (newState) {
                                    gs.autoPilotTimer = 600f
                                    gs.showGlobalMessage("AUTOPILOT ENGAGED.", 2f)
                                }
                                EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 50)
                            }

                            -2 -> {
                                lastMaxLevel =
                                    -1; scanFuture?.cancel(true); EchoAudioManager.playSound(
                                    ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 100
                                ); onBack()
                            }

                            -10 -> {
                                generateNodeList(true); EchoAudioManager.playSound(
                                    ToneGenerator.TONE_PROP_BEEP, 50
                                )
                            }

                            -9 -> {
                                filterSystem.isFilterExpanded =
                                    !filterSystem.isFilterExpanded; EchoAudioManager.playSound(
                                    ToneGenerator.TONE_PROP_BEEP, 50
                                )
                            }

                            -5 -> {
                                filterSystem.filterUniqueOnly =
                                    !filterSystem.filterUniqueOnly; generateNodeList(false)
                            }

                            -6 -> {
                                filterSystem.filterFinishedOnly =
                                    !filterSystem.filterFinishedOnly; generateNodeList(false)
                            }

                            -7 -> {
                                filterSystem.sortDescending =
                                    !filterSystem.sortDescending; generateNodeList(false)
                            }

                            -11 -> {
                                val v = FeatureFilterMode.entries; filterSystem.featureFilterMode =
                                    v[(filterSystem.featureFilterMode.id + 1) % v.size]; generateNodeList(
                                    false
                                )
                            }

                            -12 -> {
                                filterSystem.clearAllFilters(); generateNodeList(false)
                            }

                            else -> {
                                if (hitOnUp <= -20) {
                                    val f = LevelFeature.fromId(-(hitOnUp + 20))
                                    if (f != null) {
                                        if (filterSystem.selectedFeatures.contains(f)) {
                                            filterSystem.selectedFeatures.remove(f)
                                        } else {
                                            filterSystem.selectedFeatures.add(f)
                                        }
                                        generateNodeList(false)
                                    }
                                } else {
                                    selectedDetailLevel = hitOnUp
                                    EchoAudioManager.playSound(ToneGenerator.TONE_PROP_BEEP, 50)
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
